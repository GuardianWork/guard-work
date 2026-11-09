-- Envelope columns for the shared Notification response (see frontend Notification interface).
-- Copy (title/body) is rendered at dispatch time by NotificationTemplate in code and frozen
-- as a snapshot; historical rows keep NULL title/body and are shown with a backend fallback.
-- Generated metadata only, no UI language in the migration.
--
-- ADR 0012 (two-table separation, strict rollout): shared context protection is replaced by
-- one immutable-context trigger per table. The outbox owns delivery/retry state, and the
-- inbox owns presentation/read state. V83/V101 created the shared transition function; this
-- migration drops it so future event work has a single per-table authority.
ALTER TABLE notification_inbox ADD COLUMN IF NOT EXISTS category VARCHAR(50);
ALTER TABLE notification_inbox ADD COLUMN IF NOT EXISTS title VARCHAR(200);
ALTER TABLE notification_inbox ADD COLUMN IF NOT EXISTS body TEXT;
ALTER TABLE notification_inbox ADD COLUMN IF NOT EXISTS action_href VARCHAR(1024);
ALTER TABLE notification_inbox ADD COLUMN IF NOT EXISTS actor_user_id BIGINT;
ALTER TABLE notification_inbox ADD COLUMN IF NOT EXISTS actor_name VARCHAR(255);
ALTER TABLE notification_outbox ADD COLUMN IF NOT EXISTS actor_user_id BIGINT;
ALTER TABLE notification_outbox ADD COLUMN IF NOT EXISTS actor_name VARCHAR(255);

-- Backfill category for rows written before the envelope existed.
UPDATE notification_inbox SET category = CASE
  WHEN event_type LIKE 'APPLICATION_%' THEN 'application'
  WHEN event_type LIKE 'JOB_%' THEN 'job'
  WHEN event_type IN ('RECRUITER_ACCEPTED', 'MEMBERSHIP_REMOVED') THEN 'company'
  ELSE 'system'
END WHERE category IS NULL;
ALTER TABLE notification_inbox ALTER COLUMN category SET DEFAULT 'system';
ALTER TABLE notification_inbox ALTER COLUMN category SET NOT NULL;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_notification_inbox_category') THEN
    ALTER TABLE notification_inbox ADD CONSTRAINT ck_notification_inbox_category
      CHECK (category IN ('job','application','company','account','verification','announcement','community','reward','promotion','system'));
  END IF;
END $$;

-- Keep the code-level unknown fallback readable if an older/unmapped event key is ever needed.
ALTER TABLE notification_outbox DROP CONSTRAINT IF EXISTS notification_outbox_event_type_check;
ALTER TABLE notification_outbox ADD CONSTRAINT notification_outbox_event_type_check CHECK(event_type IN (
    'RECRUITER_ACCEPTED','MEMBERSHIP_REMOVED','JOB_ASSIGNED','JOB_UNASSIGNED',
    'JOB_REVISION_APPROVED','JOB_REVISION_REJECTED','JOB_NEEDS_REASSIGNMENT',
    'APPLICATION_STATUS_CHANGED','APPLICATION_REJECTED','APPLICATION_WITHDRAWN','UNKNOWN'));
ALTER TABLE notification_inbox DROP CONSTRAINT IF EXISTS user_notifications_event_type_check;
ALTER TABLE notification_inbox DROP CONSTRAINT IF EXISTS notification_inbox_event_type_check;
ALTER TABLE notification_inbox ADD CONSTRAINT notification_inbox_event_type_check CHECK(event_type IN (
    'RECRUITER_ACCEPTED','MEMBERSHIP_REMOVED','JOB_ASSIGNED','JOB_UNASSIGNED',
    'JOB_REVISION_APPROVED','JOB_REVISION_REJECTED','JOB_NEEDS_REASSIGNMENT',
    'APPLICATION_STATUS_CHANGED','APPLICATION_REJECTED','APPLICATION_WITHDRAWN','UNKNOWN'));

-- Drop the shared transition trigger/functions before installing the table-specific ones.
DROP TRIGGER IF EXISTS trg_job_outbox_context ON notification_outbox;
DROP TRIGGER IF EXISTS trg_job_inbox_context ON notification_inbox;
DROP FUNCTION IF EXISTS protect_job_notification_context();

CREATE OR REPLACE FUNCTION protect_notification_outbox_context() RETURNS trigger AS $$
BEGIN
    -- Frozen event context: never recipient, event, Company, job/revision/application, actor, or time.
    IF (NEW.event_id, NEW.recipient_user_id, NEW.event_type, NEW.company_id, NEW.company_name,
        NEW.job_id, NEW.revision_id, NEW.application_id, NEW.candidate_feedback,
        NEW.actor_user_id, NEW.actor_name, NEW.created_at)
        IS DISTINCT FROM (OLD.event_id, OLD.recipient_user_id, OLD.event_type, OLD.company_id,
        OLD.company_name, OLD.job_id, OLD.revision_id, OLD.application_id,
        OLD.candidate_feedback, OLD.actor_user_id, OLD.actor_name, OLD.created_at) THEN
        RAISE EXCEPTION 'Notification outbox context is immutable' USING ERRCODE = '23514';
    END IF;
    -- Delivery/retry state may move until the row has been delivered; then it is permanently frozen.
    IF OLD.delivered_at IS NOT NULL AND (NEW.delivered_at, NEW.attempt_count, NEW.next_attempt_at,
        NEW.error_category) IS DISTINCT FROM (OLD.delivered_at, OLD.attempt_count,
        OLD.next_attempt_at, OLD.error_category) THEN
        RAISE EXCEPTION 'Delivered notification dispatch state is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION protect_notification_inbox_context() RETURNS trigger AS $$
BEGIN
    -- Frozen source identity and event/Company/job/application/actor context.
    IF (NEW.id, NEW.source_event_id, NEW.recipient_user_id, NEW.event_type, NEW.company_id,
        NEW.company_name, NEW.job_id, NEW.revision_id, NEW.application_id,
        NEW.candidate_feedback, NEW.actor_user_id, NEW.actor_name, NEW.created_at)
        IS DISTINCT FROM (OLD.id, OLD.source_event_id, OLD.recipient_user_id, OLD.event_type,
        OLD.company_id, OLD.company_name, OLD.job_id, OLD.revision_id, OLD.application_id,
        OLD.candidate_feedback, OLD.actor_user_id, OLD.actor_name, OLD.created_at) THEN
        RAISE EXCEPTION 'Notification inbox context is immutable' USING ERRCODE = '23514';
    END IF;
    -- Presentation snapshot is rendered once at dispatch time and must not be rewritten.
    IF (NEW.category, NEW.title, NEW.body, NEW.action_href) IS DISTINCT FROM
        (OLD.category, OLD.title, OLD.body, OLD.action_href) THEN
        RAISE EXCEPTION 'Notification inbox presentation is immutable' USING ERRCODE = '23514';
    END IF;
    -- Read receipt is set once; a second change is rejected.
    IF OLD.read_at IS NOT NULL AND (NEW.read_at IS DISTINCT FROM OLD.read_at) THEN
        RAISE EXCEPTION 'Notification read receipt is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_notification_outbox_context BEFORE UPDATE ON notification_outbox
    FOR EACH ROW EXECUTE FUNCTION protect_notification_outbox_context();
CREATE TRIGGER trg_notification_inbox_context BEFORE UPDATE ON notification_inbox
    FOR EACH ROW EXECUTE FUNCTION protect_notification_inbox_context();

CREATE INDEX IF NOT EXISTS ix_notification_inbox_category ON notification_inbox(category, id DESC);
CREATE INDEX IF NOT EXISTS ix_notification_inbox_recipient ON notification_inbox(recipient_user_id, id DESC);
