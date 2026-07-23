package com.igmo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.domain.SamplePrompt;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class SamplePromptProvider {

    private static final String SAMPLE_RESOURCE_PATH = "samples/sample-prompts.json";

    private final List<SamplePrompt> samplePrompts;

    @Autowired
    public SamplePromptProvider(ObjectMapper objectMapper) {
        this(objectMapper, readResource(SAMPLE_RESOURCE_PATH), SAMPLE_RESOURCE_PATH);
    }

    SamplePromptProvider(ObjectMapper objectMapper, String json, String source) {
        this.samplePrompts = parse(objectMapper, json, source);
    }

    public List<SamplePrompt> getAll() {
        return samplePrompts;
    }

    private static String readResource(String resourcePath) {
        try (InputStream inputStream = new ClassPathResource(resourcePath).getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("샘플 프롬프트를 불러오지 못했습니다. 리소스 경로=" + resourcePath, exception);
        }
    }

    // 샘플 풀이 비어 있으면 마감 자동 채우기가 동작할 수 없으므로 스타트업에서 즉시 실패시킨다.
    private static List<SamplePrompt> parse(ObjectMapper objectMapper, String json, String source) {
        JsonNode root = readTree(objectMapper, json, source);
        List<SamplePrompt> loaded = new ArrayList<>();
        for (JsonNode node : root) {
            loaded.add(toSamplePrompt(node, source));
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("샘플 프롬프트가 비어 있습니다. 리소스 경로=" + source);
        }
        return List.copyOf(loaded);
    }

    private static JsonNode readTree(ObjectMapper objectMapper, String json, String source) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("샘플 프롬프트를 파싱하지 못했습니다. 리소스 경로=" + source, exception);
        }
    }

    private static SamplePrompt toSamplePrompt(JsonNode node, String source) {
        String prompt = node.path("prompt").asText();
        String imageUrl = node.path("imageUrl").asText();
        if (prompt.isBlank() || imageUrl.isBlank()) {
            throw new IllegalStateException(
                    "샘플 프롬프트 항목에 prompt 또는 imageUrl이 없습니다. 리소스 경로=" + source);
        }
        return new SamplePrompt(prompt, imageUrl);
    }
}
