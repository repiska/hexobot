# =============================================================
# Stage 1: Build uberjar
# =============================================================
FROM clojure:temurin-21-tools-deps-alpine AS builder

WORKDIR /build

# Download dependencies first (separate layer = better cache)
COPY deps.edn build.clj ./
RUN clojure -P -M:run
RUN clojure -T:build clean

# Copy source and build
COPY src/       src/
COPY resources/ resources/
RUN clojure -T:build uber

# =============================================================
# Stage 2: Minimal runtime image
# =============================================================
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Non-root user for security + writable logs directory
RUN addgroup -S appgroup && adduser -S appuser -G appgroup && \
    mkdir -p /app/logs && chown appuser:appgroup /app/logs

COPY --from=builder /build/target/chatbot-standalone.jar app.jar

USER appuser

EXPOSE 3000

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Dfile.encoding=UTF-8", \
  "-Dclojure.main.report=stderr", \
  "-jar", "app.jar"]
