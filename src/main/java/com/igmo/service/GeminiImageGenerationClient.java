package com.igmo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.service.exception.GeminiRequestException;
import com.igmo.service.exception.GeminiResponseException;
import com.igmo.service.exception.ImageStorageException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GeminiImageGenerationClient implements ImageGenerationClient {

    private static final URI IMAGE_GENERATION_URI = URI.create(
            "https://generativelanguage.googleapis.com/v1beta/interactions");
    private static final String IMAGE_CONTENT_TYPE = "image/jpeg";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ImageStorageClient imageStorageClient;
    private final URI imageGenerationUri;
    private final String apiKey;
    private final String model;
    private final String imageSize;

    @Autowired
    public GeminiImageGenerationClient(
            ObjectMapper objectMapper,
            ImageStorageClient imageStorageClient,
            @Value("${igmo.ai.gemini.api-key}") String apiKey,
            @Value("${igmo.ai.gemini.model}") String model,
            @Value("${igmo.ai.gemini.image-size}") String imageSize
    ) {
        this(objectMapper, HttpClient.newHttpClient(), imageStorageClient, IMAGE_GENERATION_URI, apiKey, model,
                imageSize);
    }

    GeminiImageGenerationClient(
            ObjectMapper objectMapper,
            HttpClient httpClient,
            ImageStorageClient imageStorageClient,
            URI imageGenerationUri,
            String apiKey,
            String model,
            String imageSize
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.imageStorageClient = imageStorageClient;
        this.imageGenerationUri = imageGenerationUri;
        this.apiKey = apiKey;
        this.model = model;
        this.imageSize = imageSize;
    }

    @Override
    public String generate(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new GeminiRequestException("Gemini API 키가 설정되지 않았습니다.", model, imageSize, null);
        }

        HttpResponse<String> response = sendRequest(createRequest(prompt));
        verifySuccessfulResponse(response);
        byte[] image = extractImage(response.body(), response.statusCode());
        return storeImage(image);
    }

    private HttpRequest createRequest(String prompt) {
        try {
            return HttpRequest.newBuilder(imageGenerationUri)
                    .header("x-goog-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(Map.of(
                                    "model", model,
                                    "input", List.of(Map.of(
                                            "type", "text",
                                            "text", prompt)),
                                    "response_format", Map.of(
                                            "type", "image",
                                            "mime_type", IMAGE_CONTENT_TYPE,
                                            "image_size", imageSize)))))
                    .build();
        } catch (Exception exception) {
            throw new GeminiRequestException(
                    "Gemini 이미지 생성 요청을 만들지 못했습니다.", model, imageSize, exception);
        }
    }

    private HttpResponse<String> sendRequest(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GeminiRequestException(
                    "Gemini 이미지 생성 요청이 중단되었습니다.", model, imageSize, exception);
        } catch (Exception exception) {
            throw new GeminiRequestException("Gemini 이미지 생성 요청에 실패했습니다.", model, imageSize, exception);
        }
    }

    private void verifySuccessfulResponse(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new GeminiRequestException(response.statusCode(), model, imageSize);
        }
    }

    private byte[] extractImage(String responseBody, int httpStatus) {
        JsonNode response = readResponse(responseBody, httpStatus);
        List<String> modelOutputContentTypes = extractModelOutputContentTypes(response);
        for (JsonNode step : response.path("steps")) {
            if (!"model_output".equals(step.path("type").asText())) {
                continue;
            }
            for (JsonNode content : step.path("content")) {
                JsonNode imageBase64 = content.path("data");
                if ("image".equals(content.path("type").asText()) && imageBase64.isTextual()) {
                    try {
                        return Base64.getDecoder().decode(imageBase64.asText());
                    } catch (IllegalArgumentException exception) {
                        throw new GeminiResponseException(
                                "Gemini 응답의 이미지 데이터 형식이 올바르지 않습니다.",
                                httpStatus,
                                model,
                                imageSize,
                                exception);
                    }
                }
            }
        }
        throw new GeminiResponseException(
                "Gemini 응답에 이미지 데이터가 없습니다.",
                modelOutputContentTypes,
                httpStatus,
                model,
                imageSize);
    }

    private JsonNode readResponse(String responseBody, int httpStatus) {
        try {
            return objectMapper.readTree(responseBody);
        } catch (Exception exception) {
            throw new GeminiResponseException(
                    "Gemini 응답을 파싱하지 못했습니다.",
                    httpStatus,
                    model,
                    imageSize,
                    exception);
        }
    }

    private List<String> extractModelOutputContentTypes(JsonNode response) {
        List<String> contentTypes = new java.util.ArrayList<>();
        for (JsonNode step : response.path("steps")) {
            if (!"model_output".equals(step.path("type").asText())) {
                continue;
            }
            for (JsonNode content : step.path("content")) {
                if (content.path("type").isTextual()) {
                    contentTypes.add(content.path("type").asText());
                }
            }
        }
        return contentTypes;
    }

    private String storeImage(byte[] image) {
        try {
            return imageStorageClient.store(image, IMAGE_CONTENT_TYPE);
        } catch (Exception exception) {
            throw new ImageStorageException(exception);
        }
    }
}
