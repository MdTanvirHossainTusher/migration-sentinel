# Submission form — copy/paste

Fields on the form: **Title**, **Description** (rich text + links), **Video URL**, **Source Code** (zip, ≤ 50 MB).

---

## Title

```
Migration Sentinel — an agent that catches the database migration that's safe on your laptop and an outage in production
```

---

## Description

**The problem.** A backend engineer opens a PR that adds a Flyway migration. Their local
Postgres has a few hundred rows per table, so it applies in 20 ms and every test passes.
They can't run it against a copy of production — it's too big, or access is restricted, or
standing one up takes a day. So the migration gets a human eye-scan and merges. Then the
deploy runs it against a 50-million-row table, it takes an `ACCESS EXCLUSIVE` lock for four
minutes, and the API is down for four minutes mid-deploy. The thing that separates a safe
migration from a dangerous one is **the size and shape of the data it runs against** — exactly
what the SQL text doesn't contain and the reviewer can't see.

**The agent.** Migration Sentinel gives the reviewing agent real tools instead of just the
SQL. For each review it: spins up a disposable Postgres (Testcontainers); replays the repo's
**whole prior migration history** in Flyway-version order, then a seed that puts the affected
tables at production scale; runs the candidate migration **one statement at a time**, timing
each and capturing the locks it takes; introspects the result (`pg_class` row estimates,
`pg_index`, foreign keys, `EXPLAIN`); and runs a Hibernate-`validate`-equivalent check of the
JPA mapping against the post-migration schema. An **analyzer** agent proposes findings from
that evidence; a separate **verifier** agent independently re-checks each one against the
sandbox and drops anything not backed by a tool result. The output is a report an engineer
pastes into the PR — a verdict, an evidence block per finding (row counts, lock modes,
`EXPLAIN` lines), and a suggested rewrite.

**Measured improvement** (15-case corpus with seeded defects; primary metric = recall,
because a missed outage is the expensive failure):

| Metric | Baseline (one prompt, SQL only) | Full agent |
| --- | --- | --- |
| Recall — defects caught | 85% | **100%** |
| F1 | 81% | **96%** (`gpt-5.6-luna`) · **100%** (deterministic heuristic) |
| False positives / case | 0.20 | **0.07** / 0.00 |

The prompt-only baseline can't tell `SET NOT NULL` on a 5M-row table (an outage) from the
identical statement on an empty table (instant) — it flags both or neither. The agent calls
`estimate_rows`, sees 5,000,000 vs 0, and gets both right.

**Engineering.** Transactional outbox → Kafka job queue with a hybrid relay (immediate
after-commit publish + scheduled sweep) and `SELECT … FOR UPDATE SKIP LOCKED` worker lease;
a durable `audit_event` trail written in the business transaction (declarative `@Audited`
aspect, ordered inside the transaction advice); credential redaction across logs, audit
payloads and stored trajectories; per-request LLM keys AES-GCM encrypted at rest; presigned
S3/RustFS download for the report. All behind flags — `docker compose up --build` turns it on;
`./gradlew test` needs none of it. Offline `heuristic` LLM client so the whole thing runs and
the evaluation reproduces with **no API key**.

**Reproduce it.** `docker compose up --build` (self-contained — bundles a Docker-in-Docker
sandbox engine, Kafka and RustFS), open `http://localhost:3000`, drop a `db/migration` folder.
`./gradlew evaluationTest` runs the corpus through the baseline and the full agent and asserts
the agent wins. Full guide: `docs/REPRODUCTION_GUIDE.md`.

**In the repo:**
- Improvement changelog (stages 0 → 7): `docs/CHANGELOG_IMPROVEMENT.md`
- Agent traces (frozen JSON + live endpoint) + agent instructions: `docs/traces/`, `src/main/resources/prompts/`, `docs/AGENT_TRAJECTORIES.md`
- Architecture & scaling: `docs/ARCHITECTURE.md` · Safety model: `docs/SAFETY_MODEL.md`
- Rubric self-assessment: `docs/HACKATHON_EVALUATION.md`

**Hot take.** A deterministic offline brain is what makes an agent evaluation reproducible —
but it also hides every real-API contract: auth shapes, rate limits, model-family quirks
(`gpt-5` needs `reasoning_effort=none` for tools; Gemini 3 rejects a turn without the
`thought_signature` echoed back), error envelopes. "It passes with the heuristic" and "a
judge can run it with their own key" are two different claims, and the second one needs its
own testing pass. `docs/HOT_TAKE.md`.

Repo: https://github.com/MdTanvirHossainTusher/migration-sentinel

---

## Video URL

*(your recording — see the outline in `docs/HACKATHON_EVALUATION.md` → "What a judge should do")*

---

## Source Code (zip ≤ 50 MB)

From a clean checkout of `main`:

```powershell
git archive --format=zip --output migration-sentinel.zip HEAD
```

`git archive` respects `.gitignore` and excludes `.git/`, `build/`, `node_modules/` — the
result is a few MB, well under the 50 MB limit. (Or "Download ZIP" from the GitHub repo page.)
