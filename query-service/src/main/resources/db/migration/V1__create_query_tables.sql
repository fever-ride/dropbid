-- Auction summary: one row per auction, updated by bid_placed and auction:closed events
CREATE TABLE IF NOT EXISTS auction_summary (
    auction_id       VARCHAR(36)  PRIMARY KEY,
    item_id          VARCHAR(36)  NOT NULL,
    shop_id          VARCHAR(36),
    seller_id        VARCHAR(36),
    status           VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    current_highest  BIGINT       NOT NULL DEFAULT 0,
    bid_count        BIGINT       NOT NULL DEFAULT 0,
    closed_at        TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_auction_summary_status ON auction_summary(status);
CREATE INDEX idx_auction_summary_seller ON auction_summary(seller_id);
CREATE INDEX idx_auction_summary_bid_count ON auction_summary(bid_count DESC);

-- Bid activity: one row per (auction, bidder) pair, updated by each bid_placed event
CREATE TABLE IF NOT EXISTS bid_activity (
    id               VARCHAR(36)  PRIMARY KEY,
    auction_id       VARCHAR(36)  NOT NULL,
    item_id          VARCHAR(36)  NOT NULL,
    bidder_id        VARCHAR(36)  NOT NULL,
    latest_amount    BIGINT       NOT NULL,
    bid_count        INT          NOT NULL DEFAULT 1,
    bid_status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    payment_status   VARCHAR(20),
    payment_id       VARCHAR(36),
    first_bid_at     TIMESTAMPTZ  NOT NULL,
    last_bid_at      TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_bid_activity_auction_bidder UNIQUE (auction_id, bidder_id)
);

CREATE INDEX idx_bid_activity_bidder   ON bid_activity(bidder_id, last_bid_at DESC);
CREATE INDEX idx_bid_activity_auction  ON bid_activity(auction_id);
CREATE INDEX idx_bid_activity_status   ON bid_activity(bid_status);
