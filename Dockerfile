# ── Stage 1: build jar pakai Maven ──────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# copy pom.xml dulu supaya layer dependency ke-cache selama pom.xml nggak berubah
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ── Stage 2: image runtime, cuma bawa jar hasil build ────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8045
ENTRYPOINT ["java", "-jar", "app.jar"]
