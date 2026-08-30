-- A caller may pass its own LLM API key on a single review or evaluation request instead of
-- configuring one on the server. It is AES-GCM encrypted (see support/CryptoService) and
-- kept only until a worker has used it; it is never returned by the API and the log/audit
-- maskers strip it if it ever appears in text.

ALTER TABLE review_job     ADD COLUMN llm_api_key_encrypted text;
ALTER TABLE evaluation_run ADD COLUMN llm_api_key_encrypted text;
