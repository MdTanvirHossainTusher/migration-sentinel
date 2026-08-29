# Evaluation corpus

15 migration-safety cases. One directory each.

## Files per case

| File | Required | Purpose |
| --- | --- | --- |
| `case.json` | no | `title`, `description`, `hard` |
| `baseline.sql` | no | prior migrations — the schema that already exists |
| `seed.sql` | no | rows and/or `UPDATE pg_class SET reltuples = N` to simulate production scale |
| `migration.sql` | **yes** | the candidate migration under review |
| `entity.java` / `entity.json` | no | JPA mapping, for the entity/schema drift check |
| `labels.json` | **yes** | `{ "expected": [{ "ruleCode": "...", "targetObject": "...", "severity": "..." }], "mustBeClean": false }` |

`severity` in a label is optional; when present, a finding must match it to count as a hit
(this is how cases 03/04 discriminate a tool-using agent from a prompt).

## Adding a case

1. `mkdir src/main/resources/eval/cases/16-my-case`
2. Add `migration.sql` + `labels.json` (+ `baseline.sql` / `seed.sql` as needed).
3. `./gradlew sandboxTest --tests '*EvaluationHarnessTest*'` — the new case is picked up
   automatically (the loader globs `eval/cases/*/labels.json`).

## The cases

See [docs/EVALUATION.md](../../../../../docs/EVALUATION.md) for the table and the rationale
behind the hard cases (03/04 empty-vs-large, 09 indexed-FK-no-false-positive, 15 multi-issue).
