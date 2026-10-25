# Portal routing and session contract

| Field | Value |
| --- | --- |
| Specification ID | GW-SR-AUTH-02 |
| Status | Human implementation confirmation recorded; formal lifecycle approval pending |
| Owner | VyTrg |
| Named human approver | VyTrg |
| Last updated | 2026-10-24 |
| Authorization evidence | User explicitly supplied the public/staff proxy contract and requested implementation in this session on 2026-10-24. |

This scoped amendment coordinates GW-SR-AUTH-01 with the Staff rename recorded in
[Staff specification section 4.3](../../feat/staff/spec.md#43-gw-81-runtime-adoption-and-compatibility).
The user authorized implementation of the contract below; this document does not
mark an agent-authored specification Approved or approve other authentication work.

## Confirmed contract

- Public frontend: `http://localhost:5137`; Staff frontend: `http://localhost:5138`.
  Both call `/api`, proxied by Vite to `http://localhost:8080` without stripping `/api`.
- The proxy supplies exactly one `X-Guardian-Portal: public` or `staff` header.
  A deployed gateway must remove client-supplied values and overwrite the header
  from its trusted portal routing. The header is context, never authority.
- Both use `POST /api/auth/login` with `{ usernameOrEmail, password }` and receive
  tokens in JSON. The frontend stores tokens in `sessionStorage` and sends access
  tokens as `Authorization: Bearer <accessToken>`; no cookies are introduced.
- A supplied portal must match the authenticated account's Platform role:
  public uses `USER`, staff uses `STAFF`. Company `ADMIN` does not grant Staff access.
  Wrong-portal login and access return 403. Invalid portal values, empty values,
  comma lists and duplicate portal headers return 403.
- Access tokens carry a signed `portal` claim. A supplied request portal must match
  that claim. `/api/staff/**` additionally requires signed Platform role `STAFF`;
  absent/invalid/expired Bearer tokens return 401, insufficient authority returns 403.
- Refresh tokens persist their portal and keep it through rotation. A refresh/logout
  attempted from another supplied portal returns 401 without consuming the session.
- Responses keep `{ status, message, data }`; error HTTP and envelope statuses match.
- Vite proxy requests need no direct browser CORS. Optional direct-origin access uses
  an exact configured allowlist and allows `Authorization`, `Content-Type`,
  `X-Guardian-Portal`, and `X-Request-ID`. No wildcard origins or credentialed cookies.

## Compatibility decisions disclosed during implementation

For existing Swagger, command-line and internal clients that omit the portal header,
login derives the portal from the authenticated Platform role; refresh/logout use the
persisted session portal. Access tokens issued before this change without a portal
claim derive their context from the signed role. This preserves the existing Staff
login/refresh contract and does not grant roles from headers. Unknown roles are still
denied by existing Staff guards. Explicit wrong-portal headers are never ignored.

## Persistence and rollout

New immutable migration `V2026.10.24.82__bind_refresh_tokens_to_portal.sql` adds a
non-null, constrained `portal` field and backfills existing sessions from current
Platform roles (`STAFF` -> `staff`, other accounts -> `public`). It follows the Staff
role migration and preserves token hashes, expiry and family identifiers. Deploy the
migration with the backend; old application writers cannot create refresh rows after
it because they do not supply portal, so do not run mixed old/new writers.

No new dependency or frontend framework, response envelope, token storage, token
lifetime, Company authorization, or unrelated account workflow is introduced.
