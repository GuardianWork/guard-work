/*
  Protect the application id as part of application identity.

  The initial application migration protected candidate, job, company, revision
  and application time, but omitted the primary key. This makes the identity
  guard complete for direct updates.
*/
CREATE OR REPLACE FUNCTION protect_application_identity() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Application history cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF (NEW.id, NEW.candidate_id, NEW.job_id, NEW.company_id, NEW.submitted_revision_id, NEW.applied_at)
       IS DISTINCT FROM
       (OLD.id, OLD.candidate_id, OLD.job_id, OLD.company_id, OLD.submitted_revision_id, OLD.applied_at) THEN
        RAISE EXCEPTION 'Application identity is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;