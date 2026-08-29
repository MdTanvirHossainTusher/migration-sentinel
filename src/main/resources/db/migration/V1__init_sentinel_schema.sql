-- Migration Sentinel — application metadata schema.
-- This is the reviewer's OWN bookkeeping database (jobs, findings, trajectories,
-- evaluation runs). It is NOT the database migrations are reviewed against — that
-- one is a disposable Testcontainers Postgres, see docs/SAFETY_MODEL.md.

CREATE TABLE review_job (
    id                uuid PRIMARY KEY,
    created_at        timestamp with time zone NOT NULL,
    updated_at        timestamp with time zone NOT NULL,
    status            varchar(32)  NOT NULL,
    mode              varchar(32)  NOT NULL,
    migration_filename varchar(255),
    migration_sql     text         NOT NULL,
    baseline_sql      text,
    seed_sql          text,
    entity_source     text,
    llm_provider      varchar(32),
    case_id           varchar(64),
    report_markdown   text,
    error_message     text,
    started_at        timestamp with time zone,
    finished_at       timestamp with time zone,
    duration_ms       bigint,
    tool_call_count   integer      NOT NULL DEFAULT 0,
    findings_count    integer      NOT NULL DEFAULT 0,
    sandbox_used      boolean      NOT NULL DEFAULT false
);
CREATE INDEX ix_review_job_status     ON review_job (status);
CREATE INDEX ix_review_job_created_at ON review_job (created_at);

CREATE TABLE finding (
    id                  uuid PRIMARY KEY,
    created_at          timestamp with time zone NOT NULL,
    updated_at          timestamp with time zone NOT NULL,
    review_job_id       uuid NOT NULL,
    ordinal             integer NOT NULL,
    rule_code           varchar(48) NOT NULL,
    severity            varchar(16) NOT NULL,
    title               varchar(255) NOT NULL,
    target_object       varchar(255),
    summary             text NOT NULL,
    evidence            text,
    suggested_rewrite   text,
    verdict             varchar(16) NOT NULL,
    analyzer_confidence double precision,
    CONSTRAINT fk_finding_review_job FOREIGN KEY (review_job_id) REFERENCES review_job (id)
);
CREATE INDEX ix_finding_review_job_id ON finding (review_job_id);
CREATE INDEX ix_finding_rule_code     ON finding (rule_code);

CREATE TABLE tool_call (
    id             uuid PRIMARY KEY,
    created_at     timestamp with time zone NOT NULL,
    updated_at     timestamp with time zone NOT NULL,
    review_job_id  uuid NOT NULL,
    agent_role     varchar(16) NOT NULL,
    step_no        integer NOT NULL,
    tool_name      varchar(64) NOT NULL,
    arguments_json text,
    result_json    text,
    duration_ms    bigint NOT NULL DEFAULT 0,
    ok             boolean NOT NULL DEFAULT true,
    CONSTRAINT fk_tool_call_review_job FOREIGN KEY (review_job_id) REFERENCES review_job (id)
);
CREATE INDEX ix_tool_call_review_job_id ON tool_call (review_job_id);

CREATE TABLE evaluation_run (
    id                  uuid PRIMARY KEY,
    created_at          timestamp with time zone NOT NULL,
    updated_at          timestamp with time zone NOT NULL,
    status              varchar(32) NOT NULL,
    mode                varchar(32) NOT NULL,
    llm_provider        varchar(32),
    corpus_label        varchar(64),
    total_cases         integer NOT NULL DEFAULT 0,
    completed_cases     integer NOT NULL DEFAULT 0,
    true_positives      integer NOT NULL DEFAULT 0,
    false_positives     integer NOT NULL DEFAULT 0,
    false_negatives     integer NOT NULL DEFAULT 0,
    precision           double precision,
    recall              double precision,
    f1                  double precision,
    false_positive_rate double precision,
    mean_duration_ms    bigint,
    error_message       text,
    finished_at         timestamp with time zone
);
CREATE INDEX ix_evaluation_run_created_at ON evaluation_run (created_at);

CREATE TABLE evaluation_case_result (
    id                uuid PRIMARY KEY,
    created_at        timestamp with time zone NOT NULL,
    updated_at        timestamp with time zone NOT NULL,
    evaluation_run_id uuid NOT NULL,
    case_id           varchar(64) NOT NULL,
    review_job_id     uuid,
    expected_count    integer NOT NULL,
    reported_count    integer NOT NULL,
    true_positives    integer NOT NULL,
    false_positives   integer NOT NULL,
    false_negatives   integer NOT NULL,
    passed            boolean NOT NULL,
    notes             text,
    CONSTRAINT fk_eval_case_result_run FOREIGN KEY (evaluation_run_id) REFERENCES evaluation_run (id)
);
CREATE INDEX ix_eval_case_result_run_id ON evaluation_case_result (evaluation_run_id);

CREATE TABLE approval_record (
    id            uuid PRIMARY KEY,
    created_at    timestamp with time zone NOT NULL,
    updated_at    timestamp with time zone NOT NULL,
    review_job_id uuid NOT NULL,
    finding_id    uuid,
    action        varchar(32) NOT NULL,
    approved_by   varchar(255) NOT NULL,
    target_path   varchar(1024) NOT NULL,
    applied       boolean NOT NULL,
    note          text
);
CREATE INDEX ix_approval_record_review_job_id ON approval_record (review_job_id);
