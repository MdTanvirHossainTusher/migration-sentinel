# Reviewing a real service's migrations (e.g. an identity service)

The 15-case corpus gives each case one small baseline file. A real service has hundreds of
migrations across several schemas — that is the case this tool is built for. This walkthrough
uses an identity/auth service's `db/migration` folder (~220 files, schema `identity`,
needs the `pgcrypto` and `pg_trgm` extensions) as the example.

## Prerequisites

- `docker compose up --build` running (this is where `sandbox_used` is true — the bundled
  dind engine runs the disposable Postgres).
- The service's `src/main/resources/db/migration/` folder available locally.
- Optional: an `OPENAI_API_KEY` if you want a real model to reason over the evidence. You do
  **not** need to set it on the server — the UI takes a per-request key (next section).

## Via the UI

1. Open <http://localhost:3000>.
2. **Step 1 — Load your migration folder** → *Choose folder…* → select the service's
   `db/migration` directory. Every `.sql` is read in the browser and ordered by Flyway
   version (`V10` after `V2`, `R__` last, `U__` skipped).
3. The newest file is picked as the **candidate**; everything before it is the baseline.
   Press **review** on any earlier row to review that one against only what preceded it.
4. **Step 2 — Database schema**: enter `identity` (the service's `spring.flyway.schemas`).
   Flyway creates that schema at boot, so the migrations never mention it — without this the
   replay stops at the first schema-qualified name. The page also detects likely schema
   names from the SQL and offers them as buttons.
5. **Step 3 — seed** is optional. Leave it blank and the sandbox measures whatever the
   migrations themselves create; add `UPDATE pg_class SET reltuples = 5e7 WHERE relname = '…';`
   to simulate a production-sized table for a specific check.
6. **Step 4**: pick a depth (`Full review` is the default) and a brain:
   - `heuristic` — offline, deterministic, no key.
   - `openai` / `gemini` — a field appears for an **API key used only for this review**.
     It is AES-GCM encrypted at rest, never returned by the API, and stripped from logs and
     the audit trail. It is not stored in your browser.
7. **Review**. The candidate replays against the full prior history; the report says how many
   files and statements ran, or — if one migration needs something the sandbox lacks (an
   extension, a role, production-only data) — which file stopped it and how far it got. Untick
   that file and run again.

## Via the API

```bash
# Assemble the request from the folder (filenames matter — a replay failure names the file).
python - <<'PY' > /tmp/req.json
import json, pathlib
d = pathlib.Path("path/to/identity/src/main/resources/db/migration")
files = sorted(d.glob("V*.sql"), key=lambda p: int(p.name.split("__")[0][1:]))
candidate = files[-1]
baseline = [{"filename": p.name, "sql": p.read_text()} for p in files[:-1]]
print(json.dumps({
    "filename": candidate.name,
    "migration_sql": candidate.read_text(),
    "baseline_migrations": baseline,
    "target_schema": "identity",
    "mode": "ANALYZER_VERIFIER_SPLIT",
    "provider": "openai",
    "llm_api_key": "sk-...your key..."      # optional; omit for heuristic
}))
PY

ID=$(curl -s -XPOST localhost:8080/api/v1/reviews -H 'content-type: application/json' \
      -d @/tmp/req.json | jq -r '.data.id')

# poll
curl -s localhost:8080/api/v1/reviews/$ID | jq '{status, findings_count, sandbox_used, baseline_file_count}'

# full report once COMPLETED
curl -s localhost:8080/api/v1/reviews/$ID/report | jq '.data.review, (.data.findings[] | {rule_code, severity, verdict, evidence})'
curl -s -L localhost:8080/api/v1/reviews/$ID/report.md -o report.md
```

## What to expect

- **Replay**: ~200 files / ~1,500 statements in roughly half a minute, into schema `identity`
  plus any others the migrations create. First run is slower — the sandbox pulls
  `postgres:16-alpine` inside the dind engine.
- **Extensions**: `postgres:16-alpine` ships `pgcrypto` and `pg_trgm`, so `CREATE EXTENSION`
  succeeds. A migration needing an extension the image does not have is the usual reason to
  untick a file.
- **Findings**: on a mature schema most candidates are clean; the interesting ones are new
  indexes without `CONCURRENTLY`, `SET NOT NULL` on a table the sandbox measured as large,
  and a new FK column with no covering index.
- **Audit**: `curl -s localhost:8080/api/v1/audit-events | jq '.data[]|{event_type,summary}'`
  shows `review.submitted` / `review.completed` for the run; the API key is not in it.

## Cost (with `openai`)

`gpt-4o-mini`: roughly $0.01–0.03 per review depending on how many tools the analyzer calls.
The full 15-case evaluation with `provider=openai` is roughly $0.20.
