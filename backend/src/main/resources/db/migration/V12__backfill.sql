-- Importing a mailbox's history.
--
-- The first sync deliberately takes only the newest handful of messages,
-- so that signing in is fast. Everything older has to be fetched
-- separately, in the background, over many pages — and resumably, because
-- a mailbox of ten thousand messages will outlive a deploy.
ALTER TABLE mail_accounts ADD COLUMN backfill_page_token  TEXT;
ALTER TABLE mail_accounts ADD COLUMN backfill_query       VARCHAR(200);
ALTER TABLE mail_accounts ADD COLUMN backfill_started_at  TIMESTAMPTZ;
ALTER TABLE mail_accounts ADD COLUMN backfill_finished_at TIMESTAMPTZ;
ALTER TABLE mail_accounts ADD COLUMN backfill_stored      INTEGER NOT NULL DEFAULT 0;

-- Imported mail is stored without being analysed: one AI call per message
-- across a whole mailbox is an expense nobody asked for, and most of those
-- messages will never be read. DEFERRED says "stored, deliberately not
-- queued" — distinct from PENDING, which the resume job picks up.
CREATE INDEX idx_email_analysis_deferred ON email_analysis(analysis_status)
    WHERE analysis_status = 'DEFERRED';
