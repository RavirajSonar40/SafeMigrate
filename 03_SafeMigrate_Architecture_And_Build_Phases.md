# SafeMigrate — Architecture, Stack & Build Phases

---

## 1. Final Tech Stack (Locked)

| Layer | Technology | Why |
|---|---|---|
| Target database (v1) | **PostgreSQL** | Best-documented logical replication protocol (`pgoutput`); MySQL binlog is a stretch goal, not v1 scope |
| WAL capture | Postgres **logical replication slot** + `pgoutput` plugin | Native, no third-party agent needed; the same mechanism Debezium/gh-ost-style tools use |
| Migration worker language | **Go** (recommended) or Node.js/TypeScript if you're faster in it | Go has first-class Postgres replication libraries (`pglogrepl`), strong concurrency primitives, and reads as "systems-appropriate" language choice |
| Event backbone | **Kafka** | Captured WAL changes are published as events; decouples "reading the WAL" from "applying changes," gives you replay/audit capability and multiple consumers if needed later |
| State / coordination | **Redis** | Migration progress checkpoints, distributed lock (only one active migration per table at a time), dashboard caching |
| Control plane / metadata store | **PostgreSQL** (a separate, small "SafeMigrate" database — not the target DB) | Stores migration history, approval workflow state, user accounts — reuses your Prisma/Postgres RBAC experience directly |
| Dashboard backend API | **Node.js/Express** or **Next.js API routes** | You already know this cold from Traffic Buddy / police platforms — no new learning curve here, so time goes to the hard core instead |
| Dashboard frontend | **Next.js + Tailwind** | Same reasoning — reuse what you know, don't spend scarce time here |
| Real-time progress updates | **Socket.IO** | You already used this in Traffic Buddy for live updates — same pattern, applied to migration progress instead of complaint status |
| Worker orchestration | **Docker + Kubernetes (Jobs)** | Migration workers run as Kubernetes Jobs so they are independently restartable; this is also what makes the "crash-safe resume" demo possible and convincing |
| Reverse proxy / gateway | **Nginx** | Sits in front of the dashboard API; handles TLS termination and basic rate limiting on the API |
| Demo database hosting | **AWS RDS (Postgres)** | Running the demo against a real managed Postgres instance (not just Docker-on-laptop) makes the demo more credible |
| Artifact / log storage | **AWS S3** | Store migration audit logs / before-after schema snapshots |

**Explicit non-goals for v1:** MySQL support, multi-table batch migrations, Slack notifications. These
are stretch goals only after the core is fully working and demo-ready — do not start these early.

---

## 2. System Architecture (Component Breakdown)

```
                          ┌─────────────────────────┐
                          │   Target Postgres DB     │
                          │   (the table being        │
                          │    migrated)               │
                          └───────────┬───────────────┘
                                      │ logical replication slot
                                      ▼
                          ┌─────────────────────────┐
                          │   WAL Reader (Go)         │
                          │   - connects to slot       │
                          │   - decodes pgoutput      │
                          │   - emits change events   │
                          └───────────┬───────────────┘
                                      │ publishes
                                      ▼
                          ┌─────────────────────────┐
                          │        Kafka              │
                          │  topic: wal-changes.<tbl> │
                          └───────────┬───────────────┘
                                      │ consumes
                     ┌────────────────┼────────────────┐
                     ▼                                 ▼
        ┌─────────────────────────┐      ┌─────────────────────────┐
        │  Backfill Worker (Go)     │      │  Change Applier (Go)      │
        │  - batched copy of         │      │  - applies WAL events to  │
        │    existing rows            │      │    shadow table in order  │
        │  - throttled                │      │  - idempotent apply        │
        │  - checkpoints to Redis    │      │  - checkpoints to Redis   │
        └───────────┬───────────────┘      └───────────┬───────────────┘
                     └───────────────┬───────────────────┘
                                      ▼
                          ┌─────────────────────────┐
                          │   Shadow Table             │
                          │   (in target Postgres DB)  │
                          └───────────┬───────────────┘
                                      │ once caught up
                                      ▼
                          ┌─────────────────────────┐
                          │   Cutover Coordinator      │
                          │   - atomic table rename     │
                          │   - single transaction       │
                          └─────────────────────────┘

        ┌─────────────────────────┐        ┌─────────────────────────┐
        │  Control Plane API        │◄──────►│  SafeMigrate Metadata DB │
        │  (Node/Next.js)            │        │  (Postgres)                │
        │  - migration requests      │        └─────────────────────────┘
        │  - approval workflow       │
        │  - pre-flight checks       │
        └───────────┬───────────────┘
                     │ Socket.IO (live progress)
                     ▼
        ┌─────────────────────────┐
        │   Dashboard (Next.js)     │
        └─────────────────────────┘

        All workers run as Kubernetes Jobs, deployed via Docker images.
        Nginx sits in front of the Control Plane API as reverse proxy.
```

### Component Responsibilities

- **WAL Reader**: the single most technically sensitive component. Owns the replication slot
  connection, decodes raw `pgoutput` binary messages into structured `{table, operation, old_row,
  new_row, lsn}` events. Must never lose its place — the Postgres LSN (Log Sequence Number) it has
  successfully processed is checkpointed so replication can resume exactly from there after a restart.
- **Backfill Worker**: reads existing rows from the source table in primary-key-ordered batches
  (e.g., 1000 rows at a time), writes them into the shadow table. Throttled (configurable delay
  between batches) to avoid overloading the live database. Checkpoints "last row copied" so it can
  resume mid-backfill after a crash.
- **Change Applier**: consumes WAL change events from Kafka and applies them to the shadow table.
  Must apply changes **in the same order they occurred** on the source table (this is why Kafka
  partitioning by table/primary-key matters — you want ordering guarantees per row). Must be
  idempotent — if the same event is redelivered (e.g., after a crash before checkpointing), applying
  it twice must not corrupt data.
- **Cutover Coordinator**: waits until backfill is complete AND change-applier lag is zero (no
  pending WAL events left to apply), then performs the atomic rename inside a single Postgres
  transaction. This is the highest-stakes moment in the whole system — get this transaction wrong and
  you can lose data.
- **Control Plane API**: the "boring but necessary" REST/GraphQL API — manages migration requests,
  approval workflow, pre-flight safety checks (querying `information_schema` and row counts to warn
  about NULLs, missing indexes, etc.), and orchestrates starting/stopping Kubernetes Jobs for a given
  migration.
- **Dashboard**: renders everything above as a live, watchable UI.

---

## 3. Build Phases (Hard Part First — Do Not Build the Dashboard Before This Works)

### Phase 0 — Environment Setup (2-4 days)
- Docker Compose with Postgres (`wal_level = logical`), Kafka, Redis
- Write a **load generator script** — continuously INSERT/UPDATE/DELETE on a test table. You will run
  this constantly throughout development; build it early.
- Read Postgres docs: logical replication, replication slots, `pgoutput` message format

### Phase 1 — WAL Reader (the core risk — attack first) (1-2 weeks)
- Create a replication slot programmatically
- Connect and stream raw WAL messages
- Decode `pgoutput` binary format into structured change events (this is the hardest single piece of
  code in the entire project — budget real time for it)
- Milestone: print every INSERT/UPDATE/DELETE happening on your test table, correctly decoded, in
  real time, while your load generator runs
- **Do not proceed to Phase 2 until this is rock solid** — everything else depends on this being
  correct

### Phase 2 — Kafka Event Pipeline (3-5 days)
- WAL Reader publishes decoded events to a Kafka topic (partitioned by primary key, to preserve
  per-row ordering)
- Milestone: events flow from Postgres → WAL Reader → Kafka, visible via a simple consumer that just
  logs them

### Phase 3 — Backfill Worker (1 week)
- Given a source table, create the shadow table with a target schema
- Batched copy of existing rows, throttled, with progress checkpointed to Redis
- Milestone: shadow table fully populated with a snapshot of the source table's data

### Phase 4 — Change Applier + Ordering Correctness (1-2 weeks — the second hardest phase)
- Consume from Kafka, apply changes to the shadow table in correct order
- Handle the backfill/live-write race condition explicitly (this needs a clear, written-down strategy
  before you code it — e.g., "always apply WAL events after backfill copy for that row, using
  LSN/timestamp comparison to decide which write wins")
- Milestone: run backfill and live traffic simultaneously; after backfill completes and the applier
  catches up, run a full checksum/row-count comparison between source and shadow table — **they must
  match exactly**, even with concurrent inserts, updates, and deletes happening throughout

### Phase 5 — Cutover + Rollback (1 week)
- Implement the atomic table-rename swap inside a transaction
- Implement rollback (revert to original table)
- Milestone: perform a full end-to-end migration on a live, actively-written-to table with zero
  dropped/failed requests from your load generator

### Phase 6 — Crash-Safe Resume (3-5 days — do not skip this)
- Ensure every component (WAL Reader, Backfill Worker, Change Applier) persists enough checkpoint
  state (Redis) to resume correctly after being killed
- Milestone: kill each component mid-migration (`kill -9` / delete the pod) at different points, and
  confirm the migration completes correctly after restart — no data loss, no duplication

### Phase 7 — Pre-Flight Safety Checks (3-5 days)
- Missing index detection, NOT NULL violation detection, FK constraint checks, duration estimate
- Milestone: intentionally set up a table that *will* fail a NOT NULL migration, confirm SafeMigrate
  warns about it before running

### Phase 8 — Control Plane API + Approval Workflow (1 week)
- Migration request/approval endpoints, metadata DB schema, Kubernetes Job orchestration triggered
  from the API

### Phase 9 — Dashboard (1-1.5 weeks)
- Table browser, migration request form, live progress view (Socket.IO), history/timeline, rollback
  button
- This is intentionally last — by this point the hard engine is proven, and the dashboard is "just"
  reusing skills you already have

### Phase 10 — Kubernetes Deployment + AWS Demo Setup (3-5 days)
- Deploy all components to a real (or local kind/minikube) Kubernetes cluster
- Point at a real AWS RDS Postgres instance for the final demo
- Nginx in front of the Control Plane API

### Phase 11 — Rigorous Testing Pass (see the dedicated Testing doc — do not compress this phase)

---

## 4. Realistic Time Estimate

Given you're comfortable with Postgres, Kafka, Redis, Docker/K8s already: **8-12 weeks** of consistent
part-time effort (evenings/weekends around your studies) is a realistic estimate to reach a fully
working, demo-ready core (Phases 0-7) plus a minimal but functional dashboard (Phases 8-10). Do not
feel behind if Phase 1 and Phase 4 (the two genuinely hard phases) take longer than the others — that's
expected and is where the real learning (and the real resume value) happens.

## 5. Golden Rule For This Build

**Never let the dashboard get ahead of the engine.** It will be tempting to build the pretty UI early
because it's fast and satisfying. Resist this. An unfinished dashboard on top of a rock-solid engine is
still an impressive, demoable project (you can show progress via terminal logs). A polished dashboard
on top of a broken or untested engine is not — it's the exact "looks impressive, isn't" trap that a
sharp interviewer will find in about two follow-up questions.
