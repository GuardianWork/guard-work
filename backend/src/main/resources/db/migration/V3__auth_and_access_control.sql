ALTER TABLE users
    ADD COLUMN role VARCHAR(30) NOT NULL DEFAULT 'USER',
    ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE users
    ADD CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN')),
    ADD CONSTRAINT users_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'));

CREATE INDEX idx_users_role_status ON users (role, status);

CREATE TABLE refresh_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    family_id   VARCHAR(36)  NOT NULL,
    is_revoked  BOOLEAN      NOT NULL DEFAULT false,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ,
    user_agent  VARCHAR(255),
    ip_address  VARCHAR(45)  NOT NULL
);

CREATE INDEX idx_refresh_tokens_lookup ON refresh_tokens (token_hash, is_revoked, expires_at);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);

CREATE TABLE password_reset_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    is_consumed BOOLEAN      NOT NULL DEFAULT false,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_password_reset_lookup ON password_reset_tokens (token_hash, is_consumed, expires_at);
