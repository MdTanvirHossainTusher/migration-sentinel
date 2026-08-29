"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api, Health, Review, ReviewMode } from "@/lib/api";

const MODES: { value: ReviewMode; label: string }[] = [
  { value: "BASELINE_PROMPT", label: "Baseline — one prompt, no tools" },
  { value: "ANALYZER_READ_ONLY", label: "Analyzer + read-only introspection" },
  { value: "ANALYZER_WITH_SANDBOX", label: "Analyzer + sandbox migration run" },
  { value: "ANALYZER_VERIFIED", label: "+ verification pass" },
  { value: "ANALYZER_VERIFIER_SPLIT", label: "Full — analyzer + verifier agents" },
];

const EXAMPLE = {
  filename: "V42__add_shipment_tenant.sql",
  baselineSql: `CREATE TABLE tenants (id bigserial PRIMARY KEY);
CREATE TABLE shipments (
    id bigserial PRIMARY KEY,
    carrier varchar(32) NOT NULL,
    legacy_ref varchar(64),
    shipped_at timestamptz
);`,
  seedSql: `INSERT INTO tenants DEFAULT VALUES;
INSERT INTO shipments (carrier) SELECT 'UPS' FROM generate_series(1, 2000);
UPDATE pg_class SET reltuples = 6000000 WHERE relname = 'shipments';`,
  migrationSql: `ALTER TABLE shipments DROP COLUMN legacy_ref;
ALTER TABLE shipments ADD COLUMN tenant_id bigint;
ALTER TABLE shipments ADD CONSTRAINT fk_shipments_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants (id) NOT VALID;
ALTER TABLE shipments VALIDATE CONSTRAINT fk_shipments_tenant;
CREATE INDEX idx_shipments_shipped_at ON shipments (shipped_at);`,
};

export default function ReviewPage() {
  const [health, setHealth] = useState<Health | null>(null);
  const [reviews, setReviews] = useState<Review[]>([]);
  const [form, setForm] = useState({
    filename: "",
    migrationSql: "",
    baselineSql: "",
    seedSql: "",
    entitySource: "",
    mode: "ANALYZER_VERIFIER_SPLIT" as ReviewMode,
    provider: "heuristic",
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = () => api.listReviews().then(setReviews).catch(() => {});

  useEffect(() => {
    api.health().then(setHealth).catch(() => {});
    refresh();
    const t = setInterval(refresh, 4000);
    return () => clearInterval(t);
  }, []);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await api.submitReview(form);
      setForm({ ...form, migrationSql: "", baselineSql: "", seedSql: "", entitySource: "", filename: "" });
      refresh();
      window.location.href = `/reviews/${r.id}`;
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <h2>Review a migration</h2>
      {health && (
        <p className="muted">
          v{health.version} · default brain <b>{health.default_provider}</b> · providers{" "}
          {health.available_providers.join(", ") || "heuristic"} · sandbox{" "}
          {health.docker_available ? "ready" : "unavailable (structure-only)"} · {health.evaluation_case_count} eval
          cases
        </p>
      )}

      <div className="panel">
        <div className="row">
          <div>
            <label>Migration file name</label>
            <input
              value={form.filename}
              placeholder="V42__add_column.sql"
              onChange={(e) => setForm({ ...form, filename: e.target.value })}
            />
          </div>
          <div>
            <label>Pipeline mode</label>
            <select value={form.mode} onChange={(e) => setForm({ ...form, mode: e.target.value as ReviewMode })}>
              {MODES.map((m) => (
                <option key={m.value} value={m.value}>
                  {m.label}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label>LLM provider</label>
            <select value={form.provider} onChange={(e) => setForm({ ...form, provider: e.target.value })}>
              <option value="heuristic">heuristic (offline)</option>
              {(health?.available_providers || []).filter((p) => p !== "heuristic").map((p) => (
                <option key={p} value={p}>
                  {p}
                </option>
              ))}
            </select>
          </div>
        </div>

        <label>Candidate migration SQL *</label>
        <textarea
          value={form.migrationSql}
          onChange={(e) => setForm({ ...form, migrationSql: e.target.value })}
          placeholder="ALTER TABLE ..."
        />

        <label>Prior migrations already applied (baseline schema)</label>
        <textarea value={form.baselineSql} onChange={(e) => setForm({ ...form, baselineSql: e.target.value })} />

        <label>Seed / planner-stat setup (rows, or UPDATE pg_class SET reltuples=...)</label>
        <textarea value={form.seedSql} onChange={(e) => setForm({ ...form, seedSql: e.target.value })} />

        <label>JPA entity mapping (Java source or JSON) — optional, enables the drift check</label>
        <textarea
          style={{ minHeight: 90 }}
          value={form.entitySource}
          onChange={(e) => setForm({ ...form, entitySource: e.target.value })}
        />

        <div style={{ marginTop: 14, display: "flex", gap: 10 }}>
          <button onClick={submit} disabled={busy || !form.migrationSql.trim()}>
            {busy ? "Submitting…" : "Run review"}
          </button>
          <button className="secondary" type="button" onClick={() => setForm({ ...form, ...EXAMPLE })}>
            Load example
          </button>
        </div>
        {error && <p className="err">{error}</p>}
      </div>

      <h2>Recent reviews</h2>
      <table>
        <thead>
          <tr>
            <th>When</th>
            <th>File</th>
            <th>Mode</th>
            <th>Status</th>
            <th>Findings</th>
            <th>Tools</th>
            <th>Sandbox</th>
          </tr>
        </thead>
        <tbody>
          {reviews.map((r) => (
            <tr key={r.id}>
              <td>
                <Link href={`/reviews/${r.id}`}>{new Date(r.created_at).toLocaleTimeString()}</Link>
              </td>
              <td>{r.filename || r.case_id || "—"}</td>
              <td className="muted">{r.mode.replace("ANALYZER_", "")}</td>
              <td>{r.status}</td>
              <td>
                {r.high_count > 0 && <span className="badge HIGH">{r.high_count}H</span>}{" "}
                {r.medium_count > 0 && <span className="badge MEDIUM">{r.medium_count}M</span>}{" "}
                {r.low_count > 0 && <span className="badge LOW">{r.low_count}L</span>}
                {r.status === "COMPLETED" && r.findings_count === 0 && <span className="muted">clean</span>}
              </td>
              <td>{r.tool_call_count}</td>
              <td>{r.sandbox_used ? "yes" : "—"}</td>
            </tr>
          ))}
          {reviews.length === 0 && (
            <tr>
              <td colSpan={7} className="muted">
                No reviews yet.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </>
  );
}
