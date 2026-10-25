-- Existing Companies deliberately remain unassigned; identifiers are never ownership proof.
ALTER TABLE companies ADD COLUMN owner_user_id BIGINT REFERENCES users(id);

CREATE TABLE company_memberships (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    role VARCHAR(30) NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'RECRUITER')),
    status VARCHAR(30) NOT NULL CHECK (status IN ('ACTIVE', 'LEFT', 'REMOVED')),
    joined_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    ended_by_user_id BIGINT REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((status = 'ACTIVE' AND ended_at IS NULL AND ended_by_user_id IS NULL)
        OR (status <> 'ACTIVE' AND ended_at IS NOT NULL AND ended_by_user_id IS NOT NULL AND ended_at >= joined_at))
);
CREATE UNIQUE INDEX uq_company_membership_one_active_per_user
    ON company_memberships(user_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_company_membership_one_active_owner_per_company
    ON company_memberships(company_id) WHERE status = 'ACTIVE' AND role = 'OWNER';
CREATE INDEX idx_company_memberships_company ON company_memberships(company_id, status);

CREATE TABLE company_membership_audit_events (
    id BIGSERIAL PRIMARY KEY,
    actor_user_id BIGINT NOT NULL REFERENCES users(id),
    subject_user_id BIGINT NOT NULL REFERENCES users(id),
    company_id BIGINT NOT NULL REFERENCES companies(id),
    event_type VARCHAR(100) NOT NULL,
    membership_id BIGINT REFERENCES company_memberships(id),
    invitation_id BIGINT,
    review_case_reference VARCHAR(255),
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_membership_audit_company ON company_membership_audit_events(company_id, created_at);
CREATE TRIGGER trg_protect_company_membership_audit
BEFORE UPDATE OR DELETE OR TRUNCATE ON company_membership_audit_events
FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_logs_mutation();

CREATE FUNCTION prevent_owner_reassignment() RETURNS trigger AS $$
BEGIN
    IF OLD.owner_user_id IS NOT NULL AND NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id THEN
        RAISE EXCEPTION 'Company ownership cannot be transferred or cleared in this milestone.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_prevent_owner_reassignment BEFORE UPDATE ON companies
FOR EACH ROW EXECUTE FUNCTION prevent_owner_reassignment();

CREATE FUNCTION enforce_company_owner() RETURNS trigger AS $$
DECLARE target_id BIGINT;
DECLARE target_ids BIGINT[];
DECLARE declared_owner BIGINT;
DECLARE owner_count BIGINT;
DECLARE matching_count BIGINT;
BEGIN
    -- Check both sides of a possible membership move. Deferred checks see final transaction state.
    IF TG_TABLE_NAME = 'companies' THEN target_ids := ARRAY[NEW.id];
    ELSIF TG_OP = 'INSERT' THEN target_ids := ARRAY[NEW.company_id];
    ELSIF TG_OP = 'DELETE' THEN target_ids := ARRAY[OLD.company_id];
    ELSE target_ids := ARRAY[OLD.company_id, NEW.company_id]; END IF;
    FOR target_id IN SELECT DISTINCT id FROM unnest(target_ids) AS ids(id)
    LOOP
        SELECT owner_user_id INTO declared_owner FROM companies WHERE id = target_id FOR UPDATE;
        IF NOT FOUND THEN CONTINUE; END IF;
        SELECT count(*), count(*) FILTER (WHERE user_id = declared_owner)
          INTO owner_count, matching_count FROM company_memberships
          WHERE company_id = target_id AND status = 'ACTIVE' AND role = 'OWNER';
        IF (declared_owner IS NULL AND owner_count <> 0)
           OR (declared_owner IS NOT NULL AND (owner_count <> 1 OR matching_count <> 1)) THEN
            RAISE EXCEPTION 'Company must have exactly its declared active Owner.' USING ERRCODE = '23514';
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER trg_company_owner_consistency
AFTER INSERT OR UPDATE ON companies DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_company_owner();
CREATE CONSTRAINT TRIGGER trg_membership_owner_consistency
AFTER INSERT OR UPDATE OR DELETE ON company_memberships DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_company_owner();
