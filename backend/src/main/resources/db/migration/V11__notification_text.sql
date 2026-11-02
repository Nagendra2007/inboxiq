-- What a notification should say about an email, written by the analysis
-- for a lock screen rather than cut down from the summary. Nullable: older
-- rows have none, and the summary stands in for them.
ALTER TABLE email_analysis ADD COLUMN notification_text VARCHAR(160);
