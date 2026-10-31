-- Voluntary account closure is independent of Staff enforcement.
ALTER TABLE users ADD COLUMN self_service_state VARCHAR(30) NOT NULL DEFAULT 'ACTIVE'
 CHECK (self_service_state IN ('ACTIVE','DEACTIVATED','DELETION_PENDING'));
ALTER TABLE users ADD COLUMN prior_candidate_visibility BOOLEAN;
CREATE TABLE account_action_proofs (
 id BIGSERIAL PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES users(id),
 purpose VARCHAR(30) NOT NULL CHECK (purpose IN ('DEACTIVATE','DELETE','REACTIVATE','CANCEL_DELETION')),
 email VARCHAR(255) NOT NULL,
 secret_hash VARCHAR(64),
 issued_at TIMESTAMPTZ NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > issued_at),
 failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
 UNIQUE(user_id,purpose)
);
CREATE TABLE account_deletion_requests (
 id BIGSERIAL PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES users(id),
 requested_at TIMESTAMPTZ NOT NULL,
 cancelled_at TIMESTAMPTZ CHECK (cancelled_at IS NULL OR cancelled_at >= requested_at)
);
CREATE UNIQUE INDEX uq_pending_account_deletion ON account_deletion_requests(user_id) WHERE cancelled_at IS NULL;
-- Account names win legacy conflicts; historical submitted documents are untouched.
ALTER TABLE candidate_profiles DROP COLUMN full_name;
