import { parseApiError } from "@/lib/api";

/** Renders an error headline, with any structured detail tucked into a collapsible block. */
export function ErrorBox({ error, as = "p" }: { error: string; as?: "p" | "pre" }) {
  const { message, details } = parseApiError(error);
  const Headline = as;
  return (
    <div className="err">
      <Headline className="err" style={{ margin: 0 }}>
        {message}
      </Headline>
      {details && (
        <details style={{ marginTop: 6 }}>
          <summary className="muted">details</summary>
          <pre style={{ whiteSpace: "pre-wrap", marginTop: 6 }}>{details}</pre>
        </details>
      )}
    </div>
  );
}
