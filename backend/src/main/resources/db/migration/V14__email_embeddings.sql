-- Semantic search over a mailbox.
--
-- Stored as raw bytes rather than a pgvector column on purpose. The
-- extension would be faster at scale, but it has to exist before the
-- column can be created, which makes it a migration that fails at startup
-- on any database that does not have it — including the one the tests run
-- against. One mailbox's vectors fit comfortably in memory, so the
-- similarity is computed in Java against a bounded candidate set, and this
-- table is only storage.
--
-- The model is recorded with each row: changing models changes what the
-- numbers mean, and comparing vectors from two models silently produces
-- nonsense rather than an error.
CREATE TABLE email_embeddings (
    email_id   UUID PRIMARY KEY REFERENCES emails(id) ON DELETE CASCADE,
    model      VARCHAR(100) NOT NULL,
    dimensions INTEGER NOT NULL,
    vector     BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_email_embeddings_model ON email_embeddings(model);
