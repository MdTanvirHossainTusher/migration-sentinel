# Migration Sentinel

**An agentic reviewer that catches Flyway migrations which are safe on a laptop and dangerous in production — before they merge.**

Built for the [micro1 Agentic Workflows Hackathon](https://micro1.ai). Everything in this
repo was written for the hackathon; the third-party pieces it stands on are listed under
[Prior work](#prior-work).

---

## Who has this problem

A backend engineer on a team with a real production database. They open a PR that adds a
Flyway migration. Their local Postgres has a few hundred rows in every table, so the
migration applies in 20 ms and all the tests pass. They cannot run it against a copy of
production — it's too big, or they don't have access, or spinning one up takes a day.

So the migration gets a human eye-scan in code review and merges. Then the deploy runs it
against a table with 50 million rows, it takes an `ACCESS EXCLUSIVE` lock for four minutes,
and the API is down for four minutes in the middle of a deploy.

## The bottleneck

Reading migration SQL by eye does not tell you:

- whether `ALTER TABLE ... SET NOT NULL` will scan a huge table under an exclusive lock
- whether a new foreign key column has a covering index (Postgres does not add one)
- whether `CREATE INDEX` without `CONCURRENTLY` will block writes for the whole build
- whether `ADD COLUMN ... DEFAULT gen_random_uuid()` rewrites every row
- whether a column rename breaks pods still running the old code during a rolling deploy
- whether the migration and the JPA entities still agree (Hibernate `validate` fails at boot)

The thing that separates a safe migration from a dangerous one is **the size and shape of
the data it runs against** — exactly the thing the SQL text doesn't contain and the
reviewer can't see.

## What the agent does

Migration Sentinel gives the reviewing agent **real tools instead of just the SQL**:

1. It spins up a disposable Postgres in a container (Testcontainers).
2. It replays **the repo's whole prior migration history** — every file in your
   `db/migration` folder, in Flyway version order — then a seed that puts the affected tables
   at production scale. Drop the folder on the page and the newest file becomes the candidate;
   everything before it becomes the baseline.
3. It runs the candidate migration **one statement at a time**, timing each one and
   capturing the locks it takes.
4. It introspects the result: `pg_class` row estimates, `pg_index`, foreign keys, `EXPLAIN`.
5. It runs a Hibernate-`validate`-equivalent check of the JPA mapping against the
   post-migration schema.
6. A **verifier** agent re-checks every finding and drops the ones with no tool evidence
   behind them.

The output is a review report an engineer pastes into the PR: a verdict, a table, and one
evidence block per finding — row counts, lock modes, `EXPLAIN` lines — plus a suggested
rewrite as text.

```
Verdict: do not merge as-is. 1 high-severity issue.

## 1. SET NOT NULL on invoices.tax_region
- Severity: HIGH | Rule: NOT_NULL_WITHOUT_SAFE_BACKFILL | Verifier: CONFIRMED

Evidence
  sandbox: invoices holds 5,000,000 rows (>= 1,000,000 threshold).
  SET NOT NULL scans all of them under ACCESS EXCLUSIVE.
  statement #0: ALTER TABLE invoices ALTER COLUMN tax_region SET NOT NULL — ACCESS EXCLUSIVE

Suggested rewrite
  ALTER TABLE invoices ADD CONSTRAINT invoices_tax_region_nn CHECK (tax_region IS NOT NULL) NOT VALID;
  -- backfill in batches --
  ALTER TABLE invoices VALIDATE CONSTRAINT invoices_tax_region_nn;
  ALTER TABLE invoices ALTER COLUMN tax_region SET NOT NULL;
```

## Why it beats the baseline

The baseline is one LLM prompt with the migration SQL pasted in. It has no data, so it
cannot tell case 03 (`SET NOT NULL` on a 5M-row table — an outage) apart from case 04
(the **identical SQL** on an empty table — instant and safe). It either flags both (false
positive, alert fatigue) or neither (missed outage).

The agent measures the table and gets both right. Full numbers in
[docs/EVALUATION.md](docs/EVALUATION.md); the story of how each iteration moved the numbers
is in [docs/CHANGELOG_IMPROVEMENT.md](docs/CHANGELOG_IMPROVEMENT.md).

## Quick start

```bash
# One command. Frontend on :3000, API on :8080, no API key needed (offline heuristic brain).
docker compose up --build
```

The stack is self-contained — it bundles a Docker-in-Docker engine for the sandbox, a
single-node Kafka for the job queue, and RustFS for the downloadable `report.md`. Nothing
external is required.

Then open <http://localhost:3000> and drop your `src/main/resources/db/migration` folder on
the page — or click **Load example** to see the whole flow first. Reviewing a real service's
folder (schemas, extensions, a per-request model key):
[docs/TEST_WITH_IDENTITY_MIGRATIONS.md](docs/TEST_WITH_IDENTITY_MIGRATIONS.md).

Run the whole evaluation from the CLI:

```bash
./gradlew evaluationTest
```

Full setup, commands, versions, runtime and cost: [docs/REPRODUCTION_GUIDE.md](docs/REPRODUCTION_GUIDE.md).

## Reviewer walkthrough — a real service's migrations, end to end

This is what a reviewer should do to see the whole thing work on a real repo (here, an
identity/auth service: ~220 migrations, schema `identity`, needs `pgcrypto` + `pg_trgm`).

**0 · Keys (optional).** The offline `heuristic` brain needs none. For a real model:
Gemini — <https://aistudio.google.com/apikey> (free, key starts `AIza…`); OpenAI —
<https://platform.openai.com/api-keys> (key starts `sk-…`). You paste the key per-review in
the UI; it does not go in a file. Defaults: `GEMINI_MODEL=gemini-flash-latest`,
`OPENAI_MODEL=gpt-5.6-luna` (small current-gen models — the sandbox does the measuring).
A single review works on a free-tier key (the client retries the rate limit); the 15-case
evaluation needs a **paid** key.

**1 · Stack up.** `docker compose up --build`, then optionally pre-pull the sandbox image so
the first review is fast: `docker compose exec dind docker pull postgres:16-alpine`.

**2 · Open** <http://localhost:3000> (hard-refresh to clear cached JS).

**3 · Load the folder.** *1 · Load your migration folder* → **Choose folder…** → the
service's `src/main/resources/db/migration/`. Every `.sql` is read in the browser and ordered
by Flyway version. For a first run, click **review** on an early file (e.g. `V50__…`) so only
~49 files replay (~10 s) rather than all ~220 (~35 s).

**4 · Schema.** *2 · Where the migrations build* → **Database schema** → `identity` (the
service's `spring.flyway.schemas`; the page usually detects and offers it). Leave seed blank.

**5 · Model + key.** *4 · Run it* → Depth `Full review`, Reviewing brain `gemini` or
`openai`, paste your key into the field that appears. The key is AES-GCM encrypted on the job
row, never returned by the API, and stripped from logs and the audit trail.

**6 · Run.** The review page polls itself. Behind it: a disposable Postgres starts in the
bundled dind engine → schema `identity` + extensions → prior migrations replay in version
order → the candidate runs one statement at a time (timing + locks) → the model reasons over
that evidence → a separate verifier re-checks each finding against the sandbox and drops the
unproven ones.

**7 · Read it.** KPI row shows `sandbox: yes` (it measured a real DB). The **report** tab has
the verdict + an evidence block per finding; the **trajectory** tab is the full agent trace;
**↓ download report.md** pulls the report straight from object storage via a presigned URL.
If a migration can't replay, the report names the file and marks the review *ungrounded* —
untick that file and re-run.

**8 · Verify the engineering.**

```bash
curl -s localhost:8080/api/v1/audit-events | jq '.data[] | {event_type, summary}'   # audit trail, no key in it
docker compose logs backend | grep -i "consuming review job"                        # the Kafka job path
curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' localhost:8080/api/v1/reviews/$ID/report.md  # 302 → presigned URL
```

API form of the same flow, and the cost breakdown:
[docs/TEST_WITH_IDENTITY_MIGRATIONS.md](docs/TEST_WITH_IDENTITY_MIGRATIONS.md).

## Reviewing against the whole history

The candidate runs on production, and production is every migration that came before it — so
that is what it is reviewed against, not one hand-picked predecessor.

- **Ordering is Flyway's, not the filesystem's.** Sorted as text, `V10` lands before `V2`; a
  project past nine migrations would be replayed into a schema that never existed. Versions are
  compared numerically, and repeatable (`R__`) migrations go last. Undo (`U__`) files are
  skipped — Flyway never applies them going forward.
- **Failures name the file.** With 400 migrations in play, "relation already exists" is not a
  diagnosis. The replay is per file, so a report says which migration stopped it and how far it
  got, and the review is marked ungrounded rather than passing silently.
- **Your own schema is created first.** Set the schema your project's
  `spring.flyway.schemas` names (the UI detects it from the SQL). Flyway creates it at boot,
  so the migrations never mention it — without it the replay dies at the first qualified name.
  Introspection then spans every schema the migrations built, not just `public`.
- **Skip what the sandbox cannot run.** Untick any migration that needs an extension, a role,
  or data only production has.

Measured on `kc-mis-identity`: 220 migration files across 11 schemas, 1,538 statements,
replayed in ~33 s.

## Safety model

The agent runs DDL. It never touches a real database:

- The agent has **no `jdbcUrl` parameter anywhere**. The tool layer owns the container
  lifecycle and creates a fresh disposable Postgres per review.
- Read tools (`describe_table`, `estimate_rows`, `explain`) and the one write tool
  (`run_candidate_migration`) are separated; the write path passes through a hard guard
  that refuses any URL that isn't the sandbox's own.
- Suggested rewrites are **text in the report**. The only path that writes to disk is the
  "Apply to file" button, which requires an explicit human click, records who approved it,
  writes only into a configured output directory, and is disabled by default.
- Every consequential action is written to an `audit_event` trail in the same transaction.
  A per-request model key is AES-GCM encrypted at rest and never returned or logged; a
  masking log appender redacts anything credential-shaped from the console, the audit
  payloads and the persisted trajectories.

Details: [docs/SAFETY_MODEL.md](docs/SAFETY_MODEL.md) · system design: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Prior work

| Component | Origin |
| --- | --- |
| PostgreSQL, Flyway, Hibernate/JPA, Testcontainers, Spring Boot, Next.js | Existing open source, used as-is |
| Apache Kafka, Spring for Apache Kafka, AWS SDK for Java v2, RustFS | Existing open source, used as-is |
| Demo schemas + seed generators in the 15 evaluation cases | Built for this hackathon |
| Agent tool layer, agent loop, analyzer/verifier prompts, verification pass | Built for this hackathon |
| Deterministic rule scanner + rule catalogue | Built for this hackathon |
| 15-case migration corpus + labels + scorer | Built for this hackathon |
| REST API, persistence, evaluation harness, Next.js UI | Built for this hackathon |
| Transactional outbox + Kafka transport, audit-event trail, secret redaction, encrypted per-request keys, presigned artifact storage | Built for this hackathon |

The `com.dae.*` sibling services in the parent workspace were used only as a **convention
reference** (response envelope, audit-outbox shape, masking appender, S3 presign flow) — no
code from them is copied here. No prior personal or employer code is in this submission.

## Repo layout

```
src/main/java/com/migrationsentinel/
  service/agent/     agent loop, toolbox, orchestrator, prompts (resources/prompts/)
  service/sandbox/   Testcontainers lifecycle, introspection, replay, lock analysis, JPA validate
  service/llm/       heuristic (offline) + OpenAI (gpt-5 line) + Gemini (3.x) clients, 429 backoff
  service/rules/     DDL parser, rule catalogue, deterministic scanner
  service/eval/      corpus loader, scorer, evaluation runner
  service/audit/     audit-event trail (audit_event + optional Kafka relay)
  aspect/            @Audited + AuditAspect — declarative audit inside the tx advice
  service/artifact/  presigned object storage for report.md + uploads
  service/support/   AgentJsonMapper, CryptoService (AES-GCM for per-request keys)
  messaging/         job submission gateway; local (AFTER_COMMIT) + outbox→Kafka transports
  util/              SecretMasker + masking log appender
src/main/resources/prompts/      analyzer / verifier / baseline agent instructions
src/main/resources/eval/cases/   the 15 evaluation cases
frontend/            Next.js 15 review + evaluation UI
docs/                changelog, reproduction guide, evaluation, architecture, safety model
docs/traces/         committed agent trajectories
```

## Documentation

- [docs/API_AND_USAGE.md](docs/API_AND_USAGE.md) — **how to run it, every endpoint, request/response, curl, the flow**
- [docs/CHANGELOG_IMPROVEMENT.md](docs/CHANGELOG_IMPROVEMENT.md) — the improvement story, stage by stage
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — request flow, the AFTER_COMMIT boundary, where it scales
- [docs/HACKATHON_EVALUATION.md](docs/HACKATHON_EVALUATION.md) — self-assessment against the rubric, with evidence pointers
- [docs/REPRODUCTION_GUIDE.md](docs/REPRODUCTION_GUIDE.md) — run it from a clean machine
- [docs/TEST_WITH_IDENTITY_MIGRATIONS.md](docs/TEST_WITH_IDENTITY_MIGRATIONS.md) — reviewing a real service's migration folder
- [docs/EVALUATION.md](docs/EVALUATION.md) — the metric, the rubric, the results table
- [docs/AGENT_TRAJECTORIES.md](docs/AGENT_TRAJECTORIES.md) — annotated agent runs
- [docs/traces/](docs/traces/) — committed full traces (heuristic + `gpt-5.6-luna`), ready to read
- [docs/SAFETY_MODEL.md](docs/SAFETY_MODEL.md) — how consequential actions are contained
- [docs/HOT_TAKE.md](docs/HOT_TAKE.md) — the failure mode and what it taught us
- [docs/CHECKPOINT.md](docs/CHECKPOINT.md) — build state and what is / isn't done

## Submission

| Deliverable | Where |
| --- | --- |
| **Source code + improvement changelog** | this repo · [docs/CHANGELOG_IMPROVEMENT.md](docs/CHANGELOG_IMPROVEMENT.md) (stages 0 → 7) |
| **Reproduction guide** | [docs/REPRODUCTION_GUIDE.md](docs/REPRODUCTION_GUIDE.md) — clean machine → `docker compose up --build` → the result |
| **Agent traces** | [docs/traces/](docs/traces/) — committed full trajectories (every tool call + verbatim tool result) for a heuristic run and a `gpt-5.6-luna` run; agent instructions are [`src/main/resources/prompts/`](src/main/resources/prompts/); every review also serves its own trace at `GET /api/v1/reviews/{id}/report` → `trajectory` and in the UI's **trajectory** tab |
| **Solution video** | *(recorded separately)* |
| **Self-assessment vs rubric** | [docs/HACKATHON_EVALUATION.md](docs/HACKATHON_EVALUATION.md) |

## License

MIT — see [LICENSE](LICENSE).
