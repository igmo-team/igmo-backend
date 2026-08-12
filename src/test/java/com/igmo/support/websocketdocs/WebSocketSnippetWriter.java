package com.igmo.support.websocketdocs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class WebSocketSnippetWriter {

    private static final Path OUTPUT_DIRECTORY = Path.of("build", "generated-snippets", "websocket");

    private final ObjectMapper objectMapper;

    public WebSocketSnippetWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Path write(String operationId, ObjectNode snippet) throws IOException {
        Files.createDirectories(OUTPUT_DIRECTORY);
        Path outputPath = OUTPUT_DIRECTORY.resolve(operationId + ".json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), snippet);
        return outputPath;
    }
}
