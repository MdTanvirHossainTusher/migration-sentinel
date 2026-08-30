"use client";

import { useCallback, useMemo, useState } from "react";
import { basename, compareMigrations, countStatements, parseMigrationName, ParsedName } from "./flyway";

export type LoadedFile = {
  /** Stable key; the name as typed by the picker, path stripped. */
  name: string;
  sql: string;
  parsed: ParsedName;
  statements: number;
  /** Excluded files stay listed but are not sent — useful for a migration the sandbox can't run. */
  included: boolean;
};

export type HistorySplit = {
  /** The file being reviewed — by default the newest in the set. */
  candidate: LoadedFile | null;
  /** Everything before the candidate, in replay order. */
  baseline: LoadedFile[];
  /** Files after the candidate: loaded, listed, but not part of this review. */
  later: LoadedFile[];
};

const MAX_FILES = 5000;
const MAX_TOTAL_CHARS = 20_000_000;

export function useMigrationFiles() {
  const [files, setFiles] = useState<LoadedFile[]>([]);
  const [candidateName, setCandidateName] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const add = useCallback(async (incoming: File[]) => {
    setLoadError(null);
    const sqlFiles = incoming.filter((f) => /\.sql$/i.test(f.name));
    if (sqlFiles.length === 0) {
      setLoadError("No .sql files in that selection. Pick your db/migration folder.");
      return;
    }

    setLoading(true);
    try {
      const read = await Promise.all(
        sqlFiles.map(async (f) => {
          const sql = await f.text();
          const name = basename((f as File & { webkitRelativePath?: string }).webkitRelativePath || f.name);
          return {
            name,
            sql,
            parsed: parseMigrationName(name),
            statements: countStatements(sql),
            included: true,
          } satisfies LoadedFile;
        }),
      );

      setFiles((prev) => {
        // Re-dropping the same folder replaces rather than duplicates.
        const byName = new Map(prev.map((f) => [f.name, f]));
        for (const f of read) byName.set(f.name, f);
        const merged = [...byName.values()].sort((a, b) => compareMigrations(a.parsed, b.parsed));

        if (merged.length > MAX_FILES) {
          setLoadError(`${merged.length} files is over the ${MAX_FILES}-file limit.`);
          return prev;
        }
        const total = merged.reduce((n, f) => n + f.sql.length, 0);
        if (total > MAX_TOTAL_CHARS) {
          setLoadError(
            `That history is ${(total / 1_000_000).toFixed(1)} MB, over the 20 MB limit. ` +
              `Exclude the oldest migrations, or squash them into one baseline file.`,
          );
          return prev;
        }
        return merged;
      });
    } catch (e) {
      setLoadError(`Could not read those files: ${(e as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, []);

  const remove = useCallback((name: string) => {
    setFiles((prev) => prev.filter((f) => f.name !== name));
    setCandidateName((c) => (c === name ? null : c));
  }, []);

  const toggle = useCallback((name: string) => {
    setFiles((prev) => prev.map((f) => (f.name === name ? { ...f, included: !f.included } : f)));
  }, []);

  const clear = useCallback(() => {
    setFiles([]);
    setCandidateName(null);
    setLoadError(null);
  }, []);

  /**
   * Split the loaded set into "already in production" and "the one under review". The
   * candidate defaults to the newest file, which is what a PR adds; picking any other file
   * re-reviews it against only the migrations that genuinely preceded it, never the ones
   * that came after.
   */
  const split = useMemo<HistorySplit>(() => {
    const included = files.filter((f) => f.included);
    if (included.length === 0) return { candidate: null, baseline: [], later: [] };

    const chosen = candidateName ? included.find((f) => f.name === candidateName) : undefined;
    const candidate = chosen ?? included[included.length - 1];
    const index = included.indexOf(candidate);
    return {
      candidate,
      baseline: included.slice(0, index),
      later: included.slice(index + 1),
    };
  }, [files, candidateName]);

  const totalChars = useMemo(() => files.reduce((n, f) => n + f.sql.length, 0), [files]);

  return {
    files,
    setFiles,
    add,
    remove,
    toggle,
    clear,
    split,
    totalChars,
    loading,
    loadError,
    candidateName,
    setCandidateName,
  };
}
