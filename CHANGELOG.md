# Changelog

All notable changes to this project are documented here. This file is managed by
[release-please](https://github.com/googleapis/release-please) from Conventional Commit
messages.

For the story of how the *agent* improved across iterations — the hackathon deliverable —
see [docs/CHANGELOG_IMPROVEMENT.md](docs/CHANGELOG_IMPROVEMENT.md).

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
