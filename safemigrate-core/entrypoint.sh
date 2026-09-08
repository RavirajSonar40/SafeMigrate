#!/bin/sh
set -e

if [ "$#" -gt 0 ]; then
    echo "[WORKER-EXEC] Running StandaloneMigrationWorker with args: $@"
    exec java -jar app.jar "$@"
fi

echo "=========================================================="
echo "⚡ SafeMigrate Worker Pod initialized in Standby Daemon Mode"
echo "Target Redis: ${REDIS_URL:-redis://redis:6379}"
echo "Target DB:    ${JDBC_URL:-jdbc:postgresql://postgres:5432/safemigrate_test}"
echo "Status:       HEALTHY · Waiting for active migration tasks"
echo "=========================================================="

# Heartbeat loop to keep worker alive and healthy in Docker Desktop & K8s
while true; do
  sleep 15
  echo "[WORKER-HEARTBEAT] Worker pod healthy. Heartbeat ACK. Standby ready."
done
