-- ADR 0013 supersedes the outbox half of ADR 0012: fact-time context moves from
-- individual columns into one immutable `payload` JSONB document. The inbox keeps its
-- context columns because it is the frozen read model and must stay join-free.
--
-- Referential integrity that the dropped columns provided is re-established by
-- `validate_notification_outbox_payload()` at insert/update time; payload values are
-- never rewritten afterwards, so a write-time check is sufficient.

ALTER TABLE notification_outbox ADD COLUMN IF NOT EXISTS payload JSONB;

UPDATE notification_outbox
SET payload = jsonb_strip_nulls(
    jsonb_build_object(
        'companyId', company_id,
        'companyName', company_name,
        'jobId', job_id,
        'revisionId', revision_id,
        'applicationId', application_id,
        'candidateFeedback', candidate_feedback,
        'actorUserId', actor_user_id,
        'actorName', actor_name));

ALTER TABLE notification_outbox ALTER COLUMN payload SET NOT NULL;
ALTER TABLE notification_outbox ALTER COLUMN payload SET DEFAULT '{}'::jsonb;

-- Drop foreign keys and checks that depended on the columns being folded into payload.
-- The event_type/audience/attempt_count checks do not reference those columns and survive.
DO $$
DECLARE constraint_name TEXT;
BEGIN
    FOR constraint_name IN
        SELECT DISTINCT c.conname
        FROM pg_constraint c
        JOIN pg_attribute a
          ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
        WHERE c.conrelid = 'notification_outbox'::regclass
          AND c.contype IN ('f', 'c')
          AND a.attname IN ('company_id', 'company_name', 'job_id', 'revision_id',
                            'application_id', 'candidate_feedback', 'actor_user_id', 'actor_name')
    LOOP
        EXECUTE format('ALTER TABLE notification_outbox DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END;
$$;

ALTER TABLE notification_outbox
    DROP COLUMN company_id,
    DROP COLUMN company_name,
    DROP COLUMN job_id,
    DROP COLUMN revision_id,
    DROP COLUMN application_id,
    DROP COLUMN candidate_feedback,
    DROP COLUMN actor_user_id,
    DROP COLUMN actor_name;

-- Shape checks that used to be column constraints.
ALTER TABLE notification_outbox ADD CONSTRAINT ck_notification_outbox_payload_company
    CHECK (payload ? 'companyId' AND payload ? 'companyName');
ALTER TABLE notification_outbox ADD CONSTRAINT ck_application_outbox_context
    CHECK ((event_type LIKE 'APPLICATION_%') = (payload ? 'applicationId'));
ALTER TABLE notification_outbox ADD CONSTRAINT ck_notification_outbox_payload_revision
    CHECK (NOT (payload ? 'revisionId') OR payload ? 'jobId');

-- Replacement for the referential integrity removed with the dropped columns. The payload
-- is immutable, so validating it on insert (and on any payload update attempt) is enough.
CREATE OR REPLACE FUNCTION validate_notification_outbox_payload() RETURNS trigger AS $$
DECLARE
    v_company BIGINT := (NEW.payload ->> 'companyId')::BIGINT;
    v_job BIGINT := (NEW.payload ->> 'jobId')::BIGINT;
    v_revision BIGINT := (NEW.payload ->> 'revisionId')::BIGINT;
    v_application UUID := (NEW.payload ->> 'applicationId')::UUID;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM companies WHERE id = v_company) THEN
        RAISE EXCEPTION 'Notification Company does not exist' USING ERRCODE = '23503';
    END IF;
    IF v_job IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM job_postings WHERE id = v_job AND company_id = v_company) THEN
        RAISE EXCEPTION 'Notification Job does not belong to the Company' USING ERRCODE = '23503';
    END IF;
    IF v_revision IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM job_posting_revisions WHERE id = v_revision AND job_id = v_job) THEN
        RAISE EXCEPTION 'Notification revision does not belong to the Job' USING ERRCODE = '23503';
    END IF;
    IF v_application IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM applications WHERE id = v_application) THEN
        RAISE EXCEPTION 'Notification Application does not exist' USING ERRCODE = '23503';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_notification_outbox_payload
    BEFORE INSERT OR UPDATE OF payload ON notification_outbox
    FOR EACH ROW EXECUTE FUNCTION validate_notification_outbox_payload();

-- Freeze the folded context as one document instead of one comparison per column.
CREATE OR REPLACE FUNCTION protect_notification_outbox_context() RETURNS trigger AS $$
BEGIN
    IF (NEW.event_id, NEW.recipient_user_id, NEW.event_type, NEW.audience, NEW.payload,
        NEW.created_at)
        IS DISTINCT FROM (OLD.event_id, OLD.recipient_user_id, OLD.event_type, OLD.audience,
        OLD.payload, OLD.created_at) THEN
        RAISE EXCEPTION 'Notification outbox context is immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.delivered_at IS NOT NULL AND (NEW.delivered_at, NEW.attempt_count, NEW.next_attempt_at,
        NEW.error_category) IS DISTINCT FROM (OLD.delivered_at, OLD.attempt_count,
        OLD.next_attempt_at, OLD.error_category) THEN
        RAISE EXCEPTION 'Delivered notification dispatch state is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
