-- Archiving: out of the inbox, but not destroyed. The row stays — with its
-- summary, to-dos and reply drafts — and simply stops appearing in the inbox
-- list, matching what removing the INBOX label does in Gmail itself.
ALTER TABLE emails ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;

-- The inbox list reads one mailbox's unarchived mail, newest first. Replaces
-- idx_emails_account_received (V5), which no longer matches the query.
DROP INDEX IF EXISTS idx_emails_account_received;
CREATE INDEX IF NOT EXISTS idx_emails_account_archived_received
    ON emails (mail_account_id, archived, received_at DESC);
