-- Restore the database-level event-type allowlists. The inactivity release must add its
-- new event without weakening the persistence boundary that rejects unknown inbox and
-- outbox event keys.
ALTER TABLE notification_outbox DROP CONSTRAINT IF EXISTS notification_outbox_event_type_check;
ALTER TABLE notification_outbox ADD CONSTRAINT notification_outbox_event_type_check CHECK(event_type IN (
    'RECRUITER_ACCEPTED','MEMBERSHIP_REMOVED','JOB_ASSIGNED','JOB_UNASSIGNED',
    'JOB_REVISION_APPROVED','JOB_REVISION_REJECTED','JOB_NEEDS_REASSIGNMENT',
    'APPLICATION_STATUS_CHANGED','APPLICATION_REJECTED','APPLICATION_WITHDRAWN',
    'APPLICATION_INTERVIEWING','APPLICATION_HIRED','APPLICATION_INACTIVE','UNKNOWN'));

ALTER TABLE notification_inbox DROP CONSTRAINT IF EXISTS notification_inbox_event_type_check;
ALTER TABLE notification_inbox DROP CONSTRAINT IF EXISTS user_notifications_event_type_check;
ALTER TABLE notification_inbox ADD CONSTRAINT notification_inbox_event_type_check CHECK(event_type IN (
    'RECRUITER_ACCEPTED','MEMBERSHIP_REMOVED','JOB_ASSIGNED','JOB_UNASSIGNED',
    'JOB_REVISION_APPROVED','JOB_REVISION_REJECTED','JOB_NEEDS_REASSIGNMENT',
    'APPLICATION_STATUS_CHANGED','APPLICATION_REJECTED','APPLICATION_WITHDRAWN',
    'APPLICATION_INTERVIEWING','APPLICATION_HIRED','APPLICATION_INACTIVE','UNKNOWN'));
