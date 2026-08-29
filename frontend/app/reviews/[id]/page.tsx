"use client";

import { use, useEffect, useState } from "react";
import Link from "next/link";
import { api, Finding, ReviewReport } from "@/lib/api";
import { CodeBlock } from "@/lib/CodeBlock";

export default function ReviewDetail({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const [report, setReport] = useState<ReviewReport | null>(null);
  const [tab, setTab] = useState<"report" | "trajectory" | "markdown">("report");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let stop = false;
    const load = () =>
      api
        .getReport(id)
        .then((r) => {
          if (stop) return;
          setReport(r);
          if (r.review.status === "COMPLETED" || r.review.status === "FAILED") return;
          setTimeout(load, 2500);
        })
        .catch((e) => setError((e as Error).message));
    load();
    return () => {
      stop = true;
    };
  }, [id]);

  if (error) return <p className="err">{error}</p>;
  if (!report) return <p className="muted">Loading…</p>;

  const r = report.review;
  const running = r.status === "QUEUED" || r.status === "RUNNING";

  return (
    <>
      <p>
        <Link href="/">← all reviews</Link>
      </p>
      <h2>
        {r.filename || r.case_id || "review"} <span className="muted">· {r.mode}</span>
      </h2>

      <div className="kpi">
        <div>
          <div className="n">{r.status}</div>
          <div className="l">status</div>
        </div>
        <div>
          <div className="n">{r.findings_count}</div>
          <div className="l">findings</div>
        </div>
        <div>
          <div className="n">{r.tool_call_count}</div>
          <div className="l">tool calls</div>
        </div>
        <div>
          <div className="n">{r.duration_ms ? `${(r.duration_ms / 1000).toFixed(1)}s` : "—"}</div>
          <div className="l">wall time</div>
        </div>
        <div>
          <div className="n">{r.sandbox_used ? "yes" : "no"}</div>
          <div className="l">sandbox</div>
        </div>
      </div>

      {running && <p className="muted">Agent is working… this page refreshes itself.</p>}
      {r.error_message && <pre className="err">{r.error_message}</pre>}

      {r.status === "COMPLETED" && (
        <>
          <div style={{ display: "flex", gap: 10, margin: "18px 0" }}>
            {(["report", "trajectory", "markdown"] as const).map((t) => (
              <button key={t} className={tab === t ? "" : "secondary"} onClick={() => setTab(t)}>
                {t}
              </button>
            ))}
          </div>

          {tab === "report" &&
            (report.findings.length === 0 ? (
              <div className="panel">
                <b>Safe to merge.</b> <span className="muted">No production-safety defects found.</span>
              </div>
            ) : (
              report.findings.map((f) => <FindingCard key={f.id} f={f} />)
            ))}

          {tab === "trajectory" && (
            <div className="trajectory">
              {report.trajectory.length === 0 && <p className="muted">No tool calls (baseline mode).</p>}
              {report.trajectory.map((t) => (
                <div className="step" key={t.id}>
                  <div>
                    <b>#{t.step_no}</b> <span className="badge">{t.agent_role}</span> {t.tool_name}{" "}
                    <span className="muted">
                      {t.duration_ms}ms {t.ok ? "" : "· failed"}
                    </span>
                  </div>
                  {t.arguments_json && t.arguments_json !== "{}" && (
                    <CodeBlock code={prettyJson(t.arguments_json)} language="json" label="args" copy={false} />
                  )}
                  <details>
                    <summary className="muted">tool result</summary>
                    <CodeBlock code={prettyJson(t.result_json)} language="json" label="result" />
                  </details>
                </div>
              ))}
            </div>
          )}

          {tab === "markdown" && (
            <CodeBlock code={report.report_markdown} language="plaintext" label="report.md" />
          )}
        </>
      )}
    </>
  );
}

function FindingCard({ f }: { f: Finding }) {
  const [applied, setApplied] = useState<string | null>(null);
  const [applyErr, setApplyErr] = useState<string | null>(null);

  const apply = async () => {
    const approvedBy = window.prompt(
      "This writes the suggested rewrite to a file (it never edits the original migration).\nEnter your name to approve:"
    );
    if (!approvedBy) return;
    try {
      await api.applyRewrite({
        findingId: f.id,
        targetFilename: `${f.rule_code.toLowerCase()}-${f.id.slice(0, 8)}.sql`,
        approvedBy,
        confirm: true,
      });
      setApplied(`written · approved by ${approvedBy}`);
    } catch (e) {
      setApplyErr((e as Error).message);
    }
  };

  return (
    <div className="panel">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <b>
          {f.ordinal}. {f.title}
        </b>
        <span>
          <span className={`badge ${f.severity}`}>{f.severity}</span>{" "}
          <span className={`badge ${f.verdict}`}>{f.verdict}</span>
        </span>
      </div>
      <p className="muted" style={{ margin: "4px 0" }}>
        <code>{f.rule_code}</code> {f.target_object && <>· {f.target_object}</>}
      </p>
      <p>{f.summary}</p>
      {f.evidence && (
        <>
          <h3>Evidence</h3>
          <CodeBlock code={f.evidence} language="sql" label="evidence" />
        </>
      )}
      {f.suggested_rewrite && (
        <>
          <h3>Suggested rewrite</h3>
          <CodeBlock code={f.suggested_rewrite} language="sql" label="rewrite.sql" />
          <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
            <button className="secondary" onClick={apply} disabled={!!applied}>
              Apply to file…
            </button>
            {applied && <span className="muted">{applied}</span>}
            {applyErr && <span className="err">{applyErr}</span>}
          </div>
        </>
      )}
    </div>
  );
}

function prettyJson(s?: string) {
  if (!s) return "";
  try {
    return JSON.stringify(JSON.parse(s), null, 2);
  } catch {
    return s;
  }
}
