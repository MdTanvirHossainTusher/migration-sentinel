"use client";

import { use, useEffect, useState } from "react";
import Link from "next/link";
import { api, EvaluationDetail } from "@/lib/api";

const pct = (n?: number) => (n == null ? "—" : `${(n * 100).toFixed(0)}%`);

export default function EvaluationDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const [detail, setDetail] = useState<EvaluationDetail | null>(null);

  useEffect(() => {
    let stop = false;
    const load = () =>
      api.getEvaluation(id).then((d) => {
        if (stop) return;
        setDetail(d);
        if (d.run.status === "RUNNING" || d.run.status === "QUEUED") setTimeout(load, 3000);
      });
    load();
    return () => {
      stop = true;
    };
  }, [id]);

  if (!detail) return <p className="muted">Loading…</p>;
  const { run, cases } = detail;

  return (
    <>
      <p>
        <Link href="/evaluations">← evaluations</Link>
      </p>
      <h2>
        {run.mode} <span className="muted">· {run.provider} · {run.status}</span>
      </h2>

      <div className="kpi">
        <div>
          <div className="n">{pct(run.recall)}</div>
          <div className="l">recall</div>
        </div>
        <div>
          <div className="n">{pct(run.precision)}</div>
          <div className="l">precision</div>
        </div>
        <div>
          <div className="n">{pct(run.f1)}</div>
          <div className="l">F1</div>
        </div>
        <div>
          <div className="n">
            {run.true_positives}/{run.false_positives}/{run.false_negatives}
          </div>
          <div className="l">TP / FP / FN</div>
        </div>
        <div>
          <div className="n">{run.completed_cases}/{run.total_cases}</div>
          <div className="l">cases</div>
        </div>
      </div>

      <table>
        <thead>
          <tr>
            <th>Case</th>
            <th>Exp</th>
            <th>Rep</th>
            <th>TP</th>
            <th>FP</th>
            <th>FN</th>
            <th>Pass</th>
            <th>Notes</th>
          </tr>
        </thead>
        <tbody>
          {cases.map((c) => (
            <tr key={c.case_id}>
              <td>
                {c.review_job_id ? <Link href={`/reviews/${c.review_job_id}`}>{c.case_id}</Link> : c.case_id}
              </td>
              <td>{c.expected_count}</td>
              <td>{c.reported_count}</td>
              <td>{c.true_positives}</td>
              <td className={c.false_positives ? "err" : ""}>{c.false_positives}</td>
              <td className={c.false_negatives ? "err" : ""}>{c.false_negatives}</td>
              <td>{c.passed ? "✓" : "✗"}</td>
              <td className="muted">{c.notes}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}
