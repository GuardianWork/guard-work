-- Read-only operator report. Contains personal contacts: restrict access and do not log/publish output.
-- Run before cutover and resolve every blocking row through reviewed corrections; never pick a winner.
BEGIN TRANSACTION READ ONLY;
WITH contacts AS (
    SELECT user_id, 'candidate_profiles' AS source, phone_number AS phone FROM candidate_profiles
    UNION ALL
    SELECT user_id, 'employer_profiles', phone FROM employer_profiles
), normalized AS (
    SELECT *, CASE WHEN btrim(phone) ~ '^0[35789][0-9]{8}$' THEN '+84' || substring(btrim(phone) FROM 2)
                   WHEN btrim(phone) ~ '^\+84[35789][0-9]{8}$' THEN btrim(phone) END AS canonical
    FROM contacts WHERE phone IS NOT NULL
), issues AS (
    SELECT 'INVALID_CONTACT' AS issue, user_id, source, phone, canonical FROM normalized WHERE canonical IS NULL
    UNION ALL
    SELECT 'DIFFERING_SAME_USER_CONTACTS', n.user_id, n.source, n.phone, n.canonical FROM normalized n
    WHERE user_id IN (SELECT user_id FROM normalized GROUP BY user_id HAVING count(DISTINCT canonical) > 1)
    UNION ALL
    SELECT 'CROSS_USER_COLLISION', n.user_id, n.source, n.phone, n.canonical FROM normalized n
    WHERE canonical IN (SELECT canonical FROM normalized GROUP BY canonical HAVING count(DISTINCT user_id) > 1)
)
SELECT * FROM issues ORDER BY issue, user_id, source;

-- Pending attempts are informational only and never supply a current account contact.
SELECT count(*) AS pending_attempts_not_used_for_backfill
FROM employer_registrations WHERE promoted_user_id IS NULL;
COMMIT;
