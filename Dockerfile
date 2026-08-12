FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk-alpine AS builder

COPY --from=node:24-alpine /usr/local /usr/local

WORKDIR /app

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY package.json package-lock.json ./
COPY src/test/resources ./src/test/resources
RUN npm ci && ./gradlew dependencies --no-daemon

COPY src ./src
COPY scripts ./scripts
ARG OPENAPI_SERVER_URL=http://localhost:8080
ARG WEBSOCKET_DOCS_SERVER_URL=ws://localhost:8080/ws
RUN ./gradlew bootJar --no-daemon -Popenapi.server-url=$OPENAPI_SERVER_URL -Pwebsocket-docs.server-url=$WEBSOCKET_DOCS_SERVER_URL

FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

ENV TZ=Asia/Seoul
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Seoul"

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
