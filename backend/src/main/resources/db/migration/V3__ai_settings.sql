-- App-wide AI provider settings, chosen by the administrator in the app
-- (Settings -> AI provider) and applied to every user. There is at most one
-- row (id = 1); while it is absent, the AI_* environment variables apply.
-- The API key is AES-GCM encrypted by the application (TokenEncryptionService);
-- NULL means "use the key from the environment".

CREATE TABLE ai_settings (
    id                 INTEGER PRIMARY KEY CHECK (id = 1),
    provider           VARCHAR(32)  NOT NULL,
    base_url           VARCHAR(500),
    model              VARCHAR(128) NOT NULL,
    encrypted_api_key  TEXT,
    updated_by         VARCHAR(320),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
