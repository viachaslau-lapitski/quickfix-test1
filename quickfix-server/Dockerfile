# Build context: repo root  (docker build -f quickfix-server/Dockerfile .)
# Stage 1: compile fat JAR
FROM gradle:8.5-jdk17 AS build
WORKDIR /src

# --- dependency cache layer (only busted when build files change) ---
COPY settings.gradle build.gradle gradlew ./
COPY gradle/                              gradle/
COPY quickfix-server/build.gradle         quickfix-server/
RUN ./gradlew :quickfix-server:dependencies --no-daemon -q 2>/dev/null || true

# --- source compile (busted only when source changes) ---
COPY . .
RUN ./gradlew :quickfix-server:shadowJar --no-daemon -q

# Stage 2: minimal runtime image
FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends iproute2 netcat-openbsd \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /src/quickfix-server/build/libs/quickfix-server-all.jar server.jar
COPY docker/entrypoint-server.sh entrypoint.sh
RUN chmod +x entrypoint.sh

EXPOSE 9876
ENTRYPOINT ["/app/entrypoint.sh"]
