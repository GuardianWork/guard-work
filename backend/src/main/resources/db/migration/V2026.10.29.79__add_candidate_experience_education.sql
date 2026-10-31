/* Add structured candidate history while preserving the legacy free-text columns
 * until their data has been reviewed or migrated. */

ALTER TABLE candidate_profiles
    ADD COLUMN IF NOT EXISTS experience JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS education JSONB NOT NULL DEFAULT '[]'::jsonb;

-- Remove the short-lived profile email column so the account remains the single source of truth.
ALTER TABLE candidate_profiles DROP COLUMN IF EXISTS email;
DROP INDEX IF EXISTS idx_candidate_profiles_email;

COMMENT ON COLUMN candidate_profiles.experience IS
    'Structured work experience entries; legacy experience_summary is retained for data migration.';
COMMENT ON COLUMN candidate_profiles.education IS
    'Structured education entries; legacy education_summary is retained for data migration.';
