# SafeMigrate — Project Overview

## 1. The Problem (Why This Project Exists)

Any application backed by a relational database (Postgres, MySQL) eventually needs to change its
schema: add a column, add an index, change a data type, rename something. On a small table this is
instant and harmless. On a **large, high-traffic production table** (millions of rows, constant reads
and writes), a naive schema change is dangerous:

- `ALTER TABLE` in Postgres/MySQL can take an **exclusive lock** on the table for the duration of the
  operation. On a huge table, that can mean the table is completely unusable — no reads, no writes —
  for anywhere from several seconds to several minutes.
- For a company whose product depends on that table (checkout, user auth, orders), that lock window
  is direct, visible downtime. Customers see errors. Revenue is lost. Pages get paged.
- This is not a hypothetical problem. It's common enough that **GitHub built and open-sourced a
  dedicated tool, `gh-ost`, specifically to solve it**, and Percona built a similar tool
  (`pt-online-schema-change`) for the same reason, years earlier. Both are widely used in the
  industry today. This is a real, validated, "the biggest companies in the world had to solve this"
  problem — not an invented academic exercise.

**SafeMigrate is a from-scratch reimplementation of this idea**: a tool (and eventually, a small
platform) that lets you change a production table's schema **without taking it offline**, by copying
data to a new table in the background and keeping it perfectly in sync with live traffic until it's
safe to instantly swap over.

## 2. The Core Technical Idea (In Plain English)

Instead of altering the table directly, SafeMigrate does this:

1. **Create a "shadow" table** with the new schema (e.g., same as the original, but with the new
   column/index/type already applied).
2. **Backfill** the shadow table by copying all existing rows from the original table, in small
   batches, slowly enough that it doesn't overload the live database.
3. **While backfilling is happening, the original table keeps receiving live writes** (new inserts,
   updates, deletes from real application traffic). SafeMigrate needs to capture *every one* of these
   changes and replay them onto the shadow table too — otherwise the shadow table would be stale the
   moment backfill finishes.
4. To capture those live changes, SafeMigrate **taps directly into the database's Write-Ahead Log
   (WAL)** — the internal, low-level stream every Postgres database already writes to, recording every
   single change made to the database, used internally for crash recovery and replication. This is the
   same mechanism real replication tools and CDC (Change Data Capture) systems use.
5. Once the shadow table has fully "caught up" (backfill done + every live change since then replayed),
   SafeMigrate performs an **atomic swap**: in one transaction, the original table is renamed out of
   the way and the shadow table takes its place. This swap is near-instantaneous — milliseconds, not
   minutes.
6. If anything goes wrong at any point — the process crashes, the database connection drops, a
   conflict is detected — SafeMigrate needs to **resume safely from where it left off**, or roll back
   cleanly, without ever corrupting or duplicating data.

This is genuinely difficult because you are working below the level of normal application code — you
are reading a binary replication protocol, reasoning about race conditions between "the copy I'm doing
right now" and "the write that's happening on the real table right now," and guaranteeing correctness
under crashes. This is not CRUD-app difficulty. It's the same category of problem that makes database
internals engineering a distinct, respected, well-paid specialization.

## 3. Why This Is the Right Project For a Resume

- **It's not a tutorial clone.** There is no widely-circulated "build gh-ost from scratch" tutorial
  series the way there is for building a Kafka clone or a Raft KV-store. You have to actually read
  Postgres's own documentation and reason about the protocol yourself.
- **The difficulty is inherent to the problem, not to unfamiliar tooling.** You already know Postgres,
  Kafka, Redis, Docker, and Kubernetes from your other projects. This project asks you to go *deeper*
  into tools you already trust, rather than learn an entirely new stack (which is why this was chosen
  over, e.g., eBPF/kernel-level projects).
- **It is instantly understandable to any backend engineer who interviews you.** You don't need to
  explain what a schema migration is or why locking a table is bad — every backend engineer has felt
  this pain directly. This means the "wow" lands immediately, without a long setup explanation.
- **It is genuinely useful.** Unlike a lot of portfolio projects, this is something a real team could
  actually adopt. That opens the door to open-sourcing it, getting real GitHub stars, and even real
  usage/feedback — all of which are strong, honest additions to a resume ("50+ stars," "used it to
  migrate a 10M-row table safely") beyond just "I built this."

## 4. What Makes This Hard (Be Honest With Yourself About This List)

These are the specific sub-problems that separate "an impressive systems project" from "a script that
technically works once, on my laptop, with no load":

1. **Correctly parsing Postgres's logical replication stream** (the `pgoutput` plugin format) — a
   binary/structured protocol, not a REST API. Real low-level parsing work.
2. **Ordering and applying changes correctly.** If a row is updated three times while you're mid-way
   through capturing changes, you must apply all three in the right order — never dropping one,
   never applying them out of order, never double-applying one.
3. **The backfill-vs-live-write race condition.** While you're copying historical rows in batches, a
   live UPDATE might hit a row you already copied (fine, you'll get that as a WAL event and reapply
   it) or a row you *haven't copied yet* (also fine, as long as your logic doesn't let the backfill
   overwrite a newer version with an older one). Getting this ordering guarantee right is the single
   most intellectually serious part of the project.
4. **The atomic cutover.** The moment of swapping tables must not lose or corrupt any write that
   happens in that exact instant. This needs to happen inside a single transaction, correctly.
5. **Crash-safe resume.** If your migration worker process dies (crashes, gets OOM-killed, the pod
   restarts) halfway through, it must be able to pick up exactly where it left off — not restart from
   zero (wasteful and could reintroduce ordering bugs) and not silently skip data (corruption).

If you build only the happy path (small table, no concurrent writes, no crashes, run once on your
laptop), this project loses almost all of its value. The above five points are the actual project —
everything else (UI, extra features) is secondary.

## 5. The Origin-Story Pitch (Use This in Interviews)

> "Every growing company eventually hits the problem of needing to change the schema of a huge,
> high-traffic production table — and doing that with a normal `ALTER TABLE` can lock the table and
> cause real downtime. GitHub actually built and open-sourced a tool called `gh-ost` specifically to
> solve this. I built my own version of that idea from scratch: it taps into Postgres's write-ahead
> log to capture live changes in real time, backfills a shadow table in the background, keeps it in
> sync with ongoing writes, and then does a near-instant atomic swap — with crash-safe resume if
> anything fails partway through."

This single explanation does most of the work in an interview: it states a real, recognizable problem,
names a respected prior art (which signals you did your research, not just picked a random idea), and
summarizes the hard technical mechanism in one breath.
