-- Firebase Cloud Messaging registration tokens, one per installed app.
--
-- Separate from push_subscriptions because the two are different shapes:
-- a browser subscription is an endpoint URL plus two encryption keys, an
-- FCM registration is a single opaque string and Google does the crypto.
-- Keeping them apart means a person can have the website notifying them on
-- a laptop and the app notifying them on a phone, independently.
CREATE TABLE device_tokens (
    id           UUID PRIMARY KEY,
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token        TEXT NOT NULL,
    platform     VARCHAR(16) NOT NULL DEFAULT 'android',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);

-- Google reissues a registration token whenever it feels like it, and the
-- app re-registers. One row per token, updated rather than accumulated.
CREATE UNIQUE INDEX idx_device_tokens_token ON device_tokens (token);
CREATE INDEX idx_device_tokens_user ON device_tokens (user_id);
