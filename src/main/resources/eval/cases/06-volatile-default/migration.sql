ALTER TABLE events ADD COLUMN trace_id uuid NOT NULL DEFAULT gen_random_uuid();
