# Changelog

All notable changes to this project are documented here. This file is managed by
[release-please](https://github.com/googleapis/release-please) from Conventional Commit
messages.

For the story of how the *agent* improved across iterations — the hackathon deliverable —
see [docs/CHANGELOG_IMPROVEMENT.md](docs/CHANGELOG_IMPROVEMENT.md).

## [0.3.1](https://github.com/MdTanvirHossainTusher/migration-sentinel/compare/v0.3.0...v0.3.1) (2026-08-30)


### Documentation

* real-model evaluation numbers, submission text ([4560da9](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/4560da9096c28953019bc31bf5e0110d57ca8e42))

## [0.3.0](https://github.com/MdTanvirHossainTusher/migration-sentinel/compare/v0.2.0...v0.3.0) (2026-08-30)


### Features

* **artifacts:** presigned s3 upload/confirm and downloadable report.md ([7bcbf3c](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/7bcbf3c7f6e06580b2c081f216f016ab5b2990e0))
* **audit:** declarative @Audited aspect for api operations, ordered inside the tx advice ([19c62b3](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/19c62b37659703f98652661404a5a247dd584c15))
* **audit:** durable audit-event trail with kafka relay ([8460f38](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/8460f387e2afcef02b7c82837416d42211a69ad5))
* **compose:** bundle dind sandbox, kafka and rustfs for a one-command stack ([2991113](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/29911135b430ad9447041bccf73133b4f5acb289))
* **llm:** per-request api key, encrypted at rest ([6e0e782](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/6e0e782ebdfeb9eaced9b8c0b75fcfc52bc0e0c1))
* **logging:** mask credentials in console output, audit payloads and trajectories ([1e2866f](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/1e2866fc2cbdd3169aaf9e3d7fd96deb2e480c98))
* **messaging:** transactional outbox with kafka transport and after-commit dispatch ([ca7884a](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/ca7884afbc9d374511ea3df1eec2ada63e549bd2))


### Bug Fixes

* **compose:** rustfs healthcheck accepts the s3 api 403 response ([8f0b1e9](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/8f0b1e9ff4fccd712eb7aa4a95d8eb25b2e13eb6))
* **crypto:** accept any secret string; quiet 404s for unknown endpoints ([7bf9781](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/7bf97811a0194d945395404b915ef1a4f25737fc))
* **eval:** null-safe caseIds when submitting an evaluation over kafka ([729ff1b](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/729ff1b49b4f06ed45140c790b1c6899adcacc38))
* **evaluation:** applied audit on service methods ([ed50e22](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/ed50e2255fc27601d249cf0e2e406404fec597e9))
* **gemini:** echo thought_signature for gemini 3 tools; flatten provider errors; default to gemini-flash-latest ([95e1e5e](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/95e1e5e1fb8c342f4f02eb207e14b3ac1933316f))
* **llm:** retry 429/503 with backoff; fix snake_case on the evaluation cases endpoint ([295ded1](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/295ded16691a2166b3fa6314c893354f200109ad))
* **openai:** gpt-5 line needs reasoning_effort=none and no temperature for function tools ([7c81c88](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/7c81c880d6ca81d99177e0da0258d1ee3e135a15))


### Documentation

* add platform, patterns and tools section to the readme ([2226e1c](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/2226e1c7ffdefca8f899b31fb08f4afaafd11638))
* describe the ci/cd workflows and release-please automation ([ea61b92](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/ea61b928073b24b92ee68304cccd20be28dddaed))
* reviewer walkthrough, committed agent traces, stage-7 changelog ([b6e0ec0](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/b6e0ec0175083d2be9f440c0c8a92eacda3ba388))
* stage-6 changelog, architecture, hackathon evaluation and reproduction updates ([d805ce3](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/d805ce3e993bdac32c6dd74d77db655c4decb4a8))


### Code Refactoring

* **architecture:** add outbox pattern, kafka, schedular, presigned url, S3, masking ([0364169](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/0364169fce8884706961a836b5a01547186cb634))

## [0.2.0](https://github.com/MdTanvirHossainTusher/migration-sentinel/compare/v0.1.0...v0.2.0) (2026-08-30)


### Features

* add domain model, persistence, and the unified API envelope ([525b4db](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/525b4dbf0629b06d97d70f099f14ed91e7548af5))
* **agent:** add the agent loop, toolbox, and analyzer/verifier orchestrator ([3156c0d](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/3156c0db1d093465fe49fd5e6693e2703b57f651))
* **api:** add review, evaluation, and health endpoints ([b783fc4](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/b783fc4b631c9d47a611f4777a401da8048777bd))
* **eval:** add the 15-case migration corpus, scorer, and harness ([0a363e2](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/0a363e29363caf11d264c343e749d0b1adb3b666))
* **frontend:** add the Next.js review and evaluation UI ([fd8859c](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/fd8859c5bf8a0b0153e2e909018f83a5ba72c709))
* **llm:** add the offline heuristic brain plus OpenAI and Gemini clients ([eba4e88](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/eba4e88fcbfd87c7aa1ce3584c643dbc1d2a7d9a))
* **review:** review a migration against the whole history, not one predecessor ([464d940](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/464d940bab7860a9ee3e42bfe68a9a65c9aad126))
* **review:** review a migration against the whole history, not one predecessor ([6f0cb48](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/6f0cb48d3a9a9f06af2e3fde774e4796a68ee65b))
* **rules:** add DDL parser and the deterministic migration-safety scanner ([5f619af](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/5f619afd0a41a83eb09bf41fcde873c05c88111d))
* **sandbox:** add the disposable Postgres tool layer ([cd3ccf7](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/cd3ccf70a06298ed9fa3989da52a8b7e9991669b))


### Bug Fixes

* **agent:** key the offline client's role off a marker, not prose ([63c7485](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/63c7485f890da4109c036739baf30c0bc2c90a9c))
* **api:** defence-in-depth checks on the apply-rewrite action ([4d113a4](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/4d113a496c4223ac585a70f873f064cf9788ddfc))
* **ci:** lease one sandbox container per evaluation run ([ea54766](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/ea54766b1de851eb280da6dff804eaa623ddcf9f))
* **ci:** make gradlew executable ([f54cd50](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/f54cd502343b8453395f269c386c2000efeef49b))
* **frontend:** update frontend proxy issue ([90e9342](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/90e9342afffeb595b22a841e64b81949ee2817e5))
* **gradle:** add api version for docker env ([c1e1656](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/c1e165638af14c65130377ac7a424d2730d62a89))


### Documentation

* add API & usage guide ([25b29f5](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/25b29f5720ece784360e942daa37d430e353cb7c))
* add README, improvement changelog, and hackathon deliverables ([a54d9ce](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/a54d9ce6013ddff17a347c340dbfb1563a8de60b))
* lock in the confirmed evaluation numbers ([a629ca0](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/a629ca0e328358dd04dce861bbf61f1a3cfafd1e))
* record the whole-history stage and the evaluation lease ([433828f](https://github.com/MdTanvirHossainTusher/migration-sentinel/commit/433828ff0caa66163871dfe4c7776593242f9042))

## 0.1.0 (unreleased)

Initial hackathon submission.

### Features

- Agentic Flyway migration safety reviewer: analyzer + verifier agents over a disposable
  Testcontainers Postgres sandbox.
- Five selectable pipeline modes covering every point on the improvement curve, from a
  tool-less prompt to the full two-agent split.
- Deterministic rule scanner with 11 defect classes as the grounded backbone.
- Offline `heuristic` LLM client (no API key needed) plus `openai` and `gemini` clients.
- 15-case evaluation corpus with a severity-aware scorer and a CI-enforced
  improvement-over-baseline assertion.
- Next.js 15 UI: review submission, evidence-backed reports, agent trajectory timeline,
  baseline-vs-agent evaluation comparison.
- Human-gated "apply rewrite to file" action with an approval audit trail.
