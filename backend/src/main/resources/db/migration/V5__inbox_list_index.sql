-- The inbox list always reads one mailbox, newest first. With separate
-- indexes on mail_account_id and received_at, Postgres has to collect every
-- row of the mailbox and sort it before returning a page of 25. This
-- composite index is already in that order, so it walks straight down it and
-- stops at the page size.
CREATE INDEX IF NOT EXISTS idx_emails_account_received
    ON emails (mail_account_id, received_at DESC);
