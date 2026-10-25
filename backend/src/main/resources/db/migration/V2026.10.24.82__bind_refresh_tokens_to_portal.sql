-- Existing sessions follow the persisted Platform role, not Company membership.
ALTER TABLE refresh_tokens ADD COLUMN portal VARCHAR(6);
UPDATE refresh_tokens token
SET portal = CASE WHEN account.role = 'STAFF' THEN 'staff' ELSE 'public' END
FROM users account
WHERE account.id = token.user_id;
ALTER TABLE refresh_tokens ALTER COLUMN portal SET NOT NULL;
ALTER TABLE refresh_tokens ADD CONSTRAINT refresh_tokens_portal_check
    CHECK (portal IN ('public', 'staff'));
