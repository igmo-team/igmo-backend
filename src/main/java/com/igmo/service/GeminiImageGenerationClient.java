package com.igmo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GeminiImageGenerationClient implements ImageGenerationClient {

    private static final URI IMAGE_GENERATION_URI = URI.create("https://generativelanguage.googleapis.com/v1beta/interactions");
    private static final String DATA_URL_PREFIX = "data:image/png;base64,";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI imageGenerationUri;
    private final String apiKey;
    private final String model;
    private final String imageSize;

    @Autowired
    public GeminiImageGenerationClient(
            ObjectMapper objectMapper,
            @Value("${igmo.ai.gemini.api-key}") String apiKey,
            @Value("${igmo.ai.gemini.model}") String model,
            @Value("${igmo.ai.gemini.image-size}") String imageSize
    ) {
        this(objectMapper, HttpClient.newHttpClient(), IMAGE_GENERATION_URI, apiKey, model, imageSize);
    }

    GeminiImageGenerationClient(
            ObjectMapper objectMapper,
            HttpClient httpClient,
            URI imageGenerationUri,
            String apiKey,
            String model,
            String imageSize
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
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
                                            "mime_type", "image/png",
                                            "image_size", imageSize)))))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Image generation failed with status " + response.statusCode());
            }
            return DATA_URL_PREFIX + extractImageBase64(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Image generation interrupted.", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Image generation failed.", exception);
        }
    }

    private String extractImageBase64(String responseBody) throws java.io.IOException {
        JsonNode response = objectMapper.readTree(responseBody);
        JsonNode imageBase64 = response.path("output_image").path("data");
        if (imageBase64.isMissingNode()) {
            imageBase64 = response.path("outputImage").path("data");
        }
        if (!imageBase64.isTextual()) {
            throw new IllegalStateException("Image generation response does not contain output image data.");
        }
        return imageBase64.asText();
    }
}
