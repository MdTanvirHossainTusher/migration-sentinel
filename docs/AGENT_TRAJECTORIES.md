# Agent trajectories

Every review persists its full trajectory — one row per tool call, in
[`tool_call`](../src/main/resources/db/migration/V1__init_sentinel_schema.sql). It is
served verbatim at `GET /api/v1/reviews/{id}/report` under `trajectory`, and rendered as a
timeline in the UI (the **trajectory** tab on a review). This doc walks representative runs.

**Ready-to-read captures** (no need to run anything) are committed under
[`docs/traces/`](traces/) — the full trajectory JSON for a heuristic run and a
`gpt-5.6-luna` run on the same input. Agent instructions are the prompt files:
[`analyzer.md`](../src/main/resources/prompts/analyzer.md),
[`verifier.md`](../src/main/resources/prompts/verifier.md),
[`baseline.md`](../src/main/resources/prompts/baseline.md).

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

## Trajectory 3 — real LLM (`gpt-5.6-luna`), `SET NOT NULL` on a 5M-row table

Full capture: [`docs/traces/openai-luna-not-null-large-table.json`](traces/openai-luna-not-null-large-table.json).
Same input as trajectory 1, `provider=openai`, model `gpt-5.6-luna`.

| step | agent | tool | args | what came back (abridged) | feedback that shaped the next step |
| --- | --- | --- | --- | --- | --- |
| 1 | ANALYZER | `run_candidate_migration` | `{}` | `candidateApplied:true`, statement 0 `ALTER … SET NOT NULL` → `strongestLock: ACCESS EXCLUSIVE`, `tableStatsAfter.invoices.estimatedRows: 5000000` | migration applies; one statement takes ACCESS EXCLUSIVE; table is 5M rows |
| 2 | ANALYZER | `static_scan` | `{}` | `[{ruleCode:"NOT_NULL_WITHOUT_SAFE_BACKFILL", severity:"HIGH", targetObject:"invoices.tax_region", …}]` | the deterministic scanner already flags it HIGH |
| 3 | ANALYZER | `describe_table` | `{"table":"invoices"}` | `{estimatedRows:5000000, columns:[…], indexes:[{name:"invoices_pkey",…}]}` | **the model double-checks** the table shape before trusting the scan |
| 4 | ANALYZER | `estimate_rows` | `{"table":"invoices"}` | `{estimated_rows:5000000, exact_rows:2000}` | confirms the planner estimate the lock scan will face |
| — | ANALYZER | *(final)* | | `{"findings":[{ruleCode:"NOT_NULL_WITHOUT_SAFE_BACKFILL", severity:"HIGH", evidence:"run_candidate_migration: statement 0 … strongestLock ACCESS EXCLUSIVE; estimatedRows=5000000 …"}]}` | handed to the verifier |
| 5 | VERIFIER | `run_candidate_migration` | `{}` | *(cached)* same sandbox state as step 1 | verifier independently confirms the lock and the row count |
| — | VERIFIER | *(final)* | | `{"verdicts":[{ruleCode:"NOT_NULL_WITHOUT_SAFE_BACKFILL", verdict:"CONFIRMED", note:"backed by tool output and a sandbox run"}]}` | kept, badged CONFIRMED |

**What the real LLM adds over the heuristic:** the heuristic run (trajectory 1) takes 2
analyzer tool calls; Luna takes 4 — it independently calls `describe_table` and
`estimate_rows` to verify the scanner's claim before endorsing it. When a real model
*over*-reaches — asserts a lock class or table size it never measured — the verifier's final
verdict for that finding is `REJECTED` (`note:"no tool output supports the table-size
claim"`) and it never reaches the report. That gap between "the analyzer said so" and "a
tool proved it" is the whole reason for the analyzer/verifier split (stage 4).

### Notes on running a real model

- `provider=openai` uses `OPENAI_MODEL` (default `gpt-5.6-luna`); `provider=gemini` uses
  `GEMINI_MODEL` (default `gemini-flash-latest`). The per-request `llm_api_key` overrides the
  key only, not the model.
- The GPT-5 line needs `reasoning_effort=none` for function tools on Chat Completions — the
  client sets this automatically for `gpt-5*` models.
- Free-tier keys are rate-limited (~5 req/min). A single review survives it (the client
  retries 429/503 with backoff); the 15-case evaluation needs a paid key.
