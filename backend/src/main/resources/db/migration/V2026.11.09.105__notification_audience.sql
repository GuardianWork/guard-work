-- Role-context audience for notifications.
-- One User can be both a Candidate and a company member, so the inbox is
-- partitioned by the role the recipient was notified as (CANDIDATE vs
-- RECRUITER). Past rows are backfilled from the event kind:
-- APPLICATION_STATUS_CHANGED / APPLICATION_REJECTED are candidate-facing;
-- everything else, including APPLICATION_WITHDRAWN (owner/recruiter
-- recipients), is recruiter-facing. No API field is renamed; the new
-- audience column is frozen with the rest of the notification context.

ALTER TABLE notification_outbox ADD COLUMN IF NOT EXISTS audience VARCHAR(20);
ALTER TABLE notification_inbox ADD COLUMN IF NOT EXISTS audience VARCHAR(20);

UPDATE notification_outbox SET audience = CASE
  WHEN event_type IN ('APPLICATION_STATUS_CHANGED', 'APPLICATION_REJECTED') THEN 'CANDIDATE'
  ELSE 'RECRUITER'
END WHERE audience IS NULL;
UPDATE notification_inbox SET audience = CASE
  WHEN event_type IN ('APPLICATION_STATUS_CHANGED', 'APPLICATION_REJECTED') THEN 'CANDIDATE'
  ELSE 'RECRUITER'
END WHERE audience IS NULL;

ALTER TABLE notification_outbox ALTER COLUMN audience SET NOT NULL;
ALTER TABLE notification_outbox ALTER COLUMN audience SET DEFAULT 'RECRUITER';
ALTER TABLE notification_inbox ALTER COLUMN audience SET NOT NULL;
ALTER TABLE notification_inbox ALTER COLUMN audience SET DEFAULT 'RECRUITER';

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_notification_outbox_audience') THEN
    ALTER TABLE notification_outbox ADD CONSTRAINT ck_notification_outbox_audience
      CHECK (audience IN ('CANDIDATE', 'RECRUITER'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_notification_inbox_audience') THEN
    ALTER TABLE notification_inbox ADD CONSTRAINT ck_notification_inbox_audience
      CHECK (audience IN ('CANDIDATE', 'RECRUITER'));
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_notification_inbox_recipient_audience
  ON notification_inbox(recipient_user_id, audience, id DESC);

-- Freeze audience with the rest of the context in both protect triggers.
CREATE OR REPLACE FUNCTION protect_notification_outbox_context() RETURNS trigger AS $$
BEGIN
    IF (NEW.event_id, NEW.recipient_user_id, NEW.event_type, NEW.audience, NEW.company_id, NEW.company_name,
        NEW.job_id, NEW.revision_id, NEW.application_id, NEW.candidate_feedback,
        NEW.actor_user_id, NEW.actor_name, NEW.created_at)
        IS DISTINCT FROM (OLD.event_id, OLD.recipient_user_id, OLD.event_type, OLD.audience, OLD.company_id,
        OLD.company_name, OLD.job_id, OLD.revision_id, OLD.application_id,
        OLD.candidate_feedback, OLD.actor_user_id, OLD.actor_name, OLD.created_at) THEN
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

CREATE OR REPLACE FUNCTION protect_notification_inbox_context() RETURNS trigger AS $$
BEGIN
    IF (NEW.id, NEW.source_event_id, NEW.recipient_user_id, NEW.event_type, NEW.audience, NEW.company_id,
        NEW.company_name, NEW.job_id, NEW.revision_id, NEW.application_id,
        NEW.candidate_feedback, NEW.actor_user_id, NEW.actor_name, NEW.created_at)
        IS DISTINCT FROM (OLD.id, OLD.source_event_id, OLD.recipient_user_id, OLD.event_type,
        OLD.audience, OLD.company_id, OLD.company_name, OLD.job_id, OLD.revision_id, OLD.application_id,
        OLD.candidate_feedback, OLD.actor_user_id, OLD.actor_name, OLD.created_at) THEN
        RAISE EXCEPTION 'Notification inbox context is immutable' USING ERRCODE = '23514';
    END IF;
    IF (NEW.category, NEW.title, NEW.body, NEW.action_href) IS DISTINCT FROM
        (OLD.category, OLD.title, OLD.body, OLD.action_href) THEN
        RAISE EXCEPTION 'Notification inbox presentation is immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.read_at IS NOT NULL AND (NEW.read_at IS DISTINCT FROM OLD.read_at) THEN
        RAISE EXCEPTION 'Notification read receipt is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
