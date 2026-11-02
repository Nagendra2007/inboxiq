-- The one-time code an email is asking the reader to type in somewhere, so
-- a notification can show it and offer to copy it.
--
-- Short on purpose: anything longer than this is not a code someone types.
-- It is extracted by the analysis that already runs on every email, then
-- checked against the email text before being stored, because a model will
-- happily invent a plausible six digits.
ALTER TABLE email_analysis ADD COLUMN otp_code VARCHAR(16);
