ALTER TABLE applications DROP CONSTRAINT applications_status_check;
ALTER TABLE applications ADD CONSTRAINT applications_status_check
    CHECK (status IN ('SUBMITTED','UNDER_REVIEW','SHORTLISTED','INTERVIEWING','REJECTED','WITHDRAWN'));

ALTER TABLE application_status_history DROP CONSTRAINT ck_application_history_status;
ALTER TABLE application_status_history ADD CONSTRAINT ck_application_history_status CHECK (
    (from_status IS NULL AND to_status='SUBMITTED') OR
    (from_status IN ('SUBMITTED','UNDER_REVIEW','SHORTLISTED')
        AND to_status IN ('UNDER_REVIEW','SHORTLISTED','REJECTED','WITHDRAWN')) OR
    (from_status IN ('UNDER_REVIEW','SHORTLISTED') AND to_status='INTERVIEWING') OR
    (from_status='INTERVIEWING' AND to_status IN ('REJECTED','WITHDRAWN'))
);

ALTER TABLE notification_outbox DROP CONSTRAINT notification_outbox_event_type_check;
ALTER TABLE user_notifications DROP CONSTRAINT user_notifications_event_type_check;
ALTER TABLE notification_outbox ADD CONSTRAINT notification_outbox_event_type_check CHECK(event_type IN (
    'RECRUITER_ACCEPTED','MEMBERSHIP_REMOVED','JOB_ASSIGNED','JOB_UNASSIGNED',
    'JOB_REVISION_APPROVED','JOB_REVISION_REJECTED','JOB_NEEDS_REASSIGNMENT',
    'APPLICATION_STATUS_CHANGED','APPLICATION_REJECTED','APPLICATION_WITHDRAWN',
    'APPLICATION_INTERVIEWING'));
ALTER TABLE user_notifications ADD CONSTRAINT user_notifications_event_type_check CHECK(event_type IN (
    'RECRUITER_ACCEPTED','MEMBERSHIP_REMOVED','JOB_ASSIGNED','JOB_UNASSIGNED',
    'JOB_REVISION_APPROVED','JOB_REVISION_REJECTED','JOB_NEEDS_REASSIGNMENT',
    'APPLICATION_STATUS_CHANGED','APPLICATION_REJECTED','APPLICATION_WITHDRAWN',
    'APPLICATION_INTERVIEWING'));
