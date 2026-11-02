-- Stop old account/proof writers and review the email preflight before this cutover.
LOCK TABLE users, employer_profiles IN SHARE ROW EXCLUSIVE MODE;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users GROUP BY lower(btrim(email)) HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'Email conversion blocked: normalized email collision. Review the email preflight report.';
    END IF;
END;
$$;
CREATE UNIQUE INDEX uq_users_normalized_email ON users (lower(btrim(email)));
ALTER TABLE employer_profiles ADD COLUMN email_challenge_email VARCHAR(255);
ALTER TABLE employer_profiles ADD COLUMN email_verified_email VARCHAR(255);
-- A legacy compatibility boolean never supplies actual proof. Preserve genuine timestamps.
UPDATE employer_profiles p SET email_verified_email = lower(btrim(u.email))
FROM users u WHERE u.id = p.user_id AND p.email_verified_at IS NOT NULL;
-- Existing unbound member challenges remain retained, but require authenticated resend.
-- Old pending registration records remain untouched and can no longer be promoted.
