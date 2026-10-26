CREATE TABLE job_postings (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    creator_user_id BIGINT NOT NULL REFERENCES users(id),
    status VARCHAR(30) NOT NULL CHECK (status IN ('DRAFT','PUBLISHED','PAUSED','CLOSED','EXPIRED')),
    owner_responsible BOOLEAN NOT NULL,
    published_revision_id BIGINT,
    approved_deadline TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    expired_at TIMESTAMPTZ,
    UNIQUE(id, company_id),
    CHECK ((published_revision_id IS NULL) = (approved_deadline IS NULL)),
    CHECK (status = 'DRAFT' OR published_revision_id IS NOT NULL),
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    CHECK ((status = 'EXPIRED') = (expired_at IS NOT NULL))
);
CREATE INDEX ix_job_company_queue ON job_postings(company_id, created_at DESC, id DESC);
CREATE INDEX ix_job_deadline ON job_postings(approved_deadline) WHERE status IN ('PUBLISHED','PAUSED');
CREATE TABLE job_posting_revisions (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES job_postings(id),
    revision_number INTEGER NOT NULL CHECK (revision_number > 0),
    source_revision_id BIGINT,
    content JSONB NOT NULL CHECK (jsonb_typeof(content) = 'object' AND octet_length(content::text) <= 262144),
    state VARCHAR(30) NOT NULL CHECK (state IN ('DRAFT','PENDING_REVIEW','APPROVED','REJECTED','WITHDRAWN')),
    creator_user_id BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    submitter_user_id BIGINT REFERENCES users(id),
    submitted_at TIMESTAMPTZ,
    reviewer_user_id BIGINT REFERENCES users(id),
    decided_at TIMESTAMPTZ,
    rejection_reason TEXT,
    withdrawer_user_id BIGINT REFERENCES users(id),
    withdrawn_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE(job_id, revision_number),
    UNIQUE(id, job_id),
    FOREIGN KEY(source_revision_id, job_id) REFERENCES job_posting_revisions(id, job_id),
    CHECK ((state = 'DRAFT' AND submitted_at IS NULL AND submitter_user_id IS NULL)
        OR (state <> 'DRAFT' AND submitted_at IS NOT NULL AND submitter_user_id IS NOT NULL)),
    CHECK ((state IN ('APPROVED','REJECTED') AND decided_at IS NOT NULL AND reviewer_user_id IS NOT NULL)
        OR (state NOT IN ('APPROVED','REJECTED') AND decided_at IS NULL AND reviewer_user_id IS NULL)),
    CHECK ((state = 'REJECTED' AND rejection_reason IS NOT NULL AND char_length(btrim(rejection_reason)) BETWEEN 10 AND 1000)
        OR (state <> 'REJECTED' AND rejection_reason IS NULL)),
    CHECK ((state = 'WITHDRAWN' AND withdrawn_at IS NOT NULL AND withdrawer_user_id IS NOT NULL)
        OR (state <> 'WITHDRAWN' AND withdrawn_at IS NULL AND withdrawer_user_id IS NULL))
);
CREATE UNIQUE INDEX uq_job_working_revision ON job_posting_revisions(job_id) WHERE state IN ('DRAFT','PENDING_REVIEW');
CREATE INDEX ix_job_pending_review ON job_posting_revisions(submitted_at,id) WHERE state = 'PENDING_REVIEW';
ALTER TABLE job_postings ADD CONSTRAINT fk_job_published_revision FOREIGN KEY(published_revision_id,id) REFERENCES job_posting_revisions(id,job_id);
CREATE TABLE job_assignments (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    membership_id BIGINT NOT NULL REFERENCES company_memberships(id),
    membership_joined_at TIMESTAMPTZ NOT NULL,
    assigned_by_user_id BIGINT NOT NULL REFERENCES users(id),
    assigned_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    ended_by_user_id BIGINT REFERENCES users(id),
    end_reason VARCHAR(50),
    FOREIGN KEY(job_id,company_id) REFERENCES job_postings(id,company_id),
    CHECK ((ended_at IS NULL AND ended_by_user_id IS NULL AND end_reason IS NULL)
        OR (ended_at IS NOT NULL AND ended_by_user_id IS NOT NULL AND end_reason IS NOT NULL AND ended_at >= assigned_at))
);
CREATE UNIQUE INDEX uq_job_active_assignment ON job_assignments(job_id,membership_id) WHERE ended_at IS NULL;
CREATE INDEX ix_job_assignment_member ON job_assignments(membership_id,job_id) WHERE ended_at IS NULL;
CREATE TABLE job_audit_events (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    job_id BIGINT NOT NULL,
    revision_id BIGINT,
    membership_id BIGINT REFERENCES company_memberships(id),
    actor_user_id BIGINT REFERENCES users(id),
    event_type VARCHAR(60) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    facts JSONB NOT NULL DEFAULT '{}',
    FOREIGN KEY(job_id,company_id) REFERENCES job_postings(id,company_id),
    FOREIGN KEY(revision_id,job_id) REFERENCES job_posting_revisions(id,job_id),
    CHECK (actor_user_id IS NOT NULL OR event_type = 'JOB_EXPIRED')
);
CREATE INDEX ix_job_audit ON job_audit_events(job_id,created_at,id);
CREATE TRIGGER trg_job_audit_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON job_audit_events
FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_logs_mutation();
CREATE FUNCTION protect_job_revision() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Revision history cannot be deleted' USING ERRCODE='23514'; END IF;
    IF (NEW.job_id,NEW.revision_number,NEW.source_revision_id,NEW.creator_user_id,NEW.created_at)
       IS DISTINCT FROM (OLD.job_id,OLD.revision_number,OLD.source_revision_id,OLD.creator_user_id,OLD.created_at)
       OR OLD.state IN ('APPROVED','REJECTED','WITHDRAWN')
       OR (OLD.state = 'DRAFT' AND NEW.state NOT IN ('DRAFT','PENDING_REVIEW'))
       OR (OLD.state = 'PENDING_REVIEW' AND (NEW.state NOT IN ('APPROVED','REJECTED','WITHDRAWN')
           OR (NEW.content,NEW.submitter_user_id,NEW.submitted_at) IS DISTINCT FROM (OLD.content,OLD.submitter_user_id,OLD.submitted_at))) THEN
        RAISE EXCEPTION 'Invalid revision history mutation' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_job_revision_history BEFORE UPDATE OR DELETE ON job_posting_revisions FOR EACH ROW EXECUTE FUNCTION protect_job_revision();
CREATE TRIGGER trg_job_revision_truncate BEFORE TRUNCATE ON job_posting_revisions FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_logs_mutation();
CREATE FUNCTION protect_job_posting() RETURNS trigger AS $$
DECLARE r job_posting_revisions;
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Job history cannot be deleted' USING ERRCODE='23514'; END IF;
    IF TG_OP = 'UPDATE' AND ((NEW.company_id,NEW.creator_user_id,NEW.created_at) IS DISTINCT FROM (OLD.company_id,OLD.creator_user_id,OLD.created_at)
        OR (OLD.status IN ('CLOSED','EXPIRED') AND NEW.status <> OLD.status)) THEN
        RAISE EXCEPTION 'Invalid job history mutation' USING ERRCODE='23514';
    END IF;
    IF NEW.published_revision_id IS NOT NULL THEN
        SELECT * INTO r FROM job_posting_revisions WHERE id=NEW.published_revision_id AND job_id=NEW.id;
        IF NOT FOUND OR r.state <> 'APPROVED' OR (r.content->>'deadline')::timestamptz IS DISTINCT FROM NEW.approved_deadline THEN
            RAISE EXCEPTION 'Published revision and deadline must be approved and consistent' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_job_history BEFORE INSERT OR UPDATE OR DELETE ON job_postings FOR EACH ROW EXECUTE FUNCTION protect_job_posting();
CREATE TRIGGER trg_job_truncate BEFORE TRUNCATE ON job_postings FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_logs_mutation();
CREATE FUNCTION protect_job_assignment() RETURNS trigger AS $$
DECLARE m company_memberships;
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Assignment history cannot be deleted' USING ERRCODE='23514'; END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.ended_at IS NOT NULL OR (NEW.job_id,NEW.company_id,NEW.membership_id,NEW.membership_joined_at,NEW.assigned_by_user_id,NEW.assigned_at)
            IS DISTINCT FROM (OLD.job_id,OLD.company_id,OLD.membership_id,OLD.membership_joined_at,OLD.assigned_by_user_id,OLD.assigned_at)
            OR NEW.ended_at IS NULL THEN
            RAISE EXCEPTION 'Invalid assignment history mutation' USING ERRCODE='23514';
        END IF;
    ELSE
        SELECT * INTO m FROM company_memberships WHERE id=NEW.membership_id FOR UPDATE;
        IF NOT FOUND OR m.company_id <> NEW.company_id OR m.status <> 'ACTIVE' OR m.role <> 'RECRUITER'
            OR m.joined_at <> NEW.membership_joined_at OR NOT EXISTS (SELECT 1 FROM users WHERE id=m.user_id AND status='ACTIVE') THEN
            RAISE EXCEPTION 'Assignment requires current same-Company Recruiter period' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_job_assignment_history BEFORE INSERT OR UPDATE OR DELETE ON job_assignments FOR EACH ROW EXECUTE FUNCTION protect_job_assignment();
CREATE TRIGGER trg_job_assignment_truncate BEFORE TRUNCATE ON job_assignments FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_logs_mutation();
ALTER TABLE notification_outbox DROP CONSTRAINT notification_outbox_event_type_check;
ALTER TABLE user_notifications DROP CONSTRAINT user_notifications_event_type_check;
ALTER TABLE notification_outbox ADD CONSTRAINT notification_outbox_event_type_check CHECK(event_type IN ('RECRUITER_ACCEPTED','MEMBERSHIP_REMOVED','JOB_ASSIGNED','JOB_UNASSIGNED','JOB_REVISION_APPROVED','JOB_REVISION_REJECTED','JOB_NEEDS_REASSIGNMENT'));
ALTER TABLE user_notifications ADD CONSTRAINT user_notifications_event_type_check CHECK(event_type IN ('RECRUITER_ACCEPTED','MEMBERSHIP_REMOVED','JOB_ASSIGNED','JOB_UNASSIGNED','JOB_REVISION_APPROVED','JOB_REVISION_REJECTED','JOB_NEEDS_REASSIGNMENT'));
ALTER TABLE notification_outbox ADD COLUMN job_id BIGINT REFERENCES job_postings(id), ADD COLUMN revision_id BIGINT;
ALTER TABLE user_notifications ADD COLUMN job_id BIGINT REFERENCES job_postings(id), ADD COLUMN revision_id BIGINT;
ALTER TABLE notification_outbox ADD FOREIGN KEY(revision_id,job_id) REFERENCES job_posting_revisions(id,job_id), ADD CHECK(revision_id IS NULL OR job_id IS NOT NULL);
ALTER TABLE user_notifications ADD FOREIGN KEY(revision_id,job_id) REFERENCES job_posting_revisions(id,job_id), ADD CHECK(revision_id IS NULL OR job_id IS NOT NULL);
-- Retained assignment and notification context must remain in the original Company.
ALTER TABLE company_memberships ADD CONSTRAINT uq_job_membership_company UNIQUE(id,company_id);
ALTER TABLE job_assignments ADD FOREIGN KEY(membership_id,company_id) REFERENCES company_memberships(id,company_id);
ALTER TABLE notification_outbox ADD FOREIGN KEY(job_id,company_id) REFERENCES job_postings(id,company_id);
ALTER TABLE user_notifications ADD FOREIGN KEY(job_id,company_id) REFERENCES job_postings(id,company_id);
CREATE FUNCTION protect_job_notification_context() RETURNS trigger AS $$
BEGIN
    IF (NEW.recipient_user_id,NEW.event_type,NEW.company_id,NEW.company_name,NEW.job_id,NEW.revision_id,NEW.created_at)
        IS DISTINCT FROM (OLD.recipient_user_id,OLD.event_type,OLD.company_id,OLD.company_name,OLD.job_id,OLD.revision_id,OLD.created_at) THEN
        RAISE EXCEPTION 'Notification event context is immutable' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME = 'notification_outbox' THEN
        IF NEW.event_id IS DISTINCT FROM OLD.event_id THEN RAISE EXCEPTION 'Notification identity is immutable' USING ERRCODE='23514'; END IF;
    ELSE
        IF (NEW.id,NEW.source_event_id) IS DISTINCT FROM (OLD.id,OLD.source_event_id) THEN RAISE EXCEPTION 'Notification identity is immutable' USING ERRCODE='23514'; END IF;
    END IF;
    RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_job_outbox_context BEFORE UPDATE ON notification_outbox FOR EACH ROW EXECUTE FUNCTION protect_job_notification_context();
CREATE TRIGGER trg_job_inbox_context BEFORE UPDATE ON user_notifications FOR EACH ROW EXECUTE FUNCTION protect_job_notification_context();
