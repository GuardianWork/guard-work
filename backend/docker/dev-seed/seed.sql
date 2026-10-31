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

INSERT INTO candidate_profiles (user_id, full_name, skills, experience_summary, education_summary, is_public)
SELECT id, 'Nguyễn Văn An', '["Java", "Spring Boot", "PostgreSQL"]'::jsonb,
    'Sinh viên thực tập phát triển phần mềm.', 'Sinh viên ngành Công nghệ thông tin.', false
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

COMMIT;
