ALTER TABLE users ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0 CHECK (session_version >= 0);
ALTER TABLE job_postings ADD COLUMN staff_suspended BOOLEAN NOT NULL DEFAULT false;
CREATE TABLE account_bans (
 id BIGSERIAL PRIMARY KEY,
 user_id BIGINT REFERENCES users(id),
 company_id BIGINT REFERENCES companies(id),
 reason VARCHAR(2000) NOT NULL CHECK (length(trim(reason)) > 0),
 started_at TIMESTAMPTZ NOT NULL,
 expires_at TIMESTAMPTZ,
 created_by BIGINT NOT NULL REFERENCES users(id),
 ended_at TIMESTAMPTZ,
 ended_by BIGINT REFERENCES users(id),
 end_reason VARCHAR(2000),
 CHECK ((user_id IS NOT NULL)::int + (company_id IS NOT NULL)::int = 1),
 CHECK (expires_at IS NULL OR expires_at > started_at),
 CHECK ((ended_at IS NULL AND ended_by IS NULL AND end_reason IS NULL) OR
        (ended_at IS NOT NULL AND end_reason IS NOT NULL AND length(trim(end_reason)) > 0)),
 CHECK (ended_at IS NULL OR ended_at >= started_at)
);
CREATE UNIQUE INDEX ux_account_bans_user ON account_bans(user_id) WHERE ended_at IS NULL;
CREATE UNIQUE INDEX ux_account_bans_company ON account_bans(company_id) WHERE ended_at IS NULL;
CREATE INDEX ix_account_bans_due ON account_bans(expires_at) WHERE ended_at IS NULL AND expires_at IS NOT NULL;
