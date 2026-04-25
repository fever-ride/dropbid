CREATE TABLE IF NOT EXISTS user_lookup (
    user_id    VARCHAR(36)  PRIMARY KEY,
    username   VARCHAR(255) NOT NULL,
    role       VARCHAR(20)  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS item_lookup (
    item_id    VARCHAR(36)  PRIMARY KEY,
    shop_id    VARCHAR(36)  NOT NULL,
    title      VARCHAR(500) NOT NULL,
    image_url  TEXT,
    series     VARCHAR(255),
    condition  VARCHAR(50),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
