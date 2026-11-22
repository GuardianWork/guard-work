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

-- Recruiter-authored public profile (GW-123): fill only when unset so manual edits survive reruns.
UPDATE companies
SET content = jsonb_build_object(
    'schemaVersion', 1,
    'industry', 'Công nghệ thông tin',
    'introduction', E'Công ty TNHH GuardWork Demo xây dựng nền tảng kết nối người lao động và doanh nghiệp, với các tin tuyển dụng được kiểm duyệt và hồ sơ phản hồi minh bạch.\n\nChúng tôi ưu tiên môi trường làm việc tôn trọng pháp luật lao động Việt Nam và quyền riêng tư của ứng viên.\n\nĐây là dữ liệu minh họa cho môi trường phát triển, không phải hồ sơ doanh nghiệp thực tế.',
    'workAddress', 'Tòa nhà GuardWork, tầng 9, Quận 3, Thành phố Hồ Chí Minh, Việt Nam',
    'workingHours', 'Thứ 2 - Thứ 6, 08:30 - 17:30',
    'companyScale', jsonb_build_object('employeeRange', '51-200'),
    'expertise', jsonb_build_array('Nền tảng tuyển dụng', 'Pháp luật lao động', 'Xác minh doanh nghiệp')
)
WHERE tax_code = 'GW-DEMO-LOCAL-001' AND content = '{}'::jsonb;

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

-- Bulk verified companies with opening published jobs for company directory depth.
-- Each company also publishes a recruiter-authored public profile (companies.content, GW-123,
-- schemaVersion 1) with industry/introduction/workAddress/workingHours/companyScale/expertise,
-- filled only when unset so manual edits survive reruns.
-- One industry per company (derived from its name theme) and at most two locations
-- (the home city, plus one secondary city for every fourth company). Role, salary band,
-- employment-type and work-arrangement pools all follow that industry.
-- Reruns top up only missing postings (per-fixture markers) while existing
-- Company/owner/membership state is preserved; collisions fail like the demo accounts.
DO $$
DECLARE
    staff_id_value BIGINT;
    company_index INTEGER;
    company_name_value TEXT;
    company_tax_code_value TEXT;
    owner_username_value TEXT;
    owner_email_value TEXT;
    owner_id_value BIGINT;
    company_id_value BIGINT;
    company_names TEXT[] := ARRAY[
        'Công ty TNHH Thinh Phat Digital', 'Công ty TNHH Saigon Horizon Technologies',
        'Công ty TNHH Viet Data Systems', 'Công ty TNHH Nam Duong Technologies',
        'Công ty TNHH Han River Software', 'Công ty TNHH Mekong Tech Solutions',
        'Công ty TNHH Pine Software', 'Công ty TNHH Delta Solutions',
        'Công ty TNHH Sunrise Technologies', 'Công ty TNHH Pioneer Digital',
        'Công ty TNHH Skyline Software', 'Công ty TNHH Harmony Technologies',
        'Công ty TNHH Lotus Systems', 'Công ty TNHH Bamboo Software',
        'Công ty TNHH Crystal Technologies', 'Công ty TNHH Emerald Data',
        'Công ty TNHH Ruby Software', 'Công ty TNHH Sapphire Technologies',
        'Công ty TNHH Topaz Systems', 'Công ty TNHH Amber Technologies',
        'Công ty TNHH Fox Software', 'Công ty TNHH Falcon Technologies',
        'Công ty TNHH Hawk Software', 'Công ty TNHH Eagle Systems',
        'Công ty TNHH River Software', 'Công ty TNHH Mountain Tech',
        'Công ty TNHH Island Systems', 'Công ty TNHH Desert Data',
        'Công ty TNHH Ocean Software', 'Công ty TNHH City Technologies',
        'Công ty TNHH Urban Systems', 'Công ty TNHH Rural Software',
        'Công ty TNHH Global Data', 'Công ty TNHH International Software',
        'Công ty TNHH National Technologies', 'Công ty TNHH Local Systems',
        'Công ty TNHH Metro Software', 'Công ty TNHH Green Technologies',
        'Công ty TNHH Saigon Commerce', 'Công ty TNHH Viet Commerce',
        'Công ty TNHH Viet Media', 'Công ty TNHH Viet Education',
        'Công ty TNHH Viet Healthcare', 'Công ty TNHH Viet Finance',
        'Công ty TNHH Viet Logistics', 'Công ty TNHH Viet Manufacturing',
        'Công ty TNHH Viet Retail', 'Công ty TNHH Viet Food',
        'Công ty TNHH Viet Culture', 'Công ty TNHH Viet Sport',
        'Công ty TNHH Ho Chi Minh Media', 'Công ty TNHH Hanoi Digital',
        'Công ty TNHH Da Nang Tech', 'Công ty TNHH Hai Phong Software',
        'Công ty TNHH Can Tho Systems', 'Công ty TNHH Hue Technologies',
        'Công ty TNHH Nha Trang Data', 'Công ty TNHH Bien Hoa Software',
        'Công ty TNHH Binh Duong Digital', 'Công ty TNHH Vung Tau Systems',
        'Công ty TNHH Thinh Phat Energy', 'Công ty TNHH Thinh Phat Foods',
        'Công ty TNHH Thinh Phat Education', 'Công ty TNHH Thinh Phat Healthcare',
        'Công ty TNHH Thinh Phat Finance', 'Công ty TNHH Thinh Phat Logistics',
        'Công ty TNHH Thinh Phat Manufacturing', 'Công ty TNHH Thinh Phat Retail',
        'Công ty TNHH Thinh Phat Media', 'Công ty TNHH Thinh Phat Culture',
        'Công ty TNHH Thinh Phat Sport', 'Công ty TNHH Saigon Energy',
        'Công ty TNHH Saigon Foods', 'Công ty TNHH Saigon Education',
        'Công ty TNHH Saigon Healthcare', 'Công ty TNHH Saigon Finance',
        'Công ty TNHH Saigon Logistics', 'Công ty TNHH Saigon Manufacturing',
        'Công ty TNHH Saigon Retail', 'Công ty TNHH Saigon Media',
        'Công ty TNHH Saigon Culture', 'Công ty TNHH Saigon Sport',
        'Công ty TNHH Hanoi Energy', 'Công ty TNHH Hanoi Foods',
        'Công ty TNHH Hanoi Education', 'Công ty TNHH Hanoi Healthcare',
        'Công ty TNHH Hanoi Finance', 'Công ty TNHH Hanoi Logistics',
        'Công ty TNHH Hanoi Manufacturing', 'Công ty TNHH Hanoi Retail',
        'Công ty TNHH Hanoi Media', 'Công ty TNHH Hanoi Culture',
        'Công ty TNHH Hanoi Sport', 'Công ty TNHH Da Nang Energy',
        'Công ty TNHH Da Nang Foods', 'Công ty TNHH Da Nang Education',
        'Công ty TNHH Da Nang Healthcare', 'Công ty TNHH Da Nang Finance',
        'Công ty TNHH Da Nang Logistics', 'Công ty TNHH Da Nang Manufacturing'
    ];
    job_index INTEGER;
    job_offset INTEGER;
    fixture_key TEXT;
    job_id_value BIGINT;
    revision_id_value BIGINT;
    deadline_value TIMESTAMPTZ;
    content_value JSONB;
    theme_value TEXT;
    assigned_industry TEXT;
    home_location TEXT;
    second_location TEXT;
    location_value TEXT;
    employment_type TEXT;
    employment_types TEXT[];
    work_arrangement TEXT;
    work_arrangements TEXT[];
    level TEXT;
    levels TEXT[] := ARRAY['Junior', 'Middle', 'Senior', 'Lead'];
    profile_value JSONB;
    role_pool TEXT[];
    role_value TEXT;
    salary_band INTEGER[];
    min_salary BIGINT;
    max_salary BIGINT;
    headcount INTEGER;
    job_total INTEGER := 6;
BEGIN
    SELECT id INTO STRICT staff_id_value FROM users WHERE username = 'demo_staff';
    IF NOT EXISTS (SELECT 1 FROM users u WHERE u.id = staff_id_value AND u.status = 'ACTIVE' AND u.role = 'STAFF') THEN
        RAISE EXCEPTION 'Demo staff is not eligible for seeding bulk company jobs.';
    END IF;

    FOR company_index IN 1..100 LOOP
        company_name_value := company_names[company_index];
        company_tax_code_value := 'GW-DEMO-BULK-' || lpad(company_index::text, 3, '0');
        owner_username_value := 'demo_bulk_owner_' || lpad(company_index::text, 3, '0');
        owner_email_value := 'bulk-owner-' || lpad(company_index::text, 3, '0') || '@demo.guardwork.test';

        -- Resolve the owner with the same curated collision policy as the demo accounts.
        SELECT id INTO owner_id_value FROM users WHERE username = owner_username_value;
        IF FOUND THEN
            IF EXISTS (SELECT 1 FROM users u WHERE u.id = owner_id_value
                    AND (lower(btrim(u.email)) <> owner_email_value OR u.role <> 'USER'
                         OR NOT u.email_verified OR u.status <> 'ACTIVE')) THEN
                RAISE EXCEPTION 'Mock bulk owner identifier collision for %; no data was seeded.', owner_username_value;
            END IF;
        ELSE
            IF EXISTS (SELECT 1 FROM users u WHERE lower(btrim(u.email)) = owner_email_value) THEN
                RAISE EXCEPTION 'Mock bulk owner identifier collision for %; no data was seeded.', owner_email_value;
            END IF;
            INSERT INTO users (username, email, first_name, last_name, password, role, status, email_verified, phone_number)
            VALUES (owner_username_value, owner_email_value, 'Lan', 'Phạm', '$2a$10$.Sw9NzCJH98UmXsd3ep4punGVyL7FwuBWXWykQDXbDi7wTSj3SK/S', 'USER', 'ACTIVE', true, NULL)
            RETURNING id INTO owner_id_value;
        END IF;

        -- Resolve or create the verified company. An existing company must belong to the
        -- resolved owner and stay verified and unbanned; its state is never reset.
        SELECT id INTO company_id_value FROM companies WHERE tax_code = company_tax_code_value;
        IF FOUND THEN
            IF NOT EXISTS (SELECT 1 FROM companies c WHERE c.id = company_id_value AND c.owner_user_id = owner_id_value) THEN
                RAISE EXCEPTION 'Mock bulk company identifier collision for %; no data was seeded.', company_tax_code_value;
            END IF;
            IF NOT EXISTS (SELECT 1 FROM companies c WHERE c.id = company_id_value
                    AND c.verification_status = 'VERIFIED' AND NOT c.is_banned) THEN
                RAISE EXCEPTION 'Existing bulk company % is not eligible for seeding; existing state was preserved.', company_tax_code_value;
            END IF;
        ELSE
            INSERT INTO companies (name, tax_code, registration_certificate_url, email, verification_status, verified_by, verified_at, owner_user_id)
            VALUES (company_name_value, company_tax_code_value,
                'https://example.invalid/guardwork-bulk-company-' || lpad(company_index::text, 3, '0') || '.pdf',
                owner_email_value, 'VERIFIED', staff_id_value, now(), owner_id_value)
            RETURNING id INTO company_id_value;
        END IF;

        INSERT INTO company_memberships (company_id, user_id, role, status, joined_at)
        SELECT company_id_value, owner_id_value, 'OWNER', 'ACTIVE', now()
        WHERE NOT EXISTS (SELECT 1 FROM company_memberships m
            WHERE m.company_id = company_id_value AND m.user_id = owner_id_value AND m.status = 'ACTIVE');

        -- Anchor one industry to the company from its name theme.
        theme_value := regexp_replace(company_name_value, '^.* ', '');
        assigned_industry := CASE theme_value
            WHEN 'Digital' THEN 'Công nghệ thông tin' WHEN 'Technologies' THEN 'Công nghệ thông tin'
            WHEN 'Software' THEN 'Công nghệ thông tin' WHEN 'Systems' THEN 'Công nghệ thông tin'
            WHEN 'Solutions' THEN 'Công nghệ thông tin' WHEN 'Tech' THEN 'Công nghệ thông tin'
            WHEN 'Data' THEN 'Dữ liệu và phân tích'
            WHEN 'Commerce' THEN 'Bán lẻ' WHEN 'Retail' THEN 'Bán lẻ'
            WHEN 'Media' THEN 'Truyền thông' WHEN 'Culture' THEN 'Truyền thông'
            WHEN 'Education' THEN 'Giáo dục'
            WHEN 'Finance' THEN 'Tài chính - Kế toán'
            WHEN 'Food' THEN 'Dịch vụ ăn uống' WHEN 'Foods' THEN 'Dịch vụ ăn uống'
            WHEN 'Healthcare' THEN 'Y tế'
            WHEN 'Logistics' THEN 'Logistics'
            WHEN 'Manufacturing' THEN 'Sản xuất'
            WHEN 'Sport' THEN 'Thể thao'
            WHEN 'Energy' THEN 'Năng lượng'
            ELSE NULL END;
        IF assigned_industry IS NULL THEN
            RAISE EXCEPTION 'No industry mapping for bulk company theme %; no data was seeded.', theme_value;
        END IF;

        -- Home city from the name; every fourth company also hires in a second city.
        home_location := CASE
            WHEN company_name_value ILIKE '%Saigon%' OR company_name_value ILIKE '%Ho Chi Minh%'
                THEN 'Thành phố Hồ Chí Minh, Việt Nam'
            WHEN company_name_value ILIKE '%Hanoi%' THEN 'Hà Nội, Việt Nam'
            WHEN company_name_value ILIKE '%Da Nang%' OR company_name_value ILIKE '%Han River%' THEN 'Đà Nẵng, Việt Nam'
            WHEN company_name_value ILIKE '%Mekong%' OR company_name_value ILIKE '%Can Tho%' THEN 'Cần Thơ, Việt Nam'
            WHEN company_name_value ILIKE '%Hai Phong%' THEN 'Hải Phòng, Việt Nam'
            WHEN company_name_value ILIKE '%Hue%' THEN 'Huế, Việt Nam'
            WHEN company_name_value ILIKE '%Nha Trang%' THEN 'Nha Trang, Việt Nam'
            WHEN company_name_value ILIKE '%Bien Hoa%' THEN 'Biên Hòa, Việt Nam'
            WHEN company_name_value ILIKE '%Binh Duong%' THEN 'Bình Dương, Việt Nam'
            WHEN company_name_value ILIKE '%Vung Tau%' THEN 'Vũng Tàu, Việt Nam'
            ELSE CASE WHEN company_index % 2 = 0 THEN 'Thành phố Hồ Chí Minh, Việt Nam' ELSE 'Hà Nội, Việt Nam' END
        END;
        second_location := NULL;
        IF company_index % 4 = 0 THEN
            second_location := CASE WHEN home_location LIKE 'Hà Nội%'
                THEN 'Thành phố Hồ Chí Minh, Việt Nam' ELSE 'Hà Nội, Việt Nam' END;
        END IF;

        -- Role, salary band, employment-type and arrangement pools follow the industry.
        role_pool := CASE assigned_industry
            WHEN 'Công nghệ thông tin' THEN ARRAY['Kỹ sư phần mềm Backend', 'Lập trình viên Frontend React', 'Kỹ sư DevOps', 'Chuyên viên kiểm thử phần mềm QA', 'Thiết kế UI/UX', 'Kỹ sư dữ liệu']
            WHEN 'Dữ liệu và phân tích' THEN ARRAY['Kỹ sư dữ liệu', 'Chuyên viên phân tích dữ liệu', 'Kỹ sư phần mềm Backend']
            WHEN 'Bán lẻ' THEN ARRAY['Nhân viên bán hàng', 'Chuyên viên mua hàng', 'Nhân viên hành chính văn phòng']
            WHEN 'Truyền thông' THEN ARRAY['Chuyên viên nội dung', 'Chuyên viên marketing số', 'Thiết kế UI/UX']
            WHEN 'Giáo dục' THEN ARRAY['Giáo viên tiểu học', 'Giáo viên tiếng Anh']
            WHEN 'Tài chính - Kế toán' THEN ARRAY['Chuyên viên kế toán tổng hợp', 'Chuyên viên tài chính doanh nghiệp']
            WHEN 'Dịch vụ ăn uống' THEN ARRAY['Đầu bếp', 'Nhân viên phục vụ nhà hàng']
            WHEN 'Y tế' THEN ARRAY['Nhân viên y tá', 'Bác sĩ đa khoa', 'Dược sĩ']
            WHEN 'Logistics' THEN ARRAY['Nhân viên kho vận', 'Điều phối logistics', 'Chuyên viên mua hàng']
            WHEN 'Sản xuất' THEN ARRAY['Nhân viên sản xuất', 'Giám sát sản xuất', 'Kỹ sư điện công nghiệp']
            WHEN 'Thể thao' THEN ARRAY['Huấn luyện viên thể thao', 'Nhân viên vận hành trung tâm thể thao']
            WHEN 'Năng lượng' THEN ARRAY['Kỹ sư điện công nghiệp', 'Kỹ sư cơ điện', 'Giám sát sản xuất']
        END;
        salary_band := CASE assigned_industry
            WHEN 'Công nghệ thông tin' THEN ARRAY[15000000, 40000000]
            WHEN 'Dữ liệu và phân tích' THEN ARRAY[18000000, 45000000]
            WHEN 'Bán lẻ' THEN ARRAY[6000000, 12000000]
            WHEN 'Truyền thông' THEN ARRAY[9000000, 18000000]
            WHEN 'Giáo dục' THEN ARRAY[8000000, 16000000]
            WHEN 'Tài chính - Kế toán' THEN ARRAY[10000000, 25000000]
            WHEN 'Dịch vụ ăn uống' THEN ARRAY[6000000, 13000000]
            WHEN 'Y tế' THEN ARRAY[12000000, 35000000]
            WHEN 'Logistics' THEN ARRAY[8000000, 17000000]
            WHEN 'Sản xuất' THEN ARRAY[7000000, 15000000]
            WHEN 'Thể thao' THEN ARRAY[7000000, 14000000]
            WHEN 'Năng lượng' THEN ARRAY[11000000, 26000000]
        END;
        employment_types := CASE assigned_industry
            WHEN 'Bán lẻ' THEN ARRAY['FULL_TIME', 'PART_TIME', 'PART_TIME', 'TEMPORARY', 'CONTRACT']
            WHEN 'Dịch vụ ăn uống' THEN ARRAY['PART_TIME', 'FULL_TIME', 'TEMPORARY', 'PART_TIME', 'CONTRACT']
            WHEN 'Thể thao' THEN ARRAY['PART_TIME', 'FULL_TIME', 'CONTRACT', 'PART_TIME', 'TEMPORARY']
            WHEN 'Giáo dục' THEN ARRAY['FULL_TIME', 'PART_TIME', 'CONTRACT', 'FULL_TIME', 'FULL_TIME']
            ELSE ARRAY['FULL_TIME', 'FULL_TIME', 'FULL_TIME', 'PART_TIME', 'CONTRACT']
        END;
        work_arrangements := CASE assigned_industry
            WHEN 'Công nghệ thông tin' THEN ARRAY['REMOTE', 'HYBRID', 'ONSITE', 'HYBRID', 'REMOTE']
            WHEN 'Dữ liệu và phân tích' THEN ARRAY['REMOTE', 'HYBRID', 'ONSITE', 'REMOTE', 'HYBRID']
            ELSE ARRAY['ONSITE', 'ONSITE', 'ONSITE', 'HYBRID', 'ONSITE']
        END;

        -- Recruiter-authored public profile (GW-123, schemaVersion 1): fill only when unset,
        -- so one-time manual edits survive reruns. Industry and location anchors reuse the
        -- same values that drive the postings, so hero meta and dossier stay consistent.
        profile_value := jsonb_build_object(
            'schemaVersion', 1,
            'industry', assigned_industry,
            'introduction',
                company_name_value || E' hoạt động trong lĩnh vực ' || assigned_industry
                || E'. Chúng tôi tập trung vào chất lượng sản phẩm, đội ngũ chuyên nghiệp và môi trường làm việc minh bạch.\n\n'
                || E'Tuyển dụng tại ' || home_location
                || E'. Đây là dữ liệu minh họa cho môi trường phát triển, không phải hồ sơ doanh nghiệp thực tế.',
            'workAddress',
                'Tầng ' || (3 + (company_index % 18))::text || ', Tòa nhà '
                || (ARRAY['An Phát', 'Vạn Xuân', 'Mekong', 'Bình Minh', 'Đại Phát', 'Hòa Bình'])[(company_index - 1) % 6 + 1]
                || ', ' || home_location,
            'workingHours', CASE assigned_industry
                WHEN 'Bán lẻ' THEN 'Thứ 2 - Chủ nhật, 08:00 - 17:00'
                WHEN 'Dịch vụ ăn uống' THEN 'Thứ 2 - Chủ nhật, 08:00 - 17:00'
                WHEN 'Thể thao' THEN 'Thứ 2 - Chủ nhật, 08:00 - 17:00'
                ELSE 'Thứ 2 - Thứ 6, 08:30 - 17:30' END,
            'companyScale', jsonb_build_object('employeeRange',
                (ARRAY['1-10', '11-50', '51-200', '201-500', '501-1000', '1000+'])[(company_index - 1) % 6 + 1]),
            'expertise', CASE assigned_industry
                WHEN 'Công nghệ thông tin' THEN jsonb_build_array('Phát triển phần mềm', 'Điện toán đám mây', 'DevOps', 'Kiểm thử phần mềm')
                WHEN 'Dữ liệu và phân tích' THEN jsonb_build_array('Phân tích dữ liệu', 'Kỹ thuật dữ liệu', 'Business intelligence')
                WHEN 'Bán lẻ' THEN jsonb_build_array('Vận hành bán lẻ', 'Mua hàng', 'Chăm sóc khách hàng')
                WHEN 'Truyền thông' THEN jsonb_build_array('Sản xuất nội dung', 'Tiếp thị số', 'Truyền thông thương hiệu')
                WHEN 'Giáo dục' THEN jsonb_build_array('Giảng dạy', 'Phát triển chương trình', 'Đào tạo ngoại ngữ')
                WHEN 'Tài chính - Kế toán' THEN jsonb_build_array('Kế toán tổng hợp', 'Tài chính doanh nghiệp', 'Kiểm toán')
                WHEN 'Dịch vụ ăn uống' THEN jsonb_build_array('Vận hành nhà hàng', 'Chế biến món ăn', 'Phục vụ khách hàng')
                WHEN 'Y tế' THEN jsonb_build_array('Khám chữa bệnh đa khoa', 'Điều dưỡng', 'Dược lâm sàng')
                WHEN 'Logistics' THEN jsonb_build_array('Vận hành kho vận', 'Điều phối vận tải', 'Quản lý chuỗi cung ứng')
                WHEN 'Sản xuất' THEN jsonb_build_array('Sản xuất công nghiệp', 'Quản lý chất lượng', 'Vận hành máy móc')
                WHEN 'Thể thao' THEN jsonb_build_array('Đào tạo thể thao', 'Vận hành trung tâm', 'Tổ chức giải đấu')
                WHEN 'Năng lượng' THEN jsonb_build_array('Kỹ thuật điện', 'Dự án năng lượng', 'Vận hành nhà máy')
            END
        );
        UPDATE companies
        SET content = profile_value
        WHERE id = company_id_value AND content = '{}'::jsonb;

        -- Top up missing postings per fixture marker; existing jobs are preserved.
        FOR job_index IN 1..job_total LOOP
            job_offset := (company_index - 1) * job_total + job_index;
            fixture_key := 'BULK-COMPANY-' || lpad(company_index::text, 3, '0') || '-JOB-' || lpad(job_index::text, 3, '0');
            IF EXISTS (SELECT 1 FROM job_audit_events e WHERE e.facts ->> 'developmentFixture' = fixture_key) THEN
                CONTINUE;
            END IF;

            location_value := CASE WHEN second_location IS NOT NULL AND job_index % 2 = 0 THEN second_location ELSE home_location END;
            level := levels[((job_offset - 1) / 30) % 4 + 1];
            role_value := role_pool[((company_index + job_index - 2) % array_length(role_pool, 1)) + 1];
            employment_type := employment_types[((job_offset - 1) % array_length(employment_types, 1)) + 1];
            work_arrangement := work_arrangements[((job_offset - 1) % array_length(work_arrangements, 1)) + 1];
            headcount := 1 + ((job_offset - 1) % 6);
            min_salary := salary_band[1]::BIGINT + ((job_offset - 1) % 4) * ((salary_band[2]::BIGINT - salary_band[1]::BIGINT) / 8);
            max_salary := min_salary + (salary_band[2]::BIGINT - salary_band[1]::BIGINT) / 2;
            deadline_value := now() + ((15 + ((job_offset - 1) % 75))::text || ' days')::interval;

            content_value := jsonb_build_object(
                'title', level || ' ' || role_value,
                'description', 'Mô tả công việc cho vị trí ' || level || ' ' || role_value || E'.\n\nTrách nhiệm chính:\n- Thực hiện nhiệm vụ chuyên môn theo phân công.\n- Phối hợp với các phòng ban liên quan.\n- Theo dõi KPI và báo cáo tiến độ.\n\nYêu cầu:\n- Có kiến thức và kỹ năng phù hợp với vị trí.\n- Giao tiếp tốt, chịu được áp lực công việc.\n\nQuyền lợi:\n- Lương cạnh tranh, thưởng theo hiệu suất.\n- Môi trường làm việc chuyên nghiệp.\n\nDữ liệu minh họa GuardWork, không phải tin tuyển dụng thực tế.',
                'industry', assigned_industry,
                'employmentType', employment_type,
                'workArrangement', work_arrangement,
                'location', location_value,
                'deadline', to_char(deadline_value AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                'headcount', headcount,
                'compensation', jsonb_build_object('disclosed', true, 'minimum', min_salary, 'maximum', max_salary, 'currency', 'VND', 'payPeriod', 'MONTH')
            );

            INSERT INTO job_postings(company_id, creator_user_id, status, owner_responsible, created_at, updated_at)
            VALUES (company_id_value, owner_id_value, 'DRAFT', true, now(), now()) RETURNING id INTO job_id_value;
            INSERT INTO job_posting_revisions(job_id, revision_number, content, state, creator_user_id, submitter_user_id, submitted_at, reviewer_user_id, decided_at, version, created_at)
            VALUES (job_id_value, 1, content_value, 'APPROVED', owner_id_value, owner_id_value, now() - interval '1 hour', staff_id_value, now(), 2, now())
            RETURNING id INTO revision_id_value;
            UPDATE job_postings SET status = 'PUBLISHED', published_revision_id = revision_id_value, approved_deadline = deadline_value, version = 2, updated_at = now() WHERE id = job_id_value;
            INSERT INTO job_audit_events(company_id, job_id, revision_id, actor_user_id, event_type, created_at, facts)
            VALUES (company_id_value, job_id_value, revision_id_value, owner_id_value, 'JOB_CREATED', now(), jsonb_build_object('ownerResponsible', true, 'developmentFixture', fixture_key));
        END LOOP;
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
    INSERT INTO notification_outbox(event_id, recipient_user_id, event_type, audience, payload, created_at, attempt_count, next_attempt_at, delivered_at, error_category)
    VALUES ('a04b0000-0000-4000-8000-000000000101', recruiter_id_value, 'JOB_ASSIGNED', 'RECRUITER', jsonb_strip_nulls(jsonb_build_object('companyId', company_id_value, 'companyName', company_name_value, 'jobId', job_a_id, 'revisionId', rev_a_id, 'actorUserId', owner_id_value, 'actorName', (SELECT first_name || ' ' || last_name FROM users WHERE id = owner_id_value))), base_time, 0, base_time, base_time + interval '5 seconds', NULL)
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox(source_event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, revision_id, category, title, body, action_href, actor_user_id, actor_name, created_at, read_at)
    VALUES ('a04b0000-0000-4000-8000-000000000101', recruiter_id_value, 'JOB_ASSIGNED', 'RECRUITER', company_id_value, company_name_value, job_a_id, rev_a_id, 'job', 'Bạn được giao một tin tuyển dụng', 'Bạn được giao phụ trách tin tuyển dụng #' || job_a_id || ' tại ' || company_name_value || '.', '/jobs/' || job_a_id, owner_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = owner_id_value), base_time, base_time + interval '1 hour')
    ON CONFLICT (source_event_id) DO NOTHING;

    -- 2. JOB_UNASSIGNED -> demo_recruiter (unread)
    INSERT INTO notification_outbox(event_id, recipient_user_id, event_type, audience, payload, created_at, attempt_count, next_attempt_at, delivered_at, error_category)
    VALUES ('a04c0000-0000-4000-8000-000000000201', recruiter_id_value, 'JOB_UNASSIGNED', 'RECRUITER', jsonb_strip_nulls(jsonb_build_object('companyId', company_id_value, 'companyName', company_name_value, 'jobId', job_b_id, 'revisionId', rev_b_id, 'actorUserId', owner_id_value, 'actorName', (SELECT first_name || ' ' || last_name FROM users WHERE id = owner_id_value))), base_time + interval '1 hour', 0, base_time + interval '1 hour', base_time + interval '1 hour 5 seconds', NULL)
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox(source_event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, revision_id, category, title, body, action_href, actor_user_id, actor_name, created_at, read_at)
    VALUES ('a04c0000-0000-4000-8000-000000000201', recruiter_id_value, 'JOB_UNASSIGNED', 'RECRUITER', company_id_value, company_name_value, job_b_id, rev_b_id, 'job', 'Bạn không còn phụ trách tin tuyển dụng', 'Bạn không còn phụ trách tin tuyển dụng #' || job_b_id || ' tại ' || company_name_value || '.', NULL, owner_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = owner_id_value), base_time + interval '1 hour', NULL)
    ON CONFLICT (source_event_id) DO NOTHING;

    -- 3. JOB_NEEDS_REASSIGNMENT -> demo_owner (unread)
    INSERT INTO notification_outbox(event_id, recipient_user_id, event_type, audience, payload, created_at, attempt_count, next_attempt_at, delivered_at, error_category)
    VALUES ('a04d0000-0000-4000-8000-000000000301', owner_id_value, 'JOB_NEEDS_REASSIGNMENT', 'RECRUITER', jsonb_strip_nulls(jsonb_build_object('companyId', company_id_value, 'companyName', company_name_value, 'jobId', job_c_id, 'revisionId', rev_c_id)), base_time + interval '2 hours', 0, base_time + interval '2 hours', base_time + interval '2 hours 5 seconds', NULL)
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox(source_event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, revision_id, category, title, body, action_href, actor_user_id, actor_name, created_at, read_at)
    VALUES ('a04d0000-0000-4000-8000-000000000301', owner_id_value, 'JOB_NEEDS_REASSIGNMENT', 'RECRUITER', company_id_value, company_name_value, job_c_id, rev_c_id, 'job', 'Tin tuyển dụng cần chuyển người phụ trách', 'Tin tuyển dụng #' || job_c_id || ' tại ' || company_name_value || ' cần chuyển người phụ trách mới.', '/jobs/' || job_c_id, NULL, NULL, base_time + interval '2 hours', NULL)
    ON CONFLICT (source_event_id) DO NOTHING;

    -- 4. JOB_REVISION_APPROVED -> demo_owner (read, actor demo_staff)
    INSERT INTO notification_outbox(event_id, recipient_user_id, event_type, audience, payload, created_at, attempt_count, next_attempt_at, delivered_at, error_category)
    VALUES ('a04e0000-0000-4000-8000-000000000401', owner_id_value, 'JOB_REVISION_APPROVED', 'RECRUITER', jsonb_strip_nulls(jsonb_build_object('companyId', company_id_value, 'companyName', company_name_value, 'jobId', job_b_id, 'revisionId', rev_b_id, 'actorUserId', staff_id_value, 'actorName', (SELECT first_name || ' ' || last_name FROM users WHERE id = staff_id_value))), base_time + interval '3 hours', 0, base_time + interval '3 hours', base_time + interval '3 hours 5 seconds', NULL)
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox(source_event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, revision_id, category, title, body, action_href, actor_user_id, actor_name, created_at, read_at)
    VALUES ('a04e0000-0000-4000-8000-000000000401', owner_id_value, 'JOB_REVISION_APPROVED', 'RECRUITER', company_id_value, company_name_value, job_b_id, rev_b_id, 'job', 'Tin tuyển dụng đã được duyệt', 'Tin tuyển dụng #' || job_b_id || ' tại ' || company_name_value || ' đã được phê duyệt và đăng công khai.', '/jobs/' || job_b_id, staff_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = staff_id_value), base_time + interval '3 hours', base_time + interval '4 hours')
    ON CONFLICT (source_event_id) DO NOTHING;

    -- 5. JOB_REVISION_REJECTED -> demo_owner (unread, actor demo_staff)
    INSERT INTO notification_outbox(event_id, recipient_user_id, event_type, audience, payload, created_at, attempt_count, next_attempt_at, delivered_at, error_category)
    VALUES ('a04f0000-0000-4000-8000-000000000501', owner_id_value, 'JOB_REVISION_REJECTED', 'RECRUITER', jsonb_strip_nulls(jsonb_build_object('companyId', company_id_value, 'companyName', company_name_value, 'jobId', job_c_id, 'revisionId', rev_c_id, 'actorUserId', staff_id_value, 'actorName', (SELECT first_name || ' ' || last_name FROM users WHERE id = staff_id_value))), base_time + interval '4 hours', 0, base_time + interval '4 hours', base_time + interval '4 hours 5 seconds', NULL)
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox(source_event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, revision_id, category, title, body, action_href, actor_user_id, actor_name, created_at, read_at)
    VALUES ('a04f0000-0000-4000-8000-000000000501', owner_id_value, 'JOB_REVISION_REJECTED', 'RECRUITER', company_id_value, company_name_value, job_c_id, rev_c_id, 'job', 'Tin tuyển dụng cần chỉnh sửa', 'Ban quản trị chưa thể phê duyệt tin tuyển dụng #' || job_c_id || ' tại ' || company_name_value || '. Vui lòng xem xét và chỉnh sửa.', '/jobs/' || job_c_id, staff_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = staff_id_value), base_time + interval '4 hours', NULL)
    ON CONFLICT (source_event_id) DO NOTHING;

    -- 6. APPLICATION_STATUS_CHANGED -> demo_candidate (unread, actor demo_owner)
    INSERT INTO notification_outbox(event_id, recipient_user_id, event_type, audience, payload, created_at, attempt_count, next_attempt_at, delivered_at, error_category)
    VALUES ('a0500000-0000-4000-8000-000000000601', candidate_user_id_value, 'APPLICATION_STATUS_CHANGED', 'CANDIDATE', jsonb_strip_nulls(jsonb_build_object('companyId', company_id_value, 'companyName', company_name_value, 'jobId', job_a_id, 'jobTitle', (SELECT content ->> 'title' FROM job_posting_revisions WHERE id = rev_a_id), 'applicationId', app_a_id, 'actorUserId', owner_id_value, 'actorName', (SELECT first_name || ' ' || last_name FROM users WHERE id = owner_id_value))), base_time + interval '5 hours', 0, base_time + interval '5 hours', base_time + interval '5 hours 5 seconds', NULL)
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox(source_event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, application_id, category, title, body, action_href, actor_user_id, actor_name, created_at, read_at)
    VALUES ('a0500000-0000-4000-8000-000000000601', candidate_user_id_value, 'APPLICATION_STATUS_CHANGED', 'CANDIDATE', company_id_value, company_name_value, job_a_id, app_a_id, 'application', 'Trạng thái hồ sơ đã thay đổi', company_name_value || ' vừa cập nhật trạng thái hồ sơ ứng tuyển của bạn cho vị trí ' || (SELECT content ->> 'title' FROM job_posting_revisions WHERE id = rev_a_id) || '.', '/applications/' || app_a_id, owner_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = owner_id_value), base_time + interval '5 hours', NULL)
    ON CONFLICT (source_event_id) DO NOTHING;

    -- 7. APPLICATION_REJECTED -> demo_candidate (read, actor demo_owner, with feedback)
    INSERT INTO notification_outbox(event_id, recipient_user_id, event_type, audience, payload, created_at, attempt_count, next_attempt_at, delivered_at, error_category)
    VALUES ('a0510000-0000-4000-8000-000000000701', candidate_user_id_value, 'APPLICATION_REJECTED', 'CANDIDATE', jsonb_strip_nulls(jsonb_build_object('companyId', company_id_value, 'companyName', company_name_value, 'jobId', job_b_id, 'jobTitle', (SELECT content ->> 'title' FROM job_posting_revisions WHERE id = rev_b_id), 'applicationId', app_b_id, 'candidateFeedback', 'Kinh nghiệm chưa đáp ứng yêu cầu vị trí.', 'actorUserId', owner_id_value, 'actorName', (SELECT first_name || ' ' || last_name FROM users WHERE id = owner_id_value))), base_time + interval '6 hours', 0, base_time + interval '6 hours', base_time + interval '6 hours 5 seconds', NULL)
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox(source_event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, application_id, candidate_feedback, category, title, body, action_href, actor_user_id, actor_name, created_at, read_at)
    VALUES ('a0510000-0000-4000-8000-000000000701', candidate_user_id_value, 'APPLICATION_REJECTED', 'CANDIDATE', company_id_value, company_name_value, job_b_id, app_b_id, 'Kinh nghiệm chưa đáp ứng yêu cầu vị trí.', 'application', 'Hồ sơ ứng tuyển đã bị từ chối', 'Hồ sơ ứng tuyển của bạn tại ' || company_name_value || ' đã bị từ chối. Kinh nghiệm chưa đáp ứng yêu cầu vị trí.', '/applications/' || app_b_id, owner_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = owner_id_value), base_time + interval '6 hours', base_time + interval '7 hours')
    ON CONFLICT (source_event_id) DO NOTHING;

    -- 8. APPLICATION_WITHDRAWN -> demo_owner (unread)
    INSERT INTO notification_outbox(event_id, recipient_user_id, event_type, audience, payload, created_at, attempt_count, next_attempt_at, delivered_at, error_category)
    VALUES ('a0520000-0000-4000-8000-000000000801', owner_id_value, 'APPLICATION_WITHDRAWN', 'RECRUITER', jsonb_strip_nulls(jsonb_build_object('companyId', company_id_value, 'companyName', company_name_value, 'jobId', job_c_id, 'jobTitle', (SELECT content ->> 'title' FROM job_posting_revisions WHERE id = rev_c_id), 'applicationId', app_c_id, 'actorUserId', candidate_user_id_value, 'actorName', (SELECT first_name || ' ' || last_name FROM users WHERE id = candidate_user_id_value))), base_time + interval '7 hours', 0, base_time + interval '7 hours', base_time + interval '7 hours 5 seconds', NULL)
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox(source_event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, application_id, category, title, body, action_href, actor_user_id, actor_name, created_at, read_at)
    VALUES ('a0520000-0000-4000-8000-000000000801', owner_id_value, 'APPLICATION_WITHDRAWN', 'RECRUITER', company_id_value, company_name_value, job_c_id, app_c_id, 'application', 'Hồ sơ ứng tuyển đã bị rút', 'Hồ sơ ứng tuyển tại ' || company_name_value || ' đã được rút.', '/applications/' || app_c_id, candidate_user_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = candidate_user_id_value), base_time + interval '7 hours', NULL)
    ON CONFLICT (source_event_id) DO NOTHING;

    -- 9. RECRUITER_ACCEPTED -> demo_owner (read)
    INSERT INTO notification_outbox(event_id, recipient_user_id, event_type, audience, payload, created_at, attempt_count, next_attempt_at, delivered_at, error_category)
    VALUES ('a0530000-0000-4000-8000-000000000901', owner_id_value, 'RECRUITER_ACCEPTED', 'RECRUITER', jsonb_strip_nulls(jsonb_build_object('companyId', company_id_value, 'companyName', company_name_value, 'actorUserId', recruiter_id_value, 'actorName', (SELECT first_name || ' ' || last_name FROM users WHERE id = recruiter_id_value))), base_time + interval '8 hours', 0, base_time + interval '8 hours', base_time + interval '8 hours 5 seconds', NULL)
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox(source_event_id, recipient_user_id, event_type, audience, company_id, company_name, category, title, body, action_href, actor_user_id, actor_name, created_at, read_at)
    VALUES ('a0530000-0000-4000-8000-000000000901', owner_id_value, 'RECRUITER_ACCEPTED', 'RECRUITER', company_id_value, company_name_value, 'company', 'Bạn đã được chấp nhận vào ' || company_name_value, 'Bạn đã chính thức trở thành thành viên của ' || company_name_value || '. Hãy hoàn thiện hồ sơ để ứng tuyển và nhận các thông báo liên quan.', '/companies/' || company_id_value, recruiter_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = recruiter_id_value), base_time + interval '8 hours', base_time + interval '9 hours')
    ON CONFLICT (source_event_id) DO NOTHING;

    -- 10. MEMBERSHIP_REMOVED -> demo_recruiter (unread)
    INSERT INTO notification_outbox(event_id, recipient_user_id, event_type, audience, payload, created_at, attempt_count, next_attempt_at, delivered_at, error_category)
    VALUES ('a0540000-0000-4000-8000-000000001001', recruiter_id_value, 'MEMBERSHIP_REMOVED', 'RECRUITER', jsonb_strip_nulls(jsonb_build_object('companyId', company_id_value, 'companyName', company_name_value, 'actorUserId', owner_id_value, 'actorName', (SELECT first_name || ' ' || last_name FROM users WHERE id = owner_id_value))), base_time + interval '9 hours', 0, base_time + interval '9 hours', base_time + interval '9 hours 5 seconds', NULL)
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox(source_event_id, recipient_user_id, event_type, audience, company_id, company_name, category, title, body, action_href, actor_user_id, actor_name, created_at, read_at)
    VALUES ('a0540000-0000-4000-8000-000000001001', recruiter_id_value, 'MEMBERSHIP_REMOVED', 'RECRUITER', company_id_value, company_name_value, 'company', 'Quyền truy cập đã bị thu hồi', 'Quyền truy cập của bạn vào ' || company_name_value || ' đã bị thu hồi. Liên hệ quản trị viên của công ty nếu bạn tin rằng điều này xảy ra nhầm lẫn.', NULL, owner_id_value, (SELECT first_name || ' ' || last_name FROM users WHERE id = owner_id_value), base_time + interval '9 hours', NULL)
    ON CONFLICT (source_event_id) DO NOTHING;
END;
$$;

-- GW-112: shared-CV access grants, a paused application, a post-deletion review
-- window, and a hired application whose library CV was deleted. These are
-- synthetic development fixtures; no Cloudinary object is created.
DO $$
DECLARE
    company_id_value BIGINT;
    company_name_value TEXT;
    owner_id_value BIGINT;
    recruiter_id_value BIGINT;
    candidate_user_id_value BIGINT;
    candidate_id_value UUID;
    shared_cv_id UUID;
    shared_cv_key TEXT;
    deleted_cv_id UUID;
    deleted_cv_key TEXT;
    job_live_id BIGINT;
    job_paused_id BIGINT;
    job_window_id BIGINT;
    job_hired_id BIGINT;
    rev_live_id BIGINT;
    rev_paused_id BIGINT;
    rev_window_id BIGINT;
    rev_hired_id BIGINT;
    app_live_id UUID := 'a04a0000-0000-4000-8000-000000000004';
    app_paused_id UUID := 'a04a0000-0000-4000-8000-000000000005';
    app_window_id UUID := 'a04a0000-0000-4000-8000-000000000006';
    app_hired_id UUID := 'a04a0000-0000-4000-8000-000000000007';
    fixture_time TIMESTAMPTZ := now();
    paused_notice_time TIMESTAMPTZ := now() - interval '31 days';
BEGIN
    SELECT c.id, c.name, c.owner_user_id
    INTO STRICT company_id_value, company_name_value, owner_id_value
    FROM companies c WHERE c.tax_code = 'GW-DEMO-LOCAL-001';
    SELECT id INTO STRICT recruiter_id_value FROM users WHERE username = 'demo_recruiter';
    SELECT id INTO STRICT candidate_user_id_value FROM users WHERE username = 'demo_candidate';
    SELECT id INTO STRICT candidate_id_value
    FROM candidate_profiles WHERE user_id = candidate_user_id_value;

    -- A live shared library CV and a tombstoned CV cover both sides of deletion.
    INSERT INTO candidate_cvs (id, candidate_id, file_name, file_size, content_type, public_id,
        is_default, created_at, updated_at)
    SELECT gen_random_uuid(), cp.id, 'GuardianWork_Shared_CV.pdf', 204800, 'application/pdf',
        'candidates/' || cp.id || '/cvs/demo-shared-live', false,
        fixture_time - interval '40 days', fixture_time - interval '40 days'
    FROM candidate_profiles cp WHERE cp.id = candidate_id_value
    ON CONFLICT (public_id) DO NOTHING;

    INSERT INTO candidate_cvs (id, candidate_id, file_name, file_size, content_type, public_id,
        is_default, created_at, updated_at, deleted_at)
    SELECT gen_random_uuid(), cp.id, 'GuardianWork_Review_Window.pdf', 180000, 'application/pdf',
        'candidates/' || cp.id || '/cvs/demo-review-window', false,
        fixture_time - interval '20 days', fixture_time - interval '1 day',
        fixture_time - interval '1 day'
    FROM candidate_profiles cp WHERE cp.id = candidate_id_value
    ON CONFLICT (public_id) DO NOTHING;

    SELECT id, public_id INTO STRICT shared_cv_id, shared_cv_key
    FROM candidate_cvs
    WHERE candidate_id = candidate_id_value
      AND public_id = 'candidates/' || candidate_id_value || '/cvs/demo-shared-live';
    SELECT id, public_id INTO STRICT deleted_cv_id, deleted_cv_key
    FROM candidate_cvs
    WHERE candidate_id = candidate_id_value
      AND public_id = 'candidates/' || candidate_id_value || '/cvs/demo-review-window';

    SELECT e.job_id INTO STRICT job_live_id
    FROM job_audit_events e
    WHERE e.event_type = 'JOB_CREATED' AND e.facts ->> 'developmentFixture' = 'PUBLISHED-JOB-002';
    SELECT e.job_id INTO STRICT job_paused_id
    FROM job_audit_events e
    WHERE e.event_type = 'JOB_CREATED' AND e.facts ->> 'developmentFixture' = 'PUBLISHED-JOB-003';
    SELECT e.job_id INTO STRICT job_window_id
    FROM job_audit_events e
    WHERE e.event_type = 'JOB_CREATED' AND e.facts ->> 'developmentFixture' = 'PUBLISHED-JOB-004';
    SELECT e.job_id INTO STRICT job_hired_id
    FROM job_audit_events e
    WHERE e.event_type = 'JOB_CREATED' AND e.facts ->> 'developmentFixture' = 'PUBLISHED-JOB-005';
    SELECT published_revision_id INTO STRICT rev_live_id FROM job_postings WHERE id = job_live_id;
    SELECT published_revision_id INTO STRICT rev_paused_id FROM job_postings WHERE id = job_paused_id;
    SELECT published_revision_id INTO STRICT rev_window_id FROM job_postings WHERE id = job_window_id;
    SELECT published_revision_id INTO STRICT rev_hired_id FROM job_postings WHERE id = job_hired_id;
    IF rev_live_id IS NULL OR rev_paused_id IS NULL OR rev_window_id IS NULL OR rev_hired_id IS NULL THEN
        RAISE EXCEPTION 'GW-112 seed requires published demo jobs.';
    END IF;

    INSERT INTO applications (id, candidate_id, job_id, company_id, submitted_revision_id,
        status, version, applied_at, updated_at, status_changed_at)
    VALUES (app_live_id, candidate_id_value, job_live_id, company_id_value, rev_live_id,
        'UNDER_REVIEW', 0, fixture_time - interval '5 days',
        fixture_time - interval '5 days', fixture_time - interval '5 days')
    ON CONFLICT (candidate_id, job_id) DO NOTHING;
    INSERT INTO applications (id, candidate_id, job_id, company_id, submitted_revision_id,
        status, version, applied_at, updated_at, status_changed_at)
    VALUES (app_paused_id, candidate_id_value, job_paused_id, company_id_value, rev_paused_id,
        'INACTIVE', 1, fixture_time - interval '40 days',
        paused_notice_time, paused_notice_time)
    ON CONFLICT (candidate_id, job_id) DO NOTHING;
    INSERT INTO applications (id, candidate_id, job_id, company_id, submitted_revision_id,
        status, version, applied_at, updated_at, status_changed_at)
    VALUES (app_window_id, candidate_id_value, job_window_id, company_id_value, rev_window_id,
        'SHORTLISTED', 1, fixture_time - interval '20 days',
        fixture_time - interval '10 days', fixture_time - interval '10 days')
    ON CONFLICT (candidate_id, job_id) DO NOTHING;
    INSERT INTO applications (id, candidate_id, job_id, company_id, submitted_revision_id,
        status, version, applied_at, updated_at, status_changed_at)
    VALUES (app_hired_id, candidate_id_value, job_hired_id, company_id_value, rev_hired_id,
        'HIRED', 2, fixture_time - interval '15 days',
        fixture_time - interval '2 days', fixture_time - interval '2 days')
    ON CONFLICT (candidate_id, job_id) DO NOTHING;

    INSERT INTO application_snapshots (
        application_id, full_name, email, phone_number, cover_letter, job_title, company_name,
        source_cv_id, cv_storage_key, cv_storage_provider, cv_storage_mode, cv_file_name,
        cv_content_type, cv_file_size, snapshot_schema_version, captured_at)
    SELECT app_live_id, 'An Nguyen Van', 'candidate@demo.guardwork.test', '0900000001',
        'Mong duoc gia nhan vao cong ty GuardWork.', r.content ->> 'title', company_name_value,
        shared_cv_id, shared_cv_key, 'CLOUDINARY', 'SHARED', 'GuardianWork_Shared_CV.pdf',
        'application/pdf', 204800, 1, fixture_time - interval '5 days'
    FROM job_posting_revisions r
    WHERE r.id = rev_live_id AND EXISTS (SELECT 1 FROM applications a WHERE a.id = app_live_id)
    ON CONFLICT (application_id) DO NOTHING;
    INSERT INTO application_snapshots (
        application_id, full_name, email, phone_number, cover_letter, job_title, company_name,
        source_cv_id, cv_storage_key, cv_storage_provider, cv_storage_mode, cv_file_name,
        cv_content_type, cv_file_size, snapshot_schema_version, captured_at)
    SELECT app_paused_id, 'An Nguyen Van', 'candidate@demo.guardwork.test', '0900000001',
        'Mong duoc gia nhan vao cong ty GuardWork.', r.content ->> 'title', company_name_value,
        shared_cv_id, shared_cv_key, 'CLOUDINARY', 'SHARED', 'GuardianWork_Shared_CV.pdf',
        'application/pdf', 204800, 1, fixture_time - interval '40 days'
    FROM job_posting_revisions r
    WHERE r.id = rev_paused_id AND EXISTS (SELECT 1 FROM applications a WHERE a.id = app_paused_id)
    ON CONFLICT (application_id) DO NOTHING;
    INSERT INTO application_snapshots (
        application_id, full_name, email, phone_number, cover_letter, job_title, company_name,
        source_cv_id, cv_storage_key, cv_storage_provider, cv_storage_mode, cv_file_name,
        cv_content_type, cv_file_size, snapshot_schema_version, captured_at)
    SELECT app_window_id, 'An Nguyen Van', 'candidate@demo.guardwork.test', '0900000001',
        'Mong duoc gia nhan vao cong ty GuardWork.', r.content ->> 'title', company_name_value,
        deleted_cv_id, deleted_cv_key, 'CLOUDINARY', 'SHARED', 'GuardianWork_Review_Window.pdf',
        'application/pdf', 180000, 1, fixture_time - interval '20 days'
    FROM job_posting_revisions r
    WHERE r.id = rev_window_id AND EXISTS (SELECT 1 FROM applications a WHERE a.id = app_window_id)
    ON CONFLICT (application_id) DO NOTHING;
    INSERT INTO application_snapshots (
        application_id, full_name, email, phone_number, cover_letter, job_title, company_name,
        source_cv_id, cv_storage_key, cv_storage_provider, cv_storage_mode, cv_file_name,
        cv_content_type, cv_file_size, snapshot_schema_version, captured_at)
    SELECT app_hired_id, 'An Nguyen Van', 'candidate@demo.guardwork.test', '0900000001',
        'Mong duoc gia nhan vao cong ty GuardWork.', r.content ->> 'title', company_name_value,
        deleted_cv_id, deleted_cv_key, 'CLOUDINARY', 'SHARED', 'GuardianWork_Review_Window.pdf',
        'application/pdf', 180000, 1, fixture_time - interval '15 days'
    FROM job_posting_revisions r
    WHERE r.id = rev_hired_id AND EXISTS (SELECT 1 FROM applications a WHERE a.id = app_hired_id)
    ON CONFLICT (application_id) DO NOTHING;

    INSERT INTO application_status_history (
        application_id, from_status, to_status, actor_user_id, occurred_at)
    SELECT v.application_id, v.from_status, v.to_status, v.actor_user_id, v.occurred_at
    FROM (VALUES
        (app_live_id, NULL::VARCHAR, 'SUBMITTED', candidate_user_id_value, fixture_time - interval '5 days'),
        (app_live_id, 'SUBMITTED', 'UNDER_REVIEW', owner_id_value, fixture_time - interval '4 days'),
        (app_paused_id, NULL::VARCHAR, 'SUBMITTED', candidate_user_id_value, fixture_time - interval '40 days'),
        (app_paused_id, 'SUBMITTED', 'UNDER_REVIEW', owner_id_value, fixture_time - interval '39 days'),
        (app_paused_id, 'UNDER_REVIEW', 'INACTIVE', NULL::BIGINT, paused_notice_time),
        (app_window_id, NULL::VARCHAR, 'SUBMITTED', candidate_user_id_value, fixture_time - interval '20 days'),
        (app_window_id, 'SUBMITTED', 'UNDER_REVIEW', owner_id_value, fixture_time - interval '15 days'),
        (app_window_id, 'UNDER_REVIEW', 'SHORTLISTED', owner_id_value, fixture_time - interval '10 days'),
        (app_hired_id, NULL::VARCHAR, 'SUBMITTED', candidate_user_id_value, fixture_time - interval '15 days'),
        (app_hired_id, 'SUBMITTED', 'UNDER_REVIEW', owner_id_value, fixture_time - interval '12 days'),
        (app_hired_id, 'UNDER_REVIEW', 'SHORTLISTED', owner_id_value, fixture_time - interval '5 days'),
        (app_hired_id, 'SHORTLISTED', 'HIRED', owner_id_value, fixture_time - interval '2 days')
    ) AS v(application_id, from_status, to_status, actor_user_id, occurred_at)
    WHERE EXISTS (SELECT 1 FROM applications a WHERE a.id = v.application_id)
      AND NOT EXISTS (
          SELECT 1 FROM application_status_history h
          WHERE h.application_id = v.application_id AND h.to_status = v.to_status
      );

    INSERT INTO candidate_cv_access_grants (
        id, application_id, epoch, cv_id, candidate_id, company_id, scope, storage_provider,
        storage_key, resource_type, file_name, content_type, file_size, granted_at,
        expires_at, revoked_at, revoked_reason)
    SELECT 'a0560000-0000-4000-8000-000000001201', app_live_id, 1, shared_cv_id,
        candidate_id_value, company_id_value, 'APPLICATION_REVIEW', 'CLOUDINARY', shared_cv_key,
        'image', 'GuardianWork_Shared_CV.pdf', 'application/pdf', 204800,
        fixture_time - interval '5 days', NULL, NULL, NULL
    WHERE EXISTS (SELECT 1 FROM applications a WHERE a.id = app_live_id)
    ON CONFLICT (application_id, epoch) DO NOTHING;
    INSERT INTO candidate_cv_access_grants (
        id, application_id, epoch, cv_id, candidate_id, company_id, scope, storage_provider,
        storage_key, resource_type, file_name, content_type, file_size, granted_at,
        expires_at, revoked_at, revoked_reason)
    SELECT 'a0560000-0000-4000-8000-000000001202', app_paused_id, 1, shared_cv_id,
        candidate_id_value, company_id_value, 'APPLICATION_REVIEW', 'CLOUDINARY', shared_cv_key,
        'image', 'GuardianWork_Shared_CV.pdf', 'application/pdf', 204800,
        fixture_time - interval '40 days', NULL, paused_notice_time, 'APPLICATION_INACTIVE'
    WHERE EXISTS (SELECT 1 FROM applications a WHERE a.id = app_paused_id)
    ON CONFLICT (application_id, epoch) DO NOTHING;
    INSERT INTO candidate_cv_access_grants (
        id, application_id, epoch, cv_id, candidate_id, company_id, scope, storage_provider,
        storage_key, resource_type, file_name, content_type, file_size, granted_at,
        expires_at, revoked_at, revoked_reason)
    SELECT 'a0560000-0000-4000-8000-000000001203', app_window_id, 1, deleted_cv_id,
        candidate_id_value, company_id_value, 'APPLICATION_REVIEW', 'CLOUDINARY', deleted_cv_key,
        'image', 'GuardianWork_Review_Window.pdf', 'application/pdf', 180000,
        fixture_time - interval '20 days', fixture_time + interval '29 days', NULL, NULL
    WHERE EXISTS (SELECT 1 FROM applications a WHERE a.id = app_window_id)
    ON CONFLICT (application_id, epoch) DO NOTHING;
    INSERT INTO candidate_cv_access_grants (
        id, application_id, epoch, cv_id, candidate_id, company_id, scope, storage_provider,
        storage_key, resource_type, file_name, content_type, file_size, granted_at,
        expires_at, revoked_at, revoked_reason)
    SELECT 'a0560000-0000-4000-8000-000000001204', app_hired_id, 1, deleted_cv_id,
        candidate_id_value, company_id_value, 'APPLICATION_REVIEW', 'CLOUDINARY', deleted_cv_key,
        'image', 'GuardianWork_Review_Window.pdf', 'application/pdf', 180000,
        fixture_time - interval '15 days', NULL, fixture_time - interval '2 days',
        'CANDIDATE_CV_DELETED'
    WHERE EXISTS (SELECT 1 FROM applications a WHERE a.id = app_hired_id)
    ON CONFLICT (application_id, epoch) DO NOTHING;

    -- Inactivity notifications are visible only to the Owner and currently assigned Recruiter.
    INSERT INTO notification_outbox (
        event_id, recipient_user_id, event_type, audience, payload, created_at,
        attempt_count, next_attempt_at, delivered_at, error_category)
    SELECT 'a0550000-0000-4000-8000-000000001101', owner_id_value, 'APPLICATION_INACTIVE',
        'RECRUITER',
        jsonb_strip_nulls(jsonb_build_object(
            'companyId', company_id_value, 'companyName', company_name_value,
            'jobId', a.job_id, 'jobTitle', r.content ->> 'title', 'applicationId', a.id)),
        paused_notice_time, 0, paused_notice_time,
        paused_notice_time + interval '5 seconds', NULL
    FROM applications a JOIN job_posting_revisions r ON r.id = a.submitted_revision_id
    WHERE a.id = app_paused_id
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox (
        source_event_id, recipient_user_id, event_type, audience, company_id, company_name,
        job_id, application_id, category, title, body, action_href, created_at)
    SELECT 'a0550000-0000-4000-8000-000000001101', owner_id_value, 'APPLICATION_INACTIVE',
        'RECRUITER', company_id_value, company_name_value, a.job_id, a.id, 'application',
        'Hồ sơ ứng tuyển đã không hoạt động',
        'Hồ sơ ứng tuyển cho vị trí ' || (r.content ->> 'title') || ' tại ' || company_name_value
            || ' đã không hoạt động sau một thời gian không cập nhật trạng thái. Quyền truy cập CV đã bị thu hồi; mở lại hồ sơ để tiếp tục xem xét.',
        '/applications/' || a.id, paused_notice_time
    FROM applications a JOIN job_posting_revisions r ON r.id = a.submitted_revision_id
    WHERE a.id = app_paused_id
    ON CONFLICT (source_event_id) DO NOTHING;

    INSERT INTO notification_outbox (
        event_id, recipient_user_id, event_type, audience, payload, created_at,
        attempt_count, next_attempt_at, delivered_at, error_category)
    SELECT 'a0550000-0000-4000-8000-000000001102', recruiter_id_value, 'APPLICATION_INACTIVE',
        'RECRUITER',
        jsonb_strip_nulls(jsonb_build_object(
            'companyId', company_id_value, 'companyName', company_name_value,
            'jobId', a.job_id, 'jobTitle', r.content ->> 'title', 'applicationId', a.id)),
        paused_notice_time, 0, paused_notice_time,
        paused_notice_time + interval '5 seconds', NULL
    FROM applications a JOIN job_posting_revisions r ON r.id = a.submitted_revision_id
    WHERE a.id = app_paused_id
    ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO notification_inbox (
        source_event_id, recipient_user_id, event_type, audience, company_id, company_name,
        job_id, application_id, category, title, body, action_href, created_at)
    SELECT 'a0550000-0000-4000-8000-000000001102', recruiter_id_value, 'APPLICATION_INACTIVE',
        'RECRUITER', company_id_value, company_name_value, a.job_id, a.id, 'application',
        'Hồ sơ ứng tuyển đã không hoạt động',
        'Hồ sơ ứng tuyển cho vị trí ' || (r.content ->> 'title') || ' tại ' || company_name_value
            || ' đã không hoạt động sau một thời gian không cập nhật trạng thái. Quyền truy cập CV đã bị thu hồi; mở lại hồ sơ để tiếp tục xem xét.',
        '/applications/' || a.id, paused_notice_time
    FROM applications a JOIN job_posting_revisions r ON r.id = a.submitted_revision_id
    WHERE a.id = app_paused_id
    ON CONFLICT (source_event_id) DO NOTHING;
END;
$$;

COMMIT;
