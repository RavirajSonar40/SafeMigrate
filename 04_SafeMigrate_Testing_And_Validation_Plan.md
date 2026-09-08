# SafeMigrate — Rigorous Testing & Validation Plan

A project like this lives or dies on correctness. "It worked once on my laptop" is not proof of
anything — the entire value of this project is proving it's *actually* safe under real conditions.
This document is the checklist you run through **after Phase 6** (crash-safe resume) and again as a
**final gate before considering the project "done."**

---

## 1. Categories of Testing

### A. Correctness Under No Concurrency (baseline sanity)
The simplest possible case — no writes happening during migration.
- [ ] Migrate an empty table successfully
- [ ] Migrate a small table (100 rows) successfully, verify row-for-row match after cutover
- [ ] Migrate a large table (1M+ rows) successfully, verify checksum match after cutover
- [ ] Migrate with each type of schema change separately: add column, add index, change column type,
      add NOT NULL constraint (with valid data), rename column

### B. Correctness Under Concurrent Writes (the real test)
Run your load generator continuously throughout the *entire* migration for every test below.
- [ ] Concurrent INSERTs only — verify every inserted row exists in the shadow table post-cutover
- [ ] Concurrent UPDATEs only — verify the shadow table reflects the *final* value of every updated
      row, not an intermediate one
- [ ] Concurrent DELETEs only — verify deleted rows are absent from the shadow table
- [ ] **Mixed INSERT + UPDATE + DELETE simultaneously** — this is the real-world case and the hardest
      to get right; verify full correctness via checksum/row-count comparison
- [ ] A row that is inserted *and then immediately updated* before backfill reaches it — verify the
      final state is correct, not a stale intermediate insert
- [ ] A row that is updated *and then deleted* in quick succession — verify it ends up correctly
      absent, not present with stale data
- [ ] High-throughput write burst (simulate a traffic spike) during backfill — verify no events are
      dropped and the applier keeps up or correctly reports increasing lag rather than silently
      falling behind

### C. Failure & Crash Recovery
For each of the following, forcibly kill the relevant component (`kill -9`, delete the Kubernetes pod,
or cut the DB connection) at the specified moment, then verify the migration completes correctly after
restart with no data loss or duplication.
- [ ] Kill the **WAL Reader** mid-stream — verify it resumes from the correct LSN, no events lost or
      replayed twice
- [ ] Kill the **Backfill Worker** mid-batch — verify it resumes from the last checkpointed row, no
      rows skipped or duplicated
- [ ] Kill the **Change Applier** mid-apply — verify no event is applied twice (idempotency) and none
      are silently skipped
- [ ] Kill the **entire migration process** (all components) at a random point and restart everything
      — verify the migration still completes correctly end-to-end
- [ ] Simulate a **database connection drop** (not a process crash — the DB itself becomes briefly
      unreachable) — verify the system retries and recovers rather than silently failing

### D. The Cutover Moment (highest stakes — test this the most)
- [ ] Perform a write to the source table in the exact moment the cutover transaction is executing —
      verify it is either applied to the new (shadow) table correctly, or cleanly rejected/queued, but
      never lost or applied to the now-renamed-away old table
- [ ] Abort a migration *after* backfill but *before* cutover — verify rollback leaves the original
      table completely untouched and correct
- [ ] Abort a migration *during* cutover (if your design allows interrupting the transaction) — verify
      the system ends up in one of the two valid end states (fully old table, or fully new table),
      never a partial/corrupted state

### E. Pre-Flight Safety Checks
- [ ] Attempt a migration that adds a NOT NULL column to a table with existing NULL-violating data —
      verify SafeMigrate warns *before* running, not fails midway
- [ ] Attempt a migration on a table with no primary key / no usable index — verify a clear warning
      about expected slowness, rather than silently taking hours
- [ ] Attempt a migration on a table involved in foreign key relationships — verify constraints are
      either handled correctly or clearly flagged as unsupported for this migration

### F. Performance & Load Testing
- [ ] Measure backfill throughput (rows/sec) at different throttle settings, document the tradeoff
      between migration speed and load placed on the live database
- [ ] Measure end-to-end migration time for tables of increasing size (10K, 100K, 1M, 10M rows) and
      document how it scales
- [ ] Confirm the throttling mechanism actually reduces load on the source database under a
      concurrent read/write benchmark (e.g., using `pgbench`)

### G. Multi-Migration / Concurrency at the System Level
- [ ] Attempt to start two migrations on the *same* table simultaneously — verify the distributed
      lock (Redis) correctly rejects the second one with a clear error, rather than both running and
      corrupting each other
- [ ] Run migrations on two *different* tables simultaneously — verify both complete correctly and
      independently

---

## 2. How to Actually Prove Correctness (Not Just "It Looked Fine")

For every concurrency test in Category B, use one of these two rigorous verification methods rather
than eyeballing the data:

1. **Row-count + checksum comparison**: after cutover, run `SELECT COUNT(*)` and a checksum aggregate
   (e.g., hash of all rows, ordered by primary key) on the new table, and compare it against an
   independently-maintained "expected final state" that your load generator tracks as it runs (i.e.,
   your load generator should know, in its own memory/log, exactly what the final correct state of
   every row it touched should be).
2. **Golden dataset diffing**: for smaller test tables, dump the full expected final dataset (computed
   independently, e.g., by running the same operations against a plain, un-migrated reference table)
   and do a full row-by-row diff against the migrated table.

Never rely on "the numbers looked roughly right" — for a project whose entire pitch is data
correctness under concurrency, sloppy verification undermines the whole point.

---

## 3. Demo-Ready Test Recording

Once the above categories all pass, record (screen capture) a single end-to-end demo run that
includes, visibly, in this order:

1. Load generator running continuously, visible request counter (success/fail) on screen
2. Migration started via the dashboard, pre-flight check shown
3. Backfill progress bar moving, live event log visible
4. **A deliberate `kill -9` on the migration worker mid-backfill**, shown on screen, followed by it
   automatically resuming
5. Migration reaching "ready to cut over," cutover triggered
6. Load generator's success/fail counter shown to have **zero failures** for the entire duration
7. Final row-count/checksum verification shown passing

This single recording (3-5 minutes) is worth more in an interview or on a portfolio page than any
amount of written description — it's the artifact that turns "I built a hard project" into "I can
prove it actually works."

---

## 4. A Note on Scope Discipline During Testing

It is normal and expected to find real bugs during this phase — ordering edge cases, race conditions
you didn't anticipate, checkpoint logic that doesn't quite resume correctly. **Finding and fixing these
bugs is not a sign the project is going badly — it is the actual valuable engineering work.** Budget
real time for this phase (at least 1-2 weeks) rather than treating it as a formality after "the code is
done." A project that went through this rigor and had its rough edges found and fixed is a stronger
resume story than one that was never tested hard enough to find them.
