CREATE TABLE company_invitations (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    recipient_email_normalized VARCHAR(255) NOT NULL CHECK (recipient_email_normalized = lower(btrim(recipient_email_normalized))),
    role VARCHAR(30) NOT NULL CHECK (role = 'RECRUITER'),
    invited_by_user_id BIGINT NOT NULL REFERENCES users(id),
    status VARCHAR(30) NOT NULL CHECK (status IN ('PENDING','ACCEPTED','DECLINED','EXPIRED','REVOKED')),
    secret_hash VARCHAR(64) NOT NULL UNIQUE CHECK (secret_hash ~ '^[0-9a-f]{64}$'),
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > issued_at),
    accepted_by_user_id BIGINT REFERENCES users(id),
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    delivery_status VARCHAR(30) NOT NULL CHECK (delivery_status IN ('UNKNOWN','PROVIDER_ACCEPTED','FAILED')),
    last_delivery_attempt_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_company_invitation_one_pending_per_email
    ON company_invitations(company_id,recipient_email_normalized) WHERE status='PENDING';
CREATE INDEX ix_company_invitation_owner_list ON company_invitations(company_id,status,id);
ALTER TABLE company_membership_audit_events ADD CONSTRAINT fk_membership_audit_invitation
    FOREIGN KEY(invitation_id) REFERENCES company_invitations(id);

-- Invited mailboxes need not have User accounts; never invent or look up a subject User.
ALTER TABLE company_membership_audit_events ALTER COLUMN subject_user_id DROP NOT NULL;
ALTER TABLE company_membership_audit_events ADD CONSTRAINT ck_membership_audit_subject
    CHECK (subject_user_id IS NOT NULL OR event_type IN ('INVITATION_ISSUED','INVITATION_RESENT','INVITATION_REVOKED'));
