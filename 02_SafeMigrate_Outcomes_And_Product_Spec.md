# SafeMigrate — Outcomes, Product Spec & Demo Experience

This document describes **what the finished product actually does, looks like, and feels like to use**
— both the core engine (the hard part) and the product layer wrapped around it (what makes it feel
like a real tool, not a script).

---

## 1. The End-to-End User Story

A backend engineer at a company has a production Postgres table, `orders`, with 8 million rows and
constant live traffic. They need to add a new column (`priority_score INT`) and an index on it. Doing
this with a plain `ALTER TABLE` would lock the table for minutes — unacceptable.

Instead, they:

1. Log into the SafeMigrate dashboard and connect their Postgres database (connection string, read
   access to the WAL via a replication slot).
2. See a list of their tables, with basic stats (row count, size, write rate).
3. Select `orders`, and describe the schema change they want (add column, add index, change type,
   etc.) through a simple form — or paste a raw `ALTER TABLE` statement.
4. **Before anything runs**, SafeMigrate performs a **pre-flight safety check** and shows warnings,
   e.g.: *"This table has no primary key index on `id` — backfill will be slow"* or *"340 existing
   rows have NULL in a column you're making NOT NULL — this migration will fail on cutover unless you
   backfill a default value first."*
5. They click "Start Migration." A **live progress view** appears:
   - Rows backfilled so far / total rows (progress bar + percentage)
   - Current replication lag between the shadow table and live table (in seconds/rows)
   - Live throughput (rows/sec being copied, throttled automatically if it detects DB load spikes)
   - A running log of WAL events being captured and replayed in real time
6. Once backfill is complete and lag is at zero, the dashboard shows: **"Ready to cut over."** They
   click confirm.
7. The cutover happens — shown as a distinct, timestamped event in the timeline, taking milliseconds.
   The dashboard shows: **"Migration complete. Zero downtime. 8,004,213 rows migrated in 14m 22s."**
8. They can view a full history of past migrations, each with a timeline of exactly what happened and
   when, and a **one-click rollback** button for any completed migration.

This is the story you demo live in an interview or on video: show the fake load script hammering the
table the whole time, show the progress bar moving, then show the cutover happen with zero failed
requests in your load-testing script's output.

---

## 2. Feature List (What Actually Ships)

### Core Engine (the hard, non-negotiable part)
- [ ] Connect to Postgres via a logical replication slot
- [ ] Decode WAL events (INSERT / UPDATE / DELETE) into structured change records
- [ ] Create a shadow table matching a target schema
- [ ] Batched, throttled backfill of existing rows into the shadow table
- [ ] Real-time replay of captured WAL changes onto the shadow table, in correct order
- [ ] Correctness guarantee: shadow table converges to be identical to what the live table would be
- [ ] Atomic cutover (table rename swap) inside a single transaction
- [ ] Crash-safe checkpointing — worker can die and resume without data loss or duplication
- [ ] Rollback: revert to the original table cleanly if migration is aborted or fails

### Safety & Pre-Flight Checks (adds real value, not just decoration)
- [ ] Detect missing indexes that will make backfill slow
- [ ] Detect NOT NULL constraints that existing data will violate
- [ ] Detect foreign key constraints that may block the swap
- [ ] Estimate migration duration based on table size and current throttle settings
- [ ] Warn if there is insufficient disk space for a full table copy

### Product / Dashboard Layer (makes it demo like a platform)
- [ ] Connect-a-database flow (store connection securely, test connectivity)
- [ ] Table browser (list tables, row counts, sizes, last-migrated date)
- [ ] Migration request form (schema change description or raw SQL)
- [ ] Live migration progress view (progress bar, lag, throughput, live event log)
- [ ] Migration history / timeline view per table
- [ ] One-click rollback with before/after schema diff
- [ ] Simple approval workflow: one user requests a migration, another approves it before it runs
    (reuses your RBAC experience — a genuinely realistic "enterprise" feature)

### Nice-to-Have / Stretch (only after core is rock solid)
- [ ] MySQL binlog support (in addition to Postgres WAL)
- [ ] Slack/webhook notifications on migration start/complete/fail
- [ ] Multi-table batched migrations (run several related migrations as one coordinated operation)

---

## 3. What "Done" Looks Like — The Demo Checklist

For a demo to actually land the "wow" reaction, it needs to visibly prove three things, not just claim
them:

1. **Zero downtime, provably.** Run a load-testing script hitting the table continuously throughout
   the entire migration. At the end, show: 0 failed requests, 0 errors, full request log. Don't just
   say "it's zero downtime" — show the number.
2. **Correctness under concurrency.** Have the load script perform inserts, updates, *and* deletes
   during the migration (not just inserts — that's the easy case). After cutover, run a checksum/count
   comparison between what the original table *should* contain and what the new table actually
   contains. Show they match exactly.
3. **Resilience to failure.** Mid-migration, kill the SafeMigrate worker process (`kill -9`, or delete
   the Kubernetes pod). Show it resumes automatically from its last checkpoint and completes correctly
   — not from scratch, not corrupted.

If your demo can show all three of these clearly (a short screen recording is enough), that is what
produces the "wait, you actually built this?" reaction — because you're not describing correctness,
you're proving it on camera.

---

## 4. Visual / UX Direction

Keep the dashboard **dense and technical-looking, not consumer-app cute** — this is a tool for
engineers, and it should look like something out of a company's internal infra tooling (think:
Linear, Vercel dashboard, or GitHub Actions run view) rather than a generic SaaS landing page.
Specifically:

- Dark-mode-first, monospace font for logs/table names/SQL, clear use of color only for status
  (green = healthy/complete, yellow = in progress/lag, red = failed/blocked)
- The live progress view should feel like watching a CI/CD pipeline run — a clear linear sequence of
  stages (Pre-flight → Backfill → Catch-up → Cutover → Complete), each with its own status and timing
- Show real numbers everywhere (rows/sec, lag in ms, % complete) — specificity is what makes it feel
  real and "product-grade" instead of a toy

---

## 5. The One-Sentence Pitches (Pick the Right One Per Audience)

- **For a resume bullet point:** "Built a zero-downtime database schema migration platform that tails
  Postgres's write-ahead log in real time to keep a shadow table synchronized during large-scale
  migrations, with crash-safe resume and atomic cutover — inspired by GitHub's `gh-ost`."
- **For a non-technical interviewer:** "I built a tool that lets engineering teams change how their
  database is structured without ever taking their app offline — which is a real, common cause of
  outages at growing companies."
- **For a technical deep-dive interview:** lead with the WAL-tailing and ordering-correctness problem
  (Section 4 of the Overview doc) — that's where the real conversation will go.
