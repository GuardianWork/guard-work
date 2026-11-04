-- Reject migration when legacy decisions cannot be represented truthfully.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM applications WHERE status IN ('VIEWED', 'REJECTED')) THEN
        RAISE EXCEPTION 'Application review migration requires reconciliation of legacy VIEWED/REJECTED rows';
    END IF;
END;
$$;

ALTER TABLE applications DROP CONSTRAINT applications_status_check;
ALTER TABLE applications ADD CONSTRAINT applications_status_check
    CHECK (status IN ('SUBMITTED','UNDER_REVIEW','SHORTLISTED','REJECTED','WITHDRAWN'));

CREATE TABLE application_status_history (
    id BIGSERIAL PRIMARY KEY,
    application_id UUID NOT NULL CONSTRAINT fk_application_history_application
        REFERENCES applications(id) ON DELETE RESTRICT,
    from_status VARCHAR(30),
    to_status VARCHAR(30) NOT NULL,
    actor_user_id BIGINT CONSTRAINT fk_application_history_actor
        REFERENCES users(id) ON DELETE RESTRICT,
    occurred_at TIMESTAMPTZ NOT NULL,
    rejection_reason VARCHAR(40),
    internal_explanation VARCHAR(1000),
    candidate_feedback VARCHAR(1000),
    CONSTRAINT ck_application_history_status CHECK (
        (from_status IS NULL AND to_status='SUBMITTED') OR
        (from_status IN ('SUBMITTED','UNDER_REVIEW','SHORTLISTED')
            AND to_status IN ('UNDER_REVIEW','SHORTLISTED','REJECTED','WITHDRAWN'))
    ),
    CONSTRAINT ck_application_history_rejection CHECK (
        (to_status='REJECTED' AND rejection_reason IN
            ('QUALIFICATIONS_MISMATCH','EXPERIENCE_MISMATCH','POSITION_FILLED','OTHER')
            AND (rejection_reason <> 'OTHER' OR nullif(btrim(internal_explanation),'') IS NOT NULL))
        OR (to_status <> 'REJECTED' AND rejection_reason IS NULL
            AND internal_explanation IS NULL AND candidate_feedback IS NULL)
    ),
    CONSTRAINT ck_application_history_text_lengths CHECK (
        (internal_explanation IS NULL OR char_length(internal_explanation) <= 1000)
        AND (candidate_feedback IS NULL OR char_length(candidate_feedback) <= 1000)
    )
);
CREATE INDEX ix_application_history_application ON application_status_history(application_id,id);

CREATE FUNCTION protect_application_status_history() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Application status history is append-only' USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_application_status_history_immutable
    BEFORE UPDATE OR DELETE ON application_status_history
    FOR EACH ROW EXECUTE FUNCTION protect_application_status_history();
CREATE TRIGGER trg_application_status_history_truncate
    BEFORE TRUNCATE ON application_status_history
    FOR EACH STATEMENT EXECUTE FUNCTION protect_application_status_history();

INSERT INTO application_status_history(application_id,from_status,to_status,actor_user_id,occurred_at)
SELECT a.id,NULL,'SUBMITTED',p.user_id,a.applied_at
FROM applications a JOIN candidate_profiles p ON p.id=a.candidate_id
WHERE a.status='SUBMITTED';

ALTER TABLE notification_outbox ADD COLUMN application_id UUID;
ALTER TABLE notification_outbox ADD COLUMN candidate_feedback VARCHAR(1000);
ALTER TABLE user_notifications ADD COLUMN application_id UUID;
ALTER TABLE user_notifications ADD COLUMN candidate_feedback VARCHAR(1000);
ALTER TABLE notification_outbox ADD CONSTRAINT fk_notification_outbox_application
    FOREIGN KEY(application_id) REFERENCES applications(id) ON DELETE RESTRICT;
ALTER TABLE user_notifications ADD CONSTRAINT fk_user_notifications_application
    FOREIGN KEY(application_id) REFERENCES applications(id) ON DELETE RESTRICT;

ALTER TABLE notification_outbox DROP CONSTRAINT notification_outbox_event_type_check;
ALTER TABLE user_notifications DROP CONSTRAINT user_notifications_event_type_check;
ALTER TABLE notification_outbox ADD CONSTRAINT notification_outbox_event_type_check CHECK(event_type IN (
    'RECRUITER_ACCEPTED','MEMBERSHIP_REMOVED','JOB_ASSIGNED','JOB_UNASSIGNED',
    'JOB_REVISION_APPROVED','JOB_REVISION_REJECTED','JOB_NEEDS_REASSIGNMENT',
    'APPLICATION_STATUS_CHANGED','APPLICATION_REJECTED','APPLICATION_WITHDRAWN'));
ALTER TABLE user_notifications ADD CONSTRAINT user_notifications_event_type_check CHECK(event_type IN (
    'RECRUITER_ACCEPTED','MEMBERSHIP_REMOVED','JOB_ASSIGNED','JOB_UNASSIGNED',
    'JOB_REVISION_APPROVED','JOB_REVISION_REJECTED','JOB_NEEDS_REASSIGNMENT',
    'APPLICATION_STATUS_CHANGED','APPLICATION_REJECTED','APPLICATION_WITHDRAWN'));
ALTER TABLE notification_outbox ADD CONSTRAINT ck_application_outbox_context CHECK (
    (event_type LIKE 'APPLICATION_%') = (application_id IS NOT NULL));
ALTER TABLE user_notifications ADD CONSTRAINT ck_application_inbox_context CHECK (
    (event_type LIKE 'APPLICATION_%') = (application_id IS NOT NULL));

CREATE OR REPLACE FUNCTION protect_job_notification_context() RETURNS trigger AS $$
BEGIN
    IF (NEW.recipient_user_id,NEW.event_type,NEW.company_id,NEW.company_name,NEW.job_id,
        NEW.revision_id,NEW.application_id,NEW.candidate_feedback,NEW.created_at)
        IS DISTINCT FROM (OLD.recipient_user_id,OLD.event_type,OLD.company_id,OLD.company_name,
        OLD.job_id,OLD.revision_id,OLD.application_id,OLD.candidate_feedback,OLD.created_at) THEN
        RAISE EXCEPTION 'Notification context is immutable' USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'notification_outbox' THEN
        IF OLD.delivered_at IS NOT NULL AND (NEW.delivered_at,NEW.attempt_count,NEW.next_attempt_at,NEW.error_category)
            IS DISTINCT FROM (OLD.delivered_at,OLD.attempt_count,OLD.next_attempt_at,OLD.error_category) THEN
            RAISE EXCEPTION 'Delivered notification dispatch state is immutable' USING ERRCODE = '23514';
        END IF;
    ELSIF (NEW.read_at IS DISTINCT FROM OLD.read_at AND OLD.read_at IS NOT NULL) THEN
        RAISE EXCEPTION 'Notification read receipt is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
