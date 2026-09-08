# SafeMigrate — Gaps & Additions (Completeness Pass on Docs 01–04)

This doc closes gaps found by cross-checking the Overview, Product Spec, Architecture, and Testing
docs against each other. Each item below was promised in one doc but missing (or contradicted) in
another. Treat this as an addendum, not a rewrite — fold each item into the relevant section of
docs 02–04 as you go.

---

## 1. Disk-space pre-flight check (missing from build phases and tests)

Doc 02 lists this as a shipped feature. It needs a home in both the build plan and the test plan.

**Add to Phase 7 (Architecture doc, Pre-Flight Safety Checks):**
- Query available disk space on the target Postgres instance/volume before backfill starts.
- Estimate required space as `(row count × average row size) × safety factor (~1.5–2x)` to account
  for index rebuild and WAL growth during backfill.
- Block or warn (your call — but the doc should say which) if estimated requirement exceeds a
  configurable threshold of available space.

**Add to Category E (Testing doc, Pre-Flight Safety Checks):**
- [ ] Attempt a migration on a table whose estimated backfill size exceeds available disk space —
      verify SafeMigrate warns/blocks before starting, not mid-backfill

---

## 2. Throttling: resolve the "static" vs "adaptive" mismatch

Doc 02 promises throughput that's *"throttled automatically if it detects DB load spikes."* Doc 03's
architecture only specifies a static, configurable delay between batches. These are genuinely
different features — pick one explicitly before Phase 3:

- **Option A (simpler, matches current architecture):** Static, user-configurable throttle only.
  → Update doc 02's wording to remove "automatically... detects load spikes" so the spec matches
  what's actually being built.
- **Option B (matches current product spec, adds real scope):** Add a lightweight load-detection
  signal — e.g., poll `pg_stat_activity` or replication lag / active connection count on an interval,
  and dynamically widen the delay between backfill batches when load crosses a threshold.
  → If you want this, add it explicitly as a Phase 3 sub-task, since it's new work, not something
  "configurable throttle" already covers.

Either is a legitimate choice for a portfolio project — just make the doc that's wrong (currently 02)
match the decision you actually make.

**Add to Category F (Testing doc) if you pick Option B:**
- [ ] Generate an artificial load spike on the source DB during backfill — verify the throttle
      widens automatically and DB load drops, without manual intervention

---

## 3. Post-cutover rollback: design decision + real test coverage

This is the highest-priority gap. "One-click rollback for any completed migration" (doc 02) is a
materially harder problem than rollback-before-cutover (doc 04, Category D) because writes have
already landed on the new (renamed) table since cutover. Right now there's no written design for it
and no test for it.

**Before building it, write down the actual strategy — options include:**
- **Time-boxed rollback only:** rollback is only offered for N minutes after cutover, and works by
  replaying the (small) set of post-cutover writes back onto the renamed-away original table before
  swapping back. Simpler, but needs a second short WAL-capture window post-cutover.
- **No true post-cutover rollback — only a "revert schema" migration:** be upfront that "rollback" for
  a completed migration actually means *running a new migration that reverses the schema change*, not
  reconstructing exact pre-cutover state. This is much simpler to build honestly, but doc 02's wording
  ("one-click rollback button for any completed migration") should be adjusted to reflect this if it's
  the path you take, so the demo doesn't overclaim.

**Add to Category D (Testing doc, The Cutover Moment) — new subsection:**
- [ ] Trigger rollback on a migration that completed and has received live writes since cutover —
      verify the behavior matches whichever strategy above was chosen, and that no writes made after
      cutover are silently lost during the rollback
- [ ] Attempt rollback on a migration far outside any time-box (if Option A above is chosen) — verify
      it's cleanly rejected with a clear message, not attempted with stale/incomplete data

---

## 4. Approval workflow — new test category

Doc 03 Phase 8 and doc 02 both spec a two-person request/approve workflow. It has zero test coverage
today. Add as a new category in the Testing doc:

**Category H — Approval Workflow & Access Control**
- [ ] User requests a migration; verify it cannot start until a second, different user approves it
- [ ] The requesting user attempts to approve their own request — verify this is rejected
- [ ] An unauthorized/unapproved migration is attempted directly against the Control Plane API
      (bypassing the dashboard) — verify the API itself enforces the approval gate, not just the UI
- [ ] Two different users both attempt to approve the same request near-simultaneously — verify no
      duplicate migration jobs are started

---

## 5. Connection string storage — one-line decision needed

Add a row to the Architecture doc's stack table (Section 1) or a short note in Phase 8:

> Connection strings for target databases are encrypted at rest (e.g., via a KMS-backed secrets
> manager, or an application-level encryption key stored outside the metadata DB) and are never
> logged in plaintext, including in error messages or audit logs.

Small addition, but worth having written down before Phase 8 rather than decided ad hoc while coding.

---

## 6. Raw SQL input validation

**Add to Phase 7 (Architecture doc):**
- Before accepting a pasted raw SQL statement as a migration request, parse it to confirm it is a
  single, supported `ALTER TABLE` statement (add column / add index / change type / rename column /
  add constraint) — reject anything else (multiple statements, DROP/TRUNCATE, non-DDL) with a clear
  error rather than attempting to run it.

**Add to Category E (Testing doc):**
- [ ] Paste a raw SQL statement that is not a supported ALTER TABLE form (e.g., multiple statements,
      a DROP TABLE, or arbitrary DML) — verify it is rejected at pre-flight with a clear error, never
      passed through to execution

---

## 7. Type-change casting validation

**Add to Phase 7 (Architecture doc), alongside the NOT NULL check:**
- Before running a column type change, sample (or fully scan, for smaller tables) existing data and
  verify it can be cast to the target type — surface specific rows/values that would fail the cast.

**Add to Category E (Testing doc):**
- [ ] Attempt a column type change where existing data cannot be cast to the new type (e.g., a text
      column containing non-numeric values being changed to `INT`) — verify SafeMigrate warns with the
      specific offending values *before* running, not mid-backfill

---

## Summary Table

| Gap | Was promised in | Was missing from |
|---|---|---|
| Disk space check | Doc 02 (feature list) | Doc 03 Phase 7, Doc 04 Category E |
| Adaptive throttle | Doc 02 (UX story) | Doc 03 architecture (only static throttle specified) |
| Post-cutover rollback | Doc 02 (feature list) | Doc 03 (no design), Doc 04 Category D (untested) |
| Approval workflow tests | Doc 02 + Doc 03 Phase 8 | Doc 04 (no category at all) |
| Secure connection storage | Doc 02 (UX story) | Doc 03 stack table |
| Raw SQL validation | Doc 02 (accepts raw SQL) | Doc 03 Phase 7, Doc 04 Category E |
| Type-change cast validation | Doc 03 Phase 4 (implied) | Doc 03 Phase 7, Doc 04 Category E |

Everything else in docs 01–04 checks out as internally consistent — the phase-by-phase build order,
the architecture-to-testing mapping, and the demo checklist all line up correctly with what's
described elsewhere in the set.
