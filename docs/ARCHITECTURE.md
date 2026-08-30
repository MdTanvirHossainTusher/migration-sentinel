# Architecture

How a review flows through the system, and where it scales.

## The request path

```
                    ┌──────────────┐
  browser  ─POST──▶ │ ReviewController │
                    └──────┬───────┘
                           ▼
                    ┌──────────────┐   same transaction
                    │ ReviewService │──────────────────┐
                    └──────┬───────┘                   │
                           │ writes review_job (QUEUED)│
                           │ writes audit_event         │
                           │ (kafka) writes outbox_event│
                           ▼                            ▼
                    returns 202 + id            JobSubmissionGateway
                                                        │
                    ┌───────────────────────────────────┴────────────┐
              local │ (default)                              kafka │
                    ▼                                              ▼
      @TransactionalEventListener                        OutboxRelay
        (AFTER_COMMIT) + @Async                    AFTER_COMMIT publish
                    │                              + @Scheduled sweep
                    │                                       │
                    ▼                                       ▼
             JobExecutionService  ◀── KafkaListener ──  migration-sentinel.reviews
                    │                (consumer group, SKIP LOCKED lease)
                    ▼
              ReviewRunner.runSync  ─▶ MigrationReviewOrchestrator
                    │                     │  analyzer agent  → tools
                    │                     │  verifier agent  → tools
                    │                     ▼
                    │              disposable Postgres (Testcontainers,
                    │              against the dind engine in compose)
                    ▼
        review_job (COMPLETED) + findings + tool_call trajectory
        + audit_event (review.completed)
        + report.md → object storage (presigned download URL)
```

## Why the AFTER_COMMIT boundary

The submitting method is `@Transactional`. It must be — the `review_job` row, its
`audit_event`, and (under Kafka) the `outbox_event` all have to commit atomically. But the
thing that *executes* the job must not start until that commit is durable, or it opens its
own transaction, looks up a row that is not there yet, and fails. The previous design fired
an `@Async` method straight from inside the transaction and lost that race under load —
evaluations submitted in a burst stuck at `QUEUED` with a `NoSuchElementException` in the
log and nothing to retry them.

Every transport now triggers execution from **after** the commit:

- **local** — a Spring event handled by `@TransactionalEventListener(phase = AFTER_COMMIT)`,
  then `@Async` onto a bounded pool. Zero infrastructure; used by tests and `bootRun`.
- **kafka** — the outbox row is the trigger. `OutboxRelay` publishes it on `AFTER_COMMIT`
  for latency and a `@Scheduled` sweep recovers anything the broker missed. `JobConsumer`
  executes it; the Kafka offset commits per record, so a job is only acknowledged once it
  has actually run.

## Where it scales

| Dimension | Mechanism |
| --- | --- |
| Submission throughput | `submit()` only writes rows and returns 202; no work on the request thread |
| Worker throughput | `kafka` transport: add backend replicas — the consumer group hands each partition to one pod; `local`: raise `sentinel.jobs.pool-size` |
| Duplicate delivery | Runners no-op on a job that is already terminal; consumers are idempotent |
| Outbox contention across pods | `lockDueBatch` uses `PESSIMISTIC_WRITE` + `SKIP LOCKED`, so relay pods take disjoint slices |
| Broker outage | Events stay durable in `outbox_event`; the sweep drains them when Kafka returns, with capped exponential backoff |
| Sandbox isolation | One disposable container per review (per run for the eval harness), owned entirely by the tool layer — no `jdbcUrl` reaches an agent |
| Report delivery | The rendered `report.md` is an object in storage; the API returns a short-lived presigned URL, so the backend never streams file bytes |

## Data model additions

| Table | Purpose |
| --- | --- |
| `outbox_event` | transactional outbox; `PENDING → (published) → deleted`, or `FAILED` after max retries |
| `audit_event` | durable audit trail; written in the business transaction, optionally relayed on `migration-sentinel.audit` |

### How audit events are captured

- **API operations** carry `@Audited(action, aggregateType, id)` (`aspect/Audited.java`).
  `AuditAspect` runs at `@Order(100)` — *inside* the `@Transactional` advice, which
  `AuditConfig`'s `@EnableTransactionManagement(order = 0)` pins to the outside — so
  `AuditService.record` (propagation `REQUIRED`) joins the business transaction and the
  change and its event commit or roll back together. The aspect pulls the aggregate id and
  a scalar payload off the return value by reflection, and the actor from the `X-Actor`
  header (or an `approvedBy` field). Annotated today: `review.submitted`,
  `evaluation.submitted`, `rewrite.applied`, `artifact.confirmed`.
- **Terminal states of async work** (`review.completed` / `.failed`,
  `evaluation.completed` / `.failed`) are recorded by an explicit `AuditService.record`
  call in the runner — they are domain state transitions on a worker thread, not "an API
  call returned", so an aspect on a method boundary is the wrong tool.
- Mirrors the identity service's `AuditAspect` + `@AuditEvent`, trimmed to this domain
  (no auth principal, no entity before/after snapshots).
| `artifact` | object-storage artifacts (`REVIEW_REPORT`, `USER_UPLOAD`); `PENDING → CONFIRMED` for uploads |
| `review_job.llm_api_key_encrypted`, `evaluation_run.llm_api_key_encrypted` | AES-GCM ciphertext of a per-request key; decrypted only by the worker, just before the LLM call |
| `review_job.report_artifact_id` | link to the stored `report.md` |

## Secret handling

- **At rest** — a per-request LLM key is AES-GCM encrypted (`CryptoService`,
  `sentinel.crypto.secret`) before it touches the row. It is never in an API response
  (`DtoMapper` does not map it), never in an audit payload, never logged.
- **In transit through logs** — `MaskingConsoleAppender` wraps every log event and
  `SecretMasker` redacts anything shaped like a credential (`sk-…`, `AIza…`, bearer tokens,
  `key=value` secrets, JDBC passwords). Patterns extend from `redaction.xml`.
- **In persisted trajectories** — `TrajectoryRecorder` masks tool arguments and results
  before they are stored, so a tool that echoes a connection string leaves no copy.

## Toggles

Everything added in stage 6 is off by default so the core paths are unchanged:

| Property | Default | Compose |
| --- | --- | --- |
| `sentinel.messaging.transport` | `local` | `kafka` |
| `sentinel.s3.enabled` | `false` | `true` |
| `sentinel.crypto.secret` | ephemeral (warns) | fixed dev value |
| `sentinel.rewrite-apply-enabled` | `false` | `false` |
