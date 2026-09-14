-- 1) Server-side sessions (Spring Session JDBC, standard PostgreSQL schema).
--    Signing in now survives server restarts and redeploys; before this,
--    sessions lived in one JVM's memory and every restart signed everyone out.

CREATE TABLE spring_session (
    primary_id             CHAR(36)     NOT NULL,
    session_id             CHAR(36)     NOT NULL,
    creation_time          BIGINT       NOT NULL,
    last_access_time       BIGINT       NOT NULL,
    max_inactive_interval  INT          NOT NULL,
    expiry_time            BIGINT       NOT NULL,
    principal_name         VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id  CHAR(36)      NOT NULL,
    attribute_name      VARCHAR(200)  NOT NULL,
    attribute_bytes     BYTEA         NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);

-- 2) Gmail sync state, persisted per mailbox. last_history_id (from V1) is the
--    incremental-sync checkpoint; these record the outcome of each pass.

ALTER TABLE mail_accounts ADD COLUMN initial_sync_completed_at TIMESTAMPTZ;
ALTER TABLE mail_accounts ADD COLUMN last_sync_at TIMESTAMPTZ;
ALTER TABLE mail_accounts ADD COLUMN last_sync_error VARCHAR(500);
-- Google rejected the stored refresh token (access revoked or expired): the
-- user stays signed in to InboxIQ but must reconnect Gmail.
ALTER TABLE mail_accounts ADD COLUMN reauth_required BOOLEAN NOT NULL DEFAULT FALSE;

-- 3) How many times the AI has been asked to analyze an email, so failed
--    analyses are retried a bounded number of times rather than forever.

ALTER TABLE email_analysis ADD COLUMN ai_attempts INTEGER NOT NULL DEFAULT 0;

-- Failures from before this migration are not retried automatically (that
-- could mean hundreds of AI calls at once); "Re-analyze" still works on each.
-- 3 = app.ai.analysis-max-attempts.
UPDATE email_analysis SET ai_attempts = 3 WHERE analysis_status <> 'COMPLETED';
