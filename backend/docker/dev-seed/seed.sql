-- Public development fixtures. Never run against a shared or production database.
BEGIN;
SELECT pg_advisory_xact_lock(7190261019);

CREATE TEMP TABLE demo_accounts (
    username TEXT, email TEXT, first_name TEXT, last_name TEXT,
    platform_role TEXT, company_role TEXT, phone TEXT, email_verified BOOLEAN
) ON COMMIT DROP;
INSERT INTO demo_accounts VALUES
    ('demo_candidate', 'candidate@demo.guardwork.test', 'An', 'Nguyễn Văn', 'USER', NULL, '+84900000001', true),
    ('demo_staff', 'staff@demo.guardwork.test', 'Bình', 'Trần Thanh', 'STAFF', NULL, '+84900000002', true),
    ('demo_owner', 'owner@demo.guardwork.test', 'Chi', 'Lê Minh', 'USER', 'OWNER', '+84900000003', true),
    ('demo_company_admin', 'company-admin@demo.guardwork.test', 'Dũng', 'Phạm Anh', 'USER', 'ADMIN', '+84900000004', true),
    ('demo_recruiter', 'recruiter@demo.guardwork.test', 'Hà', 'Võ Thu', 'USER', 'RECRUITER', '+84900000005', true),
    ('demo_employer_unverified', 'employer-unverified@demo.guardwork.test', 'An', 'Nguyễn Minh', 'USER', NULL, '+84900000006', false);

-- Refuse identifier collisions rather than attach fixture privileges to another user.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM users u JOIN demo_accounts d ON u.username = d.username OR u.email = d.email
        WHERE u.username <> d.username OR u.email <> d.email OR u.role <> d.platform_role
    ) THEN
        RAISE EXCEPTION 'Mock account identifier collision; no data was seeded.';
    END IF;
END;
$$;

-- BCrypt cost 10 for the public local-only password GuardWork123!.
INSERT INTO users (username, email, first_name, last_name, password, role, status, email_verified, phone_number)
SELECT username, email, first_name, last_name,
    '$2a$10$.Sw9NzCJH98UmXsd3ep4punGVyL7FwuBWXWykQDXbDi7wTSj3SK/S',
    platform_role, 'ACTIVE', email_verified, phone
FROM demo_accounts
ON CONFLICT (username) DO NOTHING;

INSERT INTO candidate_profiles (user_id, phone_number, skills, experience, education, is_public)
SELECT id, '0900000001', '["Java", "Spring Boot", "PostgreSQL"]'::jsonb,
    '[{"company":"GuardWork Demo","role":"Thực tập sinh phát triển phần mềm","startDate":"2025-01","endDate":null,"description":"Sinh viên thực tập phát triển phần mềm."}]'::jsonb,
    '[{"institution":"Đại học GuardWork","degree":"Cử nhân Công nghệ thông tin","startYear":"2021","endYear":"2025","description":"Sinh viên ngành Công nghệ thông tin."}]'::jsonb,
    false
FROM users WHERE username = 'demo_candidate'
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO companies (name, tax_code, registration_certificate_url, email, verification_status,
    verified_by, verified_at, owner_user_id)
SELECT 'Công ty TNHH GuardWork Demo', 'GW-DEMO-LOCAL-001',
    'https://example.invalid/guardwork-demo-certificate.pdf', 'company@demo.guardwork.test',
    'VERIFIED', (SELECT id FROM users WHERE username = 'demo_staff'), now(), id
FROM users WHERE username = 'demo_owner'
ON CONFLICT (tax_code) DO NOTHING;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM companies c JOIN users u ON u.id = c.owner_user_id
        WHERE c.tax_code = 'GW-DEMO-LOCAL-001' AND u.username = 'demo_owner'
          AND c.email = 'company@demo.guardwork.test'
    ) THEN
        RAISE EXCEPTION 'Mock company identifier collision; no data was seeded.';
    END IF;
END;
$$;

-- Owner and membership are inserted in one transaction for deferred consistency checks.
INSERT INTO company_memberships (company_id, user_id, role, status, joined_at)
SELECT c.id, u.id, d.company_role, 'ACTIVE', now()
FROM demo_accounts d JOIN users u ON u.username = d.username
CROSS JOIN companies c
WHERE d.company_role IS NOT NULL AND c.tax_code = 'GW-DEMO-LOCAL-001'
  AND NOT EXISTS (SELECT 1 FROM company_memberships m WHERE m.user_id = u.id);

INSERT INTO employer_profiles (user_id, applicant, full_name, gender, email_verified_at, email_verified_email,
    company_name, work_address)
SELECT u.id, true, d.last_name || ' ' || d.first_name, 'UNDISCLOSED',
    CASE WHEN d.email_verified THEN now() END,
    CASE WHEN d.email_verified THEN lower(btrim(u.email)) END,
    CASE WHEN d.email_verified THEN 'Công ty TNHH GuardWork Demo' ELSE 'Công ty kiểm thử chưa xác thực' END, 'Thành phố Hồ Chí Minh, Việt Nam'
FROM demo_accounts d JOIN users u ON u.username = d.username
WHERE d.company_role IS NOT NULL OR d.username = 'demo_employer_unverified'
ON CONFLICT (user_id) DO NOTHING;

-- Stable markers live in append-only job creation facts so reruns preserve manual changes.
-- These are synthetic development histories, not evidence of real moderation or delivery.
DO $$
DECLARE
    fixture RECORD;
    company_id_value BIGINT;
    owner_id_value BIGINT;
    staff_id_value BIGINT;
    job_id_value BIGINT;
    revision_id_value BIGINT;
    replacement_id_value BIGINT;
    membership RECORD;
    content_value JSONB;
    deadline_value TIMESTAMPTZ;
    origin_time TIMESTAMPTZ := now() - interval '2 days';
    current_version BIGINT;
    marker_count INTEGER;
    final_event TEXT;
    reason_value TEXT;
BEGIN
    SELECT c.id, c.owner_user_id INTO STRICT company_id_value, owner_id_value
    FROM companies c WHERE c.tax_code = 'GW-DEMO-LOCAL-001';
    SELECT id INTO STRICT staff_id_value FROM users WHERE username = 'demo_staff';

    FOR fixture IN SELECT * FROM (VALUES
        ('GW93-HIDE-READY', 'PUBLISHED', false, 'Bảo vệ tòa nhà — sẵn sàng kiểm tra ẩn tin'),
        ('GW93-PUBLISHED-EDIT', 'PUBLISHED', true, 'Bảo vệ trung tâm thương mại — có bản sửa chờ duyệt'),
        ('GW93-SUSPENDED', 'SUSPENDED', false, 'Bảo vệ kho hàng — tin đã bị ẩn'),
        ('GW93-SUSPENDED-EDIT', 'SUSPENDED', true, 'Bảo vệ ca đêm — đã ẩn và có bản sửa chờ duyệt'),
        ('GW93-PAUSED', 'PAUSED', false, 'Bảo vệ trường học — tạm dừng tuyển dụng'),
        ('GW93-CLOSED', 'CLOSED', false, 'Bảo vệ văn phòng — đã đóng tuyển dụng'),
        ('GW93-EXPIRED', 'EXPIRED', false, 'Bảo vệ sự kiện — đã hết hạn'),
        ('GW93-DRAFT', 'DRAFT', false, 'Bảo vệ khách sạn — bản nháp'),
        ('GW93-PENDING', 'PENDING_REVIEW', false, 'Bảo vệ khu dân cư — chờ duyệt lần đầu')
    ) AS scenarios(fixture_key, target_status, pending_edit, title)
    LOOP
        SELECT count(*), min(e.job_id) INTO marker_count, job_id_value
        FROM job_audit_events e WHERE e.event_type = 'JOB_CREATED'
          AND e.facts ->> 'developmentFixture' = fixture.fixture_key;
        IF marker_count > 1 OR (marker_count = 1 AND NOT EXISTS (
            SELECT 1 FROM job_postings j WHERE j.id = job_id_value
              AND j.company_id = company_id_value AND j.creator_user_id = owner_id_value
        )) THEN
            RAISE EXCEPTION 'Mock job identifier collision; no data was seeded.';
        END IF;
        IF marker_count = 1 THEN CONTINUE; END IF;

        -- Never reset an existing ban, verification decision or account/membership change.
        IF NOT EXISTS (SELECT 1 FROM companies c WHERE c.id = company_id_value
              AND c.verification_status = 'VERIFIED' AND NOT c.is_banned)
          OR NOT EXISTS (SELECT 1 FROM users u WHERE u.id = owner_id_value
              AND u.status = 'ACTIVE' AND u.email_verified)
          OR NOT EXISTS (SELECT 1 FROM users u WHERE u.id = staff_id_value
              AND u.status = 'ACTIVE' AND u.role = 'STAFF')
          OR NOT EXISTS (SELECT 1 FROM company_memberships m WHERE m.company_id = company_id_value
              AND m.user_id = owner_id_value AND m.role = 'OWNER' AND m.status = 'ACTIVE') THEN
            RAISE EXCEPTION 'New mock jobs require eligible demo Owner, Staff and verified Company; existing state was preserved.';
        END IF;

        deadline_value := CASE WHEN fixture.target_status = 'EXPIRED'
            THEN now() - interval '1 day' ELSE now() + interval '30 days' END;
        content_value := jsonb_build_object(
            'title', fixture.title,
            'description', E'Mô tả công việc\nKiểm soát người ra vào, tuần tra và ghi nhận sự việc tại địa điểm làm việc.\n\nYêu cầu\nCó tinh thần trách nhiệm, giao tiếp lịch sự và sẵn sàng làm việc theo ca.\n\nQuyền lợi\nĐược hướng dẫn nghiệp vụ, cấp đồng phục và làm việc trong môi trường chuyên nghiệp.\n\nDữ liệu minh họa GuardWork, không phải tin tuyển dụng thực tế.',
            'employmentType', 'FULL_TIME', 'workArrangement', 'ONSITE',
            'location', 'Thành phố Hồ Chí Minh, Việt Nam',
            'deadline', to_char(deadline_value AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
            'headcount', 3,
            'compensation', jsonb_build_object('disclosed', true, 'minimum', 8000000,
                'maximum', 12000000, 'currency', 'VND', 'payPeriod', 'MONTH')
        );
        IF fixture.target_status = 'DRAFT' THEN
            content_value := jsonb_build_object('title', fixture.title, 'description', 'Bản nháp chưa hoàn thiện để thử luồng Company.');
        END IF;
        INSERT INTO job_postings(company_id, creator_user_id, status, owner_responsible, created_at, updated_at)
        VALUES (company_id_value, owner_id_value, 'DRAFT', true, origin_time, origin_time)
        RETURNING id INTO job_id_value;
        INSERT INTO job_posting_revisions(job_id, revision_number, content, state, creator_user_id, created_at)
        VALUES (job_id_value, 1, content_value, 'DRAFT', owner_id_value, origin_time)
        RETURNING id INTO revision_id_value;
        INSERT INTO job_audit_events(company_id, job_id, revision_id, actor_user_id, event_type, created_at, facts)
        VALUES (company_id_value, job_id_value, revision_id_value, owner_id_value, 'JOB_CREATED', origin_time,
            jsonb_build_object('ownerResponsible', true, 'developmentFixture', fixture.fixture_key));
        current_version := 0;

        IF fixture.target_status <> 'DRAFT' THEN
            UPDATE job_posting_revisions SET state = 'PENDING_REVIEW', submitter_user_id = owner_id_value,
                submitted_at = origin_time + interval '1 hour', version = 1 WHERE id = revision_id_value;
            current_version := 1;
            UPDATE job_postings SET version = current_version, updated_at = origin_time + interval '1 hour' WHERE id = job_id_value;
            INSERT INTO job_audit_events(company_id, job_id, revision_id, actor_user_id, event_type, created_at, facts)
            VALUES (company_id_value, job_id_value, revision_id_value, owner_id_value, 'JOB_REVISION_SUBMITTED',
                origin_time + interval '1 hour', jsonb_build_object('jobVersion', current_version, 'revisionVersion', 1));
        END IF;

        IF fixture.target_status NOT IN ('DRAFT', 'PENDING_REVIEW') THEN
            UPDATE job_posting_revisions SET state = 'APPROVED', reviewer_user_id = staff_id_value,
                decided_at = origin_time + interval '2 hours', version = 2 WHERE id = revision_id_value;
            current_version := 2;
            UPDATE job_postings SET status = 'PUBLISHED', published_revision_id = revision_id_value,
                approved_deadline = deadline_value, version = current_version, updated_at = origin_time + interval '2 hours'
            WHERE id = job_id_value;
            INSERT INTO job_audit_events(company_id, job_id, revision_id, actor_user_id, event_type, created_at, facts)
            VALUES (company_id_value, job_id_value, revision_id_value, staff_id_value, 'JOB_REVISION_APPROVED',
                origin_time + interval '2 hours', jsonb_build_object('jobVersion', current_version, 'revisionVersion', 2));
            INSERT INTO audit_logs(admin_id, action, target_type, target_id, new_payload, ip_address, user_agent, created_at)
            VALUES (staff_id_value, 'JOB_REVISION_APPROVED', 'JOB_REVISION', revision_id_value::text,
                '{"outcome":"APPROVED"}', '127.0.0.1', 'GuardWork local development seed', origin_time + interval '2 hours');
        END IF;

        IF fixture.pending_edit THEN
            INSERT INTO job_posting_revisions(job_id, revision_number, source_revision_id, content, state, creator_user_id, created_at)
            VALUES (job_id_value, 2, revision_id_value,
                content_value || jsonb_build_object('description', content_value ->> 'description' || E'\n\nBản chỉnh sửa đang chờ duyệt: bổ sung lịch làm việc linh hoạt.'),
                'DRAFT', owner_id_value, origin_time + interval '3 hours') RETURNING id INTO replacement_id_value;
            current_version := current_version + 1;
            INSERT INTO job_audit_events(company_id, job_id, revision_id, actor_user_id, event_type, created_at, facts)
            VALUES (company_id_value, job_id_value, replacement_id_value, owner_id_value, 'JOB_DRAFT_DERIVED',
                origin_time + interval '3 hours', jsonb_build_object('sourceRevisionId', revision_id_value, 'jobVersion', current_version));
            UPDATE job_posting_revisions SET state = 'PENDING_REVIEW', submitter_user_id = owner_id_value,
                submitted_at = origin_time + interval '4 hours', version = 1 WHERE id = replacement_id_value;
            current_version := current_version + 1;
            UPDATE job_postings SET version = current_version, updated_at = origin_time + interval '4 hours' WHERE id = job_id_value;
            INSERT INTO job_audit_events(company_id, job_id, revision_id, actor_user_id, event_type, created_at, facts)
            VALUES (company_id_value, job_id_value, replacement_id_value, owner_id_value, 'JOB_REVISION_SUBMITTED',
                origin_time + interval '4 hours', jsonb_build_object('jobVersion', current_version, 'revisionVersion', 1));
        END IF;

        IF fixture.target_status IN ('SUSPENDED', 'PAUSED', 'CLOSED', 'EXPIRED') THEN
            current_version := current_version + 1;
            reason_value := CASE WHEN fixture.target_status = 'SUSPENDED'
                THEN 'Dữ liệu minh họa: nội dung chưa đáp ứng quy định đăng tin.' ELSE NULL END;
            final_event := 'JOB_' || fixture.target_status;
            UPDATE job_postings SET status = fixture.target_status, version = current_version,
                updated_at = CASE WHEN fixture.target_status = 'EXPIRED' THEN deadline_value ELSE origin_time + interval '5 hours' END,
                closed_at = CASE WHEN fixture.target_status = 'CLOSED' THEN origin_time + interval '5 hours' END,
                expired_at = CASE WHEN fixture.target_status = 'EXPIRED' THEN deadline_value END
            WHERE id = job_id_value;
            INSERT INTO job_audit_events(company_id, job_id, revision_id, actor_user_id, event_type, created_at, facts)
            VALUES (company_id_value, job_id_value, revision_id_value,
                CASE WHEN fixture.target_status = 'EXPIRED' THEN NULL
                     WHEN fixture.target_status = 'SUSPENDED' THEN staff_id_value ELSE owner_id_value END,
                final_event, CASE WHEN fixture.target_status = 'EXPIRED' THEN deadline_value ELSE origin_time + interval '5 hours' END,
                jsonb_strip_nulls(jsonb_build_object('previousStatus', 'PUBLISHED', 'status', fixture.target_status,
                    'reason', reason_value, 'jobVersion', current_version)));
            IF fixture.target_status = 'SUSPENDED' THEN
                INSERT INTO audit_logs(admin_id, action, target_type, target_id, old_payload, new_payload, reason, ip_address, user_agent, created_at)
                VALUES (staff_id_value, final_event, 'JOB', job_id_value::text, '{"status":"PUBLISHED"}',
                    '{"status":"SUSPENDED"}', reason_value, '127.0.0.1', 'GuardWork local development seed', origin_time + interval '5 hours');
            END IF;
        END IF;

        -- Grant only an existing current Recruiter period; removed memberships stay removed.
        SELECT m.* INTO membership FROM company_memberships m JOIN users u ON u.id = m.user_id
        WHERE m.company_id = company_id_value AND m.role = 'RECRUITER' AND m.status = 'ACTIVE'
          AND u.username = 'demo_recruiter' AND u.status = 'ACTIVE' AND u.email_verified;
        IF FOUND THEN
            INSERT INTO job_assignments(job_id, company_id, membership_id, membership_joined_at, assigned_by_user_id, assigned_at)
            VALUES (job_id_value, company_id_value, membership.id, membership.joined_at, owner_id_value, now());
            current_version := current_version + 1;
            UPDATE job_postings SET version = current_version, updated_at = now() WHERE id = job_id_value;
            INSERT INTO job_audit_events(company_id, job_id, membership_id, actor_user_id, event_type, created_at, facts)
            VALUES (company_id_value, job_id_value, membership.id, owner_id_value, 'JOB_ASSIGNED', now(),
                jsonb_build_object('jobVersion', current_version));
        END IF;
    END LOOP;
END;
$$;

-- Seed demo CV library metadata for the candidate portal screens.
INSERT INTO candidate_cvs (id, candidate_id, file_name, file_size, content_type, public_id, is_default, created_at, updated_at)
SELECT gen_random_uuid(), cp.id, 'Nguyen_Van_An_CV.pdf', 204800, 'application/pdf',
    'candidates/' || cp.id || '/cvs/demo-default', true, now(), now()
FROM candidate_profiles cp JOIN users u ON u.id = cp.user_id
WHERE u.username = 'demo_candidate'
ON CONFLICT (public_id) DO NOTHING;

INSERT INTO candidate_cvs (id, candidate_id, file_name, file_size, content_type, public_id, is_default, created_at, updated_at)
SELECT gen_random_uuid(), cp.id, 'GuardianWork_Portfolio.docx', 128000,
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    'candidates/' || cp.id || '/cvs/demo-portfolio', false, now(), now()
FROM candidate_profiles cp JOIN users u ON u.id = cp.user_id
WHERE u.username = 'demo_candidate'
ON CONFLICT (public_id) DO NOTHING;

COMMIT;
