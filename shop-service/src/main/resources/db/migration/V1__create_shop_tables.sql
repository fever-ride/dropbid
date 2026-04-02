CREATE TABLE IF NOT EXISTS seller_profiles (
    id           VARCHAR(36)    PRIMARY KEY,
    owner_id     VARCHAR(36)    NOT NULL UNIQUE,
    name         VARCHAR(255)   NOT NULL,
    bio          TEXT,
    rating       NUMERIC(3,2)   NOT NULL DEFAULT 0,
    verified     BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_seller_profiles_owner_id ON seller_profiles(owner_id);

CREATE TABLE IF NOT EXISTS collectible_items (
    id                     VARCHAR(36)    PRIMARY KEY,
    shop_id                VARCHAR(36)    NOT NULL REFERENCES seller_profiles(id) ON DELETE CASCADE,
    title                  VARCHAR(255)   NOT NULL,
    description            TEXT,
    series                 VARCHAR(255),
    edition                VARCHAR(255),
    condition              VARCHAR(20)    NOT NULL CHECK (condition IN ('NEW','LIKE_NEW','GOOD','FAIR')),
    original_retail_price  BIGINT         NOT NULL DEFAULT 0,
    estimated_market_value BIGINT         NOT NULL DEFAULT 0,
    image_url              TEXT,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_items_shop_id ON collectible_items(shop_id);
