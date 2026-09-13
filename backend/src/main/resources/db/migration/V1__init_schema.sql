-- InboxIQ initial schema.
-- IDs are UUIDs assigned by the application (Hibernate GenerationType.UUID),
-- so no server-side default generator is required here.

CREATE TABLE users (
    id          UUID PRIMARY KEY,
    email       VARCHAR(320) NOT NULL UNIQUE,
    name        VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE mail_accounts (
    id                        UUID PRIMARY KEY,
    user_id                   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider                  VARCHAR(32) NOT NULL,
    provider_email            VARCHAR(320) NOT NULL,
    encrypted_access_token    TEXT,
    encrypted_refresh_token   TEXT,
    token_expiry              TIMESTAMPTZ,
    last_history_id           VARCHAR(64),
    active                    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_mail_accounts_user_id ON mail_accounts(user_id);
CREATE UNIQUE INDEX idx_mail_accounts_user_provider ON mail_accounts(user_id, provider);

CREATE TABLE emails (
    id                        UUID PRIMARY KEY,
    mail_account_id           UUID NOT NULL REFERENCES mail_accounts(id) ON DELETE CASCADE,
    provider_message_id       VARCHAR(128) NOT NULL,
    thread_id                 VARCHAR(128),
    sender                    VARCHAR(500),
    recipient                 TEXT,
    cc_recipient              TEXT,
    subject                   VARCHAR(998),
    snippet                   VARCHAR(500),
    body_text                 TEXT,
    body_html                 TEXT,
    attachments_metadata      TEXT,
    has_attachments           BOOLEAN NOT NULL DEFAULT FALSE,
    received_at               TIMESTAMPTZ,
    is_read                   BOOLEAN NOT NULL DEFAULT FALSE,
    body_purged               BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_emails_account_message UNIQUE (mail_account_id, provider_message_id)
);

CREATE INDEX idx_emails_mail_account_id ON emails(mail_account_id);
CREATE INDEX idx_emails_thread_id ON emails(thread_id);
CREATE INDEX idx_emails_received_at ON emails(received_at DESC);
CREATE INDEX idx_emails_is_read ON emails(is_read);

CREATE TABLE email_analysis (
    id                  UUID PRIMARY KEY,
    email_id            UUID NOT NULL UNIQUE REFERENCES emails(id) ON DELETE CASCADE,
    summary             TEXT,
    key_points          TEXT,
    category            VARCHAR(32),
    priority            VARCHAR(16),
    priority_score      INTEGER,
    risk_score          INTEGER,
    risk_level          VARCHAR(16),
    risk_reasons        TEXT,
    requires_reply      BOOLEAN,
    action_required     BOOLEAN,
    important_dates     TEXT,
    analysis_status     VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    failure_reason      TEXT,
    ai_model_used       VARCHAR(128),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_email_analysis_category ON email_analysis(category);
CREATE INDEX idx_email_analysis_priority ON email_analysis(priority);
CREATE INDEX idx_email_analysis_risk_level ON email_analysis(risk_level);
CREATE INDEX idx_email_analysis_status ON email_analysis(analysis_status);

CREATE TABLE action_items (
    id           UUID PRIMARY KEY,
    email_id     UUID NOT NULL REFERENCES emails(id) ON DELETE CASCADE,
    description  VARCHAR(500) NOT NULL,
    deadline     DATE,
    completed    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_action_items_email_id ON action_items(email_id);
CREATE INDEX idx_action_items_deadline ON action_items(deadline);

CREATE TABLE generated_replies (
    id                  UUID PRIMARY KEY,
    email_id            UUID NOT NULL REFERENCES emails(id) ON DELETE CASCADE,
    user_prompt         VARCHAR(1000) NOT NULL,
    generated_content   TEXT NOT NULL,
    tone_adjustment     VARCHAR(32),
    sent                BOOLEAN NOT NULL DEFAULT FALSE,
    sent_at             TIMESTAMPTZ,
    gmail_message_id    VARCHAR(128),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_generated_replies_email_id ON generated_replies(email_id);
