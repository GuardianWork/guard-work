/*
  Inactive applications, grant epochs, and legal CV-copy purge.

  - Applications may become INACTIVE after a period without a status update.
    INACTIVE is reversible: a Company may reactivate the Application, which
    starts a new CV access-grant epoch when the shared library CV still exists.
  - status_changed_at is the authoritative inactivity clock. It is backfilled
    from append-only status history so existing Applications are not treated
    as immediately due.
  - An Application may have several grant epochs over its life, but at most one
    non-revoked epoch at a time.
  - A rejected legacy application copy is deleted by a worker, which records
    cv_purged_at on the immutable snapshot. Snapshot metadata is retained; only
    the bytes are removed.
*/

-- Applications: INACTIVE status and inactivity clock -----------------------------

-- Lifecycle status values are validated in the application layer; the database
-- does not enforce an enum CHECK so new statuses do not require a migration.
ALTER TABLE applications DROP CONSTRAINT applications_status_check;

ALTER TABLE applications ADD COLUMN status_changed_at TIMESTAMPTZ;
UPDATE applications application
SET status_changed_at = COALESCE(
    (SELECT max(history.occurred_at)
     FROM application_status_history history
     WHERE history.application_id = application.id),
    application.applied_at
);
ALTER TABLE applications ALTER COLUMN status_changed_at SET DEFAULT now();
ALTER TABLE applications ALTER COLUMN status_changed_at SET NOT NULL;

CREATE INDEX ix_applications_inactivity ON applications (status_changed_at)
    WHERE status IN ('SUBMITTED','UNDER_REVIEW','SHORTLISTED','INTERVIEWING');

COMMENT ON COLUMN applications.status_changed_at IS
    'Last lifecycle status transition; the inactivity worker measures from here.';

-- Transition legality is validated in the application layer; the database does
-- not enforce the status-transition CHECK.
ALTER TABLE application_status_history DROP CONSTRAINT ck_application_history_status;

-- Access grants: append-only epochs ------------------------------------------------

ALTER TABLE candidate_cv_access_grants ADD COLUMN epoch INTEGER NOT NULL DEFAULT 1;

ALTER TABLE candidate_cv_access_grants DROP CONSTRAINT uk_cv_grant_application;
ALTER TABLE candidate_cv_access_grants
    ADD CONSTRAINT uk_cv_grant_application_epoch UNIQUE (application_id, epoch);

-- At most one non-revoked epoch per Application. Candidate-deletion expiry keeps the
-- row non-revoked; a later reactivation only happens after inactivity revoked it.
CREATE UNIQUE INDEX uk_cv_grant_live_application
    ON candidate_cv_access_grants (application_id)
    WHERE revoked_at IS NULL;

ALTER TABLE candidate_cv_access_grants
    DROP CONSTRAINT IF EXISTS candidate_cv_access_grants_revoked_reason_check;
ALTER TABLE candidate_cv_access_grants
    ADD CONSTRAINT ck_cv_grant_revoked_reason CHECK (
        revoked_reason IN ('APPLICATION_CLOSED', 'ADMIN', 'APPLICATION_INACTIVE', 'CANDIDATE_CV_DELETED')
    );

CREATE OR REPLACE FUNCTION protect_candidate_cv_access_grant() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'CV access grants cannot be deleted' USING ERRCODE = '23514';
    END IF;

    IF (NEW.id, NEW.application_id, NEW.epoch, NEW.cv_id, NEW.candidate_id, NEW.company_id,
        NEW.scope, NEW.storage_provider, NEW.storage_key, NEW.resource_type,
        NEW.file_name, NEW.content_type, NEW.file_size, NEW.granted_at)
       IS DISTINCT FROM
       (OLD.id, OLD.application_id, OLD.epoch, OLD.cv_id, OLD.candidate_id, OLD.company_id,
        OLD.scope, OLD.storage_provider, OLD.storage_key, OLD.resource_type,
        OLD.file_name, OLD.content_type, OLD.file_size, OLD.granted_at) THEN
        RAISE EXCEPTION 'CV access grant identity is immutable' USING ERRCODE = '23514';
    END IF;

    IF OLD.expires_at IS NOT NULL AND NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN
        RAISE EXCEPTION 'CV access grant expiry cannot be extended or rewritten' USING ERRCODE = '23514';
    END IF;

    IF OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at THEN
        RAISE EXCEPTION 'CV access grant revocation is final' USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Rejected legacy copies: delete bytes, keep snapshot metadata --------------------

ALTER TABLE application_snapshots ADD COLUMN cv_purged_at TIMESTAMPTZ;

COMMENT ON COLUMN application_snapshots.cv_purged_at IS
    'Legacy application CV bytes were deleted; snapshot metadata is retained.';

CREATE INDEX ix_application_snapshots_cv_purge ON application_snapshots (captured_at)
    WHERE cv_purged_at IS NULL AND cv_storage_mode = 'LEGACY_COPY';

CREATE OR REPLACE FUNCTION protect_application_snapshot() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Application snapshot is immutable' USING ERRCODE = '23514';
    END IF;

    -- Everything except the purge marker must stay byte-for-byte identical.
    IF (to_jsonb(NEW) - 'cv_purged_at') IS DISTINCT FROM (to_jsonb(OLD) - 'cv_purged_at') THEN
        RAISE EXCEPTION 'Application snapshot is immutable' USING ERRCODE = '23514';
    END IF;

    -- The marker may be set exactly once, from NULL to a timestamp. It cannot be cleared.
    IF OLD.cv_purged_at IS NOT NULL OR NEW.cv_purged_at IS NULL THEN
        RAISE EXCEPTION 'CV purge marker is final' USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Company-only inactivity notification ------------------------------------------

-- Notification event types are validated by the application layer's event-type
-- enum; the database does not enforce an enum CHECK.
ALTER TABLE notification_outbox DROP CONSTRAINT IF EXISTS notification_outbox_event_type_check;

ALTER TABLE notification_inbox DROP CONSTRAINT IF EXISTS notification_inbox_event_type_check;
ALTER TABLE notification_inbox DROP CONSTRAINT IF EXISTS user_notifications_event_type_check;
