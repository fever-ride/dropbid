CREATE TABLE IF NOT EXISTS payments (
    id                 VARCHAR(36)   PRIMARY KEY,
    auction_id         VARCHAR(36)   NOT NULL,
    user_id            VARCHAR(36)   NOT NULL,
    amount             BIGINT        NOT NULL,
    status             VARCHAR(20)   NOT NULL
                          CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED','REFUNDED')),
    fail_reason        TEXT,
    gateway_decision   VARCHAR(20),   -- stored once so retries are idempotent
    retry_count        INT           NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_payments_auction_id ON payments(auction_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_payments_auction_user ON payments(auction_id, user_id);
CREATE INDEX IF NOT EXISTS idx_payments_user_id    ON payments(user_id);
CREATE INDEX IF NOT EXISTS idx_payments_status     ON payments(status);
