plugins {
    java
    id("org.springframework.boot") version "3.5.15"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.epages.restdocs-api-spec") version "0.19.4"
}

group = "com"
version = "0.0.1-SNAPSHOT"
description = "igmo"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("software.amazon.awssdk:bom:2.46.17"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("software.amazon.awssdk:s3")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    compileOnly("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.restdocs:spring-restdocs-mockmvc")
    testImplementation("com.epages:restdocs-api-spec-mockmvc:0.19.4")
    testCompileOnly("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testAnnotationProcessor("org.projectlombok:lombok")
}

val generatedSnippetsDir = layout.buildDirectory.dir("generated-snippets")
val generatedApiSpecResourceDir = layout.buildDirectory.dir("generated/resources/static/api-spec")
val generatedWebSocketDocsDir = layout.buildDirectory.dir("generated/websocket-docs")
val generatedWebSocketDocsHtmlDir = generatedWebSocketDocsDir.map { it.dir("html") }
val generatedWebSocketDocsResourceDir = layout.buildDirectory.dir("generated/websocket-docs/resources/static/websocket-docs")
val openApiServerUrl = providers.gradleProperty("openapi.server-url")
    .orElse(providers.environmentVariable("OPENAPI_SERVER_URL"))
    .orElse("http://localhost:8080")
val webSocketDocsServerUrl = providers.gradleProperty("websocket-docs.server-url")
    .orElse(providers.environmentVariable("WEBSOCKET_DOCS_SERVER_URL"))
    .orElse("ws://localhost:8080/ws")

tasks.withType<Test> {
    useJUnitPlatform()
    outputs.dir(generatedSnippetsDir)
    systemProperty("websocket.docs.server-url", webSocketDocsServerUrl.get())
}

val generateWebSocketDocs by tasks.registering(Exec::class) {
    group = "documentation"
    description = "WebSocket E2E 테스트 결과로 AsyncAPI HTML 문서를 생성합니다."
    dependsOn(tasks.test)
    inputs.files("package.json", "package-lock.json")
    inputs.dir("scripts")
    inputs.dir("src/test/resources/websocket-docs")
    inputs.dir(generatedWebSocketDocsDir)
    outputs.dir(generatedWebSocketDocsHtmlDir)
    commandLine("npm", "run", "asyncapi:generate-html")
}

val validateWebSocketDocs by tasks.registering(Exec::class) {
    group = "verification"
    description = "생성된 AsyncAPI 명세를 검증합니다."
    dependsOn(generateWebSocketDocs)
    inputs.file(generatedWebSocketDocsDir.map { it.file("asyncapi.json") })
    commandLine("npm", "run", "asyncapi:validate")
}

val processWebSocketDocs by tasks.registering(Copy::class) {
    group = "documentation"
    description = "생성된 WebSocket 문서를 Spring Boot 정적 리소스로 준비합니다."
    dependsOn(validateWebSocketDocs)
    from(generatedWebSocketDocsHtmlDir)
    into(generatedWebSocketDocsResourceDir)
}

openapi3 {
    setServer(openApiServerUrl.get())
    title = "IGMO Server API"
    description = "IGMO REST API 문서입니다. WebSocket 메시지는 별도 문서로 관리합니다."
    version = "v1"
    format = "yaml"
    tagDescriptionsPropertiesFile = "src/test/resources/openapi-tags.yaml"
    outputDirectory = generatedApiSpecResourceDir.get().asFile.path
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    classpath(generatedApiSpecResourceDir.map { it.asFile.parentFile.parentFile })
    classpath(generatedWebSocketDocsResourceDir.map { it.asFile.parentFile.parentFile })
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    from(generatedApiSpecResourceDir) {
        into("BOOT-INF/classes/static/api-spec")
    }
    from(generatedWebSocketDocsResourceDir) {
        into("BOOT-INF/classes/static/websocket-docs")
    }
}

afterEvaluate {
    tasks.named("openapi3") {
        dependsOn(tasks.test)
        doFirst {
            generatedApiSpecResourceDir.get().asFile.mkdirs()
        }
    }
    tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
        dependsOn(tasks.named("openapi3"))
        dependsOn(processWebSocketDocs)
    }
    tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
        dependsOn(tasks.named("openapi3"))
        dependsOn(processWebSocketDocs)
    }
}
