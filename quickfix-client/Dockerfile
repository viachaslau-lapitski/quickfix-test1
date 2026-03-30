# Build context: repo root  (docker build -f quickfix-client/Dockerfile .)
# Stage 1: compile fat JAR
FROM gradle:8.5-jdk17 AS build
WORKDIR /src

# --- dependency cache layer (only busted when build files change) ---
COPY settings.gradle build.gradle gradlew ./
COPY gradle/                              gradle/
COPY quickfix-client/build.gradle         quickfix-client/
RUN ./gradlew :quickfix-client:dependencies --no-daemon -q 2>/dev/null || true

# --- source compile (busted only when source changes) ---
COPY . .
RUN ./gradlew :quickfix-client:shadowJar --no-daemon -q

# Stage 2: minimal runtime image
FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends iproute2 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /src/quickfix-client/build/libs/quickfix-client-all.jar client.jar
COPY docker/entrypoint-client.sh entrypoint.sh
RUN chmod +x entrypoint.sh

ENTRYPOINT ["/app/entrypoint.sh"]
