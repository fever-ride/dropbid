ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_auction_id_key;

DROP INDEX IF EXISTS idx_payments_auction_user;
CREATE UNIQUE INDEX IF NOT EXISTS idx_payments_auction_user
    ON payments(auction_id, user_id);
