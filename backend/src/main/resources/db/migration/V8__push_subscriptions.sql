-- Browser push endpoints, one row per installed app / browser profile. A
-- person legitimately has several: phone, laptop, work machine.
--
-- The endpoint is a URL the browser's push service gave us, and p256dh/auth
-- are the keys it will decrypt the payload with. They are not secrets of
-- ours and are useless without the private VAPID key, which stays in config.
CREATE TABLE push_subscriptions (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    endpoint    TEXT NOT NULL,
    p256dh      VARCHAR(255) NOT NULL,
    auth        VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);

-- One row per endpoint: re-subscribing the same browser must update rather
-- than accumulate, or one person ends up with twelve copies of every alert.
CREATE UNIQUE INDEX idx_push_subscriptions_endpoint ON push_subscriptions (endpoint);
CREATE INDEX idx_push_subscriptions_user ON push_subscriptions (user_id);
