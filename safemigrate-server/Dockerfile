FROM eclipse-temurin:21-jre-alpine

# Create non-root system user and group for security
RUN addgroup -S safemigrate && adduser -S safemigrate -G safemigrate
USER safemigrate

WORKDIR /app

COPY target/safemigrate-server-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/migrations || exit 1

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
