"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api, EvaluationCaseMeta, EvaluationRun, Health, ReviewMode } from "@/lib/api";

const pct = (n?: number) => (n == null ? "—" : `${(n * 100).toFixed(0)}%`);

export default function EvaluationsPage() {
  const [runs, setRuns] = useState<EvaluationRun[]>([]);
  const [cases, setCases] = useState<EvaluationCaseMeta[]>([]);
  const [health, setHealth] = useState<Health | null>(null);
  const [mode, setMode] = useState<ReviewMode>("ANALYZER_VERIFIER_SPLIT");
  const [provider, setProvider] = useState("heuristic");
  const [busy, setBusy] = useState(false);

  const refresh = () => api.listEvaluations().then(setRuns).catch(() => {});

  useEffect(() => {
    refresh();
    api.listCases().then(setCases).catch(() => {});
    api.health().then(setHealth).catch(() => {});
    const t = setInterval(refresh, 4000);
    return () => clearInterval(t);
  }, []);

  const run = async () => {
    setBusy(true);
    try {
      await api.runEvaluation({ mode, provider, corpusLabel: `ui-${Date.now()}` });
      refresh();
    } finally {
      setBusy(false);
    }
  };

  const completed = runs.filter((r) => r.status === "COMPLETED");
  const baseline = completed.find((r) => r.mode === "BASELINE_PROMPT");
  const agent = completed.find((r) => r.mode === "ANALYZER_VERIFIER_SPLIT");

  return (
    <>
      <h2>Evaluation corpus ({cases.length} cases)</h2>
      <p className="muted">
        Same cases run through every pipeline mode. Metric: precision / recall / F1 on defects caught vs false
        positives, with the label severity checked — the empty-vs-large pair (03/04) only scores if the reviewer
        measured the table.
      </p>

      <div className="panel">
        <div className="row">
          <div>
            <label>Mode</label>
            <select value={mode} onChange={(e) => setMode(e.target.value as ReviewMode)}>
              <option value="BASELINE_PROMPT">BASELINE_PROMPT</option>
              <option value="ANALYZER_READ_ONLY">ANALYZER_READ_ONLY</option>
              <option value="ANALYZER_WITH_SANDBOX">ANALYZER_WITH_SANDBOX</option>
              <option value="ANALYZER_VERIFIED">ANALYZER_VERIFIED</option>
              <option value="ANALYZER_VERIFIER_SPLIT">ANALYZER_VERIFIER_SPLIT</option>
            </select>
          </div>
          <div>
            <label>Provider</label>
            <select value={provider} onChange={(e) => setProvider(e.target.value)}>
              <option value="heuristic">heuristic (offline)</option>
              {(health?.available_providers || []).filter((p) => p !== "heuristic").map((p) => (
                <option key={p}>{p}</option>
              ))}
            </select>
          </div>
          <div style={{ display: "flex", alignItems: "flex-end" }}>
            <button onClick={run} disabled={busy}>
              {busy ? "Queued…" : "Run evaluation"}
            </button>
          </div>
        </div>
      </div>

      {baseline && agent && (
        <>
          <h2>Baseline vs full agent</h2>
          <table>
            <thead>
              <tr>
                <th>Metric</th>
                <th>Baseline (prompt only)</th>
                <th>Full agent</th>
                <th>Change</th>
              </tr>
            </thead>
            <tbody>
              <Row label="Recall (defects caught)" a={baseline.recall} b={agent.recall} fmt={pct} />
              <Row label="Precision" a={baseline.precision} b={agent.precision} fmt={pct} />
              <Row label="F1" a={baseline.f1} b={agent.f1} fmt={pct} />
              <Row
                label="False positives / case"
                a={baseline.false_positive_rate}
                b={agent.false_positive_rate}
                fmt={(n) => (n == null ? "—" : n.toFixed(2))}
                lowerBetter
              />
              <Row
                label="Mean time / case"
                a={baseline.mean_duration_ms}
                b={agent.mean_duration_ms}
                fmt={(n) => (n == null ? "—" : `${(n / 1000).toFixed(1)}s`)}
                lowerBetter
              />
            </tbody>
          </table>
        </>
      )}

      <h2>Runs</h2>
      <table>
        <thead>
          <tr>
            <th>When</th>
            <th>Mode</th>
            <th>Provider</th>
            <th>Progress</th>
            <th>P</th>
            <th>R</th>
            <th>F1</th>
            <th>FP/case</th>
          </tr>
        </thead>
        <tbody>
          {runs.map((r) => (
            <tr key={r.id}>
              <td>
                <Link href={`/evaluations/${r.id}`}>{new Date(r.created_at).toLocaleTimeString()}</Link>
              </td>
              <td className="muted">{r.mode.replace("ANALYZER_", "")}</td>
              <td>{r.provider}</td>
              <td>
                {r.status === "COMPLETED"
                  ? `${r.completed_cases}/${r.total_cases}`
                  : `${r.status} ${r.completed_cases}/${r.total_cases}`}
              </td>
              <td>{pct(r.precision)}</td>
              <td>{pct(r.recall)}</td>
              <td>{pct(r.f1)}</td>
              <td>{r.false_positive_rate?.toFixed(2) ?? "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <h2>Cases</h2>
      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>Title</th>
            <th>Expected</th>
          </tr>
        </thead>
        <tbody>
          {cases.map((c) => (
            <tr key={c.id}>
              <td>
                {c.id} {c.hard && <span className="badge MEDIUM">hard</span>}
              </td>
              <td>{c.title}</td>
              <td className="muted">
                {c.must_be_clean
                  ? "clean report"
                  : c.expected.map((e) => e.rule_code).join(", ")}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

function Row({
  label,
  a,
  b,
  fmt,
  lowerBetter,
}: {
  label: string;
  a?: number;
  b?: number;
  fmt: (n?: number) => string;
  lowerBetter?: boolean;
}) {
  const delta = a != null && b != null ? b - a : null;
  const good = delta == null || delta === 0 ? "" : (lowerBetter ? delta < 0 : delta > 0) ? "" : "err";
  return (
    <tr>
      <td>{label}</td>
      <td>{fmt(a)}</td>
      <td>
        <b>{fmt(b)}</b>
      </td>
      <td className={good}>{delta == null ? "—" : `${delta >= 0 ? "+" : "−"}${fmt(Math.abs(delta))}`}</td>
    </tr>
  );
}
