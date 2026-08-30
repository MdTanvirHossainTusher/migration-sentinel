# Hackathon self-assessment

Migration Sentinel against the judging rubric. Every claim points at something in the repo.

| Criterion | Points | Where it is |
| --- | --- | --- |
| Problem & User Value | 15 | [README](../README.md#who-has-this-problem) — a backend engineer whose migration is safe on a laptop and a four-minute outage on production, because the risk is in the data, not the SQL text |
| Agent Solution & Engineering | 30 | analyzer/verifier split over real tools ([CHANGELOG stages 1–4](CHANGELOG_IMPROVEMENT.md)); disposable-sandbox tool layer ([SAFETY_MODEL](SAFETY_MODEL.md)); [ARCHITECTURE](ARCHITECTURE.md) — outbox→Kafka queue, AFTER_COMMIT dispatch, audit trail, secret redaction, encrypted per-request keys |
| End to End Quality | 20 | one `docker compose up`; downloadable `report.md`; the report format in the README; the UI walks load → size → run → verdict; nothing reads as an AI draft |
| Measured Improvement | 15 | [EVALUATION](EVALUATION.md) + [CHANGELOG_IMPROVEMENT](CHANGELOG_IMPROVEMENT.md); `./gradlew evaluationTest` reproduces the table and asserts every delta |
| Reproducibility | 15 | [REPRODUCTION_GUIDE](REPRODUCTION_GUIDE.md); self-contained compose (Kafka + RustFS + dind bundled, no external infra); offline `heuristic` brain needs no key |
| Hot Take / Insights | 5 | [HOT_TAKE](HOT_TAKE.md) — plus the stage-6 lesson: the reproducibility score is decided by the second person's first run |

## Ground rules

| Rule | How it is met |
| --- | --- |
| 02 · what existed before vs what was added | [README "Prior work"](../README.md#prior-work) — third-party libraries used as-is; everything under `com.migrationsentinel` and `frontend/` written for the hackathon |
| 04 · consequential actions sandboxed + human approval | All DDL runs in a disposable container behind `SandboxSession.assertIsSandbox`; the one disk write (`apply-rewrite`) is off by default and needs `confirm=true` + `approvedBy`, recorded in `approval_record` and `audit_event` |
| 05 · qualified human reviewer in the loop | The report is advice a reviewer pastes into a PR; the verdict never auto-merges anything |
| 07 · data you may share | All synthetic — the 15 cases build their own schemas and `pg_class` stubs; `kc-mis-identity` is referenced only as a shape, its SQL is not in the repo |
| 08 · credentials outside the submission | No key in the repo; `.env` is git-ignored, `.env.example` has empty secret fields; a per-request key is encrypted and never persisted in plaintext or logged |
| 09 · every claim tied to evidence | Numbers come from `evaluationTest`; behavioural claims from the curl transcript in [CHANGELOG stage 6](CHANGELOG_IMPROVEMENT.md#stage-6--productionization) |
| 10 · judges can run it | `docker compose up --build`, then <http://localhost:3000> |

## Deliverables

| Deliverable | File |
| --- | --- |
| Solution code + improvement changelog | this repo + [CHANGELOG_IMPROVEMENT](CHANGELOG_IMPROVEMENT.md) (stages 0 → 7) |
| Reproduction guide | [REPRODUCTION_GUIDE](REPRODUCTION_GUIDE.md) |
| Agent traces | [traces/](traces/) — committed full trajectories (heuristic + `gpt-5.6-luna`), + [AGENT_TRAJECTORIES](AGENT_TRAJECTORIES.md); agent instructions in [`prompts/`](../src/main/resources/prompts/); every review also serves its own at `GET /reviews/{id}/report` → `trajectory` |
| Solution video | *(recorded separately)* |

## What a judge should do

```bash
docker compose up --build          # ~3–5 min first time
open http://localhost:3000         # Load example → Run review → read the verdict + evidence
open http://localhost:3000/evaluations   # run BASELINE_PROMPT then ANALYZER_VERIFIER_SPLIT
./gradlew evaluationTest           # the measured-improvement table, asserted
```

To see the engineering, not just the agent:

```bash
curl -s localhost:8080/api/v1/audit-events | jq '.data[] | {event_type, summary}'
docker compose logs backend | grep -i "outbox\|consuming review"     # kafka path
curl -s localhost:8080/api/v1/reviews/$ID/report.md -L -o report.md   # presigned download
```
