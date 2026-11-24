-- Candidate JD availability notices link to the recipient's Application.
-- Keep all other event shapes and retained notification rows unchanged.
ALTER TABLE notification_outbox DROP CONSTRAINT ck_application_outbox_context;
ALTER TABLE notification_outbox ADD CONSTRAINT ck_application_outbox_context CHECK (
    (event_type LIKE 'APPLICATION_%' OR
        (event_type IN ('JOB_SUSPENDED', 'JOB_RESTORED') AND audience = 'CANDIDATE'))
    = (payload ? 'applicationId'));

ALTER TABLE notification_inbox DROP CONSTRAINT ck_application_inbox_context;
ALTER TABLE notification_inbox ADD CONSTRAINT ck_application_inbox_context CHECK (
    (event_type LIKE 'APPLICATION_%' OR
        (event_type IN ('JOB_SUSPENDED', 'JOB_RESTORED') AND audience = 'CANDIDATE'))
    = (application_id IS NOT NULL));
