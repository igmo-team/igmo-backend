import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SCRIPTS = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("cd_failure_report", SCRIPTS / "cd_failure_report.py")
REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORT)


class FailureReportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.raw = self.root / "diagnostics/raw"
        self.raw.mkdir(parents=True)
        self.workspace = self.root / "workspace"
        self.workspace.mkdir()
        self.environment = patch.dict(os.environ, {
            "BUILD_ARTIFACT_OUTCOME": "failure", "DEPLOY_OUTCOME": "skipped",
            "GITHUB_SHA": "18f141499", "GITHUB_SERVER_URL": "https://github.com",
            "GITHUB_REPOSITORY": "igmo-team/igmo-backend", "GITHUB_RUN_ID": "123",
            "GEMINI_API_KEY": "test-sensitive-key",
        }, clear=True)
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def build_result(self, stage="gradle", code=1, log=""):
        (self.raw / "result.json").write_text(json.dumps({"stage": stage, "exit_code": code}))
        (self.raw / f"{stage}.log").write_text(log)

    def xml(self, text, name="TEST-game.xml"):
        directory = self.workspace / "build/test-results/test"
        directory.mkdir(parents=True, exist_ok=True)
        (directory / name).write_text(text)

    def collect(self):
        return REPORT.collect(self.workspace, self.raw.parent)

    def test_test_failure_is_shared_by_summary_and_discord(self):
        self.build_result(log="Execution failed for task ':test'.")
        self.xml('''<testsuite name="GameTest" tests="529" failures="1" errors="0" skipped="0">
          <testcase classname="GameTest" name="로비 만료 알림">
            <failure type="java.lang.AssertionError" message="STOMP frame 미수신">
              at GameTest.awaitMessage(GameTest.java:1049)
            </failure>
          </testcase></testsuite>''')
        report = self.collect()
        self.assertEqual(529, report["tests"]["total"])
        self.assertEqual(1, report["tests"]["failed"])
        self.assertEqual(":test", report["task"])
        self.assertEqual("GameTest.java:1049", report["failures"][0]["location"])
        self.assertEqual("EC2 배포 미실행", report["deployment"])
        for text in (REPORT.markdown(report, "https://github.com/artifact/1"),
                     REPORT.discord(report, "https://github.com/artifact/1")):
            self.assertIn("STOMP frame 미수신", text)
            self.assertIn("로비 만료 알림", text)
            self.assertIn("https://github.com/artifact/1", text)
            self.assertNotIn("구독 경쟁", text)
        self.assertTrue((self.raw.parent / "report/build/test-results/test/TEST-game.xml").is_file())

    def test_compile_failure_without_xml_uses_log_and_preserves_code(self):
        self.build_result(code=23, log="src/Game.java:12: error: cannot find symbol\n"
                          "Execution failed for task ':compileJava'.")
        report = self.collect()
        self.assertEqual(23, report["exit_code"])
        self.assertEqual(":compileJava", report["task"])
        self.assertIn("cannot find symbol", report["observed_error"])
        self.assertTrue(report["warnings"])

    def test_project_stack_location_precedes_junit_internal_location(self):
        self.build_result()
        self.xml('''<testsuite tests="1" failures="1"><testcase name="intentionalFailure">
          <failure type="AssertionFailedError" message="failed">
            at org.junit.jupiter.api.AssertionUtils.fail(AssertionUtils.java:38)
            at org.junit.jupiter.api.Assertions.fail(Assertions.java:138)
            at com.igmo.CdDiagnosticsProbeTest.intentionalFailure(CdDiagnosticsProbeTest.java:12)
          </failure></testcase></testsuite>''')
        report = self.collect()
        self.assertEqual("CdDiagnosticsProbeTest.java:12", report["failures"][0]["location"])

    def test_npm_failure_does_not_report_stale_tests(self):
        self.build_result("npm", 42, "npm error code ERESOLVE")
        self.xml('<testsuite tests="99" failures="1"/>')
        report = self.collect()
        self.assertEqual("의존성 설치 (npm ci)", report["stage"])
        self.assertEqual(0, report["tests"]["reports"])
        self.assertIn("ERESOLVE", report["observed_error"])

    def test_malformed_xml_does_not_hide_valid_suite(self):
        self.build_result()
        self.xml("<broken", "TEST-broken.xml")
        self.xml('<testsuite tests="2" failures="0" errors="1" skipped="1">'
                 '<testcase name="context"><error type="IllegalStateException" message="context failed"/>'
                 '</testcase></testsuite>', "TEST-valid.xml")
        report = self.collect()
        self.assertEqual(2, report["tests"]["total"])
        self.assertEqual(1, report["tests"]["errors"])
        self.assertIn("TEST-broken.xml", report["warnings"][0])

    def test_secret_redaction_in_all_persisted_and_rendered_evidence(self):
        self.build_result(log="error: test-sensitive-key token=other-credential Bearer bearer-value")
        self.xml('<testsuite tests="1" failures="1"><testcase name="case">'
                 '<failure message="test-sensitive-key">test-sensitive-key</failure>'
                 '</testcase><system-out>test-sensitive-key</system-out></testsuite>')
        html_dir = self.workspace / "build/reports/tests/test"
        html_dir.mkdir(parents=True)
        (html_dir / "index.html").write_text("<p>test-sensitive-key</p>")
        report = self.collect()
        for path in (self.raw.parent / "report").rglob("*"):
            if path.is_file():
                content = path.read_text()
                self.assertNotIn("test-sensitive-key", content, str(path))
                self.assertNotIn("other-credential", content, str(path))
                self.assertNotIn("bearer-value", content, str(path))
        self.assertNotIn("test-sensitive-key", REPORT.discord(report))

    def test_json_special_characters_remain_valid(self):
        os.environ["GEMINI_API_KEY"] = 'credential-"quoted"'
        self.build_result(log='error: credential-"quoted"')
        self.collect()
        report = json.loads((self.raw.parent / "report/report.json").read_text())
        self.assertIn("[REDACTED]", report["observed_error"])

    def test_encoded_secrets_are_also_removed(self):
        os.environ["GEMINI_API_KEY"] = 'secret-"with&chars'
        text = 'secret-&quot;with&amp;chars secret-%22with%26chars secret-\\"with&chars'
        self.assertNotIn("secret-", REPORT.redact(text))

    def test_deployment_failure_uses_existing_reason_without_claiming_service_health(self):
        os.environ.update({"BUILD_ARTIFACT_OUTCOME": "skipped", "DEPLOY_OUTCOME": "failure",
                           "DEPLOY_FAILURE_STAGE": "Application Health Check",
                           "DEPLOY_FAILURE_REASON": "HTTP 200 미수신",
                           "DEPLOY_CLEANUP_STATUS": "success", "DEPLOY_NGINX_RECOVERY_STATUS": "failed"})
        report = self.collect()
        self.assertEqual("Application Health Check", report["stage"])
        self.assertEqual("HTTP 200 미수신", report["observed_error"])
        self.assertIn("확인 필요", report["deployment"])
        self.assertEqual("success", report["cleanup"])
        self.assertEqual("failed", report["nginx_recovery"])

    def test_missing_build_metadata_still_produces_report(self):
        report = self.collect()
        self.assertIsNone(report["exit_code"])
        self.assertIn("확인 불가", report["observed_error"])
        self.assertTrue((self.raw.parent / "report/summary.md").is_file())

    def test_workflow_name_distinguishes_diagnostic_notifications(self):
        os.environ["GITHUB_WORKFLOW"] = "[진단 테스트] CD 검증"
        report = self.collect()
        self.assertIn("진단 테스트", REPORT.markdown(report).splitlines()[0])
        self.assertIn("[진단 테스트]", REPORT.discord(report).splitlines()[0])

    def test_summary_escapes_markup_and_limits_test_list(self):
        self.build_result()
        self.xml('<testsuite tests="12" failures="12">' + ''.join(
            '<testcase name="&lt;script&gt;`bad`"><failure message="&lt;b&gt;bad&lt;/b&gt;"/>'
            '</testcase>' for _ in range(12)) + '</testsuite>')
        report = self.collect()
        summary = REPORT.markdown(report)
        self.assertNotIn("<script>", summary)
        self.assertIn("나머지 실패", summary)
        report["observed_error"] = "한" * 10000
        self.assertLessEqual(len(REPORT.discord(report)), 1900)
        report["observed_error"] = "😀" * 10000
        self.assertLessEqual(len(REPORT.discord(report).encode("utf-16-le")) // 2, 1900)

    def test_publish_appends_summary_without_an_artifact_link_when_upload_failed(self):
        self.build_result(log="Execution failed for task ':test'.")
        self.collect()
        summary = self.root / "step-summary.md"
        summary.write_text("existing summary\n")
        result = subprocess.run([
            os.sys.executable, "-B", str(SCRIPTS / "cd_failure_report.py"), "publish",
            "--diagnostics", str(self.raw.parent),
        ], env={**os.environ, "GITHUB_STEP_SUMMARY": str(summary)}, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(summary.read_text().startswith("existing summary\n"))
        self.assertIn("업로드되지 않았다면", summary.read_text())
        self.assertNotIn("[cd-failure-report 다운로드]", summary.read_text())

    def test_symlinked_evidence_is_not_uploaded(self):
        self.build_result()
        directory = self.workspace / "build/reports/tests/test"
        directory.mkdir(parents=True)
        secret = self.root / "private.txt"
        secret.write_text("must-not-upload")
        (directory / "index.html").symlink_to(secret)
        self.collect()
        self.assertFalse((self.raw.parent / "report/build/reports/tests/test/index.html").exists())

    def test_symlinked_build_directory_cannot_collect_files_outside_workspace(self):
        self.build_result()
        external = self.root / "external"
        external_xml = external / "test-results/test"
        external_xml.mkdir(parents=True)
        (external_xml / "TEST-private.xml").write_text('<testsuite tests="99" failures="0"/>')
        (self.workspace / "build").symlink_to(external)
        report = self.collect()
        self.assertEqual(0, report["tests"]["reports"])
        self.assertFalse((self.raw.parent / "report/build/test-results/test/TEST-private.xml").exists())


class ShellIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.env = {**os.environ, "PATH": f"{self.bin}:{os.environ['PATH']}",
                    "RUNNER_TEMP": str(self.root / "runner"),
                    "OPENAPI_SERVER_URL": "https://example.com", "WEBSOCKET_DOCS_SERVER_URL": "wss://example.com/ws",
                    "GITHUB_SHA": "123456789", "GITHUB_SERVER_URL": "https://github.com",
                    "GITHUB_REPOSITORY": "team/repo", "GITHUB_RUN_ID": "1", "GITHUB_ACTOR": "tester"}

    def executable(self, path, content):
        path.write_text("#!/bin/sh\n" + content)
        path.chmod(0o755)

    def build(self, npm_status=0, gradle_status=0, tee_failure=False):
        self.executable(self.bin / "npm", f'echo "npm output"; exit {npm_status}\n')
        self.executable(self.root / "gradlew", f'echo "gradle output"; exit {gradle_status}\n')
        if tee_failure:
            self.executable(self.bin / "tee", 'cat > /dev/null; exit 7\n')
        return subprocess.run(["bash", str(SCRIPTS / "build-artifact.sh")], cwd=self.root,
                              env=self.env, capture_output=True, text=True)

    def test_gradle_failure_preserves_status_and_log(self):
        result = self.build(gradle_status=23)
        self.assertEqual(23, result.returncode, result.stderr)
        raw = self.root / "runner/igmo-cd/raw"
        self.assertIn("gradle output", (raw / "gradle.log").read_text())
        self.assertEqual({"stage": "gradle", "exit_code": 23}, json.loads((raw / "result.json").read_text()))

    def test_npm_failure_stops_before_gradle(self):
        result = self.build(npm_status=42)
        self.assertEqual(42, result.returncode)
        self.assertNotIn("gradle output", result.stdout)

    def test_failed_build_can_be_collected_and_sent_without_real_external_calls(self):
        result = self.build(gradle_status=23)
        self.assertEqual(23, result.returncode)
        env = {**self.env, "BUILD_ARTIFACT_OUTCOME": "failure", "DEPLOY_OUTCOME": "skipped"}
        collected = subprocess.run([
            os.sys.executable, "-B", str(SCRIPTS / "cd_failure_report.py"), "collect",
            "--workspace", str(self.root), "--diagnostics", str(self.root / "runner/igmo-cd"),
        ], env=env, capture_output=True, text=True)
        self.assertEqual(0, collected.returncode, collected.stderr)
        report = json.loads((self.root / "runner/igmo-cd/report/report.json").read_text())
        self.assertEqual(23, report["exit_code"])
        notified, payload = self.notify(report)
        self.assertEqual(0, notified.returncode)
        self.assertIn("EC2 배포 미실행", payload["content"])

    def test_logging_failure_does_not_change_success_or_failure(self):
        self.assertEqual(0, self.build(tee_failure=True).returncode)
        self.assertEqual(23, self.build(gradle_status=23, tee_failure=True).returncode)

    def test_directory_failure_still_runs_build(self):
        (self.root / "runner").write_text("not a directory")
        result = self.build(gradle_status=23)
        self.assertEqual(23, result.returncode)
        self.assertIn("gradle output", result.stdout)

    def notify(self, report=None, curl_status=0):
        directory = self.root / "runner/igmo-cd/report"
        directory.mkdir(parents=True, exist_ok=True)
        if report is not None:
            (directory / "report.json").write_text(json.dumps(report, ensure_ascii=False))
        capture = self.root / "payload.json"
        self.executable(self.bin / "curl", f'''while [ "$#" -gt 0 ]; do
  if [ "$1" = "--data" ]; then shift; printf '%s' "$1" > '{capture}'; fi
  shift
done
printf '204'
exit {curl_status}
''')
        env = {**self.env, "DISCORD_DEPLOY_WEBHOOK_URL": "https://example.com/webhook",
               "BUILD_ARTIFACT_OUTCOME": "failure"}
        result = subprocess.run(["bash", str(SCRIPTS / "notify-discord.sh"), "failure", "https://github.com/artifact/1"],
                                env=env, capture_output=True, text=True)
        return result, json.loads(capture.read_text())

    def test_discord_uses_report_and_disables_mentions(self):
        with patch.dict(os.environ, {"BUILD_ARTIFACT_OUTCOME": "failure"}, clear=True):
            report = REPORT.collect(self.root, self.root / "runner/igmo-cd")
        report["observed_error"] = "STOMP 수신 실패 @everyone"
        result, payload = self.notify(report)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("STOMP 수신 실패", payload["content"])
        self.assertIn("https://github.com/artifact/1", payload["content"])
        self.assertEqual([], payload["allowed_mentions"]["parse"])

    def test_missing_or_invalid_report_falls_back(self):
        for content in (None, {"bad": "schema"}):
            result, payload = self.notify(content)
            self.assertEqual(0, result.returncode)
            self.assertIn("테스트·문서 생성·JAR 빌드에 실패했습니다", payload["content"])
            self.assertIn("using the existing", result.stdout)

    def test_discord_network_failure_is_non_fatal(self):
        result, _ = self.notify(curl_status=7)
        self.assertEqual(0, result.returncode)
        self.assertIn("notification failed", result.stdout)


if __name__ == "__main__":
    unittest.main()
