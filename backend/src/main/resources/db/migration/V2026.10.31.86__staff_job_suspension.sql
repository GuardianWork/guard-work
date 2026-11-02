-- Preserve existing states and rows; Staff suspension is distinct from Company pause.
ALTER TABLE job_postings DROP CONSTRAINT job_postings_status_check;
ALTER TABLE job_postings ADD CONSTRAINT job_postings_status_check
    CHECK (status IN ('DRAFT','PUBLISHED','PAUSED','CLOSED','EXPIRED','SUSPENDED'));
