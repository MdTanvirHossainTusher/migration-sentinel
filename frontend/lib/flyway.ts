// Flyway filename parsing, mirrored from the backend's FlywayVersion so the UI can show the
// history in the order it will actually be replayed.
//
// The whole point of this file is that string order is wrong: sorted as text, V10 comes
// before V2, so a project with more than nine migrations would be replayed into a schema
// that never existed. Versions are compared part by part as numbers.

export type MigrationKind = "versioned" | "repeatable" | "undo" | "unknown";

export type ParsedName = {
  kind: MigrationKind;
  parts: number[];
  description: string;
  /** Display label, e.g. "V182" or "R". */
  label: string;
};

const PATTERN = /^([VRU])([0-9]+(?:[._][0-9]+)*)?(?:__(.*?))?$/i;

export function parseMigrationName(filename: string): ParsedName {
  const base = basename(filename).replace(/\.sql$/i, "");
  const m = PATTERN.exec(base);
  if (!m) return { kind: "unknown", parts: [], description: base, label: "" };

  const prefix = m[1].toUpperCase();
  const version = m[2];
  const description = m[3] ?? "";
  const kind: MigrationKind = prefix === "R" ? "repeatable" : prefix === "U" ? "undo" : "versioned";

  // Flyway's shapes: V and U carry a version, R never does.
  const hasVersion = !!version;
  if (kind === "repeatable" ? hasVersion : !hasVersion) {
    return { kind: "unknown", parts: [], description: base, label: "" };
  }

  const parts = version ? version.split(/[._]/).map((p) => Number(p) || 0) : [];
  const label =
    kind === "versioned" ? `V${parts.join(".")}` : kind === "repeatable" ? "R" : `U${parts.join(".")}`;
  return { kind, parts, description, label };
}

const RANK: Record<MigrationKind, number> = { versioned: 0, repeatable: 1, undo: 2, unknown: 3 };

export function compareMigrations(a: ParsedName, b: ParsedName): number {
  if (RANK[a.kind] !== RANK[b.kind]) return RANK[a.kind] - RANK[b.kind];
  if (a.kind === "versioned") {
    const len = Math.max(a.parts.length, b.parts.length);
    for (let i = 0; i < len; i++) {
      const diff = (a.parts[i] ?? 0) - (b.parts[i] ?? 0);
      if (diff !== 0) return diff;
    }
    return 0;
  }
  return a.description.localeCompare(b.description);
}

export function basename(path: string): string {
  const normalized = path.replace(/\\/g, "/");
  const slash = normalized.lastIndexOf("/");
  return slash >= 0 ? normalized.slice(slash + 1) : normalized;
}

/** Rough statement count — enough to show the size of a file, not a real parser. */
export function countStatements(sql: string): number {
  const stripped = sql
    .replace(/--[^\n]*/g, "")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/'(?:[^']|'')*'/g, "''");
  return stripped.split(";").filter((s) => s.trim().length > 0).length;
}

export function formatBytes(chars: number): string {
  if (chars < 1024) return `${chars} B`;
  if (chars < 1024 * 1024) return `${(chars / 1024).toFixed(1)} KB`;
  return `${(chars / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * Guess the schema a project's migrations build into.
 *
 * A service whose Flyway config sets `schemas: identity` never says so in the SQL — Flyway
 * creates the schema at boot and the migrations just assume it. So we look for schemas the
 * files *reference* but never create themselves; that difference is exactly what the sandbox
 * has to create up front, or the replay dies partway through with "schema … does not exist".
 *
 * Only object-definition positions are scanned. `FROM x.y` and `JOIN x.y` look identical to
 * `alias.column`, so including them turns every query alias in the history into a "schema".
 */
const QUALIFIED_DDL = [
  /\bCREATE\s+(?:UNLOGGED\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?"?([A-Za-z_][A-Za-z0-9_$]*)"?\s*\./gi,
  /\bALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?(?:ONLY\s+)?"?([A-Za-z_][A-Za-z0-9_$]*)"?\s*\./gi,
  /\bCREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:CONCURRENTLY\s+)?(?:IF\s+NOT\s+EXISTS\s+)?\S*\s+ON\s+(?:ONLY\s+)?"?([A-Za-z_][A-Za-z0-9_$]*)"?\s*\./gi,
  /\bCREATE\s+(?:SEQUENCE|VIEW|MATERIALIZED\s+VIEW|TYPE)\s+(?:IF\s+NOT\s+EXISTS\s+)?"?([A-Za-z_][A-Za-z0-9_$]*)"?\s*\./gi,
  /\bINSERT\s+INTO\s+"?([A-Za-z_][A-Za-z0-9_$]*)"?\s*\./gi,
  /\bREFERENCES\s+"?([A-Za-z_][A-Za-z0-9_$]*)"?\s*\./gi,
];

const NOT_A_SCHEMA = new Set(["public", "pg_catalog", "information_schema", "pg_temp"]);

export function detectSchemas(files: { sql: string }[]): string[] {
  const created = new Set<string>();
  // Count files, not occurrences: a name used across the history is a schema, a name used
  // once is more likely a typo or a one-off.
  const referencedIn = new Map<string, number>();

  for (const f of files) {
    const sql = f.sql.replace(/--[^\n]*/g, "").replace(/\/\*[\s\S]*?\*\//g, "");

    for (const m of sql.matchAll(
      /\bCREATE\s+SCHEMA\s+(?:IF\s+NOT\s+EXISTS\s+)?"?([A-Za-z_][A-Za-z0-9_$]*)"?/gi,
    )) {
      created.add(m[1].toLowerCase());
    }

    const here = new Set<string>();
    for (const pattern of QUALIFIED_DDL) {
      for (const m of sql.matchAll(pattern)) {
        here.add(m[1].toLowerCase());
      }
    }
    for (const name of here) {
      referencedIn.set(name, (referencedIn.get(name) ?? 0) + 1);
    }
  }

  return [...referencedIn.entries()]
    .filter(([name]) => !created.has(name) && !NOT_A_SCHEMA.has(name))
    .sort((a, b) => b[1] - a[1])
    .map(([name]) => name);
}
