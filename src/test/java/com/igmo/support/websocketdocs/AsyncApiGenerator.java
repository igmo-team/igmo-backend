package com.igmo.support.websocketdocs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public class AsyncApiGenerator {

    private static final Path SNIPPET_DIRECTORY = Path.of("build", "generated-snippets", "websocket");
    private static final Path OVERVIEW_PATH = Path.of("src", "test", "resources", "websocket-docs", "overview.md");
    private static final Path OUTPUT_PATH = Path.of("build", "generated", "websocket-docs", "asyncapi.json");
    private static final String SERVER_URL_PROPERTY = "websocket.docs.server-url";

    private final ObjectMapper objectMapper;
    private final URI serverUri;

    public AsyncApiGenerator(ObjectMapper objectMapper) {
        this(objectMapper, System.getProperty(SERVER_URL_PROPERTY, "ws://localhost:8080/ws"));
    }

    public AsyncApiGenerator(ObjectMapper objectMapper, String serverUrl) {
        this.objectMapper = objectMapper;
        this.serverUri = parseServerUri(serverUrl);
    }

    public Path generate() throws IOException {
        List<JsonNode> snippets = readSnippets();
        if (snippets.isEmpty()) {
            throw new IllegalStateException("생성할 WebSocket 문서 snippet이 없습니다.");
        }

        ObjectNode document = objectMapper.createObjectNode();
        document.put("asyncapi", "3.1.0");
        document.put("defaultContentType", "application/json");
        document.putObject("info")
                .put("title", "IGMO WebSocket API")
                .put("version", "v1")
                .put("description", readOverview(snippets.getFirst()));
        addServer(document, snippets.getFirst());

        Map<String, OperationContract> requestOperations = collectRequestOperations(snippets);
        Map<String, MessageContract> messageContracts = collectMessageContracts(requestOperations.values());
        Map<DestinationKey, DestinationContract> receiveDestinations = collectReceiveDestinations(messageContracts.values());

        ObjectNode channels = document.putObject("channels");
        ObjectNode operations = document.putObject("operations");
        ObjectNode components = document.putObject("components");
        ObjectNode messages = components.putObject("messages");
        ObjectNode schemas = components.putObject("schemas");

        for (DestinationContract destination : receiveDestinations.values()) {
            addReceiveChannel(channels, destination);
            addServerReceiveOperation(operations, destination);
        }
        for (OperationContract operation : requestOperations.values()) {
            addClientSendChannel(channels, operation);
            addClientSendOperation(operations, operation);
        }
        for (MessageContract message : messageContracts.values()) {
            addMessage(messages, schemas, message);
        }
        for (OperationContract operation : requestOperations.values()) {
            addRequestMessage(messages, schemas, operation);
        }

        Files.createDirectories(OUTPUT_PATH.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(OUTPUT_PATH.toFile(), document);
        return OUTPUT_PATH;
    }

    private List<JsonNode> readSnippets() throws IOException {
        try (var paths = Files.list(SNIPPET_DIRECTORY)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(this::readSnippet)
                    .toList();
        }
    }

    private JsonNode readSnippet(Path path) {
        try {
            return objectMapper.readTree(path.toFile());
        } catch (IOException exception) {
            throw new IllegalStateException("WebSocket snippet을 읽을 수 없습니다: " + path, exception);
        }
    }

    private String readOverview(JsonNode snippet) throws IOException {
        if (!Files.exists(OVERVIEW_PATH)) {
            throw new IllegalStateException("WebSocket 문서 overview가 없습니다: " + OVERVIEW_PATH);
        }
        return Files.readString(OVERVIEW_PATH).replace("{{serverUrl}}", serverUri.toString());
    }

    private void addServer(ObjectNode document, JsonNode snippet) {
        String endpoint = requiredText(snippet.path("connection"), "endpoint");
        if (!endpoint.equals(serverUri.getPath())) {
            throw new IllegalStateException("WebSocket 문서 서버 URL 경로가 테스트 endpoint와 다릅니다: " + serverUri);
        }

        ObjectNode server = document.putObject("servers").putObject("websocket");
        server.put("host", serverUri.getAuthority());
        server.put("pathname", serverUri.getPath());
        server.put("protocol", serverUri.getScheme());
        server.put("title", "WebSocket 서버");
        server.put("description", "WebSocket endpoint: `" + serverUri + "`");
    }

    private URI parseServerUri(String serverUrl) {
        URI uri = URI.create(serverUrl);
        if (!Set.of("ws", "wss").contains(uri.getScheme()) || uri.getAuthority() == null || uri.getPath().isBlank()) {
            throw new IllegalArgumentException("WebSocket 문서 서버 URL은 ws 또는 wss endpoint여야 합니다: " + serverUrl);
        }
        return uri;
    }

    private Map<String, OperationContract> collectRequestOperations(List<JsonNode> snippets) {
        Map<String, OperationContract> operations = new LinkedHashMap<>();
        for (JsonNode snippet : snippets) {
            if (snippet.path("eventOnly").asBoolean()) {
                continue;
            }
            String operationId = requiredText(snippet, "operationId");
            OperationContract operation = operations.computeIfAbsent(operationId, ignored -> OperationContract.from(snippet));
            operation.merge(snippet);
        }
        return operations;
    }

    private Map<String, MessageContract> collectMessageContracts(Iterable<OperationContract> operations) {
        Map<String, MessageContract> messages = new LinkedHashMap<>();
        for (OperationContract operation : operations) {
            for (JsonNode triggered : operation.triggeredMessages) {
                MessageContract candidate = MessageContract.from(triggered);
                MessageContract existing = messages.putIfAbsent(candidate.messageId, candidate);
                if (existing != null) {
                    existing.merge(candidate);
                }
            }
        }
        return messages;
    }

    private Map<DestinationKey, DestinationContract> collectReceiveDestinations(Iterable<MessageContract> messages) {
        Map<DestinationKey, DestinationContract> destinations = new LinkedHashMap<>();
        for (MessageContract message : messages) {
            DestinationKey key = new DestinationKey(message.destination, message.scope);
            destinations.computeIfAbsent(key, ignored -> new DestinationContract(key)).add(message);
        }
        return destinations;
    }

    private void addClientSendChannel(ObjectNode channels, OperationContract operation) {
        String channelId = clientChannelId(operation);
        ObjectNode channel = channels.putObject(channelId);
        channel.put("address", operation.destination);
        channel.put("title", "Client → Server: " + operation.title);
        channel.put("description", "[Client → Server] SEND\n\n클라이언트가 서버에 보내는 요청 destination입니다.");
        addRoomCodeParameter(channel);
        channel.putObject("messages").putObject(operation.requestMessageId)
                .put("$ref", "#/components/messages/" + operation.requestMessageId);
    }

    private void addReceiveChannel(ObjectNode channels, DestinationContract destination) {
        ObjectNode channel = channels.putObject(destination.channelId());
        channel.put("address", destination.destination());
        channel.put("title", "Server → Client: " + destination.title());
        channel.put("description", receiveChannelDescription(destination));
        if (destination.destination().contains("{roomCode}")) {
            addRoomCodeParameter(channel);
        }
        ObjectNode channelMessages = channel.putObject("messages");
        for (MessageContract message : destination.messages()) {
            channelMessages.putObject(message.messageId)
                    .put("$ref", "#/components/messages/" + message.messageId);
        }
    }

    private void addClientSendOperation(ObjectNode operations, OperationContract operation) throws IOException {
        ObjectNode node = operations.putObject("send" + upperFirst(operation.operationId));
        node.put("action", "send");
        node.put("title", operation.destination);
        node.put("summary", "SEND · " + operation.destination);
        node.put("description", clientSendDescription(operation));
        addTags(node, operation.tags);
        node.putObject("channel").put("$ref", "#/channels/" + clientChannelId(operation));
        node.putArray("messages").addObject().put(
                "$ref", "#/channels/" + clientChannelId(operation) + "/messages/" + operation.requestMessageId
        );
    }

    private void addServerReceiveOperation(ObjectNode operations, DestinationContract destination) {
        ObjectNode node = operations.putObject("receive" + upperFirst(destination.channelId()));
        node.put("action", "receive");
        node.put("title", destination.destination());
        node.put("summary", "RECEIVE · " + destination.destination());
        node.put("description", receiveOperationDescription(destination));
        addTags(node, destination.tags());
        node.putObject("channel").put("$ref", "#/channels/" + destination.channelId());
        ArrayNode messageReferences = node.putArray("messages");
        for (MessageContract message : destination.messages()) {
            messageReferences.addObject().put(
                    "$ref", "#/channels/" + destination.channelId() + "/messages/" + message.messageId
            );
        }
    }

    private void addRequestMessage(ObjectNode messages, ObjectNode schemas, OperationContract operation) throws IOException {
        if (messages.has(operation.requestMessageId)) {
            return;
        }
        ObjectNode message = messages.putObject(operation.requestMessageId);
        message.put("title", operation.requestMessageId);
        message.put("summary", operation.requestDescription);
        message.put("description", requestMessageDescription(operation));
        message.put("contentType", "application/json");
        if (operation.requestExample == null || operation.requestExample.isNull()) {
            ObjectNode payload = message.putObject("payload");
            payload.put("type", "object");
            payload.put("description", "요청 body가 없습니다.");
            return;
        }
        String schemaId = operation.requestMessageId + "Schema";
        ObjectNode schema;
        if (operation.requestPayloadType == null) {
            schema = inferSchema(operation.requestExample);
        } else {
            try {
                Class<?> rawType = Class.forName(operation.requestPayloadType.rawType());
                schema = schemaForType(
                        rawType,
                        typeArguments(rawType, operation.requestPayloadType.typeArgument()),
                        operation.requestExample);
            } catch (ClassNotFoundException exception) {
                throw new IllegalStateException(
                        "WebSocket request payloadType을 찾을 수 없습니다: " + operation.requestPayloadType.rawType(),
                        exception);
            }
        }
        schemas.set(schemaId, schema);
        message.putArray("examples").addObject().set("payload", operation.requestExample);
        message.putObject("payload").put("$ref", "#/components/schemas/" + schemaId);
    }

    private void addMessage(ObjectNode messages, ObjectNode schemas, MessageContract contract) throws IOException {
        if (messages.has(contract.messageId)) {
            return;
        }
        String schemaId = contract.messageId + "Schema";
        schemas.set(schemaId, generateSchema(contract));
        ObjectNode message = messages.putObject(contract.messageId);
        message.put("title", contract.title);
        message.put("summary", contract.description);
        message.put("description", receivedMessageDescription(contract));
        message.put("contentType", "application/json");
        message.putArray("examples").addObject().set("payload", contract.example);
        message.putObject("payload").put("$ref", "#/components/schemas/" + schemaId);
    }

    private String clientSendDescription(OperationContract operation) throws IOException {
        StringBuilder text = new StringBuilder("[Client → Server] SEND\n\n")
                .append("### 1. 언제 사용하는가\n\n")
                .append(operation.whenToSend).append("\n\n")
                .append("### 2. 먼저 구독해야 하는 Destination\n\n");
        subscriptionsOf(operation).forEach(destination -> text.append("- `").append(destination).append("`\n"));
        text.append("\n### 3. SEND Destination\n\n`").append(operation.destination).append("`\n\n")
                .append("### 4. 요청 Payload\n\n")
                .append("`").append(operation.requestMessageId).append("`\n\n");
        if (operation.requestExample == null || operation.requestExample.isNull()) {
            text.append("요청 body가 없습니다.\n");
        } else {
            text.append("```json\n").append(prettyJson(operation.requestExample)).append("\n```\n");
        }
        text.append("\n### 5. 이 SEND와 관련해 발생할 수 있는 메시지\n\n")
                .append("아래 목록은 E2E 테스트에서 관찰한 관련 메시지입니다. HTTP 응답이 아니며, ")
                .append("모든 메시지의 발생과 도착 순서를 보장하지 않습니다. 오류 메시지는 요청 조건에 따라 발생할 수 있습니다.\n\n");
        for (JsonNode message : operation.triggeredMessages) {
            text.append("- `").append(message.path("destination").asText()).append("` → `")
                    .append(message.path("title").asText()).append("` · ")
                    .append(scopeDescription(message.path("scope").asText())).append(" · ")
                    .append(relationshipDescription(message.path("relationship").asText())).append("\n");
        }
        text.append("\n### 6. Broadcast / User Queue 구분\n\n")
                .append("- Broadcast: 같은 방의 `/topic/rooms/{roomCode}` 구독자 전체\n")
                .append("- User Queue: 현재 연결의 STOMP 사용자만\n\n")
                .append("### 7. 클라이언트 처리 방법\n\n");
        for (JsonNode message : operation.triggeredMessages) {
            text.append("- `").append(message.path("title").asText()).append("`: ")
                    .append(message.path("clientAction").asText()).append("\n");
        }
        return text.toString();
    }

    private String receiveChannelDescription(DestinationContract destination) {
        StringBuilder text = new StringBuilder("[Server → Client] RECEIVE\n\n")
                .append("**Destination:** `").append(destination.destination()).append("`\n\n")
                .append("**수신 범위:** ").append(scopeDescription(destination.scope())).append("\n\n")
                .append("**수신 가능한 메시지:**\n");
        destination.messages().forEach(message -> text.append("- `").append(message.title).append("`\n"));
        return text.toString();
    }

    private String receiveOperationDescription(DestinationContract destination) {
        StringBuilder text = new StringBuilder("[Server → Client] RECEIVE\n\n")
                .append("### Destination\n\n`").append(destination.destination()).append("`\n\n")
                .append("### 수신 범위\n\n").append(scopeDescription(destination.scope())).append("\n\n")
                .append("### 이 Destination에서 수신할 수 있는 메시지\n\n");
        for (MessageContract message : destination.messages()) {
            text.append("- `").append(message.title).append("`: ").append(message.description)
                    .append(". ").append(message.clientAction).append("\n");
        }
        return text.toString();
    }

    private String requestMessageDescription(OperationContract operation) throws IOException {
        if (operation.requestExample == null || operation.requestExample.isNull()) {
            return "### 요청 Payload\n\n요청 body가 없습니다.";
        }
        return "### 요청 Payload\n\n```json\n" + prettyJson(operation.requestExample) + "\n```";
    }

    private String receivedMessageDescription(MessageContract message) throws IOException {
        return "### 메시지 의미\n\n" + message.description
                + "\n\n### 클라이언트 처리\n\n" + message.clientAction
                + "\n\n### 대표 JSON Example\n\n```json\n" + prettyJson(message.example) + "\n```";
    }

    private Set<String> subscriptionsOf(OperationContract operation) {
        Set<String> subscriptions = new LinkedHashSet<>();
        operation.triggeredMessages.forEach(message -> subscriptions.add(message.path("destination").asText()));
        return subscriptions;
    }

    private String prettyJson(JsonNode node) throws IOException {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    private void addRoomCodeParameter(ObjectNode channel) {
        ObjectNode roomCode = channel.putObject("parameters").putObject("roomCode");
        roomCode.put("description", "대상 게임방 코드입니다.");
        roomCode.putArray("examples").add("<roomCode>");
    }

    private void addTags(ObjectNode operation, Set<String> tags) {
        ArrayNode values = operation.putArray("tags");
        tags.forEach(tag -> values.addObject().put("name", tag));
    }

    private ObjectNode inferSchema(JsonNode value) {
        ObjectNode schema = objectMapper.createObjectNode();
        if (value.isObject()) {
            schema.put("type", "object");
            ObjectNode properties = schema.putObject("properties");
            ArrayNode required = schema.putArray("required");
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                properties.set(field.getKey(), inferSchema(field.getValue()));
                required.add(field.getKey());
            }
            return schema;
        }
        if (value.isArray()) {
            schema.put("type", "array");
            if (value.isEmpty()) {
                schema.putObject("items");
            } else {
                schema.set("items", inferSchema(value.get(0)));
            }
            return schema;
        }
        if (value.isBoolean()) {
            schema.put("type", "boolean");
            return schema;
        }
        if (value.isIntegralNumber()) {
            schema.put("type", "integer");
            return schema;
        }
        if (value.isFloatingPointNumber()) {
            schema.put("type", "number");
            return schema;
        }
        if (value.isNull()) {
            schema.put("type", "null");
            return schema;
        }
        schema.put("type", "string");
        return schema;
    }

    private ObjectNode generateSchema(MessageContract contract) {
        ObjectNode schema;
        if (contract.payloadType == null) {
            schema = inferSchema(contract.example);
        } else {
            try {
                Class<?> rawType = Class.forName(contract.payloadType.rawType);
                Map<TypeVariable<?>, Type> typeArguments = typeArguments(rawType, contract.payloadType.typeArgument);
                schema = schemaForType(rawType, typeArguments, contract.example);
            } catch (ClassNotFoundException exception) {
                throw new IllegalStateException("WebSocket payloadType을 찾을 수 없습니다: " + contract.payloadType.rawType, exception);
            }
        }
        restrictEnumToObservedExample(schema, contract.example, "status");
        restrictEnumToObservedExample(schema, contract.example, "type");
        return schema;
    }

    private void restrictEnumToObservedExample(ObjectNode schema, JsonNode example, String fieldName) {
        JsonNode observedValue = example.path(fieldName);
        JsonNode fieldSchema = schema.path("properties").path(fieldName);
        if (!observedValue.isTextual() || !fieldSchema.isObject() || !fieldSchema.has("enum")) {
            return;
        }
        ArrayNode allowedValues = ((ObjectNode) fieldSchema).putArray("enum");
        allowedValues.add(observedValue.asText());
    }

    private Map<TypeVariable<?>, Type> typeArguments(Class<?> rawType, String typeArgument) throws ClassNotFoundException {
        if (typeArgument == null) {
            return Map.of();
        }
        TypeVariable<?>[] variables = rawType.getTypeParameters();
        if (variables.length != 1) {
            throw new IllegalStateException("payloadType typeArgument을 적용할 수 없습니다: " + rawType.getName());
        }
        return Map.of(variables[0], Class.forName(typeArgument));
    }

    private ObjectNode schemaForType(Type type, Map<TypeVariable<?>, Type> typeArguments, JsonNode example) {
        if (type instanceof TypeVariable<?> variable) {
            Type resolved = typeArguments.get(variable);
            if (resolved == null) {
                return inferSchema(example);
            }
            return schemaForType(resolved, typeArguments, example);
        }
        if (type instanceof ParameterizedType parameterizedType) {
            Type rawType = parameterizedType.getRawType();
            if (rawType instanceof Class<?> rawClass && Iterable.class.isAssignableFrom(rawClass)) {
                ObjectNode schema = objectMapper.createObjectNode();
                schema.put("type", "array");
                JsonNode itemExample = example != null && example.isArray() && !example.isEmpty() ? example.get(0) : null;
                schema.set("items", schemaForType(parameterizedType.getActualTypeArguments()[0], typeArguments, itemExample));
                return schema;
            }
            return schemaForType(rawType, typeArguments, example);
        }
        if (!(type instanceof Class<?> clazz)) {
            return inferSchema(example);
        }
        if (clazz.isEnum()) {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "string");
            ArrayNode values = schema.putArray("enum");
            for (Object constant : clazz.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
            return nullable(schema, example);
        }
        if (clazz.isRecord()) {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode properties = schema.putObject("properties");
            ArrayNode required = schema.putArray("required");
            for (RecordComponent component : clazz.getRecordComponents()) {
                JsonNode propertyExample = example != null && example.isObject() ? example.get(component.getName()) : null;
                properties.set(component.getName(), schemaForType(component.getGenericType(), typeArguments, propertyExample));
                required.add(component.getName());
            }
            return nullable(schema, example);
        }
        ObjectNode schema = primitiveSchema(clazz, example);
        return nullable(schema, example);
    }

    private ObjectNode primitiveSchema(Class<?> type, JsonNode example) {
        ObjectNode schema = objectMapper.createObjectNode();
        if (type == boolean.class || type == Boolean.class) {
            schema.put("type", "boolean");
            return schema;
        }
        if (type == byte.class || type == Byte.class || type == short.class || type == Short.class
                || type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            schema.put("type", "integer");
            return schema;
        }
        if (type == float.class || type == Float.class || type == double.class || type == Double.class) {
            schema.put("type", "number");
            return schema;
        }
        schema.put("type", "string");
        if (type.getPackageName().equals("java.time")) {
            schema.put("format", "date-time");
        }
        return schema;
    }

    private ObjectNode nullable(ObjectNode schema, JsonNode example) {
        if (example == null || !example.isNull() || !schema.path("type").isTextual()) {
            return schema;
        }
        String type = schema.path("type").asText();
        schema.remove("type");
        schema.putArray("type").add(type).add("null");
        if (schema.has("enum")) {
            ((ArrayNode) schema.get("enum")).addNull();
        }
        return schema;
    }

    private String clientChannelId(OperationContract operation) {
        return "clientSend" + upperFirst(operation.operationId);
    }

    private String channelId(String destination, String scope) {
        String normalized = destination.replaceAll("[^A-Za-z0-9]", " ").trim().replaceAll(" +", " ");
        StringBuilder id = new StringBuilder(scope.equals("USER") ? "user" : "topic");
        for (String part : normalized.split(" ")) {
            id.append(upperFirst(part));
        }
        return id.toString();
    }

    private String scopeDescription(String scope) {
        return scope.equals("USER")
                ? "현재 연결의 STOMP 사용자에게만 전달되는 개인 큐"
                : "같은 게임방의 `/topic/rooms/{roomCode}` 구독자 전체에게 전달되는 Broadcast";
    }

    private String relationshipDescription(String relationship) {
        return switch (relationship) {
            case "DIRECT" -> "SEND 처리 중 관찰됨";
            case "FOLLOW_UP" -> "후속 상태 전환 후 관찰됨";
            default -> relationship;
        };
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalStateException("WebSocket snippet의 " + field + " 값이 비어 있습니다.");
        }
        return value;
    }

    private String upperFirst(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private record DestinationKey(String destination, String scope) {
    }

    private final class DestinationContract {

        private final DestinationKey key;
        private final Map<String, MessageContract> messages = new LinkedHashMap<>();
        private final Set<String> tags = new LinkedHashSet<>();

        private DestinationContract(DestinationKey key) {
            this.key = key;
        }

        private void add(MessageContract message) {
            MessageContract existing = messages.putIfAbsent(message.messageId, message);
            if (existing != null && existing != message) {
                throw new IllegalStateException("동일 destination의 message 계약이 중복되었습니다: " + message.messageId);
            }
            tags.addAll(message.tags);
        }

        private String destination() {
            return key.destination;
        }

        private String scope() {
            return key.scope;
        }

        private String channelId() {
            return AsyncApiGenerator.this.channelId(destination(), scope());
        }

        private String title() {
            if (scope().equals("BROADCAST")) {
                return "방 전체 이벤트 수신";
            }
            return switch (destination()) {
                case "/user/queue/image-generation" -> "이미지 생성 상태 개인 메시지";
                case "/user/queue/guess-submission" -> "추측 제출 결과 개인 메시지";
                case "/user/queue/vote-own-option" -> "본인 투표 보기 개인 메시지";
                case "/user/queue/errors" -> "개인 오류 메시지";
                default -> "개인 메시지 수신";
            };
        }

        private List<MessageContract> messages() {
            return List.copyOf(messages.values());
        }

        private Set<String> tags() {
            return tags;
        }
    }

    private static final class OperationContract {

        private final String operationId;
        private final String title;
        private final String destination;
        private final String requestDescription;
        private final String whenToSend;
        private final String requestMessageId;
        private final JsonNode requestExample;
        private final PayloadTypeContract requestPayloadType;
        private final Set<String> tags;
        private final List<JsonNode> triggeredMessages = new ArrayList<>();

        private OperationContract(JsonNode snippet) {
            operationId = required(snippet, "operationId");
            title = required(snippet, "title");
            destination = required(snippet.path("request"), "destination");
            requestDescription = required(snippet.path("request"), "description");
            whenToSend = required(snippet, "whenToSend");
            requestMessageId = required(snippet.path("request"), "messageId");
            requestExample = snippet.path("request").get("example");
            requestPayloadType = PayloadTypeContract.from(snippet.path("request").path("payloadType"));
            tags = tagsOf(snippet.path("tags"));
        }

        static OperationContract from(JsonNode snippet) {
            return new OperationContract(snippet);
        }

        void merge(JsonNode snippet) {
            JsonNode request = snippet.path("request");
            if (!destination.equals(required(request, "destination"))
                    || !requestMessageId.equals(required(request, "messageId"))
                    || !Objects.equals(requestPayloadType,
                    PayloadTypeContract.from(request.path("payloadType")))
                    || !schemaShape(requestExample).equals(schemaShape(request.get("example")))) {
                throw new IllegalStateException("동일 operationId의 요청 계약이 다릅니다: " + operationId);
            }
            for (JsonNode message : snippet.path("triggeredMessages")) {
                String messageId = required(message, "messageId");
                boolean exists = triggeredMessages.stream()
                        .anyMatch(existing -> required(existing, "messageId").equals(messageId));
                if (!exists) {
                    triggeredMessages.add(message);
                }
            }
        }
    }

    private static final class MessageContract {

        private final String messageId;
        private final String title;
        private final String destination;
        private final String scope;
        private final String description;
        private final String clientAction;
        private final JsonNode example;
        private final PayloadTypeContract payloadType;
        private final Set<String> tags;

        private MessageContract(JsonNode node) {
            messageId = required(node, "messageId");
            title = required(node, "title");
            destination = required(node, "destination");
            scope = required(node, "scope");
            description = required(node, "description");
            clientAction = required(node, "clientAction");
            example = node.get("example");
            payloadType = PayloadTypeContract.from(node.path("payloadType"));
            tags = tagsOf(node.path("tags"));
        }

        static MessageContract from(JsonNode node) {
            return new MessageContract(node);
        }

        void merge(MessageContract candidate) {
            if (!destination.equals(candidate.destination)
                    || !scope.equals(candidate.scope)
                    || !title.equals(candidate.title)
                    || !Objects.equals(payloadType, candidate.payloadType)
                    || !schemaShape(example).equals(schemaShape(candidate.example))) {
                throw new IllegalStateException("동일 messageId의 메시지 계약이 다릅니다: " + messageId);
            }
            tags.addAll(candidate.tags);
        }
    }

    private record PayloadTypeContract(String rawType, String typeArgument) {

        private static PayloadTypeContract from(JsonNode node) {
            if (node.isMissingNode() || node.isNull()) {
                return null;
            }
            return new PayloadTypeContract(required(node, "rawType"), node.path("typeArgument").asText(null));
        }
    }

    private static Set<String> tagsOf(JsonNode array) {
        Set<String> tags = new LinkedHashSet<>();
        array.forEach(tag -> tags.add(tag.asText()));
        return tags;
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalStateException("WebSocket snippet의 " + field + " 값이 비어 있습니다.");
        }
        return value;
    }

    private static String schemaShape(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return "null";
        }
        if (value.isObject()) {
            Map<String, String> fields = new TreeMap<>();
            value.fields().forEachRemaining(field -> fields.put(field.getKey(), schemaShape(field.getValue())));
            return "object" + fields;
        }
        if (value.isArray()) {
            return value.isEmpty() ? "array[]" : "array[" + schemaShape(value.get(0)) + "]";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        if (value.isIntegralNumber()) {
            return "integer";
        }
        if (value.isFloatingPointNumber()) {
            return "number";
        }
        return "string";
    }
}
