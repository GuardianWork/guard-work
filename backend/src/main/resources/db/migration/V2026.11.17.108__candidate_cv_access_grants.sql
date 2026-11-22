/*
  Candidate CV access grants and library retention.

  An application no longer copies the CV bytes. Instead, one explicit
  candidate_cv_access_grants row records which Company may read which
  Candidate CV for which Application. The grant is the only authorization
  source for Company CV reads; it is append-only and time bounded.

  The Candidate keeps ownership of the file. When the Candidate removes a CV
  from the library, the candidate_cvs row becomes a tombstone (deleted_at) and
  every live grant for it receives a bounded review window (expires_at). The
  Cloudinary object is deleted only after no live grant remains, and the row is
  kept so grants retain a valid foreign key and purge retries stay idempotent.

  Legacy applications keep their copied CV object. The new
  application_snapshots.cv_storage_mode column distinguishes a historical
  copy (LEGACY_COPY) from a shared library reference (SHARED), so an expired
  grant can never be bypassed through the snapshot key.
*/

-- Candidate CV library lifecycle -------------------------------------------------

ALTER TABLE candidate_cvs
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN storage_deleted_at TIMESTAMPTZ;

-- A removed CV can no longer be the active default.
DROP INDEX uk_candidate_cvs_one_default;
CREATE UNIQUE INDEX uk_candidate_cvs_one_default
    ON candidate_cvs (candidate_id)
    WHERE is_default AND deleted_at IS NULL;

-- Retention worker lookup: removed CVs whose Cloudinary object is still present.
CREATE INDEX ix_candidate_cvs_retention
    ON candidate_cvs (deleted_at)
    WHERE deleted_at IS NOT NULL AND storage_deleted_at IS NULL;

COMMENT ON COLUMN candidate_cvs.deleted_at IS
    'Candidate removed the CV from their library; the row remains as an audit tombstone.';
COMMENT ON COLUMN candidate_cvs.storage_deleted_at IS
    'Cloudinary object deletion completed; null after deletion means purge is still pending.';

-- Snapshot storage mode ----------------------------------------------------------

ALTER TABLE application_snapshots
    ADD COLUMN cv_storage_mode VARCHAR(20) NOT NULL DEFAULT 'LEGACY_COPY'
        CHECK (cv_storage_mode IN ('LEGACY_COPY', 'SHARED'));

COMMENT ON COLUMN application_snapshots.cv_storage_mode IS
    'LEGACY_COPY: cv_storage_key is an application-specific copy. SHARED: cv_storage_key references the Candidate library object and Company reads require a live access grant.';

-- Access grants ------------------------------------------------------------------

CREATE TABLE candidate_cv_access_grants (
    id               UUID PRIMARY KEY,
    application_id   UUID NOT NULL REFERENCES applications (id),
    cv_id            UUID REFERENCES candidate_cvs (id),
    candidate_id     UUID NOT NULL REFERENCES candidate_profiles (id),
    company_id       BIGINT NOT NULL REFERENCES companies (id),

    scope            VARCHAR(30) NOT NULL DEFAULT 'APPLICATION_REVIEW'
                     CHECK (scope IN ('APPLICATION_REVIEW')),
    storage_provider VARCHAR(30) NOT NULL DEFAULT 'CLOUDINARY'
                     CHECK (storage_provider = 'CLOUDINARY'),

    storage_key      VARCHAR(255) NOT NULL,
    resource_type    VARCHAR(20) NOT NULL
                     CHECK (resource_type IN ('image', 'raw')),
    file_name        VARCHAR(255) NOT NULL
                     CHECK (btrim(file_name) <> ''),
    content_type     VARCHAR(100) NOT NULL
                     CHECK (content_type IN (
                         'application/pdf',
                         'application/msword',
                         'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
                     )),
    file_size        BIGINT NOT NULL CHECK (file_size > 0),

    granted_at       TIMESTAMPTZ NOT NULL,
    expires_at       TIMESTAMPTZ,
    revoked_at       TIMESTAMPTZ,
    revoked_reason   VARCHAR(30)
                     CHECK (revoked_reason IN ('APPLICATION_CLOSED', 'ADMIN')),

    CONSTRAINT uk_cv_grant_application UNIQUE (application_id),
    CONSTRAINT ck_cv_grant_window
        CHECK (expires_at IS NULL OR expires_at > granted_at),
    CONSTRAINT ck_cv_grant_revocation
        CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))
);

-- Retention lookup: which CVs still have a live Company grant.
CREATE INDEX ix_cv_grant_cv
    ON candidate_cv_access_grants (cv_id)
    WHERE revoked_at IS NULL;

-- Scheduled expiry sweep.
CREATE INDEX ix_cv_grant_expiry
    ON candidate_cv_access_grants (expires_at)
    WHERE revoked_at IS NULL AND expires_at IS NOT NULL;

-- Company-scoped application reads.
CREATE INDEX ix_cv_grant_company
    ON candidate_cv_access_grants (company_id, application_id);

COMMENT ON TABLE candidate_cv_access_grants IS
    'Explicit, time-bounded permission for one Company to read one Candidate CV for one Application.';
COMMENT ON COLUMN candidate_cv_access_grants.cv_id IS
    'Candidate library CV shared with the Application; null only if that row is ever removed by a future approved purge.';
COMMENT ON COLUMN candidate_cv_access_grants.storage_key IS
    'Reference to the Candidate CV object, not an application-specific copy.';
COMMENT ON COLUMN candidate_cv_access_grants.expires_at IS
    'Null while the Candidate keeps the CV; set to Candidate deletion time plus the review window once the CV is removed.';

-- Permit only the two intended state changes: start the window once and revoke once.
CREATE FUNCTION protect_candidate_cv_access_grant() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'CV access grants cannot be deleted' USING ERRCODE = '23514';
    END IF;

    IF (NEW.id, NEW.application_id, NEW.cv_id, NEW.candidate_id, NEW.company_id,
        NEW.scope, NEW.storage_provider, NEW.storage_key, NEW.resource_type,
        NEW.file_name, NEW.content_type, NEW.file_size, NEW.granted_at)
       IS DISTINCT FROM
       (OLD.id, OLD.application_id, OLD.cv_id, OLD.candidate_id, OLD.company_id,
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

CREATE TRIGGER trg_candidate_cv_access_grant
    BEFORE UPDATE OR DELETE ON candidate_cv_access_grants
    FOR EACH ROW EXECUTE FUNCTION protect_candidate_cv_access_grant();
