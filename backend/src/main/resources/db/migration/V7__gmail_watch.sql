-- Gmail push notifications: a users.watch() registration expires after seven
-- days, so the renewal job needs to know when each one lapses. Null means no
-- watch has ever been registered for this mailbox.
ALTER TABLE mail_accounts ADD COLUMN gmail_watch_expiration TIMESTAMPTZ;
