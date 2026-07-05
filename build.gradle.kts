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
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
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
val openApiServerUrl = providers.gradleProperty("openapi.server-url")
    .orElse(providers.environmentVariable("OPENAPI_SERVER_URL"))
    .orElse("http://localhost:8080")

tasks.withType<Test> {
    useJUnitPlatform()
    outputs.dir(generatedSnippetsDir)
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
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    from(generatedApiSpecResourceDir.map { it.asFile.parentFile.parentFile }) {
        into("BOOT-INF/classes")
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
    }
    tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
        dependsOn(tasks.named("openapi3"))
    }
}
