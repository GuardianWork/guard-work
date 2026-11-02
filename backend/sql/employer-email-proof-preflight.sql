-- Read-only operator report. Restrict access: contains account email and actual-proof timestamps.
-- Resolve normalized collisions and investigate known historical email changes before cutover.
BEGIN TRANSACTION READ ONLY;
SELECT lower(btrim(email)) AS normalized_email, array_agg(id ORDER BY id) AS user_ids
FROM users GROUP BY lower(btrim(email)) HAVING count(*) > 1;

-- These existing actual proofs will bind to the linked current email, never to a legacy User boolean.
-- Review any known historical email change before allowing the migration; do not infer proof ownership.
SELECT p.user_id, lower(btrim(u.email)) AS current_email, p.email_verified_at
FROM employer_profiles p JOIN users u ON u.id = p.user_id
WHERE p.email_verified_at IS NOT NULL ORDER BY p.user_id;

-- Old unbound member challenges stay retained but require authenticated resend after cutover.
SELECT count(*) AS unbound_member_challenges_requiring_resend
FROM employer_profiles WHERE email_secret_hash IS NOT NULL;
SELECT count(*) AS retained_pending_attempts_requiring_fresh_registration
FROM employer_registrations WHERE promoted_user_id IS NULL;
COMMIT;
