-- Persist the per-code guess budget; invitation tokens and verified accounts are unchanged.
ALTER TABLE user_email_verification
    ADD COLUMN failed_attempts INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT user_email_verification_attempts CHECK (failed_attempts BETWEEN 0 AND 5);

-- Previously emailed links cannot be used as codes after the coordinated client rollout.
UPDATE user_email_verification SET secret_hash = NULL, consumed_at = CURRENT_TIMESTAMP
WHERE consumed_at IS NULL;
