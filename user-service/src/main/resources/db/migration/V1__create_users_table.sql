CREATE TABLE IF NOT EXISTS users (
    id           VARCHAR(36)  PRIMARY KEY,
    email        VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    username     VARCHAR(100) NOT NULL,
    role         VARCHAR(20)  NOT NULL CHECK (role IN ('BUYER', 'SELLER')),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
