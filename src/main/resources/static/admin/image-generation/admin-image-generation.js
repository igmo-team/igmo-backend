const form = document.querySelector("#generation-form");
const promptInput = document.querySelector("#prompt");
const modelSelect = document.querySelector("#model");
const imageSizeSelect = document.querySelector("#image-size");
const submitButton = document.querySelector("#submit-button");
const status = document.querySelector("#form-status");
const emptyResult = document.querySelector("#empty-result");
const generatedImage = document.querySelector("#generated-image");
const resultMeta = document.querySelector("#result-meta");
const copyButton = document.querySelector("#copy-button");

let latestStorageUri = "";

async function loadOptions() {
    try {
        const response = await fetch("/admin/image-generation/options");
        const options = await readJson(response);
        if (!response.ok) {
            throw new Error(options.message || "옵션을 불러오지 못했습니다.");
        }
        fillSelect(modelSelect, options.models);
        fillSelect(imageSizeSelect, options.imageSizes);
        modelSelect.disabled = false;
        imageSizeSelect.disabled = false;
        submitButton.disabled = false;
    } catch (error) {
        showStatus(error.message);
    }
}

function fillSelect(select, values) {
    select.replaceChildren(...values.map(value => new Option(value, value)));
}

form.addEventListener("submit", async event => {
    event.preventDefault();
    setLoading(true);
    try {
        const response = await fetch("/admin/image-generation", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({
                prompt: promptInput.value,
                model: modelSelect.value,
                imageSize: imageSizeSelect.value
            })
        });
        const result = await readJson(response);
        if (!response.ok) {
            throw new Error(result.message || "이미지 생성에 실패했습니다.");
        }
        renderResult(result);
    } catch (error) {
        showStatus(error.message);
    } finally {
        setLoading(false);
    }
});

copyButton.addEventListener("click", async () => {
    await navigator.clipboard.writeText(latestStorageUri);
    copyButton.textContent = "복사됨";
    window.setTimeout(() => { copyButton.textContent = "S3 URI 복사"; }, 1500);
});

function renderResult(result) {
    latestStorageUri = result.storageUri;
    generatedImage.src = result.imageDataUrl;
    generatedImage.hidden = false;
    emptyResult.hidden = true;
    resultMeta.hidden = false;
    copyButton.hidden = false;
    document.querySelector("#result-model").textContent = result.model;
    document.querySelector("#result-size").textContent = result.imageSize;
    document.querySelector("#result-duration").textContent = `${result.durationMs} ms`;
    document.querySelector("#result-storage-uri").textContent = result.storageUri;
    showStatus("");
}

function setLoading(loading) {
    submitButton.disabled = loading;
    submitButton.textContent = loading ? "생성 중..." : "이미지 생성";
    if (loading) {
        status.classList.add("loading");
        status.textContent = "Gemini 이미지 생성 요청을 처리하고 있습니다.";
    } else {
        status.classList.remove("loading");
    }
}

function showStatus(message) {
    status.classList.remove("loading");
    status.textContent = message;
}

async function readJson(response) {
    try {
        return await response.json();
    } catch {
        return {};
    }
}

loadOptions();
