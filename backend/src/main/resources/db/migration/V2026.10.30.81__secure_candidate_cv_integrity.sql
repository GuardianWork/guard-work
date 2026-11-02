/*
  Enforce candidate CV ownership and default-CV integrity.

  Preflight checks fail loudly instead of silently deleting or re-attaching CV rows:
  - every candidate_id must reference an existing candidate profile;
  - public_id must already be unique because Cloudinary ids are global storage keys.

  Where a legacy candidate already has several default CVs, keep the newest one and
  clear the rest. This is deterministic and preserves every CV row.
*/

DO $$
DECLARE
    orphan_count BIGINT;
    duplicate_public_id_count BIGINT;
BEGIN
    SELECT count(*) INTO orphan_count
    FROM candidate_cvs cv
    LEFT JOIN candidate_profiles profile ON profile.id = cv.candidate_id
    WHERE profile.id IS NULL;

    IF orphan_count > 0 THEN
        RAISE EXCEPTION
            'candidate_cvs has % orphan candidate_id value(s); review them manually before applying this migration',
            orphan_count;
    END IF;

    SELECT count(*) INTO duplicate_public_id_count
    FROM (
        SELECT public_id
        FROM candidate_cvs
        GROUP BY public_id
        HAVING count(*) > 1
    ) duplicates;

    IF duplicate_public_id_count > 0 THEN
        RAISE EXCEPTION
            'candidate_cvs has % duplicated public_id value(s); review them manually before applying this migration',
            duplicate_public_id_count;
    END IF;
END
$$;

WITH ranked_defaults AS (
    SELECT id,
           row_number() OVER (PARTITION BY candidate_id ORDER BY created_at DESC, id DESC) AS position
    FROM candidate_cvs
    WHERE is_default
)
UPDATE candidate_cvs cv
SET is_default = FALSE,
    updated_at = now()
FROM ranked_defaults ranked
WHERE cv.id = ranked.id
  AND ranked.position > 1;

ALTER TABLE candidate_cvs
    ADD CONSTRAINT fk_candidate_cvs_candidate_id
    FOREIGN KEY (candidate_id) REFERENCES candidate_profiles (id)
    ON DELETE CASCADE;

CREATE UNIQUE INDEX uk_candidate_cvs_one_default
    ON candidate_cvs (candidate_id)
    WHERE is_default;

CREATE UNIQUE INDEX uk_candidate_cvs_public_id
    ON candidate_cvs (public_id);

CREATE INDEX idx_candidate_cvs_candidate_created_at
    ON candidate_cvs (candidate_id, created_at DESC);

COMMENT ON CONSTRAINT fk_candidate_cvs_candidate_id ON candidate_cvs IS
    'CVs belong to a candidate profile; deleting the profile removes its CV metadata.';
COMMENT ON INDEX uk_candidate_cvs_one_default IS
    'At most one default CV per candidate.';
COMMENT ON INDEX uk_candidate_cvs_public_id IS
    'Cloudinary public ids are unique storage keys.';
