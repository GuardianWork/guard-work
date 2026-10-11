ALTER TABLE company_invitations ADD COLUMN accepted_membership_id BIGINT REFERENCES company_memberships(id);
ALTER TABLE company_invitations ADD COLUMN decided_by_user_id BIGINT REFERENCES users(id);
ALTER TABLE company_invitations ADD CONSTRAINT ck_invitation_accepted_receipt
    CHECK (status <> 'ACCEPTED' OR (accepted_by_user_id IS NOT NULL AND accepted_membership_id IS NOT NULL AND decided_at IS NOT NULL));
CREATE TABLE notification_outbox (
    event_id UUID PRIMARY KEY,
    recipient_user_id BIGINT NOT NULL REFERENCES users(id),
    event_type VARCHAR(50) NOT NULL CHECK(event_type IN ('RECRUITER_ACCEPTED','MEMBERSHIP_REMOVED')),
    company_id BIGINT NOT NULL REFERENCES companies(id),
    company_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    error_category VARCHAR(50)
);
CREATE INDEX ix_notification_outbox_due ON notification_outbox(next_attempt_at) WHERE delivered_at IS NULL;
CREATE TABLE user_notifications (
    id BIGSERIAL PRIMARY KEY,
    source_event_id UUID NOT NULL UNIQUE REFERENCES notification_outbox(event_id),
    recipient_user_id BIGINT NOT NULL REFERENCES users(id),
    event_type VARCHAR(50) NOT NULL CHECK(event_type IN ('RECRUITER_ACCEPTED','MEMBERSHIP_REMOVED')),
    company_id BIGINT NOT NULL REFERENCES companies(id),
    company_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ
);
CREATE INDEX ix_user_notifications_recipient ON user_notifications(recipient_user_id,id DESC);
