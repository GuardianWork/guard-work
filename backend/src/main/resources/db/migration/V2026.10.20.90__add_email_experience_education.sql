/* Migration to add email, experience, and education columns and remove summary columns */

ALTER TABLE candidate_profiles
    ADD COLUMN email VARCHAR(255) NOT NULL,
    ADD COLUMN experience JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN education JSONB NOT NULL DEFAULT '[]'::jsonb,
    DROP COLUMN IF EXISTS experience_summary,
    DROP COLUMN IF EXISTS education_summary;

-- Ensure email uniqueness
CREATE UNIQUE INDEX IF NOT EXISTS idx_candidate_profiles_email ON candidate_profiles (email);
