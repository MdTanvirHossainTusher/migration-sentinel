-- Reviewing a candidate against the project's whole migration history, not one predecessor.
--
-- baseline_files_json keeps the ordered filenames so the UI can say what was replayed, and
-- sandbox_note carries the reason the sandbox produced nothing when a replay stops partway —
-- previously a half-applied baseline showed up as a clean, sandbox-less review with no
-- explanation anywhere.

ALTER TABLE review_job ADD COLUMN baseline_file_count integer NOT NULL DEFAULT 0;
ALTER TABLE review_job ADD COLUMN baseline_files_json text;
ALTER TABLE review_job ADD COLUMN sandbox_note text;

-- The schema a service's migrations build into (Flyway's spring.flyway.schemas). Without it
-- the sandbox replays into public, and any project with its own schema fails at the first
-- file that qualifies a name.
ALTER TABLE review_job ADD COLUMN target_schema varchar(63);
