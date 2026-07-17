package com.igmo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.util.StringUtils;

@Component
public class GeminiImageGenerationClient implements ImageGenerationClient {

    private static final URI IMAGE_GENERATION_URI = URI.create("https://generativelanguage.googleapis.com/v1beta/interactions");
    private static final String IMAGE_CONTENT_TYPE = "image/jpeg";
    private static final int MAX_ERROR_BODY_LENGTH = 2_000;

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
        this(objectMapper, HttpClient.newHttpClient(), imageStorageClient, IMAGE_GENERATION_URI, apiKey, model, imageSize);
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
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("Gemini API key is required for image generation.");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(imageGenerationUri)
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
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "Image generation failed. status=%d, body=%s".formatted(
                                response.statusCode(),
                                formatErrorBody(response.body())));
            }
            return imageStorageClient.store(extractImage(response.body()), IMAGE_CONTENT_TYPE);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Image generation interrupted.", exception);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Image generation failed.", exception);
        }
    }

    private byte[] extractImage(String responseBody) throws java.io.IOException {
        JsonNode response = objectMapper.readTree(responseBody);
        for (JsonNode step : response.path("steps")) {
            if (!"model_output".equals(step.path("type").asText())) {
                continue;
            }
            for (JsonNode content : step.path("content")) {
                JsonNode imageBase64 = content.path("data");
                if ("image".equals(content.path("type").asText()) && imageBase64.isTextual()) {
                    return Base64.getDecoder().decode(imageBase64.asText());
                }
            }
        }
        throw new IllegalStateException("Image generation response does not contain output image data.");
    }

    private String formatErrorBody(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return "<empty>";
        }
        if (responseBody.length() <= MAX_ERROR_BODY_LENGTH) {
            return responseBody;
        }
        return responseBody.substring(0, MAX_ERROR_BODY_LENGTH) + "...<truncated>";
    }
}
