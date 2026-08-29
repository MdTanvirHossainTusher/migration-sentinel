# Hot take

## The failure mode

**An agent reasoning over error text alone will confidently give the wrong answer, and it
will sound exactly as sure as when it's right.**

Case 03 and case 04 are the same five words of SQL: `ALTER TABLE t ALTER COLUMN c SET NOT
NULL`. One is a four-minute production outage. The other is instant. The prompt-only
baseline produced a MEDIUM-severity finding for both — a plausible paragraph about
exclusive locks, with no hedging, no "I'd need to check the table size". It had no way to
know and no awareness that it had no way to know.

That's the dangerous shape: not a wrong answer that looks wrong, but a wrong answer wearing
the same confidence as a right one. A reviewer who trusts it merges the outage; a reviewer
who's been burned by the false alarm on case 04 ignores the tool entirely.

## What fixed it

Not a better prompt. A tool that returns `5000000` or `0`. The moment the agent could call
`estimate_rows`, the two cases separated cleanly and the confidence became *earned* — the
finding now says "sandbox: invoices holds 5000000 rows" and a human can verify that number
in one query.

The single biggest jump on our improvement curve (F1 0.82 → 0.96) was adding read-only
database introspection. Every prompt iteration we tried before that moved F1 by less than
0.03 combined.

## What we'd build differently next time

**Make "I didn't check" a first-class output.** Our fix was the verifier pass: any finding
whose evidence doesn't cite a tool result is dropped or flagged `UNVERIFIED`. That should
have been the design from step one, not an iteration. The rule we'd start any tool-using
agent with now:

> A claim the agent could have verified with an available tool but didn't is a bug, even if
> the claim is correct.

The verifier enforces exactly that, and on adversarial input (real LLM analyzers) it's the
thing standing between a plausible paragraph and a false positive in someone's PR.

## The smaller lesson

The evaluation harness caught our own worst mistake. We added a global `ANALYZE` to the
sandbox to make `EXPLAIN` plans realistic; it reset the `pg_class` row-count stubs the test
cases use to fake production scale, and every large-table case silently dropped a severity
level. F1 fell from 1.00 to 0.71 and the CI assertion failed. Without a scored corpus that
regression ships as "looks fine, all the demos still work."

A fixture that fakes scale is fragile to anything that recomputes statistics — and you only
find out if something is measuring.
