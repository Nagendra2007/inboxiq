-- Asking questions about your own mail.
--
-- Conversations are stored so a follow-up ("which of those is the latest?")
-- has something to refer back to. Scoped to the user, never to the mail
-- account, because the question belongs to the person.
CREATE TABLE chat_conversations (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title      VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_conversations_user ON chat_conversations(user_id, updated_at DESC);

CREATE TABLE chat_messages (
    id              UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES chat_conversations(id) ON DELETE CASCADE,
    role            VARCHAR(16) NOT NULL,
    content         TEXT NOT NULL,
    -- Which emails the answer was built from, as a JSON array of ids. Kept
    -- so reopening a conversation still shows what it was citing, and so an
    -- answer can never be read without the mail it came from.
    cited_email_ids TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_messages_conversation ON chat_messages(conversation_id, created_at);
