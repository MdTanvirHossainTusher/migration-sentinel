# Agent traces

Committed, ready-to-read traces so a reviewer does not have to run anything. Each `.json`
here is the exact body of `GET /api/v1/reviews/{id}/report` for one run — the full agent
trajectory (every tool call, the arguments, and the **verbatim tool result the agent saw**),
plus the findings and the rendered report.

| File | Provider | Case | Result |
| --- | --- | --- | --- |
| `heuristic-not-null-large-table.json` | heuristic (offline) | `SET NOT NULL` on a table seeded to 5M rows | 1 HIGH finding, `CONFIRMED` |
| `openai-luna-not-null-large-table.json` | openai `gpt-5.6-luna` | same input | 1 HIGH finding, `CONFIRMED` — analyzer also self-checks with `describe_table` + `estimate_rows` |
| `evaluation-baseline-vs-openai.json` | heuristic vs openai `gpt-5.6-luna` | the whole 15-case corpus | baseline F1 0.81 → full agent F1 0.96, recall 0.85 → 1.00 (3 runs) |

## The two agents

Instructions are the prompt files, versioned in the repo:

- **Analyzer** — [`src/main/resources/prompts/analyzer.md`](../../src/main/resources/prompts/analyzer.md)
- **Verifier** — [`src/main/resources/prompts/verifier.md`](../../src/main/resources/prompts/verifier.md)
- Baseline (one prompt, no tools) — [`src/main/resources/prompts/baseline.md`](../../src/main/resources/prompts/baseline.md)

`agent` on each trajectory step is `BASELINE`, `ANALYZER` or `VERIFIER`.

## How to read one step

```json
{
  "step": 3,
  "agent": "ANALYZER",
  "tool": "describe_table",
  "arguments": { "table": "invoices" },
  "ok": true,
  "duration_ms": 52,
  "tool_result": { "table": "invoices", "estimatedRows": 5000000, "columns": [ ... ] }
}
```

`tool_result` is the exact JSON the tool handed back — the "feedback that shaped the next
step". The analyzer's final message (an un-tooled turn) is its findings JSON; the verifier's
final message is its verdicts JSON, which is what decides `CONFIRMED` / `REJECTED` /
`UNVERIFIED` on each finding.

## Capture your own

```bash
ID=$(curl -s -XPOST localhost:8080/api/v1/reviews -H 'content-type: application/json' \
  -d '{"migration_sql":"…","mode":"ANALYZER_VERIFIER_SPLIT","provider":"heuristic"}' | jq -r .data.id)
# poll until COMPLETED, then:
curl -s localhost:8080/api/v1/reviews/$ID/report | jq '.data.trajectory'
```

Or open the review in the UI and click the **trajectory** tab.
