# SafeMigrate ⚡

> **High-Performance Zero-Downtime Online Schema Migration Platform for PostgreSQL**  
> Inspired by GitHub's `gh-ost`, engineered in **Java 21 LTS** with **Virtual Threads (Project Loom)**, **Kafka KRaft CDC**, **Redis Distributed State**, a **Spring Boot 3 Control Plane**, and a **Next.js 16 Operator Cockpit**.

[![Java](https://img.shields.io/badge/Java-21%20LTS-orange.svg?style=flat-square&logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.4-brightgreen.svg?style=flat-square&logo=springboot)](https://spring.io/projects/spring-boot)
[![Next.js](https://img.shields.io/badge/Next.js-16.3-black.svg?style=flat-square&logo=next.js)](https://nextjs.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16%20Logical%20Replication-blue.svg?style=flat-square&logo=postgresql)](https://www.postgresql.org/)
[![Kafka](https://img.shields.io/badge/Apache%20Kafka-3.7%20KRaft-red.svg?style=flat-square&logo=apachekafka)](https://kafka.apache.org/)
[![Redis](https://img.shields.io/badge/Redis-7%20AOF-crimson.svg?style=flat-square&logo=redis)](https://redis.io/)
[![Kubernetes](https://img.shields.io/badge/Kubernetes-Production%20Manifests-326CE5.svg?style=flat-square&logo=kubernetes)](https://kubernetes.io/)
[![Docker](https://img.shields.io/badge/Docker-Desktop%20Pod%20Stack-2496ED.svg?style=flat-square&logo=docker)](https://www.docker.com/)

---

## 📌 Problem & Solution Overview

### The Problem: Dangerous Exclusive Table Locks
In large-scale production databases running tables with millions of rows under continuous read/write load, running a standard DDL migration (such as `ALTER TABLE ADD COLUMN`, changing data types, or rewriting constraints) acquires an **`ACCESS EXCLUSIVE` lock**.
- All incoming queries (even reads) queue behind the lock.
- Application connection pools exhaust within milliseconds.
- Catastrophic cascading failures, request timeouts, and SLA breaches occur.

### The SafeMigrate Solution
SafeMigrate executes migrations **asynchronously and concurrently with live production traffic** without ever holding long-lived locks:

1. **Pre-Flight Safety Analysis**: Inspects primary keys, foreign key cascades, column data type compatibility, disk storage headroom, and active query lock queues before any changes begin.
2. **Shadow Table Provisioning**: Creates an unconstrained shadow table (`_sm_shadow_orders`) with the intended target schema.
3. **Continuous CDC Streaming via Kafka**: Connects to PostgreSQL's native logical replication stream (`PGReplicationStream`) on Java 21 Virtual Threads, publishing every live `INSERT`, `UPDATE`, and `DELETE` into Kafka partitioned strictly by primary key.
4. **Idempotent Historical Backfill**: Reads the source table in configurable primary key chunks (`0..10000`, `10001..20000`) with adaptive backpressure and writes to the shadow table using `INSERT ON CONFLICT DO NOTHING`.
5. **Deterministic Change Replay**: Concurrently applies incoming CDC events to the shadow table with UPSERT fallback logic, guaranteeing zero data loss even when updates arrive ahead of backfill chunks.
6. **Sub-20ms Atomic Cutover**: Once backfill reaches 100% and replication lag drops below the safety threshold, executes an instantaneous atomic swap inside a single transaction bounded by a strict `SET LOCAL lock_timeout = '2000ms'` ceiling.
7. **Instant Safe Rollback**: If lock acquisition times out or an operator aborts, the shadow table is dropped and replication slots are cleaned up with zero downtime to the primary table.

---

## 🏗️ System Architecture

```
                               ┌─────────────────────────────────────────┐
                               │       Live Application Traffic          │
                               └────────────────────┬────────────────────┘
                                                    │
                             INSERT / UPDATE / DELETE reads & writes
                                                    │
                                                    ▼
                                     ┌─────────────────────────────┐
                                     │     Source Table: orders    │
                                     └──────────────┬──────────────┘
                                                    │
                       ┌────────────────────────────┴────────────────────────────┐
                       │                                                         │
             [ 1. Historical Backfill ]                               [ 2. Real-Time CDC Stream ]
                       │                                                         │
                       ▼                                                         ▼
            Backfill Worker (Batches)                                 Postgres WAL Reader
      - PK range scan (e.g. 1,000 rows)                           - Native logical replication
      - Adaptive throttling / sleep                               - test_decoding / pgoutput plugin
      - INSERT ON CONFLICT DO NOTHING                             - Java 21 Virtual Threads
                       │                                                         │
                       │                                                         ▼
                       │                                              Kafka 3.7 (KRaft Broker)
                       │                                          Topic: safemigrate-wal-events
                       │                                          - Partitioned by row PK hash
                       │                                          - Strict per-key event order
                       │                                                         │
                       │                                                         ▼
                       │                                              Change Applier Engine
                       │                                          - Consumer group replayer
                       │                                          - Idempotent UPSERT replay
                       │                                                         │
                       └────────────────────────────┬────────────────────────────┘
                                                    │
                                                    ▼
                                     ┌─────────────────────────────┐
                                     │  Shadow Table: orders_shadow│
                                     └──────────────┬──────────────┘
                                                    │
                       ┌────────────────────────────┴────────────────────────────┐
                       │                                                         │
             [ 3. Pre-Cutover Verification ]                             [ 4. Atomic Cutover ]
                       │                                                         │
         - Replication lag == 0 ms                                  BEGIN;
         - SHA-256 Merkle row hash match                           SET LOCAL lock_timeout = '2000ms';
         - Sequence high-watermark sync                             ALTER TABLE orders RENAME TO _sm_old;
         - Dual operator signoff gate                              ALTER TABLE orders_shadow RENAME TO orders;
                                                                    COMMIT;
                                                                    (Completed in < 15ms)
```

---

## 💻 Tech Stack & Architectural Decisions

| Layer | Component | Implementation Highlights |
|---|---|---|
| **Core Engine** | Java 21 LTS | High-throughput blocking I/O using **Virtual Threads** (`Thread.ofVirtual()`). Eliminates OS thread exhaustion without reactive overhead. |
| **Source DB** | PostgreSQL 16 | Native logical replication via `PGReplicationStream`. Dynamic replication slot and publication management with `test_decoding`. |
| **Event Pipeline** | Apache Kafka 3.7 | **KRaft Mode** (ZooKeeper-free). Dynamic topic provisioning, partitioned by primary-key MurmurHash for deterministic sequential ordering. |
| **State Coordinator** | Redis 7 + Redisson | Atomic checkpoint tracking (`last_pk`, `cdc_offset`), distributed table locks with auto-renewing leases, and process crash recovery. |
| **Control Plane API** | Spring Boot 3.2 | RESTful lifecycle API, preflight safety inspector, dual-signoff authorization, and Server-Sent Events (SSE) progress streaming. |
| **Operator Cockpit** | Next.js 16 + React 19 | "Precision Infrastructure Dark" design system from Stitch, live telemetry cockpit, DDL AST inspector, and interactive Pod Fleet monitor. |
| **Container Platform** | Docker Desktop & Compose | Multi-container stack grouped under the `safemigrate` project for unified observation and local development. |
| **Orchestration** | Kubernetes (`k8s/`) | Production manifests: StatefulSets (Postgres, Kafka), Deployments (Server, Dashboard, Redis), Backfill Jobs, and chaos failover scripts. |
| **Traffic Simulator** | `safemigrate-loadgen` | High-concurrency load generator capable of sustaining hundreds of concurrent transactions to prove 0-loss data integrity. |

---

## 📦 Project Modules

```
SafeMigrate/
├── safemigrate-core/           # Core Migration Engine
│   ├── src/main/java/com/safemigrate/core/
│   │   ├── backfill/           # PK-chunked batch copy, adaptive throttling & StandaloneWorker
│   │   ├── cdc/                # Change Applier, idempotent replay & UPSERT fallback
│   │   ├── cutover/            # Atomic table swap, lock timeout guards & rollback engine
│   │   ├── kafka/              # Dynamic topic admin, PK partitioned producer & consumer
│   │   ├── preflight/          # Safety Inspector (PK audit, lock contention, disk checks)
│   │   ├── state/              # Redis state store, Redisson distributed locks & checkpoints
│   │   └── wal/                # Native PG logical replication stream tailer & event parser
│   └── Dockerfile              # Standalone worker pod container image
│
├── safemigrate-server/         # Spring Boot 3 Control Plane
│   ├── src/main/java/com/safemigrate/server/
│   │   ├── controller/         # REST API endpoints & SSE progress streaming
│   │   ├── coordinator/        # Full migration lifecycle orchestrator
│   │   └── model/              # Domain models, requests, responses & state enums
│   └── Dockerfile              # Control Plane container image
│
├── safemigrate-dashboard/      # Next.js 16 Production Management Console
│   ├── src/app/
│   │   ├── overview/           # Cluster status, elevated KPIs & Docker Pod Fleet Monitor
│   │   ├── migrations/new/     # 4-stage migration wizard, DDL generator & lock estimator
│   │   ├── migrations/[id]/    # Real-time telemetry cockpit (schema diff, CDC feed, logs)
│   │   └── migrations/[id]/cutover/ # Dual-signoff safety gate & atomic swap trigger
│   ├── src/components/overview/# PodFleetView, MetricsStrip, ActiveMigrationCard
│   └── Dockerfile              # Lightweight standalone container image (<75MB)
│
├── safemigrate-loadgen/        # Concurrent Production Traffic Simulator
│   └── src/main/java/com/safemigrate/loadgen/ # Concurrent threads + SHA-256 parity verifier
│
├── k8s/                        # Production Kubernetes Manifests
│   ├── namespace.yaml          # safemigrate isolated namespace
│   ├── configmap.yaml          # Cluster config endpoints
│   ├── secrets.yaml            # Database & broker credentials
│   ├── postgres.yaml           # StatefulSet with logical replication & PVC
│   ├── kafka.yaml              # StatefulSet with KRaft quorum
│   ├── redis.yaml              # Deployment & ClusterIP Service
│   ├── server.yaml             # 2-replica Spring Boot deployment + NodePort 30080
│   ├── dashboard.yaml          # 2-replica Next.js deployment + NodePort 30000
│   ├── worker-job.yaml         # Kubernetes Job for backfill execution
│   ├── kustomization.yaml      # Declarative deployment manifest
│   ├── resilience-demo.ps1     # PowerShell pod eviction chaos script
│   └── resilience-demo.sh      # Bash pod eviction chaos script
│
├── docker/                     # Database Initialization Scripts
│   └── init.sql                # Logical replication config & sample schema
└── docker-compose.yml          # Complete 6-service Docker Desktop compose stack
```

---

## ⚡ Quickstart Guide

### Option 1: Docker Compose (All 6 Services Grouped in Docker Desktop)

Start all services in the background. Because `docker-compose.yml` specifies `name: safemigrate`, all containers appear grouped inside your **Docker Desktop** application:

```bash
docker compose up -d
```

#### Services Started:
| Service | Container Name | Internal Port | Exposed Port | Description |
|---|---|---|---|---|
| `postgres` | `safemigrate_postgres` | 5432 | `5432` | PostgreSQL 16 (`wal_level=logical`) |
| `kafka` | `safemigrate_kafka` | 9092, 29092 | `9092` | Apache Kafka 3.7 KRaft Broker |
| `redis` | `safemigrate_redis` | 6379 | `6380` | Redis 7 AOF State Store |
| `server` | `safemigrate_server` | 8080 | `8080` | Spring Boot 3 Control Plane REST API |
| `dashboard` | `safemigrate_dashboard` | 3000 | `3000` | Next.js 16 Production Management Console |
| `worker` | `safemigrate_worker` | Internal | - | Standalone Migration Worker Pod |

Check running containers:
```bash
docker compose ps
```

- **Open Dashboard**: [http://localhost:3000/overview](http://localhost:3000/overview)
- **Open API Base**: [http://localhost:8080/api/migrations](http://localhost:8080/api/migrations)

---

### Option 2: Production Kubernetes Deployment (`k8s/`)

Deploy the entire platform into a Kubernetes cluster with a single command:

```bash
# Apply all resources in the safemigrate namespace
kubectl apply -k k8s/

# Verify running pods
kubectl get pods -n safemigrate

# Verify services & NodePorts
kubectl get svc -n safemigrate
```

To run an automated pod eviction chaos demonstration:
```powershell
# Windows
powershell -ExecutionPolicy Bypass -File k8s/resilience-demo.ps1

# Linux / macOS
./k8s/resilience-demo.sh
```

---

### Option 3: Local Developer Build (Maven + Next.js)

#### 1. Start Infrastructure Dependencies:
```bash
docker compose up -d postgres kafka redis
```

#### 2. Build & Run Java Backend:
```powershell
# Windows
.\mvnw.cmd clean package -DskipTests
java -jar safemigrate-server/target/safemigrate-server-1.0.0-SNAPSHOT.jar

# Linux / macOS
./mvnw clean package -DskipTests
java -jar safemigrate-server/target/safemigrate-server-1.0.0-SNAPSHOT.jar
```

#### 3. Run Next.js Dashboard:
```bash
cd safemigrate-dashboard
npm install
npm run dev
```

---

## 📡 Control Plane REST API Reference

The Control Plane exposes a REST API on port `8080`:

### 1. Pre-Flight Safety Audit
Checks for primary keys, active lock contention, foreign key cascades, and disk headroom.
```bash
curl -X POST http://localhost:8080/api/migrations/preflight \
  -H "Content-Type: application/json" \
  -d '{
    "tableName": "orders",
    "targetDdl": "ALTER TABLE orders ADD COLUMN priority_score INTEGER DEFAULT 0"
  }'
```
**Response (`HTTP 200 OK`)**:
```json
{
  "safe": true,
  "tableName": "orders",
  "hasPrimaryKey": true,
  "estimatedRows": 1000000,
  "warnings": [],
  "reasons": ["Table has valid integer Primary Key (id)", "Lock queue is clear", "Storage headroom sufficient"]
}
```

### 2. Launch Migration Pipeline
Initializes the shadow table, launches the WAL reader, and begins batch backfilling.
```bash
curl -X POST http://localhost:8080/api/migrations \
  -H "Content-Type: application/json" \
  -d '{
    "sourceTable": "orders",
    "targetDdl": "ALTER TABLE orders ADD COLUMN priority_score INTEGER DEFAULT 0",
    "batchSize": 500,
    "throttleDelayMs": 10
  }'
```

### 3. Stream Real-Time Progress (Server-Sent Events)
Stream live progress percentage, CDC lag, copied rows, and state transitions.
```bash
curl -N http://localhost:8080/api/migrations/{id}/stream
```

### 4. Dual-Signoff Approval
Grants operator approval required before cutover execution.
```bash
curl -X POST "http://localhost:8080/api/migrations/{id}/approve?approver=sre-lead"
```

### 5. Trigger Atomic Cutover
Executes the sub-20ms atomic table swap once lag reaches zero.
```bash
curl -X POST http://localhost:8080/api/migrations/{id}/cutover
```

### 6. Emergency Abort & Rollback
Immediately releases locks, terminates workers, drops shadow artifacts, and cleans up replication slots.
```bash
curl -X POST "http://localhost:8080/api/migrations/{id}/rollback?reason=operator_abort"
```

---

## 🛡️ Core Reliability & Safety Guarantees

### 1. Zero Lock Starvation (`SET LOCAL lock_timeout`)
Cutover executes inside a single transaction with a strict 2,000ms lock timeout:
```sql
BEGIN;
SET LOCAL lock_timeout = '2000ms';
ALTER TABLE orders RENAME TO _sm_old_orders;
ALTER TABLE _sm_shadow_orders RENAME TO orders;
COMMIT;
```
If long-running analytics queries block lock acquisition, SafeMigrate automatically aborts the swap, releases the lock request immediately, and returns to `CATCHING_UP` mode. Application traffic is never queued or starved.

### 2. Race-Condition-Proof CDC & Backfill
- **Backfill Worker**: Writes using `INSERT ... ON CONFLICT (id) DO NOTHING`.
- **Change Applier**: Replays live changes using UPSERT (`INSERT ... ON CONFLICT (id) DO UPDATE`).
- **Out-of-Order Updates**: If an `UPDATE` event arrives for a row not yet reached by the backfill worker, the UPSERT creates the row with the updated state. When backfill subsequently reaches that ID, `DO NOTHING` leaves the live state intact.

### 3. Atomic Sequence High-Watermark Synchronization
Under continuous concurrent inserts, auto-incrementing sequences could drift between the old and new table. SafeMigrate synchronizes `pg_get_serial_sequence()` **while the exclusive cutover lock is actively held**, guaranteeing zero duplicate key exceptions after cutover.

### 4. Process Crash & Split-Brain Recovery
Worker processes maintain heartbeats on a **Redisson distributed lock** with a 30-second lease and record progress checkpoints in Redis (`last_pk`). If a worker container crashes (`SIGKILL` / `kill -9`):
1. The Redis lease auto-expires without manual intervention.
2. A standby worker pod acquires the lock.
3. The new worker resumes from `last_pk` with zero duplicate records.

---

## 🧪 Verification & Automated Testing

SafeMigrate includes a test suite verifying every component against live containers:

```powershell
# Run all tests across modules
.\mvnw.cmd clean test

# Flagship End-to-End REST Lifecycle with Concurrent Traffic Test
.\mvnw.cmd test -pl safemigrate-server -Dtest=MigrationApiIntegrationTest

# MockMvc REST API Controller Suite
.\mvnw.cmd test -pl safemigrate-server -Dtest=MigrationControllerTest

# Atomic Cutover & Sequence Synchronization Test
.\mvnw.cmd test -pl safemigrate-core -Dtest=EndToEndCutoverIntegrationTest

# Worker Resilience & kill -9 Crash Recovery Test
.\mvnw.cmd test -pl safemigrate-core -Dtest=WorkerResilienceIntegrationTest

# Pre-Flight Safety Inspector Test
.\mvnw.cmd test -pl safemigrate-core -Dtest=PreflightInspectorTest

# Frontend ESLint & Typecheck
cd safemigrate-dashboard
npm run lint
npm run build
```

---

## 📜 License

Apache License 2.0. Built for production-grade database engineering.
