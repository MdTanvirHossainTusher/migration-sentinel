# Reproduction guide

Written for someone on a clean machine.

## Prerequisites

| Tool | Version | Why |
| --- | --- | --- |
| Docker | 24+ with a running daemon | The sandbox is a Testcontainers Postgres; `docker compose` runs the stack |
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

First build ≈ 3–5 min. The backend container mounts the host Docker socket so the sandbox
containers it starts are siblings on the host.

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
./gradlew sandboxTest --tests '*EvaluationHarnessTest*'
```

- Runtime: ≈ 8–15 min (each of 15 cases × 4 sandbox stages spins a fresh Postgres container).
- Output: a table printed to the console —

  ```
  stage                            P      R     F1    FP/case   passed
  BASELINE_PROMPT                 0.79   0.85   0.82     0.20     12/15
  ANALYZER_READ_ONLY             1.00   0.92   0.96     0.00     14/15
  ANALYZER_WITH_SANDBOX          1.00   1.00   1.00     0.00     15/15
  ANALYZER_VERIFIED             1.00   1.00   1.00     0.00     15/15
  ANALYZER_VERIFIER_SPLIT       1.00   1.00   1.00     0.00     15/15
  ```

- The test **asserts** recall/F1 go up and false positives go down from baseline to full
  agent, and that no stage regresses. A code change that breaks the improvement fails here.

Run the unit tests (fast, no Docker):

```bash
./gradlew test
```

## With a real LLM (optional)

```bash
export OPENAI_API_KEY=sk-...            # or GEMINI_API_KEY
SENTINEL_LLM_PROVIDER=openai docker compose up --build
```

Then pick `openai` as the provider in the UI, or pass `"provider":"openai"` in the API
call. Cost: ≈ $0.01–0.03 per review on `gpt-4o-mini`, ≈ $0.20 for the full evaluation.

## Data

All synthetic. The 15 cases build their own schemas and seed their own rows; the
`pg_class` stubs simulate production scale without real data. Nothing external is fetched.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `health` shows `docker_available: false` | Start Docker. Structure-only review still works; sandbox stages don't |
| `SANDBOX_UNAVAILABLE` on a review | Same — the Docker daemon isn't reachable from the backend |
| Backend can't reach Postgres in Compose | `docker compose down -v` then `up` again |
| Sandbox container image pull is slow | `docker pull postgres:16-alpine` once beforehand |
| `./gradlew sandboxTest` → `Could not find a valid Docker environment` on **Windows + Docker Desktop 29** | docker-java (bundled in Testcontainers) can't negotiate the API version with Docker Desktop 29's socket proxy. CI (`ubuntu-latest`) is unaffected. Locally, run the tests against a real Docker-in-Docker engine: `docker run -d --privileged --name dind --network <net> -e DOCKER_TLS_CERTDIR= docker:28-dind --host=tcp://0.0.0.0:2375`, then run gradle in a `eclipse-temurin:21-jdk` container on the same network with `DOCKER_HOST=tcp://dind:2375` and `TESTCONTAINERS_HOST_OVERRIDE=dind`. `docker compose up` also works unchanged. |
