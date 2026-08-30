# Evaluation

## What "good" looks like for the user

The engineer is about to merge a migration. A good result:

- **Catches every production-safety defect** in the migration (recall). A missed outage is
  the failure that matters.
- **Doesn't cry wolf** (false positives per case). If the tool flags safe migrations, the
  engineer stops reading it.
- **Gets the severity right.** "`SET NOT NULL` on this table is HIGH" vs "…is fine here" is
  the whole value proposition — a reviewer that flags the pattern regardless of table size
  is no better than grep.
- **Shows its work.** Every finding cites the row count / lock / plan it's based on, so the
  engineer can check it in 10 seconds.

## The corpus

15 cases under [`src/main/resources/eval/cases/`](../src/main/resources/eval/cases). Each
is a directory with `case.json`, `baseline.sql` (prior migrations), `seed.sql` (rows and/or
`pg_class` stubs that put a table at production scale), `migration.sql` (the candidate),
optionally `entity.java`, and `labels.json` (the expected findings, keyed by rule code and
— where it matters — severity).

| # | Case | Expects | Note |
| --- | --- | --- | --- |
| 01 | drop-column | `DESTRUCTIVE_DDL` | |
| 02 | non-concurrent-index | `NON_CONCURRENT_INDEX` HIGH | 8M-row table |
| 03 | not-null-large-table | `NOT_NULL_WITHOUT_SAFE_BACKFILL` HIGH | **hard** — identical SQL to 04 |
| 04 | not-null-empty-table | *clean* | **hard** — identical SQL to 03, empty table |
| 05 | unindexed-fk | `UNINDEXED_FOREIGN_KEY` | |
| 06 | volatile-default | `ADD_COLUMN_VOLATILE_DEFAULT` HIGH | table rewrite |
| 07 | type-change-rewrite | `TABLE_REWRITE_TYPE_CHANGE` HIGH | |
| 08 | rename-column | `BACKWARD_INCOMPATIBLE_RENAME` HIGH | |
| 09 | fk-validated-inline | `CONSTRAINT_VALIDATION_LOCK` | FK column *is* indexed — must not false-positive |
| 10 | concurrent-index-in-txn | `UNSAFE_IN_TRANSACTION` | no `executeInTransaction=false` |
| 11 | entity-schema-drift | `ENTITY_SCHEMA_DRIFT` | needs the migration applied + Hibernate validate |
| 12 | safe-add-nullable-column | *clean* | |
| 13 | safe-concurrent-index | *clean* | `CONCURRENTLY` + out-of-transaction |
| 14 | safe-not-null-backfill | *clean* | `CHECK ... NOT VALID` → `VALIDATE` → `SET NOT NULL` |
| 15 | multi-issue | 3 findings | **hard** — must catch all three, not stop at the first |

### The challenging case and what it revealed

**03 vs 04.** The same statement, `ALTER TABLE t ALTER COLUMN c SET NOT NULL`. On the
populated table it holds `ACCESS EXCLUSIVE` for a full-table scan — an outage. On the
empty table it's a catalog update — instant. Nothing in the SQL distinguishes them.

The prompt-only baseline cannot get both right: it either flags both (a false alarm on 04
that trains the reviewer to ignore it) or neither (a missed outage on 03). It landed on
MEDIUM for both — the worst of both worlds.

The agent calls `estimate_rows` (or reads the sandbox run's snapshot), sees 5,000,000 vs 0,
and returns HIGH for 03 and nothing for 04. This is the case that proves the thesis:
**grounding the agent in the data beats reasoning about the text.**

## Scoring

[`EvaluationScorer`](../src/main/java/com/migrationsentinel/service/eval/EvaluationScorer.java).
A reported finding is a **true positive** when its rule code matches an unmatched expected
label, the objects match on the leading identifier, and (if the label pins a severity) the
severity matches. Unmatched reported findings are **false positives**; unmatched labels are
**false negatives**.

- precision = TP / (TP + FP)
- recall = TP / (TP + FN)
- F1 = harmonic mean
- false positives per case = FP / cases
- a case **passes** with zero false negatives and — for "must be clean" cases — zero false
  positives (one is tolerated otherwise)

## Results

Run `./gradlew evaluationTest`. It executes all five pipeline
stages over the corpus with the offline heuristic brain and prints the table. Deterministic
— same numbers every run, no API key. Last confirmed run (Linux Docker engine, ~4.5 min):

```
stage                              P      R     F1    FP/case   passed
BASELINE_PROMPT                 0.79   0.85   0.81       0.20     12/15
ANALYZER_READ_ONLY             1.00   0.92   0.96       0.00     14/15
ANALYZER_WITH_SANDBOX          1.00   1.00   1.00       0.00     15/15
ANALYZER_VERIFIED             1.00   1.00   1.00       0.00     15/15
ANALYZER_VERIFIER_SPLIT       1.00   1.00   1.00       0.00     15/15
```

| Metric | Baseline (prompt) | Full agent | Change |
| --- | --- | --- | --- |
| Recall | 0.85 | 1.00 | +0.15 |
| Precision | 0.79 | 1.00 | +0.21 |
| F1 | 0.81 | 1.00 | +0.19 |
| False positives / case | 0.20 | 0.00 | −0.20 |
| Cases passed | 12 / 15 | 15 / 15 | +3 |
| Human time / case | ~4 min eye-scan, misses data-scale issues | ~0 (read the report) | — |
| Cost / case | ~$0 (one short prompt) | ~$0 offline; ~$0.005 with `provider=openai` (`gpt-5.6-luna`) | — |

Per-stage breakdown and the story of each jump: [CHANGELOG_IMPROVEMENT.md](CHANGELOG_IMPROVEMENT.md).

### With a real model (`provider=openai`, `gpt-5.6-luna`)

The heuristic numbers above are the deterministic, no-key baseline. Running the same corpus
through `ANALYZER_VERIFIER_SPLIT` with a real LLM (three runs, paid key,
[`docs/traces/evaluation-baseline-vs-openai.json`](traces/evaluation-baseline-vs-openai.json)):

| Metric | Baseline (prompt only) | Full agent — `gpt-5.6-luna` | Change |
| --- | --- | --- | --- |
| Recall (defects caught) | 0.85 | **1.00** | +0.15 |
| Precision | 0.79 | **0.93** | +0.14 |
| F1 | 0.81 | **0.96** | +0.15 |
| False positives / case | 0.20 | **0.07** | −0.13 |
| Mean time / case | ~0 s | **~8.8 s** | +8.8 s |
| Cases passed | 12 / 15 | **14 / 15** | +2 |

Recall is **1.00** across all three runs — every seeded defect is caught. The single miss is
case 04 (`SET NOT NULL` on an empty table): the model flagged it `LOW` where the label
requires *clean*, so it scores as one false positive. The verifier keeps precision at 0.93
by dropping the analyzer's un-grounded claims; a run without the verifier scored 0.87.

The takeaway is the same as the heuristic curve: the tools do the measuring, so even a small
current-gen model clears the bar. `provider=openai` needs a paid key — a free-tier rate
limit fails the multi-call corpus.

### What this corpus does not measure

Worth stating plainly, because it cost us a real defect. Every case supplies one small
`baseline.sql`. That makes the cases fast and deterministic, but it bakes in an assumption —
that a migration's history is a handful of statements — which no real service satisfies.

Pointed at `kc-mis-identity` (220 migration files, 2.9 MB, 11 schemas), the tool returned a
400 before running anything: the API capped the baseline at 500 KB. The corpus scored 1.00
across the board the entire time. A second defect hid in the same blind spot — migration
files were being ordered as strings, so `V10` replayed before `V2`, which no case with fewer
than ten baseline files can expose.

Both are fixed (stage 5). The measurements are in the changelog, and they are timings and
file counts against a real repository rather than corpus scores, because the corpus is
structurally unable to move on them. The lesson we would carry to the next project: a fixture
is an assumption, and a suite that only ever scores 1.00 has stopped telling you anything.

### Running it with a real LLM

```bash
export OPENAI_API_KEY=sk-...
SENTINEL_LLM_PROVIDER=openai ./gradlew bootRun --args='--spring.profiles.active=standalone'
# then POST /api/v1/evaluations {"mode":"ANALYZER_VERIFIER_SPLIT","provider":"openai"}
```

The LLM adds natural-language nuance and catches phrasing the regex scanner misses; the
verifier is what keeps its precision at parity with the deterministic brain. Approximate
runtime: 15 cases × (one container + 3–6 model calls) ≈ 6–10 minutes, ≈ $0.10–0.25 total on
`gpt-5.6-luna` (the default; a small current-gen model — the tools do the measuring, so a
frontier model buys little here). Needs a paid key: 15 cases on a free-tier rate limit fails.
