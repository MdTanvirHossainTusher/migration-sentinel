"use client";

import { useMemo, useState } from "react";
import hljs from "highlight.js/lib/core";
import sql from "highlight.js/lib/languages/sql";
import json from "highlight.js/lib/languages/json";
import "highlight.js/styles/github.css";

hljs.registerLanguage("sql", sql);
hljs.registerLanguage("json", json);

function escapeHtml(s: string) {
  return s.replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c] as string));
}

type Lang = "sql" | "json" | "plaintext";

export function CodeBlock({
  code,
  language = "sql",
  label,
  copy = true,
}: {
  code?: string | null;
  language?: Lang;
  label?: string;
  copy?: boolean;
}) {
  const [copied, setCopied] = useState(false);
  const text = (code ?? "").trim();

  const html = useMemo(() => {
    if (!text) return "";
    if (language === "plaintext") return escapeHtml(text);
    try {
      return hljs.highlight(text, { language }).value;
    } catch {
      return escapeHtml(text);
    }
  }, [text, language]);

  if (!text) return null;

  const onCopy = () => {
    navigator.clipboard?.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1200);
    });
  };

  return (
    <div className="code">
      {(label || copy) && (
        <div className="code-bar">
          <span className="code-lang">{label || language}</span>
          {copy && (
            <button type="button" className="code-copy" onClick={onCopy}>
              {copied ? "copied" : "copy"}
            </button>
          )}
        </div>
      )}
      <pre>
        <code className="hljs" dangerouslySetInnerHTML={{ __html: html }} />
      </pre>
    </div>
  );
}
