-- V3: Replace aggregated summary tables (auction_summary, bid_activity) with
--     three normalised tables: auction, bid, auction_winner.
--
-- Key changes:
--   auction     — structural fields + denormalised counters (bidCount, currentHighest)
--   bid         — append-only; bidId PK provides idempotency without aggregation state
--   auction_winner — written once at auction close; paymentStatus lives here

DROP TABLE IF EXISTS bid_activity;
DROP TABLE IF EXISTS auction_summary;

-- ── auction ──────────────────────────────────────────────────────────────────

CREATE TABLE auction (
    auction_id       VARCHAR(36)  PRIMARY KEY,
    item_id          VARCHAR(36)  NOT NULL,
    shop_id          VARCHAR(36),
    seller_id        VARCHAR(36),
    status           VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    starting_bid     BIGINT       NOT NULL DEFAULT 0,
    start_time       VARCHAR(50),
    end_time         VARCHAR(50),
    quantity         BIGINT,
    bid_count        BIGINT       NOT NULL DEFAULT 0,
    current_highest  BIGINT       NOT NULL DEFAULT 0,
    closed_at        TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_auction_status           ON auction(status);
CREATE INDEX idx_auction_seller           ON auction(seller_id);
CREATE INDEX idx_auction_seller_status    ON auction(seller_id, status);
CREATE INDEX idx_auction_status_bidcount  ON auction(status, bid_count  DESC);
CREATE INDEX idx_auction_status_highest   ON auction(status, current_highest DESC);
CREATE INDEX idx_auction_status_updatedat ON auction(status, updated_at  DESC);

-- ── bid ───────────────────────────────────────────────────────────────────────
-- Append-only. bid_id is the natural idempotency key supplied by the
-- auction service; inserting the same bid_id twice is a no-op at the
-- application level (existsById check) and would also fail at the DB level.

CREATE TABLE bid (
    bid_id      VARCHAR(36)  PRIMARY KEY,
    auction_id  VARCHAR(36)  NOT NULL,
    bidder_id   VARCHAR(36)  NOT NULL,
    item_id     VARCHAR(36)  NOT NULL,
    amount      BIGINT       NOT NULL,
    bid_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_bid_auction        ON bid(auction_id);
CREATE INDEX idx_bid_bidder         ON bid(bidder_id);
CREATE INDEX idx_bid_bidder_bidat   ON bid(bidder_id, bid_at DESC);
CREATE INDEX idx_bid_auction_amount ON bid(auction_id, amount DESC);

-- ── auction_winner ────────────────────────────────────────────────────────────
-- One row per winner per auction. The unique constraint drives idempotency
-- in AuctionClosedConsumer: re-inserting the same (auction_id, bidder_id)
-- pair raises a constraint violation that the consumer catches and ignores.

CREATE TABLE auction_winner (
    id              VARCHAR(36)  PRIMARY KEY,
    auction_id      VARCHAR(36)  NOT NULL,
    bidder_id       VARCHAR(36)  NOT NULL,
    amount          BIGINT       NOT NULL,
    payment_status  VARCHAR(20),
    payment_id      VARCHAR(36),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_auction_winner_auction_bidder UNIQUE (auction_id, bidder_id)
);

CREATE INDEX idx_aw_auction ON auction_winner(auction_id);
CREATE INDEX idx_aw_bidder  ON auction_winner(bidder_id);
