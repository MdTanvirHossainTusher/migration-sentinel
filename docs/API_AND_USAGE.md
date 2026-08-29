# API & usage guide

Everything you need to run Migration Sentinel and drive it over HTTP: how to start it,
every endpoint, the request/response shapes, `curl` for each, the review lifecycle, and
what to expect back.

- **API base URL:** `http://localhost:8080`
- **All application endpoints are under** `/api/v1`
- **Interactive docs (Swagger UI):** `http://localhost:8080/swagger-ui.html`
- **OpenAPI JSON:** `http://localhost:8080/v3/api-docs`
- **Actuator (separate port):** `http://localhost:9091/actuator/health`, `/actuator/prometheus`

---

## 1. Running the project

### Option A — full stack, one command (recommended)

```bash
docker compose up --build
```

| Service | URL | Notes |
| --- | --- | --- |
| Frontend | http://localhost:3000 | Next.js UI |
| API | http://localhost:8080 | Spring Boot |
| Swagger UI | http://localhost:8080/swagger-ui.html | |
| Postgres (app metadata) | localhost:5440 | user/pass `sentinel`/`sentinel` |

The backend container mounts the host Docker socket so the agent's per-review sandbox
containers run on the host. No API key needed — the default `heuristic` brain is offline.

### Option B — backend from source

```bash
# App metadata DB is in-memory H2; the review sandbox still needs a Docker daemon.
./gradlew bootRun --args='--spring.profiles.active=standalone'
```

### Option C — with a real LLM

```bash
export OPENAI_API_KEY=sk-...          # or GEMINI_API_KEY=...
SENTINEL_LLM_PROVIDER=openai docker compose up --build
```

Then pass `"provider": "openai"` (or `"gemini"`) in review/evaluation requests, or leave it
out to use the configured default.

### Docker Desktop on Windows

Docker 29's socket proxy breaks Testcontainers' client. `docker compose up` is unaffected.
To run `./gradlew sandboxTest` locally, use a real Docker-in-Docker engine — see the table
at the bottom of `REPRODUCTION_GUIDE.md`.

### Health check — is it ready?

```bash
curl -s http://localhost:8080/api/v1/health | jq
```

```json
{
  "success": true,
  "data": {
    "status": "UP",
    "version": "0.1.0",
    "defaultProvider": "heuristic",
    "availableProviders": ["heuristic"],
    "dockerAvailable": true,
    "evaluationCaseCount": 15,
    "timestamp": 1788003239.559
  },
  "metadata": { "timestamp": "2026-08-29T11:33:59Z", "request_id": "bb19b344-..." }
}
```

- `dockerAvailable: false` → sandbox stages degrade to a structure-only review. Reviews
  still work; they just won't have row counts or lock evidence.
- `availableProviders` lists the LLM clients that are actually configured. `heuristic` is
  always present.
- `evaluationCaseCount` should be `15`.

---

## 2. The response envelope

Every application endpoint returns the same wrapper.

**Success**

```json
{
  "success": true,
  "code": "REVIEW_ACCEPTED",              // optional machine code
  "message": "Review queued. ...",         // optional human message
  "data": { ... },                         // the payload
  "pagination": { ... },                   // list endpoints only
  "metadata": { "timestamp": "...", "request_id": "..." }
}
```

**Error**

```json
{
  "success": false,
  "metadata": { "timestamp": "...", "request_id": "..." },
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "must not be blank",
    "details": [ { "field": "migrationSql", "type": "VALIDATION_ERROR", "message": "must not be blank" } ]
  }
}
```

**Field naming:** request bodies and most response fields are **camelCase**
(`migrationSql`, `findingsCount`, `createdAt`). The exceptions carry explicit names:
`metadata.request_id`, and the `pagination` block (`total_count`, `total_pages`,
`has_next`, `has_prev`).

**Timestamps** in `data` are epoch seconds (floats); `metadata.timestamp` is ISO-8601.

### Error codes

| HTTP | `error.code` | Meaning |
| --- | --- | --- |
| 422 | `VALIDATION_ERROR` | Request body failed validation (`details` lists the fields) |
| 404 | `NOT_FOUND` | No review / evaluation with that id |
| 400 | `BAD_REQUEST` | Bad input, or apply-rewrite is disabled |
| 409 | `CONFLICT` | — |
| 503 | `SANDBOX_UNAVAILABLE` | Docker daemon not reachable for a sandbox stage |
| 500 | `SANDBOX_SAFETY_VIOLATION` | A tool tried to reach a non-sandbox datasource (should never happen) |
| 502 | `LLM_PROVIDER_ERROR` | The chosen LLM provider rejected the request or is unreachable |
| 422 | `EVAL_CORPUS_ERROR` | Unknown evaluation case id, or the corpus failed to load |
| 500 | `INTERNAL_ERROR` | Unhandled server error |

---

## 3. Reviews

### The lifecycle

```
POST /api/v1/reviews  ──►  { id, status: "QUEUED" }        (returns immediately, HTTP 202)
                              │
                    (async on a worker thread)
                              ▼
                         RUNNING ──►  COMPLETED   (findings + report + trajectory ready)
                              └────►  FAILED       (errorMessage set)

Poll GET /api/v1/reviews/{id} until status is COMPLETED or FAILED,
then GET /api/v1/reviews/{id}/report for the full result.
```

A review with `mode: ANALYZER_VERIFIER_SPLIT` and `provider: heuristic` completes in
~3–6 s (it builds one throwaway Postgres container). `BASELINE_PROMPT` completes in
~15 ms (no sandbox).

### `ReviewMode` — which pipeline to run

| Mode | Tools the agent gets | Use it to… |
| --- | --- | --- |
| `BASELINE_PROMPT` | none — SQL text only | reproduce the prompt-only baseline |
| `ANALYZER_READ_ONLY` | schema introspection on the seeded DB (no migration run) | see the effect of grounding without running the migration |
| `ANALYZER_WITH_SANDBOX` | + `run_candidate_migration` (timing, locks, post-migration schema) | the full analyzer |
| `ANALYZER_VERIFIED` | same, plus a verification pass that drops ungrounded findings | analyzer + self-check |
| `ANALYZER_VERIFIER_SPLIT` | analyzer + a **separate** verifier agent | **default** — the strongest configuration |

Omit `mode` to get `ANALYZER_VERIFIER_SPLIT`.

---

### `POST /api/v1/reviews` — submit a migration for review

**Request body**

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `migrationSql` | string | **yes** | the candidate Flyway migration |
| `filename` | string | no | e.g. `V42__add_tier.sql` (labelling only) |
| `baselineSql` | string | no | prior migrations — the schema that already exists |
| `seedSql` | string | no | rows and/or `UPDATE pg_class SET reltuples = N` to simulate scale |
| `entitySource` | string | no | JPA entity Java source or a JSON mapping spec — enables the drift check |
| `mode` | enum | no | one of the `ReviewMode` values above (default `ANALYZER_VERIFIER_SPLIT`) |
| `provider` | string | no | `heuristic` (default), `openai`, `gemini` |

**curl**

```bash
curl -s http://localhost:8080/api/v1/reviews \
  -H 'content-type: application/json' \
  -d '{
    "filename": "V42__add_tier.sql",
    "baselineSql": "CREATE TABLE customers (id bigserial PRIMARY KEY, email varchar(255) NOT NULL);",
    "seedSql": "INSERT INTO customers (email) SELECT g||'"'"'@x.com'"'"' FROM generate_series(1,3000) g; UPDATE pg_class SET reltuples=4000000 WHERE relname='"'"'customers'"'"';",
    "migrationSql": "ALTER TABLE customers ADD COLUMN tier varchar(10) NOT NULL DEFAULT gen_random_uuid();",
    "mode": "ANALYZER_VERIFIER_SPLIT",
    "provider": "heuristic"
  }'
```

**Response — HTTP 202**

```json
{
  "success": true,
  "code": "REVIEW_ACCEPTED",
  "message": "Review queued. Poll GET /api/v1/reviews/{id} for status.",
  "data": {
    "id": "bd847a00-2776-40f4-a16c-5586537f21e3",
    "status": "QUEUED",
    "mode": "ANALYZER_VERIFIER_SPLIT",
    "provider": "heuristic",
    "filename": "V42__add_tier.sql",
    "createdAt": 1788003239.812,
    "findingsCount": 0,
    "toolCallCount": 0,
    "sandboxUsed": false,
    "highCount": 0, "mediumCount": 0, "lowCount": 0
  },
  "metadata": { "timestamp": "...", "request_id": "..." }
}
```

Grab `data.id` and poll.

---

### `GET /api/v1/reviews/{id}` — status + summary counts

```bash
curl -s http://localhost:8080/api/v1/reviews/bd847a00-2776-40f4-a16c-5586537f21e3 | jq
```

```json
{
  "success": true,
  "data": {
    "id": "bd847a00-2776-40f4-a16c-5586537f21e3",
    "status": "COMPLETED",
    "mode": "ANALYZER_VERIFIER_SPLIT",
    "provider": "heuristic",
    "filename": "V42__add_tier.sql",
    "createdAt": 1788003239.812,
    "startedAt": 1788003239.815,
    "finishedAt": 1788003244.705,
    "durationMs": 4868,
    "findingsCount": 1,
    "toolCallCount": 4,
    "sandboxUsed": true,
    "highCount": 1, "mediumCount": 0, "lowCount": 0,
    "errorMessage": null
  },
  "metadata": { ... }
}
```

- `status`: `QUEUED` → `RUNNING` → `COMPLETED` | `FAILED`
- `highCount` / `mediumCount` / `lowCount`: findings by severity (a quick triage signal)
- `sandboxUsed`: whether a real Postgres sandbox backed this review
- `errorMessage`: populated only when `status = FAILED`

---

### `GET /api/v1/reviews/{id}/report` — the full result

The main endpoint. Returns the review summary, the Markdown report to paste into a PR,
every finding with its evidence, and the complete agent trajectory.

```bash
curl -s http://localhost:8080/api/v1/reviews/bd847a00-2776-40f4-a16c-5586537f21e3/report | jq
```

```json
{
  "success": true,
  "data": {
    "review": { ...same shape as GET /reviews/{id}... },

    "reportMarkdown": "# Migration review: V42__add_tier.sql\n\n**Verdict: do not merge as-is.** 1 high-severity issue(s).\n\n| # | Severity | Rule | Object | Verdict |\n...",

    "findings": [
      {
        "id": "96399241-e7c4-4963-84d8-137acd123599",
        "ordinal": 1,
        "ruleCode": "ADD_COLUMN_VOLATILE_DEFAULT",
        "severity": "HIGH",
        "title": "ADD COLUMN tier DEFAULT gen_random_uuid() on customers",
        "targetObject": "customers.tier",
        "summary": "A constant default is metadata-only since PG11, but a volatile expression ... rewrites the entire table under ACCESS EXCLUSIVE.",
        "evidence": "sandbox: customers holds 4000000 rows; default expression 'gen_random_uuid()' is volatile and rewrites every row",
        "suggestedRewrite": "ALTER TABLE customers ADD COLUMN tier <type>;\n-- backfill in batches --\nALTER TABLE customers ALTER COLUMN tier SET DEFAULT gen_random_uuid();",
        "verdict": "CONFIRMED",
        "analyzerConfidence": 0.8
      }
    ],

    "trajectory": [
      { "id": "...", "agentRole": "ANALYZER", "stepNo": 1, "toolName": "run_candidate_migration", "argumentsJson": "{}", "resultJson": "{\"baselineApplied\":true,\"candidateApplied\":true,\"candidateDurationMs\":41,\"statements\":[{\"index\":0,\"ok\":true,\"durationMs\":38,\"strongestLock\":\"ACCESS EXCLUSIVE\"}], ...}", "durationMs": 1100, "ok": true },
      { "id": "...", "agentRole": "ANALYZER", "stepNo": 2, "toolName": "static_scan", "resultJson": "[{\"ruleCode\":\"ADD_COLUMN_VOLATILE_DEFAULT\", ...}]", "durationMs": 12, "ok": true },
      { "id": "...", "agentRole": "VERIFIER", "stepNo": 3, "toolName": "run_candidate_migration", "durationMs": 3, "ok": true },
      { "id": "...", "agentRole": "VERIFIER", "stepNo": 4, "toolName": "static_scan", "durationMs": 8, "ok": true }
    ]
  }
}
```

**`findings[]`**

| Field | Meaning |
| --- | --- |
| `ruleCode` | one of the 11 defect classes (see `docs/EVALUATION.md`) |
| `severity` | `HIGH` (block the merge) / `MEDIUM` (needs a decision) / `LOW` (note) |
| `targetObject` | the table or `table.column` the finding is about |
| `summary` | what breaks and why, in the engineer's terms |
| `evidence` | the concrete tool output behind it — row counts, lock modes, statement numbers |
| `suggestedRewrite` | corrected SQL **as text** (Sentinel never edits files) — may be `null` |
| `verdict` | `CONFIRMED` (tool evidence supports it) / `UNVERIFIED` (plausible, kept, flagged) / *rejected findings are not returned* |
| `analyzerConfidence` | 0–1 |

**`trajectory[]`** — one row per tool call, in order. `resultJson` is the exact JSON the
agent saw. `agentRole` is `BASELINE`, `ANALYZER`, or `VERIFIER`. This is the raw material
for `docs/AGENT_TRAJECTORIES.md`.

**Available tools** (which appear depends on `mode` and whether `entitySource` was given):
`list_tables`, `describe_table`, `estimate_rows`, `explain`, `static_scan`,
`validate_entities`, `run_candidate_migration`.

#### Expected results for common inputs

| Migration | Expected finding |
| --- | --- |
| `ALTER TABLE t DROP COLUMN c` | `DESTRUCTIVE_DDL` / HIGH |
| `CREATE INDEX ix ON t (c)` (large `t`) | `NON_CONCURRENT_INDEX` / HIGH |
| `ALTER TABLE t ALTER COLUMN c SET NOT NULL` (large `t`) | `NOT_NULL_WITHOUT_SAFE_BACKFILL` / HIGH |
| …same, on an **empty** `t` | *no finding* — "safe to merge" |
| `ADD COLUMN c uuid DEFAULT gen_random_uuid()` | `ADD_COLUMN_VOLATILE_DEFAULT` / HIGH |
| `ADD CONSTRAINT fk FOREIGN KEY (c) REFERENCES …` with no index on `c` | `UNINDEXED_FOREIGN_KEY` / MEDIUM |
| `CREATE INDEX CONCURRENTLY …` without `-- flyway:executeInTransaction=false` | `UNSAFE_IN_TRANSACTION` / MEDIUM |
| `ALTER TABLE t RENAME COLUMN a TO b` | `BACKWARD_INCOMPATIBLE_RENAME` / HIGH |
| entity maps `nullable=false` but migration adds the column NULLABLE | `ENTITY_SCHEMA_DRIFT` / MEDIUM |
| `ADD COLUMN c timestamptz` (nullable, no default) | *no finding* |

---

### `GET /api/v1/reviews` — list recent reviews

```bash
curl -s "http://localhost:8080/api/v1/reviews?page=0&size=20" | jq
```

```json
{
  "success": true,
  "data": [ { ...review summary... }, ... ],
  "pagination": {
    "total_count": 4, "page": 0, "size": 2,
    "total_pages": 2, "has_next": true, "has_prev": false
  }
}
```

Query params: `page` (default `0`), `size` (default `20`, max `100`). Newest first.

---

### `POST /api/v1/reviews/rewrites/apply` — write a suggested rewrite to disk (human-gated)

The **only** consequential action. It writes a finding's `suggestedRewrite` to a **new
file** in the configured output directory — it never edits the original migration.

**Disabled by default.** Enable with `SENTINEL_REWRITE_APPLY_ENABLED=true` (and optionally
`SENTINEL_REWRITE_OUTPUT_DIR=./rewrites`).

**Request body**

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `findingId` | uuid | **yes** | from `report.findings[].id` |
| `targetFilename` | string | **yes** | a filename (not a path); written under the output dir |
| `approvedBy` | string | **yes** | recorded verbatim in the audit trail |
| `confirm` | boolean | **yes** | must be `true` |
| `note` | string | no | stored with the approval |

**curl**

```bash
curl -s http://localhost:8080/api/v1/reviews/rewrites/apply \
  -H 'content-type: application/json' \
  -d '{
    "findingId": "96399241-e7c4-4963-84d8-137acd123599",
    "targetFilename": "V42__add_tier.fixed.sql",
    "approvedBy": "alice@example.com",
    "confirm": true
  }'
```

**Response when disabled — HTTP 400**

```json
{ "success": false,
  "error": { "code": "BAD_REQUEST",
             "message": "Applying rewrites to disk is disabled. Set sentinel.rewrite-apply-enabled=true to allow it." } }
```

**Response when enabled — HTTP 200**

```json
{
  "success": true,
  "code": "REWRITE_APPLIED",
  "message": "Rewrite written to the configured output directory.",
  "data": {
    "id": "…", "reviewJobId": "bd847a00-…", "findingId": "96399241-…",
    "action": "APPLY_REWRITE",
    "approvedBy": "alice@example.com",
    "targetPath": "/app/rewrites/V42__add_tier.fixed.sql",
    "applied": true,
    "note": null,
    "createdAt": 1788003300.1
  }
}
```

---

### `GET /api/v1/reviews/{id}/approvals` — apply-rewrite audit trail

```bash
curl -s http://localhost:8080/api/v1/reviews/bd847a00-.../approvals | jq
```

Returns `data: [ ...ApprovalRecord... ]`, newest first. Empty until someone applies a
rewrite for that review.

---

## 4. Evaluation

Runs the 15-case corpus through one pipeline mode and scores it (precision / recall / F1
on defects caught, severity-checked). Same lifecycle as reviews: `POST` returns a queued
run, poll `GET` until `COMPLETED`.

### `GET /api/v1/evaluations/cases` — the corpus

```bash
curl -s http://localhost:8080/api/v1/evaluations/cases | jq
```

```json
{
  "success": true,
  "data": [
    {
      "id": "01-drop-column",
      "title": "Drop a column that code may still read",
      "description": "...",
      "hard": false,
      "mustBeClean": false,
      "expected": [ { "ruleCode": "DESTRUCTIVE_DDL", "targetObject": "customers.legacy_ref" } ]
    },
    { "id": "03-not-null-large-table", "hard": true, "mustBeClean": false, "expected": [ { "ruleCode": "NOT_NULL_WITHOUT_SAFE_BACKFILL", "targetObject": "invoices.tax_region" } ] },
    { "id": "04-not-null-empty-table", "hard": true, "mustBeClean": true, "expected": [] }
  ]
}
```

### `POST /api/v1/evaluations` — start a run

**Request body**

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `mode` | enum | no | `ReviewMode` (default `ANALYZER_VERIFIER_SPLIT`) |
| `provider` | string | no | `heuristic` (default), `openai`, `gemini` |
| `caseIds` | string[] | no | subset of case ids; empty/omitted = the whole corpus |
| `corpusLabel` | string | no | free-text label stored on the run |

**curl**

```bash
curl -s http://localhost:8080/api/v1/evaluations \
  -H 'content-type: application/json' \
  -d '{"mode":"ANALYZER_VERIFIER_SPLIT","provider":"heuristic","caseIds":["01-drop-column","04-not-null-empty-table"],"corpusLabel":"smoke"}'
```

**Response — HTTP 202**

```json
{ "success": true, "code": "EVALUATION_ACCEPTED",
  "data": { "id": "e9d8d2fa-...", "status": "QUEUED", "mode": "ANALYZER_VERIFIER_SPLIT", "totalCases": 2, "completedCases": 0 } }
```

Whole-corpus runs of a sandbox mode take ~2–3 minutes (one container per case).

### `GET /api/v1/evaluations/{id}` — metrics + per-case scores

```bash
curl -s http://localhost:8080/api/v1/evaluations/e9d8d2fa-... | jq
```

```json
{
  "success": true,
  "data": {
    "run": {
      "id": "e9d8d2fa-...",
      "status": "COMPLETED",
      "mode": "ANALYZER_VERIFIER_SPLIT",
      "provider": "heuristic",
      "corpusLabel": "smoke",
      "totalCases": 2, "completedCases": 2,
      "truePositives": 1, "falsePositives": 0, "falseNegatives": 0,
      "precision": 1.0, "recall": 1.0, "f1": 1.0,
      "falsePositiveRate": 0.0,
      "meanDurationMs": 2532,
      "createdAt": 1788003261.86, "finishedAt": 1788003267.07,
      "errorMessage": null
    },
    "cases": [
      { "caseId": "01-drop-column", "expectedCount": 1, "reportedCount": 1,
        "truePositives": 1, "falsePositives": 0, "falseNegatives": 0,
        "passed": true, "notes": "exact match",
        "reviewJobId": "67d2d678-..." },
      { "caseId": "04-not-null-empty-table", "expectedCount": 0, "reportedCount": 0,
        "truePositives": 0, "falsePositives": 0, "falseNegatives": 0,
        "passed": true, "notes": "exact match", "reviewJobId": "863ec65f-..." }
    ]
  }
}
```

- `cases[].reviewJobId` links to a real review — `GET /api/v1/reviews/{reviewJobId}/report`
  shows exactly what the agent did for that case.
- `notes` explains any miss: `FP: <rule> <severity> on <object>` / `FN: <rule> on <object>`.
- `passed`: no false negatives, and (for `mustBeClean` cases) no false positives.

### `GET /api/v1/evaluations` — list runs

`?page=`, `?size=` like reviews. `data: [ ...run summaries... ]` + `pagination`.

### Baseline vs agent — reproduce the improvement result

```bash
# 1. the prompt-only baseline
curl -s http://localhost:8080/api/v1/evaluations -H 'content-type: application/json' \
  -d '{"mode":"BASELINE_PROMPT","provider":"heuristic","corpusLabel":"baseline"}'
# 2. the full agent
curl -s http://localhost:8080/api/v1/evaluations -H 'content-type: application/json' \
  -d '{"mode":"ANALYZER_VERIFIER_SPLIT","provider":"heuristic","corpusLabel":"agent"}'
# poll both; compare precision / recall / f1 / falsePositiveRate
```

Expected (offline heuristic brain, deterministic — also produced by
`./gradlew sandboxTest --tests '*EvaluationHarnessTest*'`):

| stage | P | R | F1 | FP/case | cases passed |
| --- | --- | --- | --- | --- | --- |
| `BASELINE_PROMPT` | 0.79 | 0.85 | 0.81 | 0.20 | 12 / 15 |
| `ANALYZER_READ_ONLY` | 1.00 | 0.92 | 0.96 | 0.00 | 14 / 15 |
| `ANALYZER_WITH_SANDBOX` | 1.00 | 1.00 | 1.00 | 0.00 | 15 / 15 |
| `ANALYZER_VERIFIED` | 1.00 | 1.00 | 1.00 | 0.00 | 15 / 15 |
| `ANALYZER_VERIFIER_SPLIT` | 1.00 | 1.00 | 1.00 | 0.00 | 15 / 15 |

---

## 5. End-to-end walkthrough

```bash
API=http://localhost:8080/api/v1

# 0. ready?
curl -s $API/health | jq '.data | {status, dockerAvailable, evaluationCaseCount}'

# 1. submit — a migration that drops a column AND adds a non-concurrent index to a big table
REVIEW=$(curl -s $API/reviews -H 'content-type: application/json' -d '{
  "filename": "V50__cleanup.sql",
  "baselineSql": "CREATE TABLE orders (id bigserial PRIMARY KEY, legacy_ref varchar(40), status varchar(16));",
  "seedSql": "INSERT INTO orders (status) SELECT '"'"'NEW'"'"' FROM generate_series(1,2000); UPDATE pg_class SET reltuples=9000000 WHERE relname='"'"'orders'"'"';",
  "migrationSql": "ALTER TABLE orders DROP COLUMN legacy_ref;\nCREATE INDEX idx_orders_status ON orders (status);",
  "mode": "ANALYZER_VERIFIER_SPLIT"
}')
ID=$(echo "$REVIEW" | jq -r .data.id)
echo "review $ID"

# 2. poll
until [ "$(curl -s $API/reviews/$ID | jq -r .data.status)" = "COMPLETED" ]; do sleep 2; done

# 3. read the report
curl -s $API/reviews/$ID/report | jq '.data.findings[] | {ruleCode, severity, verdict, evidence}'
```

**Expected output**

```json
{ "ruleCode": "DESTRUCTIVE_DDL", "severity": "HIGH", "verdict": "CONFIRMED",
  "evidence": "statement #0: ALTER TABLE orders DROP COLUMN legacy_ref" }
{ "ruleCode": "NON_CONCURRENT_INDEX", "severity": "HIGH", "verdict": "CONFIRMED",
  "evidence": "sandbox: orders holds ~9000000 rows; the SHARE lock is held for the whole build" }
```

`GET $API/reviews/$ID/report | jq -r .data.reportMarkdown` gives the PR-ready writeup:

```
# Migration review: V50__cleanup.sql

**Verdict: do not merge as-is.** 2 high-severity issue(s).

| # | Severity | Rule | Object | Verdict |
|---|---|---|---|---|
| 1 | HIGH | `DESTRUCTIVE_DDL` | `orders.legacy_ref` | CONFIRMED |
| 2 | HIGH | `NON_CONCURRENT_INDEX` | `orders` | CONFIRMED |
...
```

---

## 6. Frontend

<http://localhost:3000> drives the same API:

| Page | What it does |
| --- | --- |
| **Review** (`/`) | Submit form (with a **Load example** button), live-refreshing recent-reviews table |
| **Review detail** (`/reviews/{id}`) | Verdict, findings with evidence + copy-able rewrites + "Apply to file…", the tool-call trajectory timeline, and the raw Markdown |
| **Evaluation** (`/evaluations`) | Launch a run for any mode, the baseline-vs-agent comparison table, the case list |
| **Evaluation detail** (`/evaluations/{id}`) | Per-case TP/FP/FN table, each row linking to its review |

The frontend calls the API through a Next.js rewrite (`/proxy/*` → backend), so there is
no CORS to configure.
