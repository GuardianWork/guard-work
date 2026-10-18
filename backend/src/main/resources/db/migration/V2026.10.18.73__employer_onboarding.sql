CREATE TABLE employer_registrations (
    id UUID PRIMARY KEY,
    continuation_hash VARCHAR(64) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(200) NOT NULL,
    gender VARCHAR(30) NOT NULL CHECK (gender IN ('MALE','FEMALE','OTHER','UNDISCLOSED')),
    phone VARCHAR(20) NOT NULL,
    company_name VARCHAR(255) NOT NULL,
    work_address VARCHAR(1000) NOT NULL,
    email_secret_hash VARCHAR(64) NOT NULL,
    email_expires_at TIMESTAMPTZ NOT NULL,
    email_sent_at TIMESTAMPTZ NOT NULL,
    promoted_user_id BIGINT REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_employer_registration_email ON employer_registrations(email);

CREATE TABLE employer_profiles (
    user_id BIGINT PRIMARY KEY REFERENCES users(id),
    applicant BOOLEAN NOT NULL,
    full_name VARCHAR(200) NOT NULL,
    gender VARCHAR(30),
    email_verified_at TIMESTAMPTZ,
    phone VARCHAR(20),
    phone_verified_at TIMESTAMPTZ,
    company_name VARCHAR(255),
    work_address VARCHAR(1000),
    email_secret_hash VARCHAR(64),
    email_expires_at TIMESTAMPTZ,
    email_sent_at TIMESTAMPTZ,
    phone_secret_hash VARCHAR(64),
    phone_expires_at TIMESTAMPTZ,
    phone_sent_at TIMESTAMPTZ,
    phone_failures INTEGER NOT NULL DEFAULT 0,
    phone_delivered BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK (phone_verified_at IS NULL OR (phone IS NOT NULL AND email_verified_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_employer_verified_phone ON employer_profiles(phone) WHERE phone_verified_at IS NOT NULL;

CREATE TABLE company_verification_documents (
    id UUID PRIMARY KEY,
    uploader_id BIGINT NOT NULL REFERENCES users(id),
    filename VARCHAR(255) NOT NULL,
    media_type VARCHAR(100) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    content BYTEA NOT NULL CHECK (octet_length(content) BETWEEN 1 AND 10485760),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE company_verification_attempts (
    id UUID PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    submitter_id BIGINT NOT NULL REFERENCES users(id),
    company_name VARCHAR(255) NOT NULL,
    tax_code VARCHAR(50) NOT NULL,
    work_address VARCHAR(1000) NOT NULL,
    submitter_position VARCHAR(200) NOT NULL,
    representation VARCHAR(30) NOT NULL CHECK (representation IN ('LEGAL_REPRESENTATIVE','AUTHORIZED_REPRESENTATIVE')),
    declaration_text TEXT NOT NULL,
    declaration_version VARCHAR(30) NOT NULL,
    document_id UUID NOT NULL REFERENCES company_verification_documents(id),
    submitted_at TIMESTAMPTZ NOT NULL,
    outcome VARCHAR(30) CHECK (outcome IN ('VERIFIED','REJECTED')),
    rejection_reason TEXT,
    reviewer_id BIGINT REFERENCES users(id),
    reviewed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK ((outcome IS NULL AND reviewed_at IS NULL AND reviewer_id IS NULL)
        OR (outcome IS NOT NULL AND reviewed_at IS NOT NULL AND reviewer_id IS NOT NULL)),
    CHECK (outcome IS DISTINCT FROM 'REJECTED' OR length(trim(rejection_reason)) BETWEEN 10 AND 1000)
);
CREATE UNIQUE INDEX uq_company_pending_verification_attempt ON company_verification_attempts(company_id) WHERE outcome IS NULL;
CREATE INDEX idx_company_verification_attempt_history ON company_verification_attempts(company_id, submitted_at);

CREATE FUNCTION protect_company_verification_evidence() RETURNS trigger AS $$
BEGIN
    IF TG_OP <> 'UPDATE' THEN
        RAISE EXCEPTION 'Verification evidence cannot be deleted.' USING ERRCODE = '23514';
    END IF;
    IF OLD.outcome IS NOT NULL OR
       (to_jsonb(NEW) - ARRAY['outcome','rejection_reason','reviewer_id','reviewed_at','version']) IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['outcome','rejection_reason','reviewer_id','reviewed_at','version']) THEN
        RAISE EXCEPTION 'Submitted verification evidence is immutable.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_company_attempt_evidence BEFORE UPDATE OR DELETE ON company_verification_attempts
FOR EACH ROW EXECUTE FUNCTION protect_company_verification_evidence();
CREATE TRIGGER trg_company_attempt_no_truncate BEFORE TRUNCATE ON company_verification_attempts
FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_logs_mutation();
CREATE TRIGGER trg_company_document_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON company_verification_documents
FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_logs_mutation();

CREATE TABLE company_decision_deliveries (
    id UUID PRIMARY KEY REFERENCES company_verification_attempts(id),
    recipient_email VARCHAR(255) NOT NULL,
    company_name VARCHAR(255) NOT NULL,
    outcome VARCHAR(30) NOT NULL,
    rejection_reason TEXT,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0
);
