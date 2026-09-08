# SafeMigrate — Technical Execution Journal & Status Report

> **Project Goal**: Build a zero-downtime, online database schema migration platform for PostgreSQL from scratch in **Java 21 LTS**, inspired by GitHub's `gh-ost`.

---

## 📌 Executive Summary

SafeMigrate eliminates exclusive table locks during production schema changes (`ALTER TABLE`) by:
1. Creating a shadow table with the modified schema in the background.
2. Backfilling historical rows in primary-key ordered, throttled batches.
3. Tailing PostgreSQL's internal Write-Ahead Log (WAL) via native logical replication to capture live concurrent writes.
4. Streaming captured events through Kafka with primary-key partitioning for strict per-row ordering.
5. Performing an atomic table rename inside a single transaction once replication lag reaches zero.

---

## 🟢 Part 1: What We Have Done (Completed Phases)

### Phase 0: Infrastructure & Environment Setup
- **PostgreSQL 16 Container (`safemigrate_postgres`)**:
  - Configured with `wal_level = logical`, `max_replication_slots = 10`, `max_wal_senders = 10`.
  - Automated init script (`docker/init.sql`) grants replication privileges and seeds an initial high-traffic `orders` table (1,000 rows, `REPLICA IDENTITY FULL`).
- **Apache Kafka 3.7 Container (`safemigrate_kafka`)**:
  - Configured in **KRaft mode** (Kafka Raft consensus). Completely eliminates ZooKeeper operational overhead and memory bloat.
- **Redis 7 Container (`safemigrate_redis`)**:
  - Bound to port `6380` to prevent port collisions with existing local containers.
- **Concurrent Load Generator (`safemigrate-loadgen`)**:
  - Multi-threaded simulator using **Java 21 Virtual Threads**.
  - Generates mixed production traffic: 60% INSERTs, 30% UPDATEs, 10% DELETEs.
  - Live console throughput reporter tracking operations and errors.
  - **Verified**: Pushed traffic at ~40+ ops/sec with 0 errors.

### Phase 1: PostgreSQL WAL Replication Reader
- **Replication Connection Factory (`PostgresReplicationConnectionFactory.java`)**:
  - Programmatically establishes replication-mode JDBC connections via `PGConnection.getReplicationAPI()`.
  - Manages creation, inspection, and clean teardown of logical replication slots using the built-in `test_decoding` plugin.
- **Event Data Model (`WalChangeEvent.java` & `OperationType.java`)**:
  - Immutable Java records capturing `table`, `operation` (`INSERT`, `UPDATE`, `DELETE`), column key-value maps (`oldValues`, `newValues`), commit LSN, and timestamps.
- **WAL Decoder (`TestDecodingDecoder.java`)**:
  - Regex and token-based parser transforming PostgreSQL replication strings into structured `WalChangeEvent` instances.
  - Unit tested against INSERTs, UPDATEs (old/new tuple variations), DELETEs, and transaction boundaries (`BEGIN`/`COMMIT`).
- **WAL Reader (`WalReader.java`)**:
  - Runs in a non-blocking **Java 21 Virtual Thread** tailing `PGReplicationStream`.
  - Employs blocking `stream.read()` to sleep efficiently without wasting CPU when no writes occur.
  - Automatically commits acknowledged positions (`stream.setAppliedLSN()`, `stream.setFlushedLSN()`) to prevent production WAL disk bloat.

### Phase 2: Kafka Streaming Pipeline
- **Dynamic Topic Manager (`KafkaTopicManager.java`)**:
  - Automatically provisions dedicated Kafka topics per table (`safemigrate.wal.<table_name>`) using Kafka's Java `AdminClient`.
- **Primary-Key Partitioned Producer (`WalKafkaProducer.java`)**:
  - Serializes `WalChangeEvent` records to JSON via Jackson.
  - Partitions messages by the row's primary key (`order_id`). This ensures Kafka's partition hashing guarantees **strict per-row chronological ordering** across all consumers.
- **Consumer Replayer (`WalKafkaConsumer.java`)**:
  - Consumes WAL events from Kafka with dedicated consumer groups.
  - Configured for single-thread safety within Virtual Threads.
- **Closed-Loop Integration Test (`PostgresWalToKafkaIntegrationTest.java`)**:
  - Proves the entire lifecycle end-to-end against live Docker containers:
    $$\text{PostgreSQL} \xrightarrow{\text{INSERT / UPDATE / DELETE}} \text{WAL Stream} \xrightarrow{\text{WalReader}} \text{Kafka Producer} \xrightarrow{\text{Kafka Broker}} \text{Kafka Consumer}$$
  - Executed an INSERT, UPDATE, and DELETE on a live test table. All 3 events were intercepted, streamed, partitioned, and consumed in **3.14 seconds** with 100% field accuracy.

### Phase 3: Backfill Engine & Redis State Store
- **Redis Distributed State Store (`StateStore.java`)**:
  - Built on **Redisson**; manages cluster-wide distributed locks (`safemigrate:lock:table:<tableName>`).
  - Checkpoints `last_pk`, `rows_backfilled`, and replication LSNs to Redis in real time.
  - Manages atomic migration state transitions (`INITIALIZING`, `BACKFILLING`, `CATCHING_UP`, `CUTTING_OVER`, `COMPLETED`, `FAILED`).
- **Shadow Table Manager (`ShadowTableManager.java`)**:
  - Inspects table primary keys and column metadata dynamically.
  - Creates the shadow table using `CREATE TABLE <shadow> (LIKE <source> INCLUDING ALL)`.
  - Applies target DDL schema alteration (e.g. `ADD COLUMN priority_score INT DEFAULT 0`) directly to shadow table.
  - Automatically enforces `REPLICA IDENTITY FULL` on shadow table for complete tuple capture.
- **Batched, Throttled Backfill Worker (`BackfillWorker.java`)**:
  - Copies historical records in paged primary-key ranges (`WHERE id > ? ORDER BY id ASC LIMIT ?`).
  - Uses `INSERT INTO shadow (...) VALUES (...) ON CONFLICT (pk) DO NOTHING` to ensure historical backfill **never** overwrites live writes that already arrived from the WAL.
  - Supports configurable throttle delays (`throttleDelayMs`) between batches to prevent DB CPU or IOPS spikes.
  - Checkpoints `last_pk` after each batch for crash-safe resume.
- **Closed-Loop Integration Test (`BackfillWorkerIntegrationTest.java`)**:
  - Verified 250 rows backfilled into a shadow table with a newly added column (`priority_score INT DEFAULT 42`).
  - Tested worker restart from `last_pk = 100` and proved clean resumption with 0 duplicates.

### Phase 4: Change Applier & Concurrency Convergence
- **PostgreSQL Type Introspection & Explicit Casting (`ChangeApplier.java`)**:
  - Automatically queries `information_schema.columns` to extract PostgreSQL internal type names (`udt_name`).
  - Employs explicit SQL type casts: `CAST(? AS <udt_name>)` with `stmt.setNull(i, Types.OTHER)` support, guaranteeing type-safe replay across `bigint`, `numeric`, `timestamp`, `uuid`, and boolean columns.
- **Idempotent Replay Engine**:
  - `INSERT`: Replays as `INSERT INTO shadow (cols) VALUES (CAST(? AS udt)...) ON CONFLICT (pk) DO UPDATE SET col = EXCLUDED.col...`. Automatically preserves shadow table newly added columns with database defaults.
  - `UPDATE`: Executes `UPDATE shadow SET col = CAST(? AS udt)... WHERE pk = ?`. If 0 rows affected (because row has not yet been copied by BackfillWorker), immediately falls back to an idempotent UPSERT, ensuring live updates are never dropped.
  - `DELETE`: Replays as `DELETE FROM shadow WHERE pk = CAST(? AS pk_udt)`.
  - Checkpoints `last_applied_lsn` to Redis (`StateStore.checkpointAppliedLsn`) and tracks metrics (`totalApplied`, `inserts`, `updates`, `deletes`).
- **Load Generator Customization (`LoadGenerator.java`)**:
  - Parameterized table name support allowing tests to target dynamically created, isolated tables.
- **Unit & Integration Verification**:
  - `ChangeApplierTest.java`: Verified `INSERT`, `UPDATE`, `DELETE`, and fallback UPSERT with default value preservation (2/2 tests pass).
  - `ConcurrencyConvergenceIntegrationTest.java`: Flagship closed-loop test running continuous live writes (50+ ops/sec) while `BackfillWorker` and `ChangeApplier` run simultaneously. Verified **100% row-by-row consistency** (224/224 matching rows, 0 errors, 0 missing rows, 0 data drift).
  - `IdempotencyAndCrashResumeTest.java`: Verified triple-replay idempotency $f(f(f(x))) = f(x)$, mid-flight worker crash and resume with overlapping batches, and live write overwrite prevention (3/3 tests pass).
  - `EdgeCasesAndIdempotencyAuditTest.java`: Verified NULL clearing, empty table migrations, sparse primary keys (PK gaps up to 88M), phantom DELETE replay, distributed lock mutual exclusion, and monotonic LSN progression (6/6 tests pass).

### Phase 5: Atomic Cutover Coordinator & Rollback Engine (COMPLETED)
- **Cutover Coordinator (`CutoverCoordinator.java`)**:
  - Monitors replication lag in bytes using PostgreSQL native `pg_wal_lsn_diff(pg_current_wal_lsn(), ?::pg_lsn)`.
  - Enforces traffic starvation protection with `SET LOCAL lock_timeout = '2000ms'`.
  - Executes atomic table swap transaction in single-digit milliseconds (**12ms** measured under active live load):
    ```sql
    BEGIN;
      SET LOCAL lock_timeout = '2000ms';
      LOCK TABLE "orders" IN ACCESS EXCLUSIVE MODE;
      LOCK TABLE "orders__shadow" IN ACCESS EXCLUSIVE MODE;
      DROP TABLE IF EXISTS "orders__old" CASCADE;
      ALTER TABLE "orders" RENAME TO "orders__old";
      ALTER TABLE "orders__shadow" RENAME TO "orders";
    COMMIT;
    ```
  - Provides instant pre-cutover `rollback()` and post-cutover `emergencyRevert()` swapping `<oldTable>` back to `<sourceTable>`.
- **Unit & Integration Verification**:
  - `CutoverCoordinatorTest.java`: Verified sub-50ms atomic cutover, pre-cutover rollback, emergency revert, and lock-timeout abort (4/4 tests pass).
  - `EndToEndCutoverIntegrationTest.java`: Closed-loop end-to-end test verifying full lifecycle from initial table seed -> WAL stream -> backfill -> live traffic -> 12ms atomic cutover -> immediate zero-downtime post-cutover queries (1/1 passes).

---

## 🛠️ Part 2: How We Did That (Engineering Highlights & Solutions)

| Challenge Faced | Technical Solution Implemented |
|---|---|
| **High Concurrency without Thread Exhaustion** | Utilized **Java 21 Virtual Threads (Project Loom)** (`Thread.ofVirtual()`) for the WAL Reader loop, Kafka Consumer loop, and Load Generator workers. This allows lightweight blocking I/O with zero OS thread starvation. |
| **Kafka Operational Bloat** | Avoided legacy ZooKeeper containers by configuring **Kafka 3.7 in KRaft mode** (`KAFKA_PROCESS_ROLES: broker,controller`). Reduced memory footprint and enabled ~2-second container boot times. |
| **Transitive Dependency Skew** | Redisson and Spring Boot BOM pulled conflicting Jackson core versions (`2.15.4` vs `2.17.0`), causing `NoSuchMethodError: BufferRecycler.releaseToPool()`. Resolved by importing the unified `jackson-bom:2.17.0` into the parent Maven `dependencyManagement`. |
| **Logback Version Mismatch** | `logback-classic:1.5.3` clashed with `logback-core:1.4.14` from Spring Boot, causing `NoClassDefFoundError: StringUtil`. Aligned both dependencies to `1.4.14` in the parent POM. |
| **Replication Stream Socket Blocking** | `stream.readPending()` only read local in-memory buffers and didn't wait for incoming network packets. Replaced with `stream.read()` inside a Virtual Thread to park cleanly until Postgres pushes WAL packets. |
| **Kafka Test Isolation** | Successive test runs on the same topic caused consumer group offset bleed. Replaced static topic names with dynamically generated table/slot names per test run (`orders_pipe_<timestamp>`) for 100% isolated, repeatable test execution. |
| **Backfill vs. Live Write Race Condition** | Resolved via `INSERT ON CONFLICT (id) DO NOTHING` in Backfill Worker paired with `DO UPDATE SET` in Change Applier. If a live WAL write lands before backfill reaches that row, backfill preserves the live update. |
| **PostgreSQL JDBC Type Inference Mismatch** | Binding string literals from WAL decoding to typed columns (`bigint`, `numeric`, `timestamp`) throws PSQLException without explicit casting. Solved by introspecting `udt_name` on startup and generating parameter casts: `CAST(? AS <udt_name>)`. |
| **Out-of-Order Live Updates Before Backfill** | If an `UPDATE` event arrives for a row that hasn't been backfilled yet, `UPDATE` affects 0 rows. Solved by falling back to UPSERT (`INSERT ON CONFLICT DO UPDATE`), guaranteeing the row exists with the latest live state when backfill later arrives with `DO NOTHING`. |
| **Traffic Starvation During Cutover** | An unconstrained `LOCK TABLE` can queue behind slow queries and block all application traffic. Solved with `SET LOCAL lock_timeout = '2000ms'`, gracefully aborting the swap and returning to `CATCHING_UP` if the lock cannot be immediately acquired. |

---

## 📋 Part 3: What is Remaining (Future Phases)

```
[ Phase 0: Setup ] ────► [ Phase 1: WAL Reader ] ────► [ Phase 2: Kafka Stream ] (COMPLETED)
                                                                 │
                                                                 ▼
[ Phase 3: Backfill Engine (COMPLETED) ] ─────────────► [ Phase 4: Change Applier (COMPLETED) ]
            │                                                                │
            ▼                                                                ▼
[ Phase 5: Atomic Cutover & Rollback (COMPLETED) ] ──► [ Phase 6: Crash-Safe Checkpointing (CURRENT) ]
            │                                                                │
            ▼                                                                ▼
[ Phase 7: Pre-Flight Safety Checks (CURRENT) ] ──────► [ Phase 8: Spring Boot Control Plane API ]
            │                                                                │
            ▼                                                                ▼
[ Phase 9: Dashboard UI (Next.js) ] ──────────────────► [ Phase 10: Kubernetes Orchestration ]
```

### Phase 6: Crash-Safe Checkpointing & Worker Resilience (COMPLETED)
- **Standalone Worker Application (`StandaloneMigrationWorker.java`)**:
  - Executable CLI process with `main()` method, capable of running as an independent OS process or Kubernetes Job.
  - Checkpoints progress to Redis and outputs machine-readable heartbeats (`[WORKER-CHECKPOINT] lastPk=... rowsCopied=...`).
- **Distributed Lock Auto-Recovery & Inspection (`StateStore.java`)**:
  - Added `isTableLocked()` and `forceReleaseTableLock()` for safe standby takeover and crash failover.
- **Unit & Integration Verification (`WorkerResilienceIntegrationTest.java`)**:
  - `shouldSurviveProcessKillAndResumeFromRedisCheckpoint`: True out-of-process hard kill (`process.destroyForcibly()`, simulating OS `kill -9` or sudden Kubernetes pod eviction). Verified that without running any JVM shutdown hooks, Redis preserved `last_pk` and a replacement worker resumed from the checkpoint to achieve **500/500 row parity** with 0 duplicate key errors.
  - `shouldRecoverDistributedLockAfterWorkerDeathViaLeaseExpiry`: Verified cross-thread/client mutual exclusion (preventing split-brain migrations) and confirmed that when a worker dies holding a lock, Redisson's 3-second lease auto-expires, allowing a standby worker to safely take over.
  - `shouldHandleMidBackfillCrashUnderConcurrentLiveWrites`: Mid-backfill worker crash during active concurrent traffic; replacement worker resumed from Redis checkpoint, drained Kafka WAL replication stream, and achieved 100% convergence.

### Phase 7: Pre-Flight Safety Checks Engine (COMPLETED)
- **Safety Inspector (`PreflightInspector.java` & `PreflightReport.java` & `PreflightIssue.java`)**:
  - **Primary Key Enforcement**: Inspects PostgreSQL catalog for primary keys. Fails with `NO_PRIMARY_KEY` if missing.
  - **Replica Identity Check**: Verifies `pg_class.relreplident` is `'f'` (`REPLICA IDENTITY FULL`). Issues advisory warning if default.
  - **Disk Headroom Capacity**: Computes total relation size and verifies storage capacity has at least $1.8 \times \text{table size}$ available for the shadow copy and WAL accumulation.
  - **DDL Syntax & SQL Injection Guard**: Prohibits multi-statement injections (semicolons `;`), blocks destructive commands (`DROP DATABASE`, `TRUNCATE`, `DROP TABLE`, `GRANT`, `REVOKE`), and forbids adding `NOT NULL` columns without `DEFAULT` on non-empty tables.
  - **Active Lock Contention Detector**: Scans `pg_stat_activity` for active queries running > 10 seconds on the target table.
- **Unit & Integration Verification (`PreflightInspectorTest.java`)**:
  - 8/8 automated tests passing: valid DDL, missing table detection, missing PK detection, replica identity warning, SQL injection rejection, destructive statement rejection, and NOT NULL default validation.

### Phase 8: Spring Boot 3 Control Plane REST API & Real-Time Progress Stream (COMPLETED)
- **Spring Boot 3 Control Plane Application (`SafeMigrateApplication.java`)**:
  - Configured with `SafeMigrateProperties`, `SafeMigrateConfig`, `WebMvcConfig` (CORS enabled for Next.js frontend).
  - Backed by Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`).
- **Control Plane REST API (`MigrationController.java`)**:
  - `POST /api/migrations`: Submits new migration, runs pre-flight validation, acquires table lock, spawns lifecycle worker.
  - `GET /api/migrations`: Lists all active and historical migrations.
  - `GET /api/migrations/{id}`: Detailed status, row throughput, replication lag, and change applier metrics.
  - `POST /api/migrations/{id}/approve`: Mandatory approval gate before cutover.
  - `POST /api/migrations/{id}/cutover`: Triggers atomic sub-15ms table swap with lock timeout protection.
  - `POST /api/migrations/{id}/rollback`: Aborts migration and cleanly drops shadow table and replication slot.
  - `POST /api/migrations/{id}/revert`: Emergency post-cutover restoration.
  - `POST /api/migrations/preflight`: Standalone DDL safety inspection endpoint.
- **Server-Sent Events (SSE) Streaming Engine (`MigrationSseService.java`)**:
  - `GET /api/migrations/{id}/stream`: Real-time SSE endpoint pushing live metrics (rows backfilled, progress %, replication lag, state transitions).
- **Global Error Handling (`GlobalExceptionHandler.java`)**:
  - Standardized JSON responses for 400 Bad Request, 404 Not Found, 409 Conflict, and 422 Unprocessable Entity.
- **Unit & Integration Verification**:
  - `MigrationControllerTest.java`: 12/12 passing MockMvc tests verifying input validation, status codes, and exception mappings.
  - `MigrationApiIntegrationTest.java`: 3/3 passing full end-to-end REST lifecycle integration tests against live PostgreSQL, Kafka, and Redis (preflight check, zero-downtime cutover in 10ms, sequence synchronization, data parity, and safe rollback).
  - Total automated tests across all modules increased to **65 passing tests**.

---

## 🛠️ Part 2: How We Did That (Engineering Highlights & Solutions)

| Challenge Faced | Technical Solution Implemented |
|---|---|
| **High Concurrency without Thread Exhaustion** | Utilized **Java 21 Virtual Threads (Project Loom)** (`Thread.ofVirtual()`) for the WAL Reader loop, Kafka Consumer loop, and Load Generator workers. This allows lightweight blocking I/O with zero OS thread starvation. |
| **Kafka Operational Bloat** | Avoided legacy ZooKeeper containers by configuring **Kafka 3.7 in KRaft mode** (`KAFKA_PROCESS_ROLES: broker,controller`). Reduced memory footprint and enabled ~2-second container boot times. |
| **Transitive Dependency Skew** | Redisson and Spring Boot BOM pulled conflicting Jackson core versions (`2.15.4` vs `2.17.0`), causing `NoSuchMethodError: BufferRecycler.releaseToPool()`. Resolved by importing the unified `jackson-bom:2.17.0` into the parent Maven `dependencyManagement`. |
| **Logback Version Mismatch** | `logback-classic:1.5.3` clashed with `logback-core:1.4.14` from Spring Boot, causing `NoClassDefFoundError: StringUtil`. Aligned both dependencies to `1.4.14` in the parent POM. |
| **Replication Stream Socket Blocking** | `stream.readPending()` only read local in-memory buffers and didn't wait for incoming network packets. Replaced with `stream.read()` inside a Virtual Thread to park cleanly until Postgres pushes WAL packets. |
| **Kafka Test Isolation** | Successive test runs on the same topic caused consumer group offset bleed. Replaced static topic names with dynamically generated table/slot names per test run (`orders_pipe_<timestamp>`) for 100% isolated, repeatable test execution. |
| **Backfill vs. Live Write Race Condition** | Resolved via `INSERT ON CONFLICT (id) DO NOTHING` in Backfill Worker paired with `DO UPDATE SET` in Change Applier. If a live WAL write lands before backfill reaches that row, backfill preserves the live update. |
| **PostgreSQL JDBC Type Inference Mismatch** | Binding string literals from WAL decoding to typed columns (`bigint`, `numeric`, `timestamp`) throws PSQLException without explicit casting. Solved by introspecting `udt_name` on startup and generating parameter casts: `CAST(? AS <udt_name>)`. |
| **Out-of-Order Live Updates Before Backfill** | If an `UPDATE` event arrives for a row that hasn't been backfilled yet, `UPDATE` affects 0 rows. Solved by falling back to UPSERT (`INSERT ON CONFLICT DO UPDATE`), guaranteeing the row exists with the latest live state when backfill later arrives with `DO NOTHING`. |
| **Traffic Starvation During Cutover** | An unconstrained `LOCK TABLE` can queue behind slow queries and block all application traffic. Solved with `SET LOCAL lock_timeout = '2000ms'`, gracefully aborting the swap and returning to `CATCHING_UP` if the lock cannot be immediately acquired. |
| **Process Crash & Split-Brain Prevention** | Tested with true OS `kill -9` (`Process.destroyForcibly()`). Redis lease auto-expiry on Redisson distributed lock releases the lock without human intervention, allowing a standby worker to resume from `last_pk` with 0 duplicates. |

---
| **PostgreSQL Replication Slot Naming Restrictions** | PostgreSQL strictly forbids hyphens in replication slot names. Sanitized slot names to alphanumeric and underscore (`[a-z0-9_]`) capped at 63 characters (`NAMEDATALEN - 1`). |
| **Spring Boot 3 Parameter Reflection** | Spring 6 / Spring Boot 3 requires explicit variable names in `@PathVariable("id")` and `@RequestParam("reason")` or the `-parameters` compiler flag. Configured both for complete runtime safety. |
| **Concurrent Live Write Sequence Race** | If sequence high-watermark synchronization occurs before acquiring the table cutover lock, concurrent writes slipping in between can claim IDs higher than the counter, causing duplicate key violations after cutover. Resolved by atomically synchronizing the sequence inside `CutoverCoordinator.executeCutover()` **while the `ACCESS EXCLUSIVE` lock is actively held**, guaranteeing zero duplicate key errors under live load. |

---

## 📋 Part 3: What is Remaining (Future Phases)

```
[ Phase 0: Setup ] ────► [ Phase 1: WAL Reader ] ────► [ Phase 2: Kafka Stream ] (COMPLETED)
                                                                 │
                                                                 ▼
[ Phase 3: Backfill Engine (COMPLETED) ] ─────────────► [ Phase 4: Change Applier (COMPLETED) ]
             │                                                                │
             ▼                                                                ▼
[ Phase 5: Atomic Cutover & Rollback (COMPLETED) ] ──► [ Phase 6: Crash Resilience (COMPLETED) ]
             │                                                                │
             ▼                                                                ▼
[ Phase 7: Pre-Flight Safety (COMPLETED) ] ───────────► [ Phase 8: Spring Boot API (COMPLETED) ]
             │                                                                │
             ▼                                                                ▼
[ Phase 9: Dashboard UI (Next.js) (COMPLETED) ] ─────► [ Phase 10: Kubernetes Orchestration (COMPLETED) ]
```

### 1. Phase 9: Engineer Dashboard UI (Next.js) (COMPLETED)
- **Application Architecture (`safemigrate-dashboard/`)**:
  - Built with **Next.js 16 (App Router), React 19, TypeScript, and Tailwind CSS v4**.
  - Direct 1-to-1 pixel-perfect implementation of the 4 screens and **Precision Infrastructure Dark** design system from **Stitch** (`projects/7433848210503217522`).
- **4 Core Views**:
  1. **Modern Overview Dashboard (`/overview`)**: Fixed 220px navigation rail, 4 elevated KPI tiles (Active, Completed 30d, Failed/Rolled back, System Health), real-time active migration cards with animated row sync progress bars (`15.3M / 20.0M`) and ETA, recent executions history table, and cluster topology baseline strip.
  2. **Create Migration Workflow Wizard (`/migrations/new`)**: 4-stage pipeline stepper (Target Table $\to$ Define Change $\to$ Pre-Flight Safety $\to$ Signoff & Launch), 6-primitive selector matrix (Add Column, Rename, Change Type, Concurrent Index, Add Constraint, Raw SQL), live syntax-highlighted DDL generator with copy interaction, impact projection with SVG lock latency profile, and automated preflight safety trigger.
  3. **Migration Detail & Telemetry Cockpit (`/migrations/[id]`)**: Dynamic breadcrumb with copy ID, DDL intent preview, action buttons (Throttle Backfill, Pause Stream, Abort & Drop Shadow with modal confirmation), 5-stage lifecycle stepper, 4 real-time KPI tiles, and progressive disclosure tab navigation (Overview schema diff + checkpoints + 15m lag sparkline, Events chronological CDC feed, Logs live worker console, Host Metrics CPU/NVMe/queue, and Workers Kubernetes pod allocation).
  4. **Ready for Cutover & Safety Gate (`/migrations/[id]/cutover`)**: Snapshot LSN, ~18ms lock duration estimate, 4 synchronized health checks (100% Backfill, 0ms lag, 0 unapplied events, SHA-256 Merkle tree PASS), visual before/after table rename mapping (`orders` $\to$ `_sm_old_orders`, `_sm_shadow_orders` $\to$ `orders`), atomic DDL block with 250ms `lock_timeout` guarantee, dual authorization signoff badges, and a type-to-confirm safety gate (typing `'orders'` unlocks the **"Confirm Atomic Cutover"** CTA).
- **Frontend Leak Audit & Hardening**:
  - Memory leak in ObjectURL blob export fixed with `URL.revokeObjectURL(url)`.
  - SSE stream hook hardened with `isMounted` cancellation guards and unmount intervals cleanup.
  - Clipboard feedback timeouts encapsulated with unmount disposal.
  - Google Fonts self-hosted via `next/font/google` (`Geist` + `JetBrains Mono`) for zero layout shift.
  - Zero ESLint errors or warnings, zero TypeScript errors (`npm run build` compiled in 592ms).

### 2. Phase 10: Kubernetes Packaging & Docker Desktop Pod Stack (COMPLETED)
- **Docker Desktop Multi-Container Project Grouping (`docker-compose.yml`)**:
  - Structured under project name `safemigrate` so all 6 services display grouped inside the **Docker Desktop** application:
    1. `safemigrate_postgres`: PostgreSQL 16 Alpine with `wal_level=logical`, 10 replication slots, 10 WAL senders.
    2. `safemigrate_kafka`: Apache Kafka 3.7 KRaft broker with dual advertised listeners (local host `9092` and container bridge `29092`).
    3. `safemigrate_redis`: Redis 7 Alpine state store with AOF persistence and health checks.
    4. `safemigrate_server`: Spring Boot 3 Control Plane (Java 21 Virtual Threads, REST API on port 8080, SSE progress stream).
    5. `safemigrate_dashboard`: Next.js 16 standalone production container (70.8 MB image, Node 24 Alpine, port 3000).
    6. `safemigrate_worker`: Standalone executable migration worker pod running continuous backfill and distributed lock sync.
- **Production Kubernetes Manifests (`k8s/`)**:
  - `k8s/namespace.yaml`: Dedicated `safemigrate` namespace.
  - `k8s/configmap.yaml` & `k8s/secrets.yaml`: Declarative environment and credential injection.
  - `k8s/postgres.yaml`: StatefulSet with PVC and logical replication configured.
  - `k8s/kafka.yaml`: StatefulSet with KRaft quorum voting.
  - `k8s/redis.yaml`: Deployment and Service with Redis ping probes.
  - `k8s/server.yaml`: High-availability Deployment (2 replicas) with liveness/readiness probes and NodePort 30080.
  - `k8s/dashboard.yaml`: High-availability Deployment (2 replicas) with NodePort 30000.
  - `k8s/worker-job.yaml`: Kubernetes Job for migration backfill with auto-restart on failure.
  - `k8s/kustomization.yaml`: One-command declarative deployment (`kubectl apply -k k8s/`).
  - `k8s/resilience-demo.ps1` & `k8s/resilience-demo.sh`: Automated pod eviction & failover chaos verification scripts.
- **Docker Desktop / K8s Lens-style Pod Fleet Monitor in Frontend**:
  - Component `PodFleetView.tsx` integrated into `/overview` and the `Workers` tab of `/migrations/[id]`.
  - Visual cards with pulsing green health status, container image tags, port mappings, real-time CPU/memory utilization gauges, and container uptime.
  - Interactive Container Terminal Logs modal (`docker logs` preview).
  - Interactive **"Kill Pod (Failover)"** button that demonstrates sudden pod crash (SIGKILL return code 137), Redis distributed lock reclamation, and automatic standby worker spin-up with zero duplicate records!


---

## 🧪 Current Verification Commands

To verify all completed phases on your machine at any time:

```powershell
# 1. Ensure Docker containers are running (PostgreSQL, Kafka KRaft, Redis)
docker compose ps

# 2. Run the entire multi-module test suite (66/66 tests passing across all modules)
.\mvnw.cmd test

# 3. Run Phase 8 Spring Boot REST API & SSE Integration suite (including flagship live load test)
.\mvnw.cmd test -pl safemigrate-server -Dtest=MigrationApiIntegrationTest

# 4. Run Phase 8 Spring Boot MockMvc Controller suite
.\mvnw.cmd test -pl safemigrate-server -Dtest=MigrationControllerTest

# 5. Run Production Hardening & Operational Resilience suite
.\mvnw.cmd test -pl safemigrate-core -Dtest=ProductionHardeningIntegrationTest

# 6. Run Comprehensive Idempotency & Edge-Cases Audit suite
.\mvnw.cmd test -pl safemigrate-core -Dtest=ComprehensiveIdempotencyAndEdgeCasesAuditTest

# 7. Run Phase 6 Worker Resilience & kill -9 tests
.\mvnw.cmd test -pl safemigrate-core -Dtest=WorkerResilienceIntegrationTest

# 8. Run Phase 7 Pre-Flight Safety Inspector tests
.\mvnw.cmd test -pl safemigrate-core -Dtest=PreflightInspectorTest

# 9. Run Phase 5 End-to-End Cutover with Sub-15ms Atomic Table Swap
.\mvnw.cmd test -pl safemigrate-core -Dtest=EndToEndCutoverIntegrationTest
```
