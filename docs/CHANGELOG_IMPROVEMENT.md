# Improvement changelog

How Migration Sentinel got from "an LLM reads the SQL" to "an agent that measures the
database and is right every time" on the 15-case corpus.

**Metric.** Primary: **recall** on production-safety defects (a missed outage is the
expensive failure). Secondary: **false positives per case** (alert fatigue makes a
reviewer ignore the tool) and **F1**. The label severity is checked too — reporting
`SET NOT NULL` as a risk on an empty table is scored as a false positive, not a hit.

**How to reproduce this table.** `./gradlew evaluationTest`
runs all five stages over the corpus with the offline heuristic brain (deterministic, no
API key) and prints it. The test also asserts the direction of every delta, so a
regression fails CI.

Stage 5 leaves the corpus numbers untouched by construction — every case has a handful of
baseline files, so none of them exercise a real repository's history. Its evidence is a
220-file service instead, and the reason the corpus could not see the defect is the most
useful thing in this document.


| Stage | What changed | P | R | F1 | FP/case | Cases passed |
| --- | --- | --- | --- | --- | --- | --- |
| 0 · Baseline | One prompt, migration SQL only, no tools | 0.79 | 0.85 | 0.81 | 0.20 | 12 / 15 |
| 1 · + schema introspection | Agent can read the seeded DB (row estimates, indexes, FKs) but not run the migration | 1.00 | 0.92 | 0.96 | 0.00 | 14 / 15 |
| 2 · + sandbox migration run | Agent replays baseline + seed, then runs the candidate statement-by-statement; drift check now works | 1.00 | 1.00 | 1.00 | 0.00 | 15 / 15 |
| 3 · + verification pass | Every finding must cite tool output or it is dropped / flagged UNVERIFIED | 1.00 | 1.00 | 1.00 | 0.00 | 15 / 15 |
| 4 · + analyzer/verifier split | A dedicated verifier agent with its own prompt and tools, so the analyzer never grades itself | 1.00 | 1.00 | 1.00 | 0.00 | 15 / 15 |
| 5 · + whole migration history | Reviews against every prior migration in the repo, in Flyway order, in the schema they build | 1.00 | 1.00 | 1.00 | 0.00 | 15 / 15 |

---

## Stage 0 — Baseline

**What we tried and why.** The reasonable starting point: paste the migration SQL into one
prompt and ask for the production risks. This is what a careful engineer does in their head
during code review, and what a naive "add an LLM" integration would ship.

**Evidence.** F1 0.81, recall 0.85, 0.20 false positives per case. It gets the
structure-only cases right — a `DROP COLUMN` is a `DROP COLUMN` — but it fails on exactly
the cases that need data:

- **Case 03 vs 04** (the hard pair): identical SQL, `ALTER TABLE ... SET NOT NULL`. One
  table has 5M rows (an outage), the other is empty (instant). The prompt sees the same
  text both times. It settled on MEDIUM for both — wrong severity on 03 (should block the
  merge), false alarm on 04.
- **Case 09**: it flagged an unindexed foreign key that is actually indexed by a prior
  migration it couldn't see.
- **Case 11**: it can't run Hibernate validate, so it missed the entity/schema drift.

**Decision.** Established the floor. The failures all point the same way: the missing
input is the database, not a better prompt.

## Stage 1 — Schema introspection tools

**What we tried and why.** Gave the agent read-only tools over a real Postgres with the
prior migrations and a seed applied: `describe_table`, `estimate_rows` (from
`pg_class.reltuples`), `list_tables`, `explain`. It still cannot run the candidate
migration — it has to reason about what the migration *would* do against a schema it can
measure.

**Evidence.** F1 jumped to 0.96, recall to 0.92, **false positives to zero**. The row
estimate resolves the hard pair: case 03 is now HIGH (5M rows measured), case 04 produces
nothing (0 rows measured). Case 09's false positive disappears because the covering index
is visible in `pg_index`.

It still misses **case 11**: the entity/schema drift check needs the *post*-migration
schema, and this stage never applies the migration.

**Decision.** Kept. This is the single biggest jump on the curve — grounding the agent in
the data is worth more than every prompt change combined.

## Stage 2 — Sandbox migration run

**What we tried and why.** Added one write tool, `run_candidate_migration`. It replays the
baseline and seed, then executes the candidate **one statement at a time** in the
disposable container — timing each statement, capturing the lock it takes, and snapshotting
the result. Row estimates are read *before* the run (a migration that rewrites a table
resets `pg_class`, and we lose the simulated scale otherwise).

**Evidence.** F1 1.00, recall 1.00, 15/15 cases pass. Case 11 is now caught: with the
migration applied, the Hibernate-validate-equivalent check sees `display_name` is NULLABLE
in the schema while the entity maps it `nullable=false`. The report also gains real
evidence blocks — measured lock modes and per-statement timing, not just "this looks risky".

**Decision.** Kept. This is the stage that makes the reports something an engineer would
actually paste into a PR.

## Stage 3 — Verification pass

**What we tried and why.** With a real LLM in the loop (`provider=openai`), the analyzer
sometimes asserts things it did not check — "this locks a large table" without ever calling
`estimate_rows`. Added a pass that re-reads every proposed finding and drops any whose
`evidence` does not point at a tool result (verdict `REJECTED`), or keeps it flagged
`UNVERIFIED` if it's structurally plausible but unproven.

**Evidence.** On the deterministic corpus the analyzer already grounds every finding, so
the numbers don't move — stages 2–4 are identical here (confirmed by the harness:
`ANALYZER_WITH_SANDBOX`, `ANALYZER_VERIFIED` and `ANALYZER_VERIFIER_SPLIT` all score
P/R/F1 = 1.00, 15/15). What the pass changes deterministically is the report: every
finding now carries a `CONFIRMED` badge (tool evidence attached) or `UNVERIFIED` (kept but
flagged), and any finding whose rule code is not in the catalogue is dropped outright. Its
value scales with the messiness of the analyzer: with `provider=openai` the analyzer
occasionally asserts a lock class or a table size it never checked, and this pass is what
keeps those out of the report (see `docs/AGENT_TRAJECTORIES.md` for how to capture one).

**Decision.** Kept. Zero cost on clean input, real protection on messy input.

## Stage 4 — Analyzer / verifier split

**What we tried and why.** In stage 3 the same agent proposed and then checked its own
findings. Split it into two agents with separate instruction files
(`resources/prompts/analyzer.md`, `resources/prompts/verifier.md`): the analyzer proposes,
the verifier independently re-runs the sandbox and judges. The verifier can also override
severity (it downgrades `SET NOT NULL` on a table it measured as empty).

**Evidence.** Same corpus numbers. The value is structural: the verifier's prompt never
sees the analyzer's reasoning, only its conclusions and the tools, so it can't be talked
into agreeing. This is the configuration the demo and the default API mode use.

**Decision.** Kept as the default (`ANALYZER_VERIFIER_SPLIT`). Stages 0–3 stay in the
codebase and are selectable per review, which is how the table above is generated.

## Stage 5 — Review against the whole migration history

**What we tried and why.** Stages 0–4 are measured on the 15-case corpus, where each case
carries one hand-written `baseline.sql`. Pointing the tool at a real service exposed the gap
that framing hides: the candidate does not run against *a* predecessor, it runs against
*every* migration that came before it. The API accepted only a single pre-concatenated
`baseline_sql` capped at 500 KB, and the UI offered one textarea to paste it into. Our own
`kc-mis-identity` is 220 files and 2.9 MB — the request was rejected with a 400 before
anything ran, so on the repo this tool was built for, the answer was never grounded at all.

Replaced it with a file-list input (`baseline_migrations`), a folder dropzone in the UI, and
ordering that is Flyway's rather than the filesystem's.

**Evidence.** The corpus numbers are unchanged (1.00 / 1.00 / 1.00) — every case has fewer
than ten baseline files, so nothing in the table exercises this. That is the point: the
corpus could not see the defect. The evidence is on a real repository instead:

| | Before | After |
| --- | --- | --- |
| `kc-mis-identity` (220 files, 2.9 MB) | rejected — over the 500 KB cap | 220 / 220 files, 1,538 statements replayed in ~35 s |
| Schemas introspected | `public` only (hardcoded) | all 11 the migrations build (`auth`, `identity`, `rbac`, …), 155 tables |
| Replay failure at file 84 | `sandbox setup failed: schema "identity" does not exist` | names the file, the statement, and how far it got |

Three defects fell out of running it for real, none of which the corpus could catch:

- **Ordering.** Sorted as text, `V10` precedes `V2`. Any project past nine migrations was
  being replayed into a schema that never existed. Versions are now compared numerically.
- **Fabricated evidence.** `SchemaFacts` treated `baselineApplied` as "the sandbox measured
  something". A half-applied baseline reports zero tables, so the unindexed-FK rule emitted
  *"checked via pg_index"* for a lookup that never ran — the exact failure the verifier stage
  exists to prevent, arriving through the deterministic path where no verifier looks. It now
  keys off `schemaObserved`, per table.
- **A structure-only pass claimed "safe to merge".** With no sandbox, an empty findings list
  means *unchecked*, not *clean*. It now says so, in the report and the UI.

**Decision.** Kept. The corpus measures whether the agent reasons correctly given a schema;
this stage is about whether it can be pointed at a schema anyone actually has. Lesson: an
evaluation corpus tells you your agent is right, not that your product is usable — the
fixture shape (one small baseline file) was itself an assumption, and it hid a 400.

---

## Experiments we removed

### LangChain4j `AiServices`

**Tried.** The first agent implementation used `dev.langchain4j:langchain4j` with
`AiServices` and `@Tool` methods — the ergonomic path, and what the project brief suggested.

**Evidence.** Two problems. (1) The framework's tool-call loop is opaque; capturing a clean,
persistable trajectory (which is a hackathon deliverable and the substrate for the eval
scorer) meant fighting it. (2) The offline deterministic brain — the thing that makes the
evaluation reproducible with no API key — doesn't fit the `ChatLanguageModel` abstraction
without a lot of scaffolding.

**Decision.** Removed. Replaced with an explicit ~80-line agent loop
(`service/agent/AgentLoop.java`) over a small `LlmClient` interface. OpenAI and Gemini are
raw HTTP; the heuristic client is a fixed policy. Every provider produces an identical
trajectory shape. Lesson: for a project where the trajectory *is* a deliverable, an
explicit loop beats a framework.

### Global `ANALYZE` after the migration

**Tried.** The sandbox snapshot originally ran `ANALYZE` before reading table stats, so
`EXPLAIN` plans would be realistic.

**Evidence.** It silently destroyed the evaluation. Cases simulate production scale with
`UPDATE pg_class SET reltuples = 5000000` instead of inserting 5M rows; `ANALYZE` reset
those to the ~2000 real rows, and every large-table case quietly dropped to MEDIUM. The
harness caught it — F1 fell from 1.00 to 0.71.

**Decision.** Removed the global `ANALYZE`. Row estimates are read before the candidate
runs, and the introspector falls back to an exact `COUNT(*)` only when the estimate looks
small. Lesson: a fixture that fakes scale is fragile to anything that recomputes
statistics — the evaluation harness is what makes that visible.

### One sandbox container per evaluation case

**Tried.** Every review starts its own disposable Postgres, evaluation cases included. It is
the obvious reading of the safety rule ("a fresh container per review") and it is trivially
correct: no case can contaminate another.

**Evidence.** The first CI run that actually executed these tests timed out. The corpus is 15
cases and four of the five stages use a sandbox, so a full run is 60 container starts — about
60 s per case on a GitHub runner, almost entirely churn, against a 15-minute-per-stage budget.
A `sentinel.sandbox.reuse-within-evaluation` property already existed for this, was documented
in the config class, and was set to `true` in `application.yaml` — and was read by nothing. It
had never been wired up, and nothing noticed because the job it would have helped had never
run: `gradlew` was committed non-executable, so all three Gradle CI jobs had been failing at
`Permission denied` since the repo was created.

**Decision.** Implemented the lease the property promised. An evaluation run holds one
container and wipes it between cases — dropping every non-system schema and resetting the
database-level `search_path`, not just `public`, because migrations create schemas of their own
and the replayer pins a search path. Still disposable: created for the run, destroyed with it.
Lesson: a green checkmark that never ran is worse than a red one, and a configuration knob
nobody reads is indistinguishable from a comment.

---

The main failure mode and the takeaway: [docs/HOT_TAKE.md](HOT_TAKE.md).
