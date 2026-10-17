"""Collect observed CD failures and render GitHub/Discord reports without network calls."""

import argparse
import html
import json
import os
from pathlib import Path
import re
from urllib.parse import quote, quote_plus
import xml.etree.ElementTree as ET


STAGES = (
    ("CHECKOUT_OUTCOME", "Checkout"),
    ("VALIDATE_OUTCOME", "배포 설정 검증"),
    ("SETUP_JAVA_OUTCOME", "JDK 설정"),
    ("SETUP_NODE_OUTCOME", "Node.js 설정"),
    ("BUILD_ARTIFACT_OUTCOME", "애플리케이션 빌드"),
    ("AWS_AUTH_OUTCOME", "AWS 인증"),
    ("ECR_LOGIN_OUTCOME", "ECR 로그인"),
    ("VERIFY_IMAGE_OUTCOME", "기존 이미지 검증"),
    ("QEMU_OUTCOME", "QEMU 설정"),
    ("BUILDX_OUTCOME", "Buildx 설정"),
    ("BUILD_PUSH_OUTCOME", "이미지 빌드·업로드"),
    ("DEPLOY_OUTCOME", "AWS SSM 배포"),
)
MAX_FILE_BYTES = 5 * 1024 * 1024


def redact(text):
    text = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", text)
    secrets = [value for key, value in os.environ.items()
               if re.search(r"SECRET|TOKEN|PASSWORD|WEBHOOK|API_KEY", key, re.I) and len(value) >= 4]
    for value in sorted(secrets, key=len, reverse=True):
        forms = {value, html.escape(value, quote=True),
                 html.escape(value, quote=True).replace("&#x27;", "&apos;"),
                 json.dumps(value)[1:-1], quote(value, safe=""), quote_plus(value)}
        for form in sorted(forms, key=len, reverse=True):
            text = text.replace(form, "[REDACTED]")
    text = re.sub(r"(?i)(bearer\s+)[\w.\-+/=]+", r"\1[REDACTED]", text)
    text = re.sub(r"(?i)((?:password|token|api[_-]?key|secret)\s*[=:]\s*)[^\s<>&\"']+",
                  r"\1[REDACTED]", text)
    return text


def read_text(path):
    # Keep the end of logs, where Gradle records the failure. Oversized XML is skipped below.
    with path.open("rb") as source:
        size = path.stat().st_size
        source.seek(max(0, size - MAX_FILE_BYTES))
        text = source.read(MAX_FILE_BYTES).decode("utf-8", errors="replace")
    return redact(("[앞부분 생략: 크기 제한]\n" if size > MAX_FILE_BYTES else "") + text)


def scrub_data(value):
    if isinstance(value, str):
        return redact(value)
    if isinstance(value, list):
        return [scrub_data(item) for item in value]
    if isinstance(value, dict):
        return {key: scrub_data(item) for key, item in value.items()}
    return value


def workflow_url():
    return (f"{os.environ.get('GITHUB_SERVER_URL', 'https://github.com')}/"
            f"{os.environ.get('GITHUB_REPOSITORY', '')}/actions/runs/"
            f"{os.environ.get('GITHUB_RUN_ID', '')}")


def collect(workspace, diagnostics):
    destination = diagnostics / "report"
    destination.mkdir(parents=True, exist_ok=True)
    report = {
        "stage": "실패 단계 확인 불가", "task": "", "exit_code": None,
        "observed_error": "상세 원인 확인 불가. 실행 로그를 확인해주세요.",
        "tests": {"total": 0, "failed": 0, "errors": 0, "skipped": 0, "reports": 0},
        "failures": [], "warnings": [], "commit": os.environ.get("GITHUB_SHA", ""),
        "workflow": os.environ.get("GITHUB_WORKFLOW", "CD"),
        "workflow_url": workflow_url(),
        "image_build": {"success": "이미지 빌드·업로드 단계 성공", "failure": "이미지 빌드·업로드 단계 실패",
                        "skipped": "이미지 빌드·업로드 미실행"}.get(
                            os.environ.get("BUILD_PUSH_OUTCOME"), "이미지 빌드·업로드 상태 확인 불가"),
        "deployment": ("EC2 배포 단계 실행됨. 트래픽 전환·현재 서비스 상태는 확인 필요"
                       if os.environ.get("DEPLOY_OUTCOME") in {"failure", "success", "cancelled"}
                       else "EC2 배포 미실행"),
        "cleanup": os.environ.get("DEPLOY_CLEANUP_STATUS", "확인 불가"),
        "nginx_recovery": os.environ.get("DEPLOY_NGINX_RECOVERY_STATUS", "확인 불가"),
    }
    failed_step = next((key for key, _ in STAGES if os.environ.get(key) == "failure"), "")
    report["stage"] = dict(STAGES).get(failed_step, report["stage"])
    if failed_step == "DEPLOY_OUTCOME":
        report["stage"] = os.environ.get("DEPLOY_FAILURE_STAGE") or report["stage"]
        report["observed_error"] = os.environ.get("DEPLOY_FAILURE_REASON") or report["observed_error"]

    if failed_step == "BUILD_ARTIFACT_OUTCOME":
        raw = diagnostics / "raw"
        build_stage = ""
        try:
            result = json.loads((raw / "result.json").read_text())
            build_stage = result["stage"]
            report["exit_code"] = result["exit_code"]
            if result["stage"] == "npm":
                report["stage"] = "의존성 설치 (npm ci)"
        except (OSError, ValueError, KeyError):
            report["warnings"].append("빌드 종료 정보가 없습니다.")
        logs = []
        for path in sorted(raw.glob("*.log")):
            if path.is_symlink():
                continue
            content = read_text(path)
            (destination / path.name).write_text(content, encoding="utf-8")
            logs.append(content)
        log = "\n".join(logs)
        task = re.search(r"Execution failed for task '([^']+)'", log)
        if not task:
            task = re.search(r"> Task (\S+) FAILED", log)
        if task:
            report["task"] = task.group(1)
        errors = [line.strip() for line in log.splitlines()
                  if re.search(r"(?i)npm (?:err!|error)|error:|^> |Exception|Caused by:", line)
                  and not line.startswith("> Task")]
        if errors:
            report["observed_error"] = errors[0][:1000]

        xml_directory = workspace / "build/test-results/test"
        for path in sorted(xml_directory.glob("TEST-*.xml")) if build_stage != "npm" else []:
            if path.is_symlink() or workspace.resolve() not in path.resolve().parents:
                continue
            try:
                if path.stat().st_size > MAX_FILE_BYTES:
                    raise ValueError("size limit")
                suite = ET.fromstring(read_text(path))
                if suite.tag != "testsuite":
                    raise ValueError("unexpected XML root")
                counts = {key: int(suite.get(attribute, "0")) for key, attribute in
                          (("total", "tests"), ("failed", "failures"), ("errors", "errors"), ("skipped", "skipped"))}
                for key, value in counts.items():
                    report["tests"][key] += value
                report["tests"]["reports"] += 1
                for case in suite.findall("testcase"):
                    for failure in list(case.findall("failure")) + list(case.findall("error")):
                        stack = failure.text or ""
                        location = re.search(r"\bat com\.igmo\.[^\n]*?\(([\w$]+\.java:\d+)\)", stack)
                        project_location = location.group(1) if location else None
                        fallback_location = re.search(r"[\w$]+\.java:\d+", stack)
                        report["failures"].append({
                            "class": case.get("classname", suite.get("name", "")),
                            "test": case.get("name", ""), "type": failure.get("type", ""),
                            "message": failure.get("message", "")[:2000],
                            "location": (project_location or
                                         (fallback_location.group() if fallback_location else "확인 불가")),
                            "stack": stack[:4000],
                        })
            except (OSError, ET.ParseError, ValueError):
                report["warnings"].append(f"테스트 XML을 읽지 못했습니다: {path.name}")
        if report["failures"]:
            report["observed_error"] = report["failures"][0]["message"] or report["failures"][0]["type"]
        elif not report["tests"]["reports"]:
            report["warnings"].append("테스트 리포트가 없습니다. 테스트 실행 전 실패했을 수 있습니다.")

        for relative in ("build/test-results/test", "build/reports/tests/test") if build_stage != "npm" else []:
            source = workspace / relative
            if not source.exists() or source.is_symlink():
                continue
            for path in source.rglob("*"):
                if (not path.is_file() or path.is_symlink()
                        or workspace.resolve() not in path.resolve().parents
                        or source.resolve() not in path.resolve().parents
                        or path.suffix not in {".xml", ".html", ".css", ".js"}):
                    continue
                target = destination / relative / path.relative_to(source)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(read_text(path), encoding="utf-8")

    report = scrub_data(report)
    (destination / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    (destination / "summary.md").write_text(markdown(report), encoding="utf-8")
    return report


def safe(text):
    text = html.escape(str(text)).replace("`", "&#96;").replace("\n", " ").replace("\r", " ")
    return re.sub(r"([\\\[\]*_()#|~])", r"\\\1", text)


def markdown(report, artifact_url=""):
    tests = report["tests"]
    lines = [f"## {safe(report['workflow'])} 실패", "", "- 환경: production", f"- 커밋: {safe(report['commit'][:7])}",
             f"- 실패 단계: {safe(report['stage'])}", f"- 실패 task: {safe(report['task'] or '확인 불가')}",
             f"- 종료 코드: {safe(report['exit_code'] if report['exit_code'] is not None else '확인 불가')}",
             f"- 이미지: {safe(report['image_build'])}", f"- 배포 진행: {safe(report['deployment'])}",
             f"- Target 정리: {safe(report['cleanup'])}", f"- Nginx 복구: {safe(report['nginx_recovery'])}",
             "", "### 관측 오류", "", safe(report["observed_error"]), ""]
    if tests["reports"]:
        lines.append(f"테스트: {tests['total']}개 / 실패 {tests['failed']}개 / 오류 {tests['errors']}개 / 생략 {tests['skipped']}개")
    for failure in report["failures"][:10]:
        lines += ["", f"- 테스트: {safe(failure['class'])} / {safe(failure['test'])}",
                  f"  - 예외: {safe(failure['type'])}", f"  - 위치: {safe(failure['location'])}",
                  f"  - 오류: {safe(failure['message'])}"]
    if len(report["failures"]) > 10:
        lines.append("나머지 실패와 스택은 artifact의 report.json·테스트 리포트를 확인해주세요.")
    lines += ["", f"[실행 로그]({report['workflow_url']})"]
    lines.append(f"[cd-failure-report 다운로드]({artifact_url})" if artifact_url
                 else "상세 자료: 실행 화면의 cd-failure-report artifact를 확인해주세요. 업로드되지 않았다면 실행 로그를 확인해주세요.")
    lines += ["", *[f"- 참고: {safe(warning)}" for warning in report["warnings"]]]
    return "\n".join(lines) + "\n"


def discord(report, artifact_url=""):
    tests = report["tests"]
    lines = [f"🚨 IGMO {report['workflow']} 실패", "", "환경: production", f"Commit: {report['commit'][:7]}",
             f"실패 단계: {report['stage']} {report['task']}",
             f"관측 오류: {report['observed_error'][:500]}", f"이미지: {report['image_build']}",
             f"배포 진행: {report['deployment']}",
             f"Target 정리: {report['cleanup']} / Nginx 복구: {report['nginx_recovery']}"]
    if tests["reports"]:
        lines.append(f"테스트: {tests['total']}개 / 실패 {tests['failed']}개 / 오류 {tests['errors']}개")
    if report["failures"]:
        failure = report["failures"][0]
        lines += [f"실패 테스트: {failure['class'][:160]} / {failure['test'][:160]}",
                  f"위치: {failure['location']}"]
    lines += ["", f"상세 리포트: {report['workflow_url']}"]
    if artifact_url:
        lines.append(f"첨부 자료: {artifact_url}")
    return "\n".join(lines).encode("utf-16-le")[:3800].decode("utf-16-le", errors="ignore")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("collect", "publish", "discord"))
    parser.add_argument("--workspace", type=Path, default=Path.cwd())
    parser.add_argument("--diagnostics", type=Path, required=True)
    parser.add_argument("--artifact-url", default="")
    args = parser.parse_args()
    if args.mode == "collect":
        collect(args.workspace, args.diagnostics)
        return
    report = json.loads((args.diagnostics / "report/report.json").read_text())
    if args.mode == "discord":
        print(discord(report, args.artifact_url))
    else:
        text = markdown(report, args.artifact_url)
        with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as output:
            output.write(text)


if __name__ == "__main__":
    main()
