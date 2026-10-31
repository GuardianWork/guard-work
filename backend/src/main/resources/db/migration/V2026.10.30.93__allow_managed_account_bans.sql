-- Retain legacy SUSPENDED and DELETED states; managed Staff bans have their own lifecycle.
ALTER TABLE users DROP CONSTRAINT users_status_check;
ALTER TABLE users ADD CONSTRAINT users_status_check
 CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED', 'BANNED'));
