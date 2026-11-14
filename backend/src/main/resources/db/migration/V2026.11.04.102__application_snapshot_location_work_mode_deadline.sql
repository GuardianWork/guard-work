ALTER TABLE application_snapshots
    ADD COLUMN IF NOT EXISTS job_location VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS work_mode VARCHAR(30),
    ADD COLUMN IF NOT EXISTS job_deadline TIMESTAMPTZ;

COMMENT ON COLUMN application_snapshots.job_location IS
    'Published job location captured at submission time; may be null for older snapshots.';
COMMENT ON COLUMN application_snapshots.work_mode IS
    'Published work arrangement captured at submission time; may be null for older snapshots.';
COMMENT ON COLUMN application_snapshots.job_deadline IS
    'Published job deadline captured at submission time; may be null for older snapshots.';