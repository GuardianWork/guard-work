/*
  UC-CAN-11 application submission.

  An application is the stable identity of one candidate's submission to one job.
  The submitted contact details, cover letter, job summary and CV copy live in
  application_snapshots so later profile, job or CV changes cannot rewrite what
  the recruiter received.

  source_cv_id intentionally has no foreign key: deleting a candidate CV must not
  mutate or remove an already-submitted application snapshot (EC-CAN-CV-04).
*/

CREATE TABLE applications (
    id UUID PRIMARY KEY,
    candidate_id UUID NOT NULL REFERENCES candidate_profiles(id),
    job_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    submitted_revision_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL
        CHECK (status IN ('SUBMITTED', 'VIEWED', 'REJECTED')),
    version BIGINT NOT NULL DEFAULT 0,
    applied_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_applications_candidate_job UNIQUE (candidate_id, job_id),
    CONSTRAINT fk_applications_job_company
        FOREIGN KEY (job_id, company_id) REFERENCES job_postings(id, company_id),
    CONSTRAINT fk_applications_revision_job
        FOREIGN KEY (submitted_revision_id, job_id)
        REFERENCES job_posting_revisions(id, job_id)
);

CREATE INDEX ix_applications_candidate
    ON applications (candidate_id, applied_at DESC);
CREATE INDEX ix_applications_job
    ON applications (job_id, applied_at DESC);
CREATE INDEX ix_applications_company
    ON applications (company_id, applied_at DESC);

CREATE TABLE application_snapshots (
    application_id UUID PRIMARY KEY REFERENCES applications(id),
    full_name VARCHAR(150) NOT NULL
        CHECK (btrim(full_name) <> ''),
    email VARCHAR(255) NOT NULL
        CHECK (position('@' in email) > 1),
    phone_number VARCHAR(10) NOT NULL
        CHECK (phone_number ~ '^0[0-9]{9}$'),
    cover_letter VARCHAR(1000),
    job_title VARCHAR(200) NOT NULL
        CHECK (btrim(job_title) <> ''),
    company_name VARCHAR(255) NOT NULL
        CHECK (btrim(company_name) <> ''),
    source_cv_id UUID,
    cv_storage_key VARCHAR(255) NOT NULL,
    cv_storage_provider VARCHAR(30) NOT NULL DEFAULT 'CLOUDINARY'
        CHECK (cv_storage_provider = 'CLOUDINARY'),
    cv_file_name VARCHAR(255) NOT NULL
        CHECK (btrim(cv_file_name) <> ''),
    cv_content_type VARCHAR(100) NOT NULL
        CHECK (cv_content_type IN (
            'application/pdf',
            'application/msword',
            'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
        )),
    cv_file_size BIGINT NOT NULL CHECK (cv_file_size > 0),
    snapshot_schema_version INTEGER NOT NULL DEFAULT 1,
    captured_at TIMESTAMPTZ NOT NULL
);

COMMENT ON TABLE applications IS
    'One candidate submission per job; unique (candidate_id, job_id) prevents duplicates.';
COMMENT ON TABLE application_snapshots IS
    'Immutable submitted contact, job and CV copy. source_cv_id is historical only.';
COMMENT ON COLUMN application_snapshots.source_cv_id IS
    'Original candidate CV id for traceability; no FK so CV deletion cannot mutate the snapshot.';

CREATE FUNCTION protect_application_identity() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Application history cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF (NEW.candidate_id, NEW.job_id, NEW.company_id, NEW.submitted_revision_id, NEW.applied_at)
       IS DISTINCT FROM
       (OLD.candidate_id, OLD.job_id, OLD.company_id, OLD.submitted_revision_id, OLD.applied_at) THEN
        RAISE EXCEPTION 'Application identity is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_application_identity
    BEFORE UPDATE OR DELETE ON applications
    FOR EACH ROW EXECUTE FUNCTION protect_application_identity();

CREATE FUNCTION protect_application_snapshot() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Application snapshot is immutable' USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_application_snapshot
    BEFORE UPDATE OR DELETE ON application_snapshots
    FOR EACH ROW EXECUTE FUNCTION protect_application_snapshot();
