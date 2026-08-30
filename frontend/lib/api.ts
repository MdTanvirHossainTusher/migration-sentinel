// All browser calls go through Next's /proxy rewrite to the Spring backend, so there is
// no CORS to configure and the same code works locally and in docker compose.

const BASE = "/proxy/api/v1";

export type ApiEnvelope<T> = {
  success: boolean;
  code?: string;
  message?: string;
  data: T;
  pagination?: Pagination;
  error?: { code: string; message: string; details?: unknown[] };
};

export type Pagination = {
  total_count: number;
  page: number;
  size: number;
  total_pages: number;
  has_next: boolean;
  has_prev: boolean;
};

export type ReviewMode =
  | "BASELINE_PROMPT"
  | "ANALYZER_READ_ONLY"
  | "ANALYZER_WITH_SANDBOX"
  | "ANALYZER_VERIFIED"
  | "ANALYZER_VERIFIER_SPLIT";

export type Severity = "HIGH" | "MEDIUM" | "LOW";
export type Verdict = "CONFIRMED" | "REJECTED" | "UNVERIFIED";

export type Review = {
  id: string;
  status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED";
  mode: ReviewMode;
  provider: string;
  filename?: string;
  case_id?: string;
  created_at: string;
  started_at?: string;
  finished_at?: string;
  duration_ms?: number;
  findings_count: number;
  tool_call_count: number;
  sandbox_used: boolean;
  /** Why the sandbox produced no measurements, when it produced none. */
  sandbox_note?: string;
  /** How many prior migration files were replayed before the candidate. */
  baseline_file_count: number;
  high_count: number;
  medium_count: number;
  low_count: number;
  error_message?: string;
};

export type Finding = {
  id: string;
  ordinal: number;
  rule_code: string;
  severity: Severity;
  title: string;
  target_object?: string;
  summary: string;
  evidence?: string;
  suggested_rewrite?: string;
  verdict: Verdict;
  analyzer_confidence?: number;
};

export type ToolCall = {
  id: string;
  agent_role: "BASELINE" | "ANALYZER" | "VERIFIER";
  step_no: number;
  tool_name: string;
  arguments_json?: string;
  result_json?: string;
  duration_ms: number;
  ok: boolean;
};

export type ReviewReport = {
  review: Review;
  report_markdown?: string;
  /** Presigned URL to download report.md when object storage is enabled. */
  report_download_url?: string;
  findings: Finding[];
  trajectory: ToolCall[];
};

export type EvaluationRun = {
  id: string;
  status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED";
  mode: ReviewMode;
  provider: string;
  corpus_label?: string;
  total_cases: number;
  completed_cases: number;
  true_positives: number;
  false_positives: number;
  false_negatives: number;
  precision?: number;
  recall?: number;
  f1?: number;
  false_positive_rate?: number;
  mean_duration_ms?: number;
  created_at: string;
  finished_at?: string;
  error_message?: string;
};

export type EvaluationCaseResult = {
  case_id: string;
  expected_count: number;
  reported_count: number;
  true_positives: number;
  false_positives: number;
  false_negatives: number;
  passed: boolean;
  notes?: string;
  review_job_id?: string;
};

export type EvaluationDetail = { run: EvaluationRun; cases: EvaluationCaseResult[] };

export type EvaluationCaseMeta = {
  id: string;
  title: string;
  description: string;
  hard: boolean;
  must_be_clean: boolean;
  expected: { rule_code: string; target_object: string }[];
};

export type Health = {
  status: string;
  version: string;
  default_provider: string;
  available_providers: string[];
  docker_available: boolean;
  evaluation_case_count: number;
  timestamp: string;
};

/**
 * Turn any error string into a headline + optional detail block. The backend already
 * flattens provider errors ("Gemini API error (404): This model is no longer available…"),
 * but if a raw JSON body ever leaks through — from an upstream service, say — this pulls the
 * human sentence out of `error.message` and keeps the rest for a collapsible "details".
 */
export function parseApiError(raw: string): { message: string; details?: string } {
  if (!raw) return { message: "Something went wrong." };
  const brace = raw.indexOf("{");
  if (brace === -1) return { message: raw };

  const prefix = raw.slice(0, brace).trim().replace(/[:\-\s]+$/, "");
  try {
    const json = JSON.parse(raw.slice(brace));
    const err = json.error ?? json;
    const message = err.message ?? err.msg ?? raw;
    const rest: Record<string, unknown> = { ...err };
    delete rest.message;
    delete rest.msg;
    const details = Object.keys(rest).length ? JSON.stringify(rest, null, 2) : undefined;
    return { message: prefix ? `${prefix}: ${message}` : message, details };
  } catch {
    return { message: raw };
  }
}

async function call<T>(path: string, init?: RequestInit): Promise<ApiEnvelope<T>> {
  const res = await fetch(`${BASE}${path}`, {
    ...init,
    headers: { "Content-Type": "application/json", ...(init?.headers || {}) },
    cache: "no-store",
  });
  const raw = await res.text();
  let body: ApiEnvelope<T> | null = null;
  try {
    body = raw ? (JSON.parse(raw) as ApiEnvelope<T>) : null;
  } catch {
    // Non-JSON body — usually the proxy or backend is down and returned an HTML/text error page.
    throw new Error(
      `API returned a non-JSON response (${res.status}). Is the backend up? ${raw.slice(0, 120)}`.trim(),
    );
  }
  if (!res.ok || !body || body.success === false) {
    throw new Error(body?.error?.message || body?.message || `Request failed (${res.status})`);
  }
  return body;
}

export const api = {
  health: () => call<Health>("/health").then((r) => r.data),

  submitReview: (payload: {
    filename?: string;
    migrationSql: string;
    /** The project's prior migrations, one entry per file. The server orders them. */
    baselineMigrations?: { filename: string; sql: string }[];
    baselineSql?: string;
    /** The project's Flyway `schemas` — created in the sandbox before the replay. */
    targetSchema?: string;
    seedSql?: string;
    entitySource?: string;
    mode: ReviewMode;
    provider: string;
    /** Optional per-request API key; used for this review only, never stored client-side. */
    llmApiKey?: string;
  }) =>
    call<Review>("/reviews", {
      method: "POST",
      body: JSON.stringify({
        filename: payload.filename || undefined,
        migration_sql: payload.migrationSql,
        baseline_migrations: payload.baselineMigrations?.length ? payload.baselineMigrations : undefined,
        baseline_sql: payload.baselineSql || undefined,
        target_schema: payload.targetSchema || undefined,
        seed_sql: payload.seedSql || undefined,
        entity_source: payload.entitySource || undefined,
        mode: payload.mode,
        provider: payload.provider,
        llm_api_key: payload.llmApiKey || undefined,
      }),
    }).then((r) => r.data),

  getReview: (id: string) => call<Review>(`/reviews/${id}`).then((r) => r.data),
  getReport: (id: string) => call<ReviewReport>(`/reviews/${id}/report`).then((r) => r.data),
  listReviews: () => call<Review[]>("/reviews?size=30").then((r) => r.data),

  applyRewrite: (payload: {
    findingId: string;
    targetFilename: string;
    approvedBy: string;
    note?: string;
    confirm: boolean;
  }) =>
    call("/reviews/rewrites/apply", {
      method: "POST",
      body: JSON.stringify({
        finding_id: payload.findingId,
        target_filename: payload.targetFilename,
        approved_by: payload.approvedBy,
        note: payload.note || undefined,
        confirm: payload.confirm,
      }),
    }),

  runEvaluation: (payload: {
    mode: ReviewMode;
    provider: string;
    corpusLabel?: string;
    llmApiKey?: string;
  }) =>
    call<EvaluationRun>("/evaluations", {
      method: "POST",
      body: JSON.stringify({
        mode: payload.mode,
        provider: payload.provider,
        corpus_label: payload.corpusLabel || undefined,
        llm_api_key: payload.llmApiKey || undefined,
      }),
    }).then((r) => r.data),
  getEvaluation: (id: string) => call<EvaluationDetail>(`/evaluations/${id}`).then((r) => r.data),
  listEvaluations: () => call<EvaluationRun[]>("/evaluations?size=30").then((r) => r.data),
  listCases: () => call<EvaluationCaseMeta[]>("/evaluations/cases").then((r) => r.data),
};
