# Safety model

The agent runs DDL. This document is how that is contained. It maps directly to hackathon
ground rules 04 (consequential actions through a sandbox, human approval before the action)
and 05 (a qualified human in the loop).

## 1. The agent never names a database

There is **no `jdbcUrl`, `host`, or connection-string parameter on any tool**. The agent
cannot ask to connect somewhere. The tool layer
([`Toolbox`](../src/main/java/com/migrationsentinel/service/agent/Toolbox.java)) owns the
connection: for each review,
[`SandboxManager`](../src/main/java/com/migrationsentinel/service/sandbox/SandboxManager.java)
starts a fresh `PostgreSQLContainer`, and every tool call runs against that one container,
which is destroyed when the review ends.

The agent asks *"how many rows in `orders`?"* and gets a number. It cannot choose where the
question is asked.

## 2. Read tools and the one write tool are separated

| Tool | Type | What it does |
| --- | --- | --- |
| `list_tables`, `describe_table`, `estimate_rows`, `explain` | read | `SELECT` against catalog / `information_schema`, `EXPLAIN` |
| `static_scan`, `validate_entities` | read | run analysers over already-collected state |
| `run_candidate_migration` | **write** | replays baseline + seed, runs the candidate DDL |

Only `run_candidate_migration` executes DDL, and it goes through one guard. Every
`Connection` the sandbox hands out is checked:

```java
// SandboxSession.assertIsSandbox
if (!jdbcUrl.equals(candidateUrl)) {
    throw new SandboxSafetyException(
        "Refusing DDL against a datasource that is not the disposable sandbox: " + candidateUrl);
}
```

`Connection.open()` calls this on the URL the driver actually connected to.
`MigrationReplayerIT.guardRejectsNonSandboxUrl` tests it.

## 3. The sandbox is disposable and offline

- One container per review, `container.stop()` in a `finally` block
  ([`MigrationReviewOrchestrator`](../src/main/java/com/migrationsentinel/service/agent/MigrationReviewOrchestrator.java)).
- Pinned image (`postgres:16-alpine`), no volumes, `fsync=off` — nothing survives it.
- A `statement_timeout` (default 45s) caps any single migration statement.
- The container has no network route to anything but itself.

## 4. Suggested rewrites are output, not action

The agent returns corrected SQL as **text in the report**, with a copy button. It performs
no file writes and no git operations.

The one path that writes to disk is the **"Apply to file"** button, and it:

1. is **disabled by default** (`sentinel.rewrite-apply-enabled=false`);
2. requires an explicit `confirm: true` in the request and an `approvedBy` name;
3. writes **only** inside `sentinel.rewrite-output-dir` (path-traversal checked), and
   **never** touches the original migration file — it writes a new `*.fixed.sql`;
4. records an [`ApprovalRecord`](../src/main/java/com/migrationsentinel/model/entity/ApprovalRecordEntity.java)
   row — who, when, which finding, which file — for every write.

`GET /api/v1/reviews/{id}/approvals` is the per-review apply trail.

## 4a. Everything consequential is audited, and secrets are redacted

- **`audit_event`** — `review.submitted` / `.completed` / `.failed`,
  `evaluation.submitted` / `.completed` / `.failed`, `rewrite.applied`, `artifact.confirmed`
  are each written in the same transaction as the change (API operations via an `@Audited`
  aspect ordered inside the transaction advice; async terminal states via an explicit call
  in the runner), and under the kafka transport relayed on `migration-sentinel.audit`.
  Browse it at `GET /api/v1/audit-events`.
- **A per-request LLM API key** is AES-GCM encrypted
  ([`CryptoService`](../src/main/java/com/migrationsentinel/service/support/CryptoService.java))
  before it is stored on the job row, decrypted only by the worker immediately before the
  LLM call, and never mapped into any API response.
- **`SecretMasker`** + a masking log appender strip anything credential-shaped (`sk-…`,
  `AIza…`, bearer tokens, `key=value` secrets, JDBC passwords) from the console, the audit
  payloads and the persisted agent trajectories. Patterns extend from `redaction.xml`.
- **Object storage** never receives a credential: uploads are presigned PUT URLs, the
  `report.md` is stored server-side, and downloads are short-lived presigned GET URLs.

## 5. The human is the reviewer

The output is a review *for* a person, not an autonomous merge gate. It ends with a verdict
and evidence; the engineer decides. Findings the tools couldn't prove are labelled
`UNVERIFIED` rather than hidden or asserted.

## What is deliberately not covered

- The reviewed migration's SQL is executed as-is in the sandbox. It could contain something
  slow or silly; the `statement_timeout` and the disposable container bound the blast
  radius to "one throwaway container for up to a minute".
- `run_candidate_migration` needs a superuser in the sandbox (it stubs `pg_class` for the
  evaluation cases). That superuser exists **only** inside the throwaway container.
