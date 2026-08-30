# CLAUDE.md

Guidance for Claude Code working in this repo.

## Commands

```bash
./gradlew test                                    # fast unit tests, no Docker
./gradlew sandboxTest                              # Testcontainers tests (needs Docker)
./gradlew evaluationTest                           # the measured-improvement table (needs Docker)
./gradlew bootRun --args='--spring.profiles.active=standalone'   # backend, H2 metadata DB
docker compose up --build                          # full stack (frontend :3000, api :8080)
cd frontend && npm install && npm run dev          # frontend only
```

Java 21. The offline `heuristic` LLM provider is the default — everything runs with no API key.

## Architecture

Agentic reviewer for Flyway migrations. `com.migrationsentinel`.

| Package | Role |
| --- | --- |
| `controller/` | REST endpoints, `ApiResponse<T>` envelope via `ResponseBuilder` |
| `service/agent/` | `AgentLoop`, `Toolbox`, `MigrationReviewOrchestrator`, `PromptLibrary` (prompts in `resources/prompts/`) |
| `service/sandbox/` | Testcontainers lifecycle, introspection, migration replay, lock analysis, JPA validate |
| `service/llm/` | `LlmClient` + heuristic / openai / gemini implementations, `LlmClientRegistry` (`resolve(provider, apiKey)`) |
| `service/rules/` | `DdlParser`, `RuleCatalog`, `StaticRuleScanner` (deterministic) |
| `service/eval/` | corpus loader, scorer, evaluation runner |
| `service/audit/` | `AuditService` — audit_event row + optional Kafka relay, same transaction |
| `service/artifact/` | `ArtifactStorageService` — presigned S3 upload/confirm, server-side report storage |
| `service/support/` | `AgentJsonMapper` (camelCase), `CryptoService` (AES-GCM for per-request keys) |
| `messaging/` | `JobSubmissionGateway` — `local/` (AFTER_COMMIT event) and `outbox/` (outbox→Kafka) transports; `JobExecutionService` is the shared execution entry point |
| `util/` | `SecretMasker` + `MaskingConsoleAppender` (redaction) |
| `model/entity/` | JPA entities; `model/enums/` domain enums |
| `payload/` | `request/`, `response/`, `common/`, `dto/` |

### Stage-6 conventions

- **Job dispatch is always AFTER_COMMIT.** `submit()` is `@Transactional` and calls
  `JobSubmissionGateway`; never call a runner directly from a submit path. `local` transport
  = `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("jobExecutor")`; `kafka` =
  `OutboxRecorder` → `OutboxRelay` → `JobConsumer`. Runners no-op on a terminal job.
- **Kafka is opt-in.** `KafkaAutoConfiguration` is excluded in `application.yaml`; all Kafka
  beans live in `config/KafkaConfig` gated on `sentinel.messaging.transport=kafka`. Tests,
  `bootRun` and the eval harness run `local`.
- **S3 is opt-in.** `sentinel.s3.enabled=false` by default → report stays inline; every S3
  bean and `ArtifactStorageService` is `@ConditionalOnProperty`. Inject via `ObjectProvider`.
- **Secrets never travel in the clear.** Per-request keys are `CryptoService`-encrypted on
  the row; `SecretMasker.mask(...)` is applied in `TrajectoryRecorder`, `AuditService` and
  `OutboxRecorder`; `DtoMapper` never maps `llmApiKeyEncrypted`.

## Conventions

- Every controller returns `ResponseEntity<ApiResponse<T>>` built with `ResponseBuilder`.
- Wire format is snake_case (primary Jackson mapper). **Agent-internal JSON is camelCase**
  via `AgentJsonMapper` — inject that, not `ObjectMapper`, in `service/agent`, `service/llm`,
  `service/eval`, and the sandbox validators.
- Flyway migrations are numbered and never edited once added. `ddl-auto: validate`.
- A review's baseline is the project's **whole** migration history. `baseline_migrations`
  (a list of files) is the real input — `MigrationHistory` orders it with `FlywayVersion`
  (numeric, so V10 > V2) and flattens it with per-file marker comments; `baseline_sql` keeps
  the flattened form and `MigrationHistory.split` recovers the files. Only the flattened form
  is persisted.
- `target_schema` is the project's `spring.flyway.schemas`. The replayer creates it and sets
  the search path before replaying; `SchemaIntrospector` scopes to every non-system schema,
  never a hardcoded `public`.
- `SandboxRunResult.schemaObserved` — not `baselineApplied` — is what makes `SchemaFacts`
  claim the sandbox measured something. A half-applied baseline must not let a rule cite
  `pg_index` for a lookup that never ran.
- Evaluation cases live in `src/main/resources/eval/cases/<id>/`. `labels.json` keys
  findings by `ruleCode` (+ optional `severity`).
- The 5 `ReviewMode` values map 1:1 to the stages in `docs/CHANGELOG_IMPROVEMENT.md`.
- Sandbox tests carry `@SandboxTest` (`@Tag("sandbox")`), excluded from `./gradlew test`.
  `sandboxTest` runs them *except* `EvaluationHarnessTest`, which has its own `evaluationTest`
  task and CI job — it is minutes of work and was previously running twice per build.
- An evaluation run leases **one** sandbox container and wipes it between cases
  (`SandboxManager.leaseForEvaluation`). Per-case containers made the harness time out in CI.

## Safety invariant

No tool takes a connection string. All DDL runs in a per-review disposable container behind
`SandboxSession.assertIsSandbox`. See `docs/SAFETY_MODEL.md` before touching
`service/sandbox/` or the apply-rewrite path.
