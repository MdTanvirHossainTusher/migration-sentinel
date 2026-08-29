# Agent trajectories

Every review persists its full trajectory — one row per tool call, in
[`tool_call`](../src/main/resources/db/migration/V1__init_sentinel_schema.sql). It is
served verbatim at `GET /api/v1/reviews/{id}/report` under `trajectory`, and rendered as a
timeline in the UI (the **trajectory** tab on a review). This doc walks two representative
runs.

## How to pull a trajectory yourself

```bash
curl -s localhost:8080/api/v1/reviews/$ID/report \
  | python -c "import json,sys; [print(t['step_no'], t['agent_role'], t['tool_name'], t['duration_ms'], 'ms') for t in json.load(sys.stdin)['data']['trajectory']]"
```

Each entry: `agent_role` (BASELINE / ANALYZER / VERIFIER), `step_no`, `tool_name`,
`arguments_json`, `result_json` (the exact tool output the agent saw), `duration_ms`, `ok`.

---

## Trajectory 1 — case 03 (`SET NOT NULL` on a 5M-row table), mode `ANALYZER_VERIFIER_SPLIT`, heuristic brain

The migration: `ALTER TABLE invoices ALTER COLUMN tax_region SET NOT NULL;`
The seed stubs `invoices` to 5,000,000 rows.

| step | agent | tool | args | what came back (abridged) | feedback that shaped the next step |
| --- | --- | --- | --- | --- | --- |
| 1 | ANALYZER | `run_candidate_migration` | `{}` | `baselineApplied:true, candidateApplied:true, candidateDurationMs:6, statements:[{index:0, ok:true, ms:4, strongestLock:"ACCESS EXCLUSIVE"}], tableStatsAfter:[{table:"invoices", estimatedRows:5000000, ...}]` | migration applies; the one statement took `ACCESS EXCLUSIVE`; the table is 5M rows |
| 2 | ANALYZER | `static_scan` | `{}` | `[{ruleCode:"NOT_NULL_WITHOUT_SAFE_BACKFILL", severity:"HIGH", targetObject:"invoices.tax_region", evidence:"sandbox: invoices holds 5000000 rows (>= 1000000 threshold). SET NOT NULL scans all of them under ACCESS EXCLUSIVE.", suggestedRewrite:"ALTER TABLE invoices ADD CONSTRAINT ... CHECK (tax_region IS NOT NULL) NOT VALID; ..."}]` | one grounded finding, HIGH, with a rewrite |
| — | ANALYZER | *(final)* | | `{"findings":[{ruleCode:"NOT_NULL_WITHOUT_SAFE_BACKFILL", severity:"HIGH", ...}], "summary":"1 potential issue found"}` | handed to the verifier |
| 3 | VERIFIER | `run_candidate_migration` | `{}` | *(cached)* same as step 1 | verifier independently confirms the sandbox state |
| 4 | VERIFIER | `static_scan` | `{}` | *(cached)* same as step 2 | |
| — | VERIFIER | *(final)* | | `{"verdicts":[{ruleCode:"NOT_NULL_WITHOUT_SAFE_BACKFILL", targetObject:"invoices.tax_region", verdict:"CONFIRMED", note:"backed by tool output and a sandbox run"}]}` | finding kept, badged CONFIRMED |

**Final report:** verdict *do not merge as-is*, one HIGH finding, evidence block quotes the
5,000,000 row count and the `ACCESS EXCLUSIVE` lock, suggested rewrite is the
`CHECK ... NOT VALID` → `VALIDATE` → `SET NOT NULL` sequence.

Contrast case 04 (identical SQL, empty table): step 1 returns `estimatedRows:0`, step 2's
`static_scan` returns `[]` (the scanner drops the finding when the sandbox measured zero
rows), the analyzer's final answer has an empty `findings` array, and the report reads
*safe to merge*. Same statement, opposite verdict, and the trajectory shows exactly why.

---

## Trajectory 2 — case 11 (entity / schema drift), mode `ANALYZER_VERIFIER_SPLIT`

The migration adds `users.display_name` as NULLABLE; the entity maps it `nullable=false`.

| step | agent | tool | result (abridged) |
| --- | --- | --- | --- |
| 1 | ANALYZER | `run_candidate_migration` | `candidateApplied:true` — `users.display_name` now exists, NULLABLE |
| 2 | ANALYZER | `static_scan` | `[]` — a nullable `ADD COLUMN` is structurally fine |
| 3 | ANALYZER | `validate_entities` | `{ran:true, consistent:false, items:[{entity:"User", detail:"field 'displayName' is nullable=false but column 'users.display_name' is NULLABLE"}]}` |
| — | ANALYZER final | | `{"findings":[{ruleCode:"ENTITY_SCHEMA_DRIFT", targetObject:"User", evidence:"Hibernate-validate-equivalent: field 'displayName' is nullable=false but column 'users.display_name' is NULLABLE"}]}` |
| 4–6 | VERIFIER | `run_candidate_migration`, `static_scan`, `validate_entities` | cached |
| — | VERIFIER final | | `{"verdicts":[{ruleCode:"ENTITY_SCHEMA_DRIFT", verdict:"CONFIRMED", note:"backed by tool output"}]}` |

This finding is only reachable because step 1 **applied** the migration — the drift check
compares the entity against the *post*-migration schema. In `ANALYZER_READ_ONLY` mode step 1
is `run baseline only`, `validate_entities` returns `{ran:false, reason:"read-only mode..."}`,
and the finding is missed. That single difference is stage 1 → stage 2 on the improvement
curve.

---

## With a real LLM (`provider=openai`)

The trajectory shape is identical — same tool names, same `agent_role` split — but the
analyzer typically also calls `describe_table` and `estimate_rows` directly to double-check
specific tables, and its final `summary` is prose. When it over-reaches (e.g. asserts
"locks `orders` for minutes" without having called `estimate_rows` on `orders`), the
verifier's final verdict for that finding is `REJECTED` with
`note:"no tool output supports the table-size claim"`, and it never reaches the report.
Capture one with:

```bash
export OPENAI_API_KEY=sk-...
SENTINEL_LLM_PROVIDER=openai docker compose up --build
# submit any review with "provider":"openai", then read /reviews/{id}/report → trajectory
```
