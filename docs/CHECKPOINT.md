# Build checkpoint

Where the project stands, and what a next session should pick up.

## Done

- **Backend** — Spring Boot 3.4 / Java 21, layered like the reference service
  (`controller` / `service` / `repository` / `model` / `payload` / `mapper` / `exception`
  / `config`). Unified `ApiResponse` envelope, global exception handler, Flyway V1 for the
  app's own metadata schema (`ddl-auto: validate`).
- **Agent pipeline** — explicit agent loop over an `LlmClient` interface; three clients
  (`heuristic` offline default, `openai`, `gemini` — both raw HTTP, no SDK); analyzer /
  verifier orchestrator with five selectable modes mapping to the improvement-curve stages;
  every tool call persisted as a trajectory.
- **Sandbox** — `SandboxManager` + `SandboxSession` (URL guard), `SchemaIntrospector`
  (pg_class / pg_index / FKs / EXPLAIN), `MigrationReplayer` (baseline → seed → per-statement
  candidate, pre-migration row snapshot, lock inference + live pg_locks poll),
  `JpaMappingValidator` (Hibernate-validate-equivalent).
- **Rules** — SQL splitter, `DdlParser`, `RuleCatalog` (11 defect classes),
  `StaticRuleScanner` (deterministic backbone, sandbox-aware).
- **Evaluation** — 15-case corpus on the classpath, `EvaluationCorpus` loader,
  `EvaluationScorer` (severity-aware), `EvaluationRunner`, REST endpoints.
- **Frontend** — Next.js 15 App Router: review submit + report + trajectory timeline +
  apply-rewrite flow; evaluation runner + baseline-vs-agent comparison + per-case table.
- **Ops** — Dockerfile (backend + frontend), one-command `compose.yml` (mounts docker.sock
  for Testcontainers), CI (`ci.yml`: unit / sandbox / evaluation smoke / frontend build),
  `release-please.yml` + config + manifest, `pr-title.yml`.
- **Docs** — this file, README, CHANGELOG_IMPROVEMENT, REPRODUCTION_GUIDE, EVALUATION,
  AGENT_TRAJECTORIES, SAFETY_MODEL, HOT_TAKE.
- **Tests** — unit: `SqlScriptTest`, `DdlParserTest`, `StaticRuleScannerTest`. Sandbox
  (`@Tag("sandbox")`): `MigrationReplayerIT`, `EvaluationHarnessTest` (the measured-improvement
  assertion).

## Verified in this build environment

- `./gradlew compileJava compileTestJava` — clean.
- `./gradlew test` — 15 unit tests pass (`SqlScriptTest`, `DdlParserTest`, `StaticRuleScannerTest`).
- `./gradlew bootRun --args='--spring.profiles.active=standalone'` — boots in ~7s, Flyway
  V1 applies, Hibernate `ddl-auto: validate` **passes** (entities match the schema),
  `/api/v1/health` returns `evaluation_case_count: 15` (corpus loads from the classpath).
- End-to-end review via the API (`BASELINE_PROMPT`, heuristic): submit → async run →
  findings persisted → Markdown + JSON report generated correctly.
- `frontend/`: `npm run build` — clean, type-check passes, 5 routes.

## Sandbox tests — confirmed green

`./gradlew sandboxTest` (MigrationReplayerIT) and `./gradlew evaluationTest` both pass when
run against a real Linux Docker engine (verified via a `docker:28-dind` engine on this
machine — Docker Desktop 29's socket proxy breaks the Testcontainers client, so `dind`
or a Linux host is required locally; CI on `ubuntu-latest` is unaffected — see
`REPRODUCTION_GUIDE.md`).

The `EvaluationHarnessTest` run produced exactly the numbers now in `EVALUATION.md` /
`CHANGELOG_IMPROVEMENT.md`: baseline P/R/F1 = 0.79 / 0.85 / 0.81, full agent = 1.00 across
the board, 12/15 → 15/15 cases passed.

## Known gaps / next steps
- `SandboxProperties.reuseWithinEvaluation` is declared but not yet wired — the evaluation
  starts one container per case. Reusing one per run would cut evaluation time ~3×.
- OpenAI/Gemini clients are untested against the live APIs (no key in this environment).
  The request/response shapes follow the current public schemas.
- Frontend has no automated tests beyond `next build`.

## How to resume

1. `docker compose up --build`, confirm a review runs end to end via the UI.
2. `./gradlew test`, then `./gradlew sandboxTest`, then `./gradlew evaluationTest`.
3. If the harness table differs from the docs, update the two doc tables.
4. Record the 5-minute solution video (problem → baseline → one live review → evaluation
   comparison → changelog highlight).
