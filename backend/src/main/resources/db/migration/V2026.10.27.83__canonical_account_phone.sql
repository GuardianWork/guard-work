-- Deploy with old contact writers stopped. Preserve original contacts/proof as history.
LOCK TABLE users, candidate_profiles, employer_profiles IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE canonical_phone_backfill ON COMMIT DROP AS
SELECT user_id, phone,
       CASE WHEN btrim(phone) ~ '^0[35789][0-9]{8}$' THEN '+84' || substring(btrim(phone) FROM 2)
            WHEN btrim(phone) ~ '^\+84[35789][0-9]{8}$' THEN btrim(phone)
       END AS normalized
FROM (
    SELECT user_id, phone_number AS phone FROM candidate_profiles
    UNION ALL
    SELECT user_id, phone FROM employer_profiles
) contacts
WHERE phone IS NOT NULL;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM canonical_phone_backfill WHERE normalized IS NULL) THEN
        RAISE EXCEPTION 'Canonical phone conversion blocked: invalid contacts. Review the phone preflight report.';
    END IF;
    IF EXISTS (SELECT 1 FROM canonical_phone_backfill GROUP BY user_id HAVING count(DISTINCT normalized) > 1) THEN
        RAISE EXCEPTION 'Canonical phone conversion blocked: differing contacts for one User. Review the phone preflight report.';
    END IF;
    IF EXISTS (SELECT 1 FROM canonical_phone_backfill GROUP BY normalized HAVING count(DISTINCT user_id) > 1) THEN
        RAISE EXCEPTION 'Canonical phone conversion blocked: cross-User collision. Review the phone preflight report.';
    END IF;
END;
$$;

ALTER TABLE users ADD COLUMN phone_number VARCHAR(20);
-- After the guards, DISTINCT only collapses equivalent representations for the same User.
UPDATE users u SET phone_number = contacts.normalized
FROM (SELECT DISTINCT user_id, normalized FROM canonical_phone_backfill) contacts
WHERE u.id = contacts.user_id;
ALTER TABLE users ADD CONSTRAINT uq_users_phone_number UNIQUE (phone_number);
ALTER TABLE users ADD CONSTRAINT ck_users_phone_number
    CHECK (phone_number IS NULL OR phone_number ~ '^\+84[35789][0-9]{8}$');
