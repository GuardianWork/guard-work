CREATE TABLE jd_scan_runs (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    input_hash VARCHAR(64) NOT NULL,
    input_content JSONB NOT NULL CHECK (jsonb_typeof(input_content) = 'object'),
    analysis_text TEXT NOT NULL CHECK (octet_length(analysis_text) <= 262144),
    profile VARCHAR(100) NOT NULL,
    legal_snapshot VARCHAR(100) NOT NULL,
    mode VARCHAR(10) NOT NULL CHECK (mode IN ('stub','crawler')),
    as_of_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED')),
    report JSONB CHECK (report IS NULL OR (jsonb_typeof(report) = 'object' AND octet_length(report::text) <= 524288)),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 3),
    available_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    failure_code VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    UNIQUE(company_id, input_hash, profile, legal_snapshot, mode, as_of_date),
    UNIQUE(id, company_id),
    CHECK ((status = 'RUNNING') = (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK ((status = 'COMPLETED') = (report IS NOT NULL)),
    CHECK ((status IN ('COMPLETED','FAILED')) = (finished_at IS NOT NULL))
);
CREATE INDEX ix_jd_scan_queue ON jd_scan_runs(available_at,id) WHERE status = 'QUEUED';
CREATE INDEX ix_jd_scan_leases ON jd_scan_runs(lease_until,id) WHERE status = 'RUNNING';

CREATE TABLE jd_revision_scans (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    revision_id BIGINT NOT NULL,
    revision_version BIGINT NOT NULL CHECK (revision_version >= 0),
    scan_run_id BIGINT NOT NULL,
    requested_by BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(job_id,company_id) REFERENCES job_postings(id,company_id),
    FOREIGN KEY(revision_id,job_id) REFERENCES job_posting_revisions(id,job_id),
    FOREIGN KEY(scan_run_id,company_id) REFERENCES jd_scan_runs(id,company_id),
    UNIQUE(revision_id,revision_version,scan_run_id)
);
CREATE INDEX ix_jd_revision_scans ON jd_revision_scans(revision_id,scan_run_id);

CREATE TABLE jd_scan_attempts (
    id BIGSERIAL PRIMARY KEY,
    scan_run_id BIGINT NOT NULL REFERENCES jd_scan_runs(id),
    claim_token UUID NOT NULL UNIQUE,
    attempt_number INTEGER NOT NULL CHECK (attempt_number BETWEEN 1 AND 3),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    outcome VARCHAR(30) CHECK (outcome IN ('COMPLETED','TRANSIENT_FAILURE','INVALID_RESPONSE','LEASE_EXPIRED')),
    duration_ms BIGINT CHECK (duration_ms >= 0),
    UNIQUE(scan_run_id,attempt_number),
    CHECK ((outcome IS NULL) = (finished_at IS NULL))
);

CREATE FUNCTION protect_jd_scan_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'JD scan history cannot be deleted'; END IF;
    IF OLD.status IN ('COMPLETED','FAILED') OR
       ROW(NEW.company_id,NEW.input_hash,NEW.input_content,NEW.analysis_text,NEW.profile,
           NEW.legal_snapshot,NEW.mode,NEW.as_of_date,NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.company_id,OLD.input_hash,OLD.input_content,OLD.analysis_text,OLD.profile,
           OLD.legal_snapshot,OLD.mode,OLD.as_of_date,OLD.created_at) THEN
       RAISE EXCEPTION 'JD scan snapshot or completed history cannot change';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_jd_scan_snapshot BEFORE UPDATE OR DELETE ON jd_scan_runs
FOR EACH ROW EXECUTE FUNCTION protect_jd_scan_snapshot();
CREATE TRIGGER trg_jd_scan_truncate BEFORE TRUNCATE ON jd_scan_runs
FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_logs_mutation();
CREATE TRIGGER trg_jd_scan_association_history BEFORE UPDATE OR DELETE OR TRUNCATE ON jd_revision_scans
FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_logs_mutation();
