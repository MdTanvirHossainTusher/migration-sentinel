# Reproduction guide

Written for someone on a clean machine.

## Prerequisites

| Tool | Version | Why |
| --- | --- | --- |
| Docker | 24+ with a running daemon | `docker compose` runs the whole stack — app, Postgres, Kafka, RustFS, and a Docker-in-Docker engine for the sandbox |
| JDK | 21 (Temurin) | Backend build/run. The Gradle wrapper is included |
| Node | 20+ | Only if you run the frontend outside Docker |
| Git | any | Clone the repo |

No cloud accounts. No API key (the default `heuristic` brain is offline and deterministic).

Check Docker is up:

```bash
docker info >/dev/null && echo "docker ok"
```

## Option A — the whole stack, one command

```bash
git clone <repo-url> migration-sentinel && cd migration-sentinel
docker compose up --build
```

- Frontend: <http://localhost:3000>
- API + Swagger: <http://localhost:8080/swagger-ui.html>
- Health (shows Docker/sandbox status): <http://localhost:8080/api/v1/health>

First build ≈ 3–5 min. The stack is self-contained: it brings up a `docker:dind` engine for
the Testcontainers sandbox (so `sandbox_used` is true with no host-socket fiddling), a
single-node Kafka for the job queue (`sentinel.messaging.transport=kafka`), and RustFS for
the downloadable `report.md` (`sentinel.s3.enabled=true`). Nothing external is required.
The first review is slower — the sandbox pulls `postgres:16-alpine` inside the dind engine.

**Try it:** open the frontend → **Load example** → **Run review**. You'll get a report with
a `DESTRUCTIVE_DDL` finding (the dropped column), an `UNINDEXED_FOREIGN_KEY`, a
`NON_CONCURRENT_INDEX` on the 6M-row table, and a per-statement sandbox timeline.

**Run the evaluation from the UI:** go to **Evaluation**, run `BASELINE_PROMPT`, then
`ANALYZER_VERIFIER_SPLIT`. The "Baseline vs full agent" table appears once both finish.

## Option B — backend from source, no Docker Compose

```bash
# App metadata DB in-memory (H2). The sandbox still needs the Docker daemon.
./gradlew bootRun --args='--spring.profiles.active=standalone'
```

Submit a review:

```bash
curl -s localhost:8080/api/v1/reviews -H 'content-type: application/json' -d '{
  "filename": "V42__demo.sql",
  "baselineSql": "CREATE TABLE orders (id bigserial primary key, status varchar(16));",
  "seedSql": "INSERT INTO orders (status) SELECT '"'"'NEW'"'"' FROM generate_series(1,1000); UPDATE pg_class SET reltuples=5000000 WHERE relname='"'"'orders'"'"';",
  "migrationSql": "ALTER TABLE orders ALTER COLUMN status SET NOT NULL;",
  "mode": "ANALYZER_VERIFIER_SPLIT",
  "provider": "heuristic"
}' | tee /tmp/review.json

ID=$(python -c "import json,sys;print(json.load(open('/tmp/review.json'))['data']['id'])")
sleep 8
curl -s localhost:8080/api/v1/reviews/$ID/report | python -m json.tool
```

Expected: one `NOT_NULL_WITHOUT_SAFE_BACKFILL` finding, severity `HIGH`, verdict
`CONFIRMED`, evidence citing `orders holds 5000000 rows`.

## The evaluation (the measured-improvement result)

```bash
./gradlew evaluationTest
```

- Runtime: ≈ 3–6 min. The run leases **one** Postgres container and wipes it between cases;
  a container per case pushed this past 15 min per stage and timed CI out.
- Output: a table printed to the console —

  ```
  stage                            P      R     F1    FP/case   passed
  BASELINE_PROMPT                 0.79   0.85   0.81     0.20     12/15
  ANALYZER_READ_ONLY             1.00   0.92   0.96     0.00     14/15
  ANALYZER_WITH_SANDBOX          1.00   1.00   1.00     0.00     15/15
  ANALYZER_VERIFIED             1.00   1.00   1.00     0.00     15/15
  ANALYZER_VERIFIER_SPLIT       1.00   1.00   1.00     0.00     15/15
  ```

- The test **asserts** recall/F1 go up and false positives go down from baseline to full
  agent, and that no stage regresses. A code change that breaks the improvement fails here.

Run the sandbox integration tests (Docker, ≈ 1 min — the evaluation is a separate task so
this stays quick):

```bash
./gradlew sandboxTest
```

Run the unit tests (fast, no Docker):

```bash
./gradlew test
```

## Reviewing a real repository's migration folder

The 15-case corpus gives each case one small baseline file. A real service has hundreds, and
that is the case the tool is built for.

1. Open <http://localhost:3000> and drop your `src/main/resources/db/migration` folder onto
   step 1. Every `.sql` file is read in the browser and ordered by Flyway version — `V10`
   after `V2`, repeatable (`R__`) migrations last, undo (`U__`) files skipped.
2. The newest file is selected as the candidate; everything before it becomes the baseline.
   Press **review** on any other row to review that migration against only what preceded it.
3. Step 2 asks for the schema your migrations build into — your project's
   `spring.flyway.schemas`. Flyway creates it at boot so the migrations never mention it, and
   without it the replay stops at the first schema-qualified name. The page detects likely
   candidates from the SQL and offers them.
4. Untick any migration the sandbox cannot run unchanged (one needing an extension, a role,
   or production-only data).

The equivalent API call sends the files rather than one blob, which is what lets a failure
name the migration that caused it:

```bash
curl -s -X POST localhost:8080/api/v1/reviews \
  -H 'Content-Type: application/json' -d '{
  "baseline_migrations": [
    {"filename": "V1__init.sql",   "sql": "CREATE TABLE orders (id bigserial PRIMARY KEY);"},
    {"filename": "V2__status.sql", "sql": "ALTER TABLE orders ADD COLUMN status varchar(16);"}
  ],
  "filename": "V3__status_not_null.sql",
  "migration_sql": "ALTER TABLE orders ALTER COLUMN status SET NOT NULL;",
  "target_schema": "public",
  "mode": "ANALYZER_VERIFIER_SPLIT",
  "provider": "heuristic"
}'
```

Measured on `kc-mis-identity` (220 files, 2.9 MB, 11 schemas): 1,538 statements replayed in
≈ 35 s, 155 tables introspected. If a migration fails to replay, the report says which file
and how far it got, and marks the review ungrounded rather than reporting it clean.

## With a real LLM (optional)

Two ways to supply a key:

**Per request (no server config).** Pick `openai` or `gemini` in the UI and paste a key into
the field that appears — it is used for that one review, AES-GCM encrypted at rest, never
returned by the API, and stripped from logs and the audit trail. Or via the API:

```bash
curl -s -XPOST localhost:8080/api/v1/reviews -H 'content-type: application/json' -d '{
  "migration_sql": "ALTER TABLE orders ALTER COLUMN status SET NOT NULL;",
  "mode": "ANALYZER_VERIFIER_SPLIT", "provider": "openai", "llm_api_key": "sk-..."
}'
```

**On the server.** Put the key in `.env` (`OPENAI_API_KEY=` / `GEMINI_API_KEY=`) before
`docker compose up`. The provider dropdown then shows it as "key on server".

Cost: ≈ $0.005 per review on the default `gpt-5.6-luna` ($0.20/$1.20 per 1M in/out),
≈ $0.10–0.25 for the full 15-case evaluation. Use a paid key for the evaluation — 15 cases
hit a free-tier rate limit. Full walkthrough for a real service's folder:
[TEST_WITH_IDENTITY_MIGRATIONS.md](TEST_WITH_IDENTITY_MIGRATIONS.md).

## Data

All synthetic. The 15 cases build their own schemas and seed their own rows; the
`pg_class` stubs simulate production scale without real data. Nothing external is fetched.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `health` shows `docker_available: false` in **compose** | The `dind` service is still starting — `docker compose logs dind`; the backend waits on its healthcheck, so this clears on its own |
| `health` shows `docker_available: false` in **`bootRun` on Windows** | Expected — a host `bootRun` hits the Docker Desktop 29 socket-proxy incompatibility. Use `docker compose up` (bundled dind) for the grounded sandbox |
| Reviews stay `QUEUED` in compose | `docker compose logs kafka` — the broker is still forming its quorum; the backend healthcheck gates on it, so this is transient |
| Backend can't reach Postgres in Compose | `docker compose down -v` then `up` again |
| Sandbox container image pull is slow | `docker compose exec dind docker pull postgres:16-alpine` once beforehand |
| `report.md` download 404s | `sentinel.s3.enabled` is off (the default outside compose) — the report is then inline at `GET /reviews/{id}/report` instead |
| `./gradlew sandboxTest` → `Could not find a valid Docker environment` on **Windows + Docker Desktop 29** | docker-java (bundled in Testcontainers) can't negotiate the API version with Docker Desktop 29's socket proxy. CI (`ubuntu-latest`) is unaffected. Locally, run the tests against a real Docker-in-Docker engine: `docker run -d --privileged --name dind -e DOCKER_TLS_CERTDIR= docker:27-dind --host=tcp://0.0.0.0:2375`, then run gradle with `DOCKER_HOST=tcp://dind:2375` and `TESTCONTAINERS_HOST_OVERRIDE=dind`. `docker compose up` already does this for you. |
