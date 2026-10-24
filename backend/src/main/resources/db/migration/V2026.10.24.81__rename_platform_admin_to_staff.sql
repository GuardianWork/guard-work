-- Platform authority is separate from company_memberships.role (including Company ADMIN).
ALTER TABLE users DROP CONSTRAINT users_role_check;
UPDATE users SET role = 'STAFF' WHERE role = 'ADMIN';
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('USER', 'STAFF'));
