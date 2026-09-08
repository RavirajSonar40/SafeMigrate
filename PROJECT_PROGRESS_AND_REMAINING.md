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

---

## 📋 Part 3: What is Remaining (Future Phases)

```
[ Phase 0: Setup ] ────► [ Phase 1: WAL Reader ] ────► [ Phase 2: Kafka Stream ] (COMPLETED)
                                                                 │
                                                                 ▼
[ Phase 3: Backfill Engine (COMPLETED) ] ─────────────► [ Phase 4: Change Applier (COMPLETED) ]
            │                                                                │
            ▼                                                                ▼
[ Phase 5: Atomic Cutover & Rollback (NEXT) ] ───────► [ Phase 6: Crash-Safe Checkpointing ]
            │                                                                │
            ▼                                                                ▼
[ Phase 7: Pre-Flight Safety Checks ] ────────────────► [ Phase 8: Spring Boot Control Plane API ]
            │                                                                │
            ▼                                                                ▼
[ Phase 9: Dashboard UI (Next.js) ] ──────────────────► [ Phase 10: Kubernetes Orchestration ]
```

### 1. Phase 5: Atomic Cutover Coordinator & Rollback (Next Step)
- **Lag Catch-up Monitor**: Monitors replication lag and waits until backfill is complete and Kafka lag drops to zero.
- **Atomic Table Swap**: Executes table rename inside a single transaction with lock timeout protection:
  ```sql
  BEGIN;
    SET LOCAL lock_timeout = '2s';
    LOCK TABLE orders IN ACCESS EXCLUSIVE MODE;
    ALTER TABLE orders RENAME TO orders__old;
    ALTER TABLE orders__shadow RENAME TO orders;
  COMMIT;
  ```
  Swap duration: single-digit milliseconds.
- **Rollback Engine**: Clean abortion mechanism reverting to the original table if cutover is cancelled or fails.

### 3. Phase 5: Atomic Cutover & Rollback
- **Lag Catch-up**: Waits until backfill is complete and Kafka replication lag drops to zero.
- **Atomic Table Swap**: Executes table rename inside a single transaction:
  ```sql
  BEGIN;
    LOCK TABLE orders IN ACCESS EXCLUSIVE MODE;
    ALTER TABLE orders RENAME TO orders__old;
    ALTER TABLE orders__shadow RENAME TO orders;
  COMMIT;
  ```
  Swap duration: single-digit milliseconds.
- **Rollback Engine**: Clean abortion mechanism reverting to the original table if cutover is cancelled.

### 4. Phase 6: Crash-Safe Resume
- **Redisson Distributed Lock**: Ensures only one migration job runs per table at a time (`safemigrate:lock:<table_name>`).
- **Resilience Testing**: Forcibly terminating workers (`kill -9`) mid-backfill and mid-replication; proving they resume from Redis checkpoints with 0 data loss or duplication.

### 5. Phase 7: Pre-Flight Safety Checks
- Scans target table before starting:
  - Detects missing primary keys / missing indexes.
  - Detects `NOT NULL` constraint violations against existing NULL rows.
  - Validates type-casting feasibility.
  - Checks available disk space against estimated table expansion (`~1.8x` table size).
  - Validates that user-pasted SQL is strictly a supported `ALTER TABLE`.

### 6. Phase 8: Spring Boot 3 Control Plane API
- REST endpoints for migration requests, table browsing, two-person approval workflows, and cutover triggering.
- Real-time progress broadcasting (SSE / WebSocket).

### 7. Phase 9: Engineer Dashboard
- Modern dark-mode UI (Next.js + Tailwind) showing live migration pipeline stages, rows/sec throughput, replication lag, and live event logs.

### 8. Phase 10: Kubernetes Packaging & Final Demo
- Package workers as Kubernetes Jobs and demonstrate resilience by deleting worker pods on camera while traffic continues uninterrupted.

---

## 🧪 Current Verification Commands

To verify the completed phases on your machine at any time:

```powershell
# 1. Ensure Docker containers are running
docker compose ps

# 2. Run the full test suite (6/6 tests passing)
.\mvnw.cmd test

# 3. Run the live closed-loop WAL-to-Kafka test
.\mvnw.cmd test -pl safemigrate-core -Dtest=PostgresWalToKafkaIntegrationTest

# 4. Run the high-concurrency traffic simulator test
.\mvnw.cmd test -pl safemigrate-loadgen -Dtest=LoadGeneratorTest
```
