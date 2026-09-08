FROM eclipse-temurin:21-jre-alpine

# Create non-root system user and group
RUN addgroup -S safemigrate && adduser -S safemigrate -G safemigrate

WORKDIR /app

COPY target/safemigrate-core-1.0.0-SNAPSHOT-worker.jar app.jar
COPY entrypoint.sh entrypoint.sh

# Strip CRLF if present and set execute permissions
RUN sed -i 's/\r$//' entrypoint.sh && chmod +x entrypoint.sh && chown -R safemigrate:safemigrate /app

USER safemigrate

ENTRYPOINT ["/app/entrypoint.sh"]
