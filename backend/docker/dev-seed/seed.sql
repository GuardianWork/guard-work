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


-- Integrated optional companion seed for job-search filter verification.
DO $$
DECLARE
    company_id_value BIGINT;
    owner_id_value BIGINT;
    staff_id_value BIGINT;
    fixture_count INTEGER;
    job_id_value BIGINT;
    revision_id_value BIGINT;
    deadline_value TIMESTAMPTZ;
    content_value JSONB;
BEGIN
    SELECT c.id, c.owner_user_id INTO STRICT company_id_value, owner_id_value
    FROM companies c WHERE c.tax_code = 'GW-DEMO-LOCAL-001';
    SELECT id INTO STRICT staff_id_value FROM users WHERE username = 'demo_staff';

    IF NOT EXISTS (SELECT 1 FROM users WHERE id = owner_id_value AND status = 'ACTIVE' AND email_verified) THEN
        RAISE EXCEPTION 'Demo owner is not eligible for seeding search fixtures.';
    END IF;

    -- Fixture 1: remote Java backend role.
    SELECT count(*) INTO fixture_count
    FROM job_audit_events WHERE facts ->> 'developmentFixture' = 'SEARCH-FILTER-REMOTE-JAVA';
    IF fixture_count = 0 THEN
        deadline_value := now() + interval '45 days';
        content_value := jsonb_build_object(
            'title', 'Lap trinh vien Java backend',
            'description', E'Phat trien API Spring Boot, thiet ke PostgreSQL va tu dong hoa kiem thu.\n\nPhu hop ung vien co kinh nghiem Java, Spring Boot, PostgreSQL va giao tiep tieng Anh.',
            'employmentType', 'FULL_TIME', 'workArrangement', 'REMOTE',
            'location', 'Ha Noi, Viet Nam',
            'deadline', to_char(deadline_value AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
            'headcount', 2,
            'compensation', jsonb_build_object('disclosed', true, 'minimum', 15000000,
                'maximum', 25000000, 'currency', 'VND', 'payPeriod', 'MONTH')
        );
        INSERT INTO job_postings(company_id, creator_user_id, status, owner_responsible, created_at, updated_at)
        VALUES (company_id_value, owner_id_value, 'DRAFT', true, now(), now()) RETURNING id INTO job_id_value;
        INSERT INTO job_posting_revisions(job_id, revision_number, content, state, creator_user_id,
            submitter_user_id, submitted_at, reviewer_user_id, decided_at, version, created_at)
        VALUES (job_id_value, 1, content_value, 'APPROVED', owner_id_value, owner_id_value,
            now() - interval '1 hour', staff_id_value, now(), 2, now())
        RETURNING id INTO revision_id_value;
        UPDATE job_postings SET status = 'PUBLISHED', published_revision_id = revision_id_value,
            approved_deadline = deadline_value, version = 2, updated_at = now() WHERE id = job_id_value;
        INSERT INTO job_audit_events(company_id, job_id, revision_id, actor_user_id, event_type, created_at, facts)
        VALUES (company_id_value, job_id_value, revision_id_value, owner_id_value, 'JOB_CREATED', now(),
            jsonb_build_object('ownerResponsible', true, 'developmentFixture', 'SEARCH-FILTER-REMOTE-JAVA'));
    END IF;

    -- Fixture 2: hybrid part-time customer support role.
    SELECT count(*) INTO fixture_count
    FROM job_audit_events WHERE facts ->> 'developmentFixture' = 'SEARCH-FILTER-HYBRID-SUPPORT';
    IF fixture_count = 0 THEN
        deadline_value := now() + interval '10 days';
        content_value := jsonb_build_object(
            'title', 'Chuyen vien ho tro khach hang',
            'description', E'Ho tro khach hang qua chat va dien thoai, ghi nhan yeu cau va phoi hop xu ly.',
            'employmentType', 'PART_TIME', 'workArrangement', 'HYBRID',
            'location', 'Da Nang, Viet Nam',
            'deadline', to_char(deadline_value AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
            'headcount', 1,
            'compensation', jsonb_build_object('disclosed', true, 'minimum', 5000000,
                'maximum', 8000000, 'currency', 'VND', 'payPeriod', 'MONTH')
        );
        INSERT INTO job_postings(company_id, creator_user_id, status, owner_responsible, created_at, updated_at)
        VALUES (company_id_value, owner_id_value, 'DRAFT', true, now(), now()) RETURNING id INTO job_id_value;
        INSERT INTO job_posting_revisions(job_id, revision_number, content, state, creator_user_id,
            submitter_user_id, submitted_at, reviewer_user_id, decided_at, version, created_at)
        VALUES (job_id_value, 1, content_value, 'APPROVED', owner_id_value, owner_id_value,
            now() - interval '1 hour', staff_id_value, now(), 2, now())
        RETURNING id INTO revision_id_value;
        UPDATE job_postings SET status = 'PUBLISHED', published_revision_id = revision_id_value,
            approved_deadline = deadline_value, version = 2, updated_at = now() WHERE id = job_id_value;
        INSERT INTO job_audit_events(company_id, job_id, revision_id, actor_user_id, event_type, created_at, facts)
        VALUES (company_id_value, job_id_value, revision_id_value, owner_id_value, 'JOB_CREATED', now(),
            jsonb_build_object('ownerResponsible', true, 'developmentFixture', 'SEARCH-FILTER-HYBRID-SUPPORT'));
    END IF;

    -- Fixture 3: internship marketing role.
    SELECT count(*) INTO fixture_count
    FROM job_audit_events WHERE facts ->> 'developmentFixture' = 'SEARCH-FILTER-INTERNSHIP-MARKETING';
    IF fixture_count = 0 THEN
        deadline_value := now() + interval '60 days';
        content_value := jsonb_build_object(
            'title', 'Thuc tap sinh Marketing',
            'description', E'Ho tro quan ly fanpage, viet bai va phan tich hieu qua chien dich quang cao.',
            'employmentType', 'INTERNSHIP', 'workArrangement', 'ONSITE',
            'location', 'Thanh pho Ho Chi Minh, Viet Nam',
            'deadline', to_char(deadline_value AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
            'headcount', 5,
            'compensation', jsonb_build_object('disclosed', true, 'minimum', 2000000,
                'maximum', 4000000, 'currency', 'VND', 'payPeriod', 'MONTH')
        );
        INSERT INTO job_postings(company_id, creator_user_id, status, owner_responsible, created_at, updated_at)
        VALUES (company_id_value, owner_id_value, 'DRAFT', true, now(), now()) RETURNING id INTO job_id_value;
        INSERT INTO job_posting_revisions(job_id, revision_number, content, state, creator_user_id,
            submitter_user_id, submitted_at, reviewer_user_id, decided_at, version, created_at)
        VALUES (job_id_value, 1, content_value, 'APPROVED', owner_id_value, owner_id_value,
            now() - interval '1 hour', staff_id_value, now(), 2, now())
        RETURNING id INTO revision_id_value;
        UPDATE job_postings SET status = 'PUBLISHED', published_revision_id = revision_id_value,
            approved_deadline = deadline_value, version = 2, updated_at = now() WHERE id = job_id_value;
        INSERT INTO job_audit_events(company_id, job_id, revision_id, actor_user_id, event_type, created_at, facts)
        VALUES (company_id_value, job_id_value, revision_id_value, owner_id_value, 'JOB_CREATED', now(),
            jsonb_build_object('ownerResponsible', true, 'developmentFixture', 'SEARCH-FILTER-INTERNSHIP-MARKETING'));
    END IF;
END;
$$;

-- Bulk published job postings for search/filter and browsing verification.
DO $$
DECLARE
    company_id_value BIGINT;
    owner_id_value BIGINT;
    staff_id_value BIGINT;
    fixture_count INTEGER;
    job_id_value BIGINT;
    revision_id_value BIGINT;
    deadline_value TIMESTAMPTZ;
    content_value JSONB;
    roles TEXT[] := ARRAY[
        'Kỹ sư phần mềm Backend', 'Lập trình viên Frontend React', 'Kỹ sư dữ liệu',
        'Kỹ sư DevOps', 'Chuyên viên kiểm thử phần mềm QA', 'Thiết kế UI/UX',
        'Chuyên viên marketing số', 'Chuyên viên nội dung', 'Quản trị nhân sự',
        'Chuyên viên kế toán tổng hợp', 'Chuyên viên tài chính doanh nghiệp', 'Nhân viên bán hàng',
        'Chuyên viên chăm sóc khách hàng', 'Nhân viên kho vận', 'Điều phối logistics',
        'Kỹ sư điện công nghiệp', 'Nhân viên sản xuất', 'Giám sát sản xuất',
        'Đầu bếp', 'Nhân viên phục vụ nhà hàng', 'Giáo viên tiểu học',
        'Giáo viên tiếng Anh', 'Nhân viên y tá', 'Bác sĩ đa khoa', 'Dược sĩ',
        'Kỹ sư xây dựng', 'Kiến trúc sư', 'Nhân viên an ninh',
        'Chuyên viên mua hàng', 'Nhân viên hành chính văn phòng'
    ];
    industries TEXT[] := ARRAY[
        'Công nghệ thông tin', 'Công nghệ thông tin', 'Dữ liệu và phân tích',
        'Công nghệ thông tin', 'Công nghệ thông tin', 'Thiết kế và truyền thông',
        'Marketing', 'Truyền thông', 'Nhân sự',
        'Tài chính - Kế toán', 'Tài chính', 'Bán lẻ',
        'Dịch vụ khách hàng', 'Logistics', 'Logistics',
        'Sản xuất công nghiệp', 'Sản xuất', 'Sản xuất',
        'Dịch vụ ăn uống', 'Dịch vụ ăn uống', 'Giáo dục',
        'Giáo dục', 'Y tế', 'Y tế', 'Y tế',
        'Xây dựng', 'Thiết kế và xây dựng', 'An ninh',
        'Chuỗi cung ứng', 'Hành chính'
    ];
    locations TEXT[] := ARRAY[
        'Hà Nội, Việt Nam', 'Thành phố Hồ Chí Minh, Việt Nam', 'Đà Nẵng, Việt Nam',
        'Hải Phòng, Việt Nam', 'Cần Thơ, Việt Nam', 'Huế, Việt Nam',
        'Nha Trang, Việt Nam', 'Biên Hòa, Việt Nam', 'Bình Dương, Việt Nam',
        'Vũng Tàu, Việt Nam'
    ];
    employment_types TEXT[] := ARRAY['FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERNSHIP', 'TEMPORARY'];
    work_arrangements TEXT[] := ARRAY['ONSITE', 'HYBRID', 'REMOTE'];
    levels TEXT[] := ARRAY['Junior', 'Middle', 'Senior', 'Lead'];
    n INTEGER;
    role_index INTEGER;
    location_value TEXT;
    employment_type TEXT;
    work_arrangement TEXT;
    level TEXT;
    title TEXT;
    industry TEXT;
    min_salary BIGINT;
    max_salary BIGINT;
    headcount INTEGER;
    fixture_key TEXT;
BEGIN
    SELECT c.id, c.owner_user_id INTO STRICT company_id_value, owner_id_value
    FROM companies c WHERE c.tax_code = 'GW-DEMO-LOCAL-001';
    SELECT id INTO STRICT staff_id_value FROM users WHERE username = 'demo_staff';

    IF NOT EXISTS (SELECT 1 FROM users WHERE id = owner_id_value AND status = 'ACTIVE' AND email_verified) THEN
        RAISE EXCEPTION 'Demo owner is not eligible for seeding bulk published jobs.';
    END IF;

    FOR n IN 1..100 LOOP
        fixture_key := 'PUBLISHED-JOB-' || lpad(n::text, 3, '0');
        SELECT count(*) INTO fixture_count
        FROM job_audit_events WHERE facts ->> 'developmentFixture' = fixture_key;
        IF fixture_count > 0 THEN
            CONTINUE;
        END IF;

        role_index := ((n - 1) % array_length(roles, 1)) + 1;
        level := levels[((n - 1) / 30) + 1];
        title := level || ' ' || roles[role_index];
        industry := industries[role_index];
        location_value := locations[((n * 3 - 2) % array_length(locations, 1)) + 1];
        employment_type := employment_types[((n - 1) % array_length(employment_types, 1)) + 1];
        work_arrangement := work_arrangements[((n * 2 - 1) % array_length(work_arrangements, 1)) + 1];
        headcount := 1 + ((n - 1) % 6);
        min_salary := 5000000 + ((n - 1) % 20) * 1000000;
        max_salary := min_salary + 3000000 + ((n - 1) % 5) * 1000000;
        deadline_value := now() + ((15 + (n % 75))::text || ' days')::interval;

        content_value := jsonb_build_object(
            'title', title,
            'description', 'Mô tả công việc cho vị trí ' || title || E'.\n\nTrách nhiệm chính:\n- Thực hiện nhiệm vụ chuyên môn theo phân công.\n- Phối hợp với các phòng ban liên quan.\n- Theo dõi KPI và báo cáo tiến độ.\n\nYêu cầu:\n- Có kiến thức và kỹ năng phù hợp với vị trí.\n- Giao tiếp tốt, chịu được áp lực công việc.\n\nQuyền lợi:\n- Lương cạnh tranh, thưởng theo hiệu suất.\n- Môi trường làm việc chuyên nghiệp.\n\nDữ liệu minh họa GuardWork, không phải tin tuyển dụng thực tế.',
            'industry', industry,
            'employmentType', employment_type,
            'workArrangement', work_arrangement,
            'location', location_value,
            'deadline', to_char(deadline_value AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
            'headcount', headcount,
            'compensation', jsonb_build_object('disclosed', true, 'minimum', min_salary,
                'maximum', max_salary, 'currency', 'VND', 'payPeriod', 'MONTH')
        );

        INSERT INTO job_postings(company_id, creator_user_id, status, owner_responsible, created_at, updated_at)
        VALUES (company_id_value, owner_id_value, 'DRAFT', true, now(), now()) RETURNING id INTO job_id_value;
        INSERT INTO job_posting_revisions(job_id, revision_number, content, state, creator_user_id,
            submitter_user_id, submitted_at, reviewer_user_id, decided_at, version, created_at)
        VALUES (job_id_value, 1, content_value, 'APPROVED', owner_id_value, owner_id_value,
            now() - interval '1 hour', staff_id_value, now(), 2, now())
        RETURNING id INTO revision_id_value;
        UPDATE job_postings SET status = 'PUBLISHED', published_revision_id = revision_id_value,
            approved_deadline = deadline_value, version = 2, updated_at = now() WHERE id = job_id_value;
        INSERT INTO job_audit_events(company_id, job_id, revision_id, actor_user_id, event_type, created_at, facts)
        VALUES (company_id_value, job_id_value, revision_id_value, owner_id_value, 'JOB_CREATED', now(),
            jsonb_build_object('ownerResponsible', true, 'developmentFixture', fixture_key));
    END LOOP;
END;
$$;

-- Seeded notification inbox fixtures (synthetic histories, not evidence of real delivery).
-- Each fixture uses a fixed event_id so reruns preserve manual reads and never duplicate delivery.
DO $$
DECLARE
    company_id_value BIGINT;
    company_name_value TEXT;
    owner_id_value BIGINT;
    recruiter_id_value BIGINT;
    candidate_user_id_value BIGINT;
    candidate_id_value UUID;
    staff_id_value BIGINT;
    job_a_id BIGINT;
    job_b_id BIGINT;
    job_c_id BIGINT;
    rev_a_id BIGINT;
    rev_b_id BIGINT;
    rev_c_id BIGINT;
    app_a_id UUID := 'a04a0000-0000-4000-8000-000000000001';
    app_b_id UUID := 'a04a0000-0000-4000-8000-000000000002';
    app_c_id UUID := 'a04a0000-0000-4000-8000-000000000003';
    app_a_at TIMESTAMPTZ := now() - interval '3 days';
    app_b_at TIMESTAMPTZ := now() - interval '2 days 12 hours';
    app_c_at TIMESTAMPTZ := now() - interval '2 days';
    base_time TIMESTAMPTZ := now() - interval '2 days 6 hours';
BEGIN
    SELECT c.id, c.name, c.owner_user_id INTO STRICT company_id_value, company_name_value, owner_id_value
    FROM companies c WHERE c.tax_code = 'GW-DEMO-LOCAL-001';
    SELECT id INTO STRICT owner_id_value FROM users WHERE username = 'demo_owner';
    SELECT id INTO STRICT recruiter_id_value FROM users WHERE username = 'demo_recruiter';
    SELECT id INTO STRICT candidate_user_id_value FROM users WHERE username = 'demo_candidate';
    SELECT id INTO STRICT staff_id_value FROM users WHERE username = 'demo_staff';
    SELECT id INTO STRICT candidate_id_value FROM candidate_profiles WHERE user_id = candidate_user_id_value;
    SELECT e.job_id INTO STRICT job_a_id FROM job_audit_events e WHERE e.event_type = 'JOB_CREATED' AND e.facts ->> 'developmentFixture' = 'GW93-HIDE-READY';
    SELECT e.job_id INTO STRICT job_b_id FROM job_audit_events e WHERE e.event_type = 'JOB_CREATED' AND e.facts ->> 'developmentFixture' = 'GW93-PUBLISHED-EDIT';
    SELECT e.job_id INTO STRICT job_c_id FROM job_audit_events e WHERE e.event_type = 'JOB_CREATED' AND e.facts ->> 'developmentFixture' = 'PUBLISHED-JOB-001';
    SELECT published_revision_id INTO STRICT rev_a_id FROM job_postings WHERE id = job_a_id;
    SELECT published_revision_id INTO STRICT rev_b_id FROM job_postings WHERE id = job_b_id;
    SELECT published_revision_id INTO STRICT rev_c_id FROM job_postings WHERE id = job_c_id;
    IF rev_a_id IS NULL OR rev_b_id IS NULL OR rev_c_id IS NULL THEN
        RAISE EXCEPTION 'Seeded notification jobs require published revisions.';
    END IF;

    -- Companion demo applications so APPLICATION_* fixtures satisfy ck_application_inbox_context.
    INSERT INTO applications (id, candidate_id, job_id, company_id, submitted_revision_id, status, version, applied_at, updated_at)
    VALUES (app_a_id, candidate_id_value, job_a_id, company_id_value, rev_a_id, 'UNDER_REVIEW', 0, app_a_at, app_a_at)
    ON CONFLICT (candidate_id, job_id) DO NOTHING;
    INSERT INTO application_snapshots (
        application_id, full_name, email, phone_number, cover_letter, job_title, company_name,
        cv_storage_key, cv_storage_provider, cv_file_name, cv_content_type, cv_file_size,
        snapshot_schema_version, captured_at)
    VALUES (
        app_a_id, 'An Nguyen Van', 'candidate@demo.guardwork.test', '0900000001',
        'Mong duoc gia nhan vao cong ty GuardWork.', (SELECT content ->> 'title' FROM job_posting_revisions WHERE id = rev_a_id),
        company_name_value, 'candidates/demo/app_a/cv', 'CLOUDINARY', 'Nguyen_Van_An_CV.pdf',
        'application/pdf', 204800, 1, app_a_at)
    ON CONFLICT (application_id) DO NOTHING;
    INSERT INTO applications (id, candidate_id, job_id, company_id, submitted_revision_id, status, version, applied_at, updated_at)
    VALUES (app_b_id, candidate_id_value, job_b_id, company_id_value, rev_b_id, 'REJECTED', 0, app_b_at, app_b_at)
    ON CONFLICT (candidate_id, job_id) DO NOTHING;
    INSERT INTO application_snapshots (
        application_id, full_name, email, phone_number, cover_letter, job_title, company_name,
        cv_storage_key, cv_storage_provider, cv_file_name, cv_content_type, cv_file_size,
        snapshot_schema_version, captured_at)
    VALUES (
        app_b_id, 'An Nguyen Van', 'candidate@demo.guardwork.test', '0900000001',
        'Mong duoc gia nhan vao cong ty GuardWork.', (SELECT content ->> 'title' FROM job_posting_revisions WHERE id = rev_b_id),
        company_name_value, 'candidates/demo/app_b/cv', 'CLOUDINARY', 'Nguyen_Van_An_CV.pdf',
        'application/pdf', 204800, 1, app_b_at)
    ON CONFLICT (application_id) DO NOTHING;
    INSERT INTO applications (id, candidate_id, job_id, company_id, submitted_revision_id, status, version, applied_at, updated_at)
    VALUES (app_c_id, candidate_id_value, job_c_id, company_id_value, rev_c_id, 'WITHDRAWN', 0, app_c_at, app_c_at)
    ON CONFLICT (candidate_id, job_id) DO NOTHING;
    INSERT INTO application_snapshots (
        application_id, full_name, email, phone_number, cover_letter, job_title, company_name,
        cv_storage_key, cv_storage_provider, cv_file_name, cv_content_type, cv_file_size,
        snapshot_schema_version, captured_at)
    VALUES (
        app_c_id, 'An Nguyen Van', 'candidate@demo.guardwork.test', '0900000001',
        'Mong duoc gia nhan vao cong ty GuardWork.', (SELECT content ->> 'title' FROM job_posting_revisions WHERE id = rev_c_id),
        company_name_value, 'candidates/demo/app_c/cv', 'CLOUDINARY', 'Nguyen_Van_An_CV.pdf',
        'application/pdf', 204800, 1, app_c_at)
    ON CONFLICT (application_id) DO NOTHING;

    -- 1. JOB_ASSIGNED -> demo_recruiter (read)
    INSERT INTO notification_outbox(event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, revision_id, created_at, attempt_count, next_attempt_at, delivered_at, error_category, actor_user_id, actor_name)
    VALUES ('a04b0000-0000-4000-8000-000000000101', recruiter_id_value, 'JOB_ASSIGNED', 'RECRUITER', company_id_value, company_name_value, job_a_id, rev_a_id, base_time, 0, base_time, base_time + interval '5 seconds', NULL, owner_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = owner_id_value))
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox(source_event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, revision_id, category, title, body, action_href, actor_user_id, actor_name, created_at, read_at)
    VALUES ('a04b0000-0000-4000-8000-000000000101', recruiter_id_value, 'JOB_ASSIGNED', 'RECRUITER', company_id_value, company_name_value, job_a_id, rev_a_id, 'job', 'Bạn được giao một tin tuyển dụng', 'Bạn được giao phụ trách tin tuyển dụng #' || job_a_id || ' tại ' || company_name_value || '.', '/jobs/' || job_a_id, owner_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = owner_id_value), base_time, base_time + interval '1 hour')
    ON CONFLICT (source_event_id) DO NOTHING;

    -- 2. JOB_UNASSIGNED -> demo_recruiter (unread)
    INSERT INTO notification_outbox(event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, revision_id, created_at, attempt_count, next_attempt_at, delivered_at, error_category, actor_user_id, actor_name)
    VALUES ('a04c0000-0000-4000-8000-000000000201', recruiter_id_value, 'JOB_UNASSIGNED', 'RECRUITER', company_id_value, company_name_value, job_b_id, rev_b_id, base_time + interval '1 hour', 0, base_time + interval '1 hour', base_time + interval '1 hour 5 seconds', NULL, owner_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = owner_id_value))
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox(source_event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, revision_id, category, title, body, action_href, actor_user_id, actor_name, created_at, read_at)
    VALUES ('a04c0000-0000-4000-8000-000000000201', recruiter_id_value, 'JOB_UNASSIGNED', 'RECRUITER', company_id_value, company_name_value, job_b_id, rev_b_id, 'job', 'Bạn không còn phụ trách tin tuyển dụng', 'Bạn không còn phụ trách tin tuyển dụng #' || job_b_id || ' tại ' || company_name_value || '.', NULL, owner_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = owner_id_value), base_time + interval '1 hour', NULL)
    ON CONFLICT (source_event_id) DO NOTHING;

    -- 3. JOB_NEEDS_REASSIGNMENT -> demo_owner (unread)
    INSERT INTO notification_outbox(event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, revision_id, created_at, attempt_count, next_attempt_at, delivered_at, error_category, actor_user_id, actor_name)
    VALUES ('a04d0000-0000-4000-8000-000000000301', owner_id_value, 'JOB_NEEDS_REASSIGNMENT', 'RECRUITER', company_id_value, company_name_value, job_c_id, rev_c_id, base_time + interval '2 hours', 0, base_time + interval '2 hours', base_time + interval '2 hours 5 seconds', NULL, NULL, NULL)
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox(source_event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, revision_id, category, title, body, action_href, actor_user_id, actor_name, created_at, read_at)
    VALUES ('a04d0000-0000-4000-8000-000000000301', owner_id_value, 'JOB_NEEDS_REASSIGNMENT', 'RECRUITER', company_id_value, company_name_value, job_c_id, rev_c_id, 'job', 'Tin tuyển dụng cần chuyển người phụ trách', 'Tin tuyển dụng #' || job_c_id || ' tại ' || company_name_value || ' cần chuyển người phụ trách mới.', '/jobs/' || job_c_id, NULL, NULL, base_time + interval '2 hours', NULL)
    ON CONFLICT (source_event_id) DO NOTHING;

    -- 4. JOB_REVISION_APPROVED -> demo_owner (read, actor demo_staff)
    INSERT INTO notification_outbox(event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, revision_id, created_at, attempt_count, next_attempt_at, delivered_at, error_category, actor_user_id, actor_name)
    VALUES ('a04e0000-0000-4000-8000-000000000401', owner_id_value, 'JOB_REVISION_APPROVED', 'RECRUITER', company_id_value, company_name_value, job_b_id, rev_b_id, base_time + interval '3 hours', 0, base_time + interval '3 hours', base_time + interval '3 hours 5 seconds', NULL, staff_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = staff_id_value))
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox(source_event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, revision_id, category, title, body, action_href, actor_user_id, actor_name, created_at, read_at)
    VALUES ('a04e0000-0000-4000-8000-000000000401', owner_id_value, 'JOB_REVISION_APPROVED', 'RECRUITER', company_id_value, company_name_value, job_b_id, rev_b_id, 'job', 'Tin tuyển dụng đã được duyệt', 'Tin tuyển dụng #' || job_b_id || ' tại ' || company_name_value || ' đã được phê duyệt và đăng công khai.', '/jobs/' || job_b_id, staff_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = staff_id_value), base_time + interval '3 hours', base_time + interval '4 hours')
    ON CONFLICT (source_event_id) DO NOTHING;

    -- 5. JOB_REVISION_REJECTED -> demo_owner (unread, actor demo_staff)
    INSERT INTO notification_outbox(event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, revision_id, created_at, attempt_count, next_attempt_at, delivered_at, error_category, actor_user_id, actor_name)
    VALUES ('a04f0000-0000-4000-8000-000000000501', owner_id_value, 'JOB_REVISION_REJECTED', 'RECRUITER', company_id_value, company_name_value, job_c_id, rev_c_id, base_time + interval '4 hours', 0, base_time + interval '4 hours', base_time + interval '4 hours 5 seconds', NULL, staff_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = staff_id_value))
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox(source_event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, revision_id, category, title, body, action_href, actor_user_id, actor_name, created_at, read_at)
    VALUES ('a04f0000-0000-4000-8000-000000000501', owner_id_value, 'JOB_REVISION_REJECTED', 'RECRUITER', company_id_value, company_name_value, job_c_id, rev_c_id, 'job', 'Tin tuyển dụng cần chỉnh sửa', 'Ban quản trị chưa thể phê duyệt tin tuyển dụng #' || job_c_id || ' tại ' || company_name_value || '. Vui lòng xem xét và chỉnh sửa.', '/jobs/' || job_c_id, staff_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = staff_id_value), base_time + interval '4 hours', NULL)
    ON CONFLICT (source_event_id) DO NOTHING;

    -- 6. APPLICATION_STATUS_CHANGED -> demo_candidate (unread, actor demo_owner)
    INSERT INTO notification_outbox(event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, application_id, created_at, attempt_count, next_attempt_at, delivered_at, error_category, actor_user_id, actor_name)
    VALUES ('a0500000-0000-4000-8000-000000000601', candidate_user_id_value, 'APPLICATION_STATUS_CHANGED', 'CANDIDATE', company_id_value, company_name_value, job_a_id, app_a_id, base_time + interval '5 hours', 0, base_time + interval '5 hours', base_time + interval '5 hours 5 seconds', NULL, owner_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = owner_id_value))
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox(source_event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, application_id, category, title, body, action_href, actor_user_id, actor_name, created_at, read_at)
    VALUES ('a0500000-0000-4000-8000-000000000601', candidate_user_id_value, 'APPLICATION_STATUS_CHANGED', 'CANDIDATE', company_id_value, company_name_value, job_a_id, app_a_id, 'application', 'Trạng thái hồ sơ đã thay đổi', 'Hồ sơ ứng tuyển của bạn tại ' || company_name_value || ' vừa được cập nhật trạng thái.', '/applications/' || app_a_id, owner_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = owner_id_value), base_time + interval '5 hours', NULL)
    ON CONFLICT (source_event_id) DO NOTHING;

    -- 7. APPLICATION_REJECTED -> demo_candidate (read, actor demo_owner, with feedback)
    INSERT INTO notification_outbox(event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, application_id, candidate_feedback, created_at, attempt_count, next_attempt_at, delivered_at, error_category, actor_user_id, actor_name)
    VALUES ('a0510000-0000-4000-8000-000000000701', candidate_user_id_value, 'APPLICATION_REJECTED', 'CANDIDATE', company_id_value, company_name_value, job_b_id, app_b_id, 'Kinh nghiệm chưa đáp ứng yêu cầu vị trí.', base_time + interval '6 hours', 0, base_time + interval '6 hours', base_time + interval '6 hours 5 seconds', NULL, owner_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = owner_id_value))
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox(source_event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, application_id, candidate_feedback, category, title, body, action_href, actor_user_id, actor_name, created_at, read_at)
    VALUES ('a0510000-0000-4000-8000-000000000701', candidate_user_id_value, 'APPLICATION_REJECTED', 'CANDIDATE', company_id_value, company_name_value, job_b_id, app_b_id, 'Kinh nghiệm chưa đáp ứng yêu cầu vị trí.', 'application', 'Hồ sơ ứng tuyển đã bị từ chối', 'Hồ sơ ứng tuyển của bạn tại ' || company_name_value || ' đã bị từ chối. Kinh nghiệm chưa đáp ứng yêu cầu vị trí.', '/applications/' || app_b_id, owner_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = owner_id_value), base_time + interval '6 hours', base_time + interval '7 hours')
    ON CONFLICT (source_event_id) DO NOTHING;

    -- 8. APPLICATION_WITHDRAWN -> demo_owner (unread)
    INSERT INTO notification_outbox(event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, application_id, created_at, attempt_count, next_attempt_at, delivered_at, error_category, actor_user_id, actor_name)
    VALUES ('a0520000-0000-4000-8000-000000000801', owner_id_value, 'APPLICATION_WITHDRAWN', 'RECRUITER', company_id_value, company_name_value, job_c_id, app_c_id, base_time + interval '7 hours', 0, base_time + interval '7 hours', base_time + interval '7 hours 5 seconds', NULL, candidate_user_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = candidate_user_id_value))
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox(source_event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, application_id, category, title, body, action_href, actor_user_id, actor_name, created_at, read_at)
    VALUES ('a0520000-0000-4000-8000-000000000801', owner_id_value, 'APPLICATION_WITHDRAWN', 'RECRUITER', company_id_value, company_name_value, job_c_id, app_c_id, 'application', 'Hồ sơ ứng tuyển đã bị rút', 'Hồ sơ ứng tuyển tại ' || company_name_value || ' đã được rút.', '/applications/' || app_c_id, candidate_user_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = candidate_user_id_value), base_time + interval '7 hours', NULL)
    ON CONFLICT (source_event_id) DO NOTHING;

    -- 9. RECRUITER_ACCEPTED -> demo_owner (read)
    INSERT INTO notification_outbox(event_id, recipient_user_id, event_type, audience, company_id, company_name, created_at, attempt_count, next_attempt_at, delivered_at, error_category, actor_user_id, actor_name)
    VALUES ('a0530000-0000-4000-8000-000000000901', owner_id_value, 'RECRUITER_ACCEPTED', 'RECRUITER', company_id_value, company_name_value, base_time + interval '8 hours', 0, base_time + interval '8 hours', base_time + interval '8 hours 5 seconds', NULL, recruiter_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = recruiter_id_value))
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox(source_event_id, recipient_user_id, event_type, audience, company_id, company_name, category, title, body, action_href, actor_user_id, actor_name, created_at, read_at)
    VALUES ('a0530000-0000-4000-8000-000000000901', owner_id_value, 'RECRUITER_ACCEPTED', 'RECRUITER', company_id_value, company_name_value, 'company', 'Bạn đã được chấp nhận vào ' || company_name_value, 'Bạn đã chính thức trở thành thành viên của ' || company_name_value || '. Hãy hoàn thiện hồ sơ để ứng tuyển và nhận các thông báo liên quan.', '/companies/' || company_id_value, recruiter_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = recruiter_id_value), base_time + interval '8 hours', base_time + interval '9 hours')
    ON CONFLICT (source_event_id) DO NOTHING;

    -- 10. MEMBERSHIP_REMOVED -> demo_recruiter (unread)
    INSERT INTO notification_outbox(event_id, recipient_user_id, event_type, audience, company_id, company_name, created_at, attempt_count, next_attempt_at, delivered_at, error_category, actor_user_id, actor_name)
    VALUES ('a0540000-0000-4000-8000-000000001001', recruiter_id_value, 'MEMBERSHIP_REMOVED', 'RECRUITER', company_id_value, company_name_value, base_time + interval '9 hours', 0, base_time + interval '9 hours', base_time + interval '9 hours 5 seconds', NULL, owner_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = owner_id_value))
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox(source_event_id, recipient_user_id, event_type, audience, company_id, company_name, category, title, body, action_href, actor_user_id, actor_name, created_at, read_at)
    VALUES ('a0540000-0000-4000-8000-000000001001', recruiter_id_value, 'MEMBERSHIP_REMOVED', 'RECRUITER', company_id_value, company_name_value, 'company', 'Quyền truy cập đã bị thu hồi', 'Quyền truy cập của bạn vào ' || company_name_value || ' đã bị thu hồi. Liên hệ quản trị viên của công ty nếu bạn tin rằng điều này xảy ra nhầm lẫn.', NULL, owner_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = owner_id_value), base_time + interval '9 hours', NULL)
    ON CONFLICT (source_event_id) DO NOTHING;
END;
$$;

COMMIT;
