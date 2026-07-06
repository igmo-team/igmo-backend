FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /app

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src/test/resources ./src/test/resources
RUN ./gradlew dependencies --no-daemon

COPY src ./src
ARG OPENAPI_SERVER_URL=http://localhost:8080
RUN ./gradlew bootJar --no-daemon -Popenapi.server-url=$OPENAPI_SERVER_URL

FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
