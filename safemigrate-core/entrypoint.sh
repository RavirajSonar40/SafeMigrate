#!/bin/sh
set -e

if [ "$#" -gt 0 ]; then
    exec java -jar app.jar "$@"
fi

# Fallback to environment variables if no CLI args are supplied
exec java -jar app.jar \
    "${JDBC_URL:-jdbc:postgresql://postgres:5432/safemigrate_test}" \
    "${DB_USER:-postgres}" \
    "${DB_PASS:-password}" \
    "${REDIS_URL:-redis://redis:6379}" \
    "${MIGRATION_ID:-mig-docker-fleet-01}" \
    "${SOURCE_TABLE:-users}" \
    "${SHADOW_TABLE:-users_shadow}" \
    "${PK_COLUMN:-id}" \
    "${COLUMNS:-id,email,status,created_at}" \
    "${BATCH_SIZE:-500}" \
    "${THROTTLE_MS:-100}"
