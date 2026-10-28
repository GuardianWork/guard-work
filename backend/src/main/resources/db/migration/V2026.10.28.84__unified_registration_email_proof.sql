-- Preserve every existing User's verification flag and eligibility; newly inserted Users opt in.
ALTER TABLE users ADD COLUMN recruiting_email_verification_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ALTER COLUMN recruiting_email_verification_required SET DEFAULT TRUE;

CREATE TABLE user_email_verification (
    user_id BIGINT PRIMARY KEY REFERENCES users(id),
    email VARCHAR(255) NOT NULL,
    secret_hash VARCHAR(64),
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT user_email_verification_expiry CHECK (expires_at > issued_at),
    CONSTRAINT user_email_verification_consumed CHECK (consumed_at IS NULL OR secret_hash IS NULL)
);
