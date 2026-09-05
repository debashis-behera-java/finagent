# ============================================================
# FINAGENT backend — multi-stage image (Phase 12, hardened Phase 17)
# Stage 1: Maven build (Java 21) → Stage 2: minimal JRE runtime.
# The final image contains only the Spring Boot jar + curl (for the
# Compose healthcheck). No source, no Maven cache, no secrets.
# Run as non-root user `finagent`.
# Phase 17: container-aware heap (75% of container memory) via
# MaxRAMPercentage; all configuration (profiles, secrets) arrives at
# runtime via environment — nothing is baked in.
# ============================================================

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Dependency layer first for better rebuild caching.
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

# --- Runtime ---
FROM eclipse-temurin:21-jre
RUN groupadd -r finagent && useradd -r -g finagent finagent \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
# Pinned by spring-boot-maven-plugin <finalName>finagent-backend</finalName>.
COPY --from=build --chown=finagent:finagent /app/target/finagent-backend.jar app.jar

USER finagent
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
