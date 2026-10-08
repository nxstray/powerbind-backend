# ── Stage 1: build jar pakai Maven ──────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# copy pom.xml dulu supaya layer dependency ke-cache selama pom.xml nggak berubah
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B clean package -DskipTests

# ── Stage 2: image runtime, cuma bawa jar hasil build ────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Hardening: proses JVM jalan sebagai user non-root (image yang jalan sebagai
# root ditandai oleh Trivy/docker scout). "app" = system user tanpa password.
RUN addgroup -S app && adduser -S app -G app

COPY --from=build --chown=app:app /app/target/*.jar app.jar

USER app

EXPOSE 8045
ENTRYPOINT ["java", "-jar", "app.jar"]
