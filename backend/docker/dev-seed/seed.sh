#!/bin/sh
set -eu

if [ "${GUARDWORK_DEV_SEED_ENABLED:-true}" != "true" ]; then
    echo 'Local mock data disabled.'
    exit 0
fi

# Wait for every committed migration, including migrations added in the future.
# Never create schema objects or modify Flyway history from this service.
versions=""
expected=0
for migration in /migrations/V*__*.sql; do
    version=${migration##*/V}
    version=${version%%__*}
    versions="${versions}${versions:+,}'${version}'"
    expected=$((expected + 1))
done

echo 'Waiting for backend Flyway migrations. Start the backend with ./mvnw spring-boot:run.'
until [ "$(psql -XAtq -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM flyway_schema_history WHERE success AND version IN ($versions)" 2>/dev/null || true)" = "$expected" ]; do
    sleep 2
done

psql -X -v ON_ERROR_STOP=1 -f /seed/seed.sql
echo 'Local mock data ready. Login: POST http://localhost:8080/api/auth/login'
echo 'Default password for NEW mock accounts: GuardWork123! (existing passwords and data are preserved).'
psql -X -P pager=off -c "SELECT u.username, u.email, u.role AS platform_role, m.role AS company_role, u.status FROM users u LEFT JOIN company_memberships m ON m.user_id = u.id AND m.status = 'ACTIVE' WHERE u.username IN ('demo_candidate', 'demo_admin', 'demo_owner', 'demo_company_admin', 'demo_recruiter') ORDER BY u.username"
