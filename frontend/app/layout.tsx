import "./globals.css";
import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = {
  title: "Migration Sentinel",
  description: "Agentic Flyway migration safety reviewer",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <header className="top">
          <div className="wrap">
            <h1>▚ MIGRATION SENTINEL</h1>
            <nav>
              <Link href="/">Review</Link>
              <Link href="/evaluations">Evaluation</Link>
              <a href="/proxy/swagger-ui.html" target="_blank" rel="noreferrer">
                API
              </a>
            </nav>
          </div>
        </header>
        <div className="wrap">{children}</div>
      </body>
    </html>
  );
}
