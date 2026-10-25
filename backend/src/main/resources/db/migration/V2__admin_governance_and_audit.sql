CREATE TABLE companies (
    id                           BIGSERIAL PRIMARY KEY,
    name                         VARCHAR(255) NOT NULL,
    tax_code                     VARCHAR(50)  NOT NULL UNIQUE,
    registration_certificate_url TEXT         NOT NULL,
    verification_status          VARCHAR(30)  NOT NULL DEFAULT 'PENDING'
        CHECK (verification_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    rejection_reason             TEXT,
    verified_by                  BIGINT REFERENCES users(id),
    verified_at                  TIMESTAMPTZ,
    is_banned                    BOOLEAN      NOT NULL DEFAULT false,
    version                      BIGINT       NOT NULL DEFAULT 0,
    created_at                   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_companies_verification_queue
    ON companies (verification_status, created_at);

CREATE TABLE audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    admin_id    BIGINT       NOT NULL REFERENCES users(id),
    action      VARCHAR(100) NOT NULL,
    target_type VARCHAR(50)  NOT NULL,
    target_id   VARCHAR(100) NOT NULL,
    old_payload JSONB,
    new_payload JSONB,
    reason      TEXT,
    ip_address  VARCHAR(45)  NOT NULL,
    user_agent  VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_admin_created
    ON audit_logs (admin_id, created_at DESC);

CREATE INDEX idx_audit_logs_target
    ON audit_logs (target_type, target_id);

CREATE OR REPLACE FUNCTION prevent_audit_logs_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs table is append-only: UPDATE, DELETE, and TRUNCATE are prohibited.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_protect_audit_logs
BEFORE UPDATE OR DELETE OR TRUNCATE ON audit_logs
FOR EACH STATEMENT
EXECUTE FUNCTION prevent_audit_logs_mutation();
