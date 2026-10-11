-- Only an authenticated Platform Administrator can record an approved review through the
-- restricted command. Evidence documents remain outside this database and its logs.
CREATE TABLE company_ownership_reviews (
    case_reference VARCHAR(255) PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    applicant_user_id BIGINT NOT NULL REFERENCES users(id),
    reviewer_user_id BIGINT NOT NULL REFERENCES users(id),
    evidence_type VARCHAR(80) NOT NULL CHECK (evidence_type IN
        ('LEGAL_REPRESENTATIVE_REGISTRATION', 'LEGAL_REPRESENTATIVE_AUTHORIZATION')),
    decision VARCHAR(30) NOT NULL CHECK (decision = 'APPROVED'),
    company_version BIGINT NOT NULL,
    verification_status VARCHAR(30) NOT NULL,
    company_banned BOOLEAN NOT NULL,
    company_updated_at TIMESTAMPTZ NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL,
    evidence_valid_until TIMESTAMPTZ,
    CHECK (evidence_valid_until IS NULL OR evidence_valid_until > reviewed_at)
);
CREATE INDEX idx_ownership_reviews_company ON company_ownership_reviews(company_id);
CREATE TRIGGER trg_protect_company_ownership_reviews
BEFORE UPDATE OR DELETE OR TRUNCATE ON company_ownership_reviews
FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_logs_mutation();

ALTER TABLE company_membership_audit_events
    ADD COLUMN reviewer_user_id BIGINT REFERENCES users(id),
    ADD COLUMN evidence_type VARCHAR(80),
    ADD COLUMN review_decision VARCHAR(30),
    ADD COLUMN reviewed_at TIMESTAMPTZ,
    ADD COLUMN assignment_outcome VARCHAR(30);
ALTER TABLE company_membership_audit_events ADD CONSTRAINT ck_reviewed_owner_audit CHECK (
    event_type <> 'LEGACY_OWNER_ASSIGNED' OR (
        review_case_reference IS NOT NULL AND reviewer_user_id IS NOT NULL
        AND reviewer_user_id <> actor_user_id AND evidence_type IS NOT NULL
        AND review_decision IS NOT NULL AND review_decision = 'APPROVED' AND reviewed_at IS NOT NULL
        AND assignment_outcome IS NOT NULL AND assignment_outcome = 'ASSIGNED'));
CREATE UNIQUE INDEX uq_legacy_assignment_case
    ON company_membership_audit_events(review_case_reference) WHERE event_type = 'LEGACY_OWNER_ASSIGNED';
