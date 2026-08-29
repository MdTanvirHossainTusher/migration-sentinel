# syntax=docker/dockerfile:1.7

# ── Stage 1: build ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
RUN --mount=type=cache,id=gradle-home,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon dependencies --quiet || true

COPY src ./src
RUN --mount=type=cache,id=gradle-home,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon bootJar -x test && \
    java -Djarmode=tools -jar build/libs/*.jar extract --layers --launcher --destination build/extracted

# ── Stage 2: runtime ───────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN apk add --no-cache curl && \
    addgroup -S app && adduser -S app -G app
USER app

COPY --from=builder /workspace/build/extracted/dependencies/ ./
COPY --from=builder /workspace/build/extracted/spring-boot-loader/ ./
COPY --from=builder /workspace/build/extracted/snapshot-dependencies/ ./
COPY --from=builder /workspace/build/extracted/application/ ./

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=70.0"
EXPOSE 8080 9091
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} org.springframework.boot.loader.launch.JarLauncher"]
