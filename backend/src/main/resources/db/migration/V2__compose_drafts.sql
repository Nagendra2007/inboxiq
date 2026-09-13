-- Supports composing a brand-new email (not a reply to an existing one)
-- through the same AI draft/adjust/confirm/send workflow. A compose draft
-- has no source email, so email_id becomes optional; ownership is instead
-- verified directly via mail_account_id, which every draft now carries
-- (backfilled below for existing reply drafts).

ALTER TABLE generated_replies ALTER COLUMN email_id DROP NOT NULL;

ALTER TABLE generated_replies ADD COLUMN mail_account_id UUID REFERENCES mail_accounts(id) ON DELETE CASCADE;
ALTER TABLE generated_replies ADD COLUMN to_address VARCHAR(320);
ALTER TABLE generated_replies ADD COLUMN draft_subject VARCHAR(998);

UPDATE generated_replies gr
SET mail_account_id = e.mail_account_id
FROM emails e
WHERE e.id = gr.email_id AND gr.mail_account_id IS NULL;

ALTER TABLE generated_replies ALTER COLUMN mail_account_id SET NOT NULL;

CREATE INDEX idx_generated_replies_mail_account_id ON generated_replies(mail_account_id);
