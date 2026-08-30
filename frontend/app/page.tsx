"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { api, Health, Review, ReviewMode } from "@/lib/api";
import { detectSchemas, formatBytes } from "@/lib/flyway";
import { EXAMPLE_HISTORY, EXAMPLE_SEED } from "@/lib/example";
import { LoadedFile, useMigrationFiles } from "@/lib/useMigrationFiles";
import { parseMigrationName, countStatements } from "@/lib/flyway";

const MODES: { value: ReviewMode; label: string; hint: string }[] = [
  { value: "ANALYZER_VERIFIER_SPLIT", label: "Full review (recommended)", hint: "Analyzer proposes, a separate verifier drops anything the sandbox does not back up." },
  { value: "ANALYZER_VERIFIED", label: "Analyzer + self-check", hint: "One agent, then it re-checks its own findings." },
  { value: "ANALYZER_WITH_SANDBOX", label: "Analyzer + sandbox run", hint: "Runs the migration for real, no verification pass." },
  { value: "ANALYZER_READ_ONLY", label: "Analyzer, read-only", hint: "Inspects the schema but never runs the candidate." },
  { value: "BASELINE_PROMPT", label: "Baseline — one prompt, no tools", hint: "The comparison point: an LLM reading the SQL with no data. Kept so you can see the difference." },
];

export default function ReviewPage() {
  const [health, setHealth] = useState<Health | null>(null);
  const [reviews, setReviews] = useState<Review[]>([]);
  const history = useMigrationFiles();
  const [seedSql, setSeedSql] = useState("");
  const [targetSchema, setTargetSchema] = useState("");
  const [entitySource, setEntitySource] = useState("");
  const [mode, setMode] = useState<ReviewMode>("ANALYZER_VERIFIER_SPLIT");
  const [provider, setProvider] = useState("heuristic");
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = () => api.listReviews().then(setReviews).catch(() => {});

  useEffect(() => {
    api.health().then(setHealth).catch(() => {});
    refresh();
    const t = setInterval(refresh, 4000);
    return () => clearInterval(t);
  }, []);

  const { candidate, baseline, later } = history.split;

  // Schemas the migrations assume exist but never create — Flyway makes these at boot from
  // `spring.flyway.schemas`, so the sandbox has to as well.
  const suggestedSchemas = useMemo(() => detectSchemas(history.files), [history.files]);

  useEffect(() => {
    if (!targetSchema && suggestedSchemas.length > 0) setTargetSchema(suggestedSchemas[0]);
  }, [suggestedSchemas, targetSchema]);

  const loadExample = () => {
    history.setFiles(
      EXAMPLE_HISTORY.map((f) => ({
        name: f.name,
        sql: f.sql,
        parsed: parseMigrationName(f.name),
        statements: countStatements(f.sql),
        included: true,
      })),
    );
    history.setCandidateName(EXAMPLE_HISTORY[EXAMPLE_HISTORY.length - 1].name);
    setSeedSql(EXAMPLE_SEED);
  };

  const submit = async () => {
    if (!candidate) return;
    setBusy(true);
    setError(null);
    try {
      const r = await api.submitReview({
        filename: candidate.name,
        migrationSql: candidate.sql,
        baselineMigrations: baseline.map((f) => ({ filename: f.name, sql: f.sql })),
        targetSchema,
        seedSql,
        entitySource,
        mode,
        provider,
      });
      window.location.href = `/reviews/${r.id}`;
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Explainer />

      <h2>1 · Load your migration folder</h2>
      <FileDropzone onFiles={history.add} loading={history.loading} hasFiles={history.files.length > 0} />
      {history.loadError && <p className="err">{history.loadError}</p>}

      {history.files.length > 0 ? (
        <>
          <HistorySummary
            baseline={baseline}
            candidate={candidate}
            later={later}
            totalChars={history.totalChars}
            onClear={history.clear}
          />
          <FileList
            files={history.files}
            candidateName={candidate?.name ?? null}
            onPick={history.setCandidateName}
            onToggle={history.toggle}
            onRemove={history.remove}
          />
        </>
      ) : (
        <p className="muted" style={{ marginTop: 10 }}>
          Nothing loaded yet.{" "}
          <button className="linklike" type="button" onClick={loadExample}>
            Load a worked example
          </button>{" "}
          to see the whole flow, or open <b>Paste SQL by hand</b> below.
        </p>
      )}

      <h2>2 · Where the migrations build</h2>
      <div className="panel">
        <div className="row">
          <div>
            <label>Database schema</label>
            <input
              value={targetSchema}
              onChange={(e) => setTargetSchema(e.target.value)}
              placeholder="public"
            />
          </div>
        </div>
        <p className="muted hint">
          Your project&apos;s <code>spring.flyway.schemas</code>. Flyway creates it at boot, so the
          migrations never mention it — but the sandbox has to create it too, or the replay stops at the
          first file that writes into it. Leave blank for <code>public</code>.
          {suggestedSchemas.length > 0 && (
            <>
              {" "}Detected in your files:{" "}
              {suggestedSchemas.slice(0, 4).map((s) => (
                <button key={s} type="button" className="linklike" onClick={() => setTargetSchema(s)}>
                  <code>{s}</code>{" "}
                </button>
              ))}
            </>
          )}
        </p>
      </div>

      <h2>3 · Tell it how big the tables are</h2>
      <p className="muted" style={{ marginTop: -6 }}>
        This is the part the SQL does not contain and a reviewer cannot see. Without it,{" "}
        <code>SET NOT NULL</code> on an empty table and the identical statement on a 50-million-row table
        look the same. Optional — leave it blank and the sandbox measures whatever the migrations
        themselves create.
      </p>
      <div className="panel">
        <label>Seed rows / planner statistics</label>
        <textarea
          value={seedSql}
          onChange={(e) => setSeedSql(e.target.value)}
          placeholder={"INSERT INTO orders (…) SELECT … FROM generate_series(1, 5000);\n-- or, without inserting millions of rows:\nUPDATE pg_class SET reltuples = 50000000 WHERE relname = 'orders';"}
        />
      </div>

      <h2>4 · Run it</h2>
      <div className="panel">
        <div className="row">
          <div>
            <label>Depth</label>
            <select value={mode} onChange={(e) => setMode(e.target.value as ReviewMode)}>
              {MODES.map((m) => (
                <option key={m.value} value={m.value}>
                  {m.label}
                </option>
              ))}
            </select>
            <p className="muted hint">{MODES.find((m) => m.value === mode)?.hint}</p>
          </div>
          <div>
            <label>Reviewing brain</label>
            <select value={provider} onChange={(e) => setProvider(e.target.value)}>
              <option value="heuristic">heuristic — offline, no API key</option>
              {(health?.available_providers || [])
                .filter((p) => p !== "heuristic")
                .map((p) => (
                  <option key={p} value={p}>
                    {p}
                  </option>
                ))}
            </select>
            <p className="muted hint">
              The deterministic rules and the sandbox run either way; the provider only changes who
              reasons over the evidence.
            </p>
          </div>
        </div>

        <div className="actions">
          <button onClick={submit} disabled={busy || !candidate}>
            {busy ? "Submitting…" : candidate ? `Review ${candidate.name}` : "Load a migration first"}
          </button>
          <button className="secondary" type="button" onClick={loadExample}>
            Load example
          </button>
          <button className="secondary" type="button" onClick={() => setShowAdvanced((v) => !v)}>
            {showAdvanced ? "Hide" : "Paste SQL by hand"}
          </button>
        </div>

        {!candidate && history.files.length > 0 && (
          <p className="err">Every file is excluded — include at least one to review.</p>
        )}
        {error && <p className="err">{error}</p>}

        {showAdvanced && (
          <ManualEntry
            onLoad={(name, sql) => {
              history.setFiles((prev) => {
                const next = prev.filter((f) => f.name !== name);
                next.push({
                  name,
                  sql,
                  parsed: parseMigrationName(name),
                  statements: countStatements(sql),
                  included: true,
                });
                return next;
              });
              history.setCandidateName(name);
            }}
            entitySource={entitySource}
            setEntitySource={setEntitySource}
          />
        )}
      </div>

      <h2>Recent reviews</h2>
      <RecentReviews reviews={reviews} />

      {health && (
        <p className="muted footnote">
          v{health.version} · default brain <b>{health.default_provider}</b> · sandbox{" "}
          {health.docker_available ? (
            "ready"
          ) : (
            <b className="err">unavailable — Docker is not running, so reviews degrade to structure-only</b>
          )}{" "}
          · {health.evaluation_case_count} evaluation cases
        </p>
      )}
    </>
  );
}

/* ─── what this thing is ──────────────────────────────────────────────── */

function Explainer() {
  const [open, setOpen] = useState(true);

  useEffect(() => {
    try {
      setOpen(window.localStorage.getItem("ms.explainer.collapsed") !== "1");
    } catch {
      /* private window — just show it */
    }
  }, []);

  const toggle = () => {
    setOpen((was) => {
      try {
        window.localStorage.setItem("ms.explainer.collapsed", was ? "1" : "0");
      } catch {
        /* ignore */
      }
      return !was;
    });
  };

  return (
    <section className="hero">
      <div className="hero-head">
        <div>
          <h2 className="hero-title">Catch the migration that is safe on your laptop and an outage in production</h2>
          <p className="hero-sub">
            Your local database has a few hundred rows, so the migration runs in 20&nbsp;ms and the tests
            pass. The same statement against 50 million rows can hold an <code>ACCESS EXCLUSIVE</code> lock
            for four minutes. Reading the SQL cannot tell you which one you wrote — the difference is in the
            data, not the text.
          </p>
        </div>
        <button className="secondary small" type="button" onClick={toggle}>
          {open ? "Hide" : "What is this?"}
        </button>
      </div>

      {open && (
        <>
          <ol className="steps">
            <li>
              <b>Rebuilds your schema.</b> Every prior migration in your folder is replayed into a throwaway
              Postgres container, in Flyway version order — so the candidate meets the schema production
              actually has, not a hand-picked predecessor.
            </li>
            <li>
              <b>Makes the tables production-sized.</b> Your seed rows, or a planner-statistics stub, so the
              cost of a scan is real.
            </li>
            <li>
              <b>Runs your migration for real, one statement at a time.</b> Timing each one and capturing the
              locks it takes.
            </li>
            <li>
              <b>Measures the result and re-checks itself.</b> Row estimates, indexes, foreign keys,{" "}
              <code>EXPLAIN</code>, a Hibernate-validate check of your entities — then a second agent drops
              every finding with no tool evidence behind it.
            </li>
          </ol>
          <p className="muted hero-foot">
            You get a report to paste into the PR: a verdict, the evidence behind each finding, and a
            suggested rewrite. Nothing touches a real database — all DDL runs in a disposable container, and
            rewrites are text you copy yourself.
          </p>
        </>
      )}
    </section>
  );
}

/* ─── loading a migration folder ──────────────────────────────────────── */

function FileDropzone({
  onFiles,
  loading,
  hasFiles,
}: {
  onFiles: (files: File[]) => void;
  loading: boolean;
  hasFiles: boolean;
}) {
  const [over, setOver] = useState(false);
  const folderInput = useRef<HTMLInputElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  const collect = (list: FileList | null) => {
    if (list) onFiles(Array.from(list));
  };

  return (
    <div
      className={`dropzone${over ? " over" : ""}`}
      onDragOver={(e) => {
        e.preventDefault();
        setOver(true);
      }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => {
        e.preventDefault();
        setOver(false);
        collect(e.dataTransfer.files);
      }}
    >
      <p className="dz-title">
        {loading ? "Reading files…" : hasFiles ? "Drop more .sql files to add them" : "Drop your db/migration folder here"}
      </p>
      <p className="muted">
        All of it — every migration, not just the last one. The files are read in your browser and sent
        with the review; nothing is uploaded until you press Review.
      </p>
      <div className="actions">
        <button className="secondary" type="button" onClick={() => folderInput.current?.click()}>
          Choose folder…
        </button>
        <button className="secondary" type="button" onClick={() => fileInput.current?.click()}>
          Choose files…
        </button>
      </div>
      {/* webkitdirectory is non-standard but is what every current browser implements. */}
      <input
        ref={folderInput}
        type="file"
        hidden
        multiple
        // @ts-expect-error -- directory picking is not in React's typings
        webkitdirectory=""
        directory=""
        onChange={(e) => {
          collect(e.target.files);
          e.target.value = "";
        }}
      />
      <input
        ref={fileInput}
        type="file"
        hidden
        multiple
        accept=".sql"
        onChange={(e) => {
          collect(e.target.files);
          e.target.value = "";
        }}
      />
    </div>
  );
}

function HistorySummary({
  baseline,
  candidate,
  later,
  totalChars,
  onClear,
}: {
  baseline: LoadedFile[];
  candidate: LoadedFile | null;
  later: LoadedFile[];
  totalChars: number;
  onClear: () => void;
}) {
  const range =
    baseline.length > 0
      ? `${baseline[0].parsed.label || baseline[0].name} → ${
          baseline[baseline.length - 1].parsed.label || baseline[baseline.length - 1].name
        }`
      : "";

  return (
    <div className="summary">
      <div className="summary-main">
        <div className="chain">
          <span className="chip">
            <b>{baseline.length}</b> prior migration{baseline.length === 1 ? "" : "s"}
            {range && <span className="muted"> · {range}</span>}
          </span>
          <span className="arrow">→</span>
          <span className="chip candidate">
            reviewing <b>{candidate ? candidate.name : "—"}</b>
          </span>
        </div>
        <p className="muted">
          {baseline.length === 0
            ? "No earlier migrations — the candidate will run against an empty database."
            : `All ${baseline.length} are replayed into the sandbox first, in version order, so the candidate meets the real schema.`}
          {later.length > 0 && ` ${later.length} newer file(s) loaded but not part of this review.`}
          {" "}
          {formatBytes(totalChars)} of SQL.
        </p>
      </div>
      <button className="secondary small" type="button" onClick={onClear}>
        Clear all
      </button>
    </div>
  );
}

function FileList({
  files,
  candidateName,
  onPick,
  onToggle,
  onRemove,
}: {
  files: LoadedFile[];
  candidateName: string | null;
  onPick: (name: string) => void;
  onToggle: (name: string) => void;
  onRemove: (name: string) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const candidateIndex = files.findIndex((f) => f.name === candidateName);
  // A 440-file list is noise. Show the tail around the candidate until asked for the rest.
  const collapse = !expanded && files.length > 12;
  const shown = collapse ? files.slice(Math.max(0, candidateIndex - 9)) : files;
  const hidden = files.length - shown.length;

  return (
    <div className="filelist">
      {collapse && hidden > 0 && (
        <button className="linklike showall" type="button" onClick={() => setExpanded(true)}>
          Show {hidden} earlier migration{hidden === 1 ? "" : "s"}
        </button>
      )}
      <table>
        <thead>
          <tr>
            <th style={{ width: 34 }}></th>
            <th style={{ width: 74 }}>Version</th>
            <th>File</th>
            <th style={{ width: 90 }}>Statements</th>
            <th style={{ width: 90 }}>Role</th>
            <th style={{ width: 60 }}></th>
          </tr>
        </thead>
        <tbody>
          {shown.map((f) => {
            const isCandidate = f.name === candidateName;
            const isLater = candidateIndex >= 0 && files.indexOf(f) > candidateIndex;
            return (
              <tr key={f.name} className={`${isCandidate ? "is-candidate" : ""} ${f.included ? "" : "excluded"}`}>
                <td>
                  <input
                    type="checkbox"
                    className="check"
                    checked={f.included}
                    onChange={() => onToggle(f.name)}
                    aria-label={`Include ${f.name}`}
                  />
                </td>
                <td>
                  {f.parsed.label ? (
                    <code>{f.parsed.label}</code>
                  ) : (
                    <span className="badge MEDIUM" title="Not a Flyway V/R filename — replayed last, in load order">
                      ?
                    </span>
                  )}
                </td>
                <td className="fname">{f.name}</td>
                <td className="muted">{f.statements}</td>
                <td>
                  {!f.included ? (
                    <span className="muted">skipped</span>
                  ) : isCandidate ? (
                    <span className="badge HIGH">candidate</span>
                  ) : isLater ? (
                    <span className="muted">newer</span>
                  ) : (
                    <span className="muted">baseline</span>
                  )}
                </td>
                <td className="rowactions">
                  {!isCandidate && f.included && (
                    <button className="linklike" type="button" onClick={() => onPick(f.name)} title="Review this file instead">
                      review
                    </button>
                  )}
                  <button className="linklike danger" type="button" onClick={() => onRemove(f.name)} title="Remove">
                    ×
                  </button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <p className="muted hint">
        Untick a file to skip it — useful for a migration the sandbox cannot run unchanged (one that needs
        an extension, a role, or data that only exists in production). Press <b>review</b> on any row to
        review that migration instead, against only what came before it.
      </p>
    </div>
  );
}

/* ─── the escape hatch ────────────────────────────────────────────────── */

function ManualEntry({
  onLoad,
  entitySource,
  setEntitySource,
}: {
  onLoad: (name: string, sql: string) => void;
  entitySource: string;
  setEntitySource: (v: string) => void;
}) {
  const [name, setName] = useState("V42__add_column.sql");
  const [sql, setSql] = useState("");

  return (
    <div className="advanced">
      <h3>Paste a migration</h3>
      <p className="muted">
        Adds one file to the list above. Prior migrations still come from the folder you loaded — paste
        them as files if you have them, since that is what lets a replay failure name the file it came
        from.
      </p>
      <div className="row">
        <div>
          <label>File name</label>
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="V42__add_column.sql" />
        </div>
      </div>
      <label>SQL</label>
      <textarea value={sql} onChange={(e) => setSql(e.target.value)} placeholder="ALTER TABLE …" />
      <div className="actions">
        <button
          className="secondary"
          type="button"
          disabled={!sql.trim() || !name.trim()}
          onClick={() => {
            onLoad(name.trim(), sql);
            setSql("");
          }}
        >
          Add to list
        </button>
      </div>

      <h3>JPA entities (optional)</h3>
      <p className="muted">
        Java entity source or a JSON mapping spec. Enables the drift check: does Hibernate still validate
        against the schema this migration produces?
      </p>
      <textarea
        style={{ minHeight: 90 }}
        value={entitySource}
        onChange={(e) => setEntitySource(e.target.value)}
        placeholder="@Entity public class Shipment { … }"
      />
    </div>
  );
}

/* ─── recent reviews ──────────────────────────────────────────────────── */

function RecentReviews({ reviews }: { reviews: Review[] }) {
  if (reviews.length === 0) {
    return <p className="muted">No reviews yet.</p>;
  }
  return (
    <table>
      <thead>
        <tr>
          <th>When</th>
          <th>Migration</th>
          <th>Replayed</th>
          <th>Status</th>
          <th>Findings</th>
          <th>Sandbox</th>
        </tr>
      </thead>
      <tbody>
        {reviews.map((r) => (
          <tr key={r.id}>
            <td>
              <Link href={`/reviews/${r.id}`}>{new Date(r.created_at).toLocaleTimeString()}</Link>
            </td>
            <td className="fname">{r.filename || r.case_id || "—"}</td>
            <td className="muted">
              {r.baseline_file_count > 0 ? `${r.baseline_file_count} prior` : "—"}
            </td>
            <td>{r.status}</td>
            <td>
              {r.high_count > 0 && <span className="badge HIGH">{r.high_count}H</span>}{" "}
              {r.medium_count > 0 && <span className="badge MEDIUM">{r.medium_count}M</span>}{" "}
              {r.low_count > 0 && <span className="badge LOW">{r.low_count}L</span>}
              {r.status === "COMPLETED" && r.findings_count === 0 && <span className="muted">clean</span>}
            </td>
            <td>
              {r.sandbox_used ? (
                "yes"
              ) : r.sandbox_note ? (
                <span className="err" title={r.sandbox_note}>
                  no — see review
                </span>
              ) : (
                "—"
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
