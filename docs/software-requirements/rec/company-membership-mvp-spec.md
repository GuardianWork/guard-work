# [GW-SR-REC-02] Company onboarding, ownership, and Recruiter access

| Field | Value |
|---|---|
| **Specification ID** | GW-SR-REC-02 |
| **Tracker Specification ID** | GW-SR-COMPANY-MEMBERSHIP-MVP |
| **Project** | GuardWork |
| **Module** | Company Membership MVP |
| **File** | `company-membership-mvp-spec.md` |
| **Version** | 0.3.0 |
| **Date** | 2026-10-08 |
| **Last Updated** | 2026-10-08 |
| **Owner** | Thinh |
| **Approver** | Thinh |
| **Lifecycle** | Draft |
| **Status** | Implementation Complete; original review register retained |

> Completion recorded on 2026-10-08: the user explicitly confirmed that GW-001 is completed and requested implementation from branch `feat/GW-64-define-and-approve-the-company-membership` (commit `77ea324`). That confirmation authorizes the dependent tickets to use this contract package, including the AUTH response envelope and real HTTP/Security/PostgreSQL test boundary. The original review metadata and pending-review table are retained as history; they do not override the user's completion confirmation. GW-005 implemented the replaceable mailer port with Resend's HTTPS API through JDK `HttpClient`, without a new dependency. Deployment credentials and sender setup remain operational configuration. Agents do not independently declare human approval.

## 1. Purpose and scope

This specification defines the first Company Membership milestone: authenticated Company creation with an initial Owner, reviewed assignment of Owners to existing Companies, Owner access to verification status and correction, invitations for Recruiters of verified Companies, invitation acceptance and decline, immediate Recruiter access removal, and the minimal in-app inbox for acceptance and removal events.

It preserves the existing Company verification states and moderation workflow. Company ownership does not verify a Company. Company Membership does not grant access to Candidate data or implement Job Assignment, Job Publishing, or Application processing.

### 1.1 Delivery boundaries

The seven issue files under `.scratch/company-membership-mvp/issues/` are the delivery plan. This replaces the earlier nine-ticket planning list only; it does not change the parent tracker specification or product requirements.

| Issue | Delivery boundary |
|---|---|
| GW-001 | Review and approve this specification and its consequential API, persistence, migration, authorization, email, notification, and test contracts. No product behavior is implemented by this ticket. |
| GW-002 | Authenticated Company creation, atomic first Owner membership, current-user Company context, and access to the Owner workspace and verification status. |
| GW-003 | Controlled, reviewed Owner assignment to existing Companies; no public claim endpoint or ownership transfer. |
| GW-004 | Owner-only correction and resubmission of rejected verification requests, retaining Company identity and moderation history. |
| GW-005 | Owner invitation management for verified Companies, including private email delivery, issue, resend, revoke, expiry, and failure recovery. |
| GW-006 | Invitation preview, acceptance or decline, atomic Recruiter membership activation, Owner notification, and the minimal recipient-scoped inbox. |
| GW-007 | Owner removal and Recruiter departure, immediate authorization revocation, retained history, removal notification, and inbox access after removal. |

Email delivery is part of GW-005. The minimal in-app inbox is part of GW-006 and GW-007. These boundaries do not expand any ticket in the parent tracker specification.

### 1.2 Out of scope

- Frontend implementation; Job Assignments; Job Publishing; Application, Interview, Offer, and Candidate-data processing.
- Platform Admin membership management, Admin invitations, role promotion or demotion, and ownership transfer.
- General mailbox verification, password-reset email repair, email campaigns, notification preferences, push delivery, and a general notification platform.
- Company closure, account deletion, billing, or changes to platform roles.
- A public Company ownership claim endpoint or a new ownership review dashboard.

## 2. Domain model and lifecycle

Use the terms in the root `GLOSSARY.md`. A Company Role is scoped to a Company Membership and never becomes a platform role.

| Concept | Contract |
|---|---|
| Company | The employer on whose behalf Company Members recruit. Existing verification fields and the `PENDING`, `VERIFIED`, and `REJECTED` states remain authoritative. `is_banned` is the existing suspension flag. |
| Company Membership | A retained relationship between a User and a Company for one membership period, with a Company Role and `ACTIVE`, `LEFT`, or `REMOVED` state. |
| Company Role | The stored role may be `OWNER`, `ADMIN`, or `RECRUITER`. This milestone creates Owners and Recruiters; it grants no Admin-management capability. |
| Company Invitation | A single-use, revocable, expiring invitation for one normalized email address, one Company, and the `RECRUITER` Company Role. |
| Notification | A minimal inbox item addressed to exactly one User. It does not grant Company access. |

### 2.1 Invitation lifecycle

```text
PENDING -> ACCEPTED
        -> DECLINED
        -> EXPIRED
        -> REVOKED
```

An invitation is not a Company Membership. Resend keeps the invitation record pending while atomically replacing its secret, inviter, issue time, and expiry. An expired, declined, accepted, or revoked invitation cannot be made pending again; a new invitation creates a new record.

### 2.2 Membership lifecycle

```text
ACTIVE -> LEFT       (Recruiter leaves)
       -> REMOVED    (Owner removes Recruiter)
```

Inactive membership periods are retained. A later admission creates a new membership row rather than reactivating an old period, so prior join and departure facts remain intact. User suspension and Company suspension restrict access without silently changing membership state.

## 3. Functional requirements

### 3.1 Company creation and current-user context

| ID | Requirement |
|---|---|
| REQ-REC-10 | Company registration shall require an authenticated, active User. The User identity shall come from the authenticated principal, never from request data. |
| REQ-REC-11 | A User with no active Company Membership may create one Company and its first active Owner Membership in one transaction. The new Company begins in the existing `PENDING` verification workflow. |
| REQ-REC-12 | A User with an active Company Membership shall not create another Company. Inactive historical memberships do not make the User ineligible. |
| REQ-REC-13 | A failed Company creation shall leave neither a Company nor an Owner Membership. Concurrent requests shall preserve one active membership per User and one active Owner per managed Company. |
| REQ-REC-14 | `GET /api/auth/me` shall resolve current Company context from current persisted membership. It returns `activeMembership: null` without an active membership and the active Company identity and Company Role when one exists. It shall not add Company authority to a JWT or change the platform role. |
| REQ-REC-15 | A newly created or reviewed-assigned Company shall have exactly one active Owner. An unassigned legacy Company remains unavailable for Company management. |
| REQ-REC-16 | A Company Member may continue using Candidate capabilities, but Company Membership alone shall not expose Candidate profiles, CVs, applications, or other Candidate data. |

### 3.2 Verification access and correction

| ID | Requirement |
|---|---|
| REQ-REC-17 | The current Owner shall be able to inspect the Company's verification status, current rejection feedback, and version while verification is pending or rejected. |
| REQ-REC-18 | Only the current Owner may correct and resubmit that Owner's rejected Company through authenticated, Company-scoped management. The existing Company ID, tax-code uniqueness, version check, and moderation history are preserved. |
| REQ-REC-19 | Resubmission is allowed only from `REJECTED`, clears the current rejection feedback, increments the optimistic version, and returns the Company to `PENDING`. It does not verify the Company. |
| REQ-REC-20 | Unauthenticated callers, unrelated Users, Recruiters, inactive members, and suspended Users cannot read or change private verification details. Existing Platform Administrator review and decision authority remains separate. |
| REQ-REC-21 | Pending or rejected Companies cannot issue or resend Recruiter invitations. Owners retain status, feedback, and correction access before verification. |

### 3.3 Recruiter invitations

| ID | Requirement |
|---|---|
| REQ-REC-22 | Only the current Owner of a `VERIFIED`, non-suspended Company may issue or resend an invitation. The only invitation role in this milestone is `RECRUITER`. |
| REQ-REC-23 | An invitation may be sent to an address without a registered User. Registration and login continue through the existing authentication capability. |
| REQ-REC-24 | A pending invitation identifies the Company, normalized recipient email, proposed Recruiter role, current inviter, issue time, seven-calendar-day expiry, delivery-attempt state, and a non-reversible secret hash. |
| REQ-REC-25 | At most one unexpired pending invitation may exist per Company and normalized email. The database enforces pending uniqueness; the service first transitions an expired pending row to `EXPIRED` before allowing a replacement. |
| REQ-REC-26 | Resend shall be an Owner-authorized operation. It atomically replaces the secret hash, inviter, issue time, and expiry; a previously issued secret stops working as soon as resend commits. |
| REQ-REC-27 | The Owner may revoke a pending invitation. Revocation and acceptance serialize on that invitation; whichever commits first determines the result. A revoked invitation never activates a membership. |
| REQ-REC-28 | Invitation issue, resend, preview, acceptance, and decline shall not reveal whether an unrelated User account or Company Membership exists. Owner responses shall not disclose whether the recipient has a membership in another Company. |
| REQ-REC-29 | The invitation secret shall be delivered only to the invited mailbox, shall not be returned by Owner APIs, and shall not be stored in plaintext, logs, audit records, analytics, or notification payloads. |

### 3.4 Invitation preview and acceptance

| ID | Requirement |
|---|---|
| REQ-REC-30 | The recipient shall authenticate as a User whose normalized email exactly matches the invited email and present the private email-delivered secret. An `emailVerified` flag alone is not proof of mailbox access. |
| REQ-REC-31 | A registered recipient may sign in; an unregistered recipient may register through existing authentication using the invited email. The recipient must then authenticate before preview or acceptance. |
| REQ-REC-32 | Preview shall return only the Company name, proposed Recruiter role, expiry, and confirmation details. Previewing or opening an email link does not consume the invitation or activate a membership. |
| REQ-REC-33 | Acceptance requires an explicit confirmation request. At its commit boundary, the service shall recheck the secret, recipient email and account status, invitation state and expiry, Company verification and suspension state, and the User's active-membership eligibility. |
| REQ-REC-34 | Acceptance atomically consumes the invitation, creates an active Recruiter Membership, records audit facts, and records Owner-notification intent. It shall not create two memberships or two notifications when requests race or the same recipient retries after an uncertain response. |
| REQ-REC-35 | A User with an active Company Membership cannot accept another Company's invitation until the current membership is inactive. The response shall not include the other Company's name or ID. |
| REQ-REC-36 | A repeated acceptance by the same authenticated recipient for the already-consumed invitation may return the original accepted result idempotently; it shall not create a new membership or notification. Every other use of a consumed secret is unavailable. |
| REQ-REC-37 | An invitation that is expired, revoked, declined, used, invalid, or presented by a different authenticated email cannot activate a membership. These conditions share a privacy-preserving response. |

### 3.5 Membership access and revocation

| ID | Requirement |
|---|---|
| REQ-REC-38 | An Owner may remove an active Recruiter from the Owner's Company. A Recruiter may end only their own active membership. |
| REQ-REC-39 | Owner departure and removal are rejected. Membership APIs do not provide ownership transfer or Admin management. |
| REQ-REC-40 | Removal stores `REMOVED`; voluntary departure stores `LEFT`. Neither deletes historical Company Membership or prior authorship. A later admission creates a new membership period. |
| REQ-REC-41 | Every protected Company read and write shall check current User status, Company suspension, active Company Membership, Company Role, and the requested Company scope. A still-valid JWT does not preserve revoked access. |
| REQ-REC-42 | A protected mutation and removal shall serialize on the current membership guard through transaction commit. If revocation commits first, the protected mutation fails without partial business effects. If a protected mutation commits first, removal follows it. |
| REQ-REC-43 | Company eligibility shall be rechecked at the relevant commit boundary for creation, invitation issue/resend, acceptance, and protected Company changes. Invitation acceptance requires the Company still be `VERIFIED` and not suspended. |

### 3.6 Minimal notification inbox and audit

| ID | Requirement |
|---|---|
| REQ-REC-44 | Successful Recruiter acceptance creates one notification intent for the Company's Owner. Owner removal creates one notification intent for the removed Recruiter. Recruiter departure does not notify the departing User in this milestone. |
| REQ-REC-45 | A User may list and mark read only their own inbox items. A removed Recruiter may read their removal notification without an active Company Membership. |
| REQ-REC-46 | Notifications contain only the event type, Company identity needed for the message, and event time. Any link to a Company resource must recheck current authorization. An old notification never restores access. |
| REQ-REC-47 | Membership change, required audit fact, and notification intent are committed together. Post-commit notification delivery failure shall not undo admission, removal, or departure; retry shall be idempotent. |
| REQ-REC-48 | Audit facts record actor, subject, Company, action, relevant invitation or membership ID, timestamp, and ownership-review case reference where applicable. They contain no invitation secret, credential, Candidate data, or unnecessary identity documents. |
| REQ-REC-49 | All User-facing names, emails, rejection feedback, and notification data preserve Unicode and Vietnamese text. Persist instants in UTC and apply the specified Vietnam calendar rule when calculating invitation expiry. |

## 4. Business and concurrency rules

| ID | Rule |
|---|---|
| BR-REC-10 | A User may have at most one active Company Membership. |
| BR-REC-11 | Every membership-managed Company has exactly one active Owner Membership matching its stored owner reference; an unassigned legacy Company has none and is not member-managed. |
| BR-REC-12 | Company Role is scoped to Company Membership and never becomes a platform role. |
| BR-REC-13 | Company creation and Owner Membership creation commit atomically; Company creation does not imply verification. |
| BR-REC-14 | Invitation issue, resend, and acceptance require a currently verified, non-suspended Company. Owner verification status and rejected-request correction remain available before verification. |
| BR-REC-15 | A Company and normalized email address have at most one pending invitation. |
| BR-REC-16 | An invitation expires at the same local wall-clock time seven calendar days after issuance in `Asia/Ho_Chi_Minh`; `now >= expires_at` is expired. |
| BR-REC-17 | Acceptance requires the private invitation secret, an authenticated account with the exact normalized invited email, and explicit confirmation; `emailVerified` alone is insufficient. |
| BR-REC-18 | Invitation acceptance is single-use and atomically consumes the invitation while creating at most one active membership. |
| BR-REC-19 | Resend rotates the invitation secret and expiry; a secret issued before resend is invalid once resend commits. |
| BR-REC-20 | Membership removal/departure retains the inactive membership period; re-admission creates a new period. |
| BR-REC-21 | Owner cannot leave, be removed, or be transferred through membership administration in this milestone. |
| BR-REC-22 | Current membership, Company Role, User state, and Company state are rechecked at each protected boundary; a revocation committed first prevents a protected write from committing partial effects. |
| BR-REC-23 | Only the notification recipient can read or mark read their inbox items. Notification intent is committed with the membership event; post-commit delivery retries cannot reverse that event. |
| BR-REC-24 | Company identifiers, tax code, and email are selectors only; none is sufficient to claim ownership or Company access. |
| BR-REC-25 | Company Membership alone grants no access to Candidate data and no Platform Administrator authority. |

### 4.1 Atomic creation

Company creation runs in one database transaction. It locks or otherwise serializes on the authenticated User, verifies the User is active and has no active Company Membership, creates the Company as `PENDING`, creates its active `OWNER` Membership, sets the Company owner reference, and records the creation audit fact. Any failure rolls back every row. Database uniqueness and the owner consistency constraint remain the final backstop against concurrent requests.

### 4.2 Invitation issue, expiry, and resend

- Normalize an email using the same trim-and-lowercase rule as User registration. Do not rewrite plus-addresses or infer mailbox equivalence.
- Generate a cryptographically random secret with at least 256 bits of entropy and encode it for a URL-safe link. Persist only its SHA-256 hash. Compare hashes in constant time where application comparison is used.
- Store `issued_at` and `expires_at` as UTC instants. `expires_at` is the same local wall-clock time seven calendar days after issue in `Asia/Ho_Chi_Minh`, converted to UTC. A secret is expired when `now >= expires_at`.
- Issue and resend require a current Owner, an active Owner Membership, an active User, a `VERIFIED` Company, and `is_banned = false` in the transaction that updates the invitation.
- Resend rotates the hash before delivery. If resend and acceptance race, the transaction that first commits the invitation row wins; the losing request sees a terminal or changed invitation and cannot proceed using stale state.
- There is no automatic account lookup during invitation issue. If the recipient already has an active membership, acceptance fails with a generic eligibility conflict that does not disclose the other Company.
- Expiry is enforced on every preview, accept, resend, and issue operation. Issue first marks an expired pending row `EXPIRED`; no scheduled expiry service is required for correctness.

### 4.3 Acceptance and notification recovery

Acceptance first resolves the submitted secret to a candidate invitation, then locks the authenticated User, Company, and invitation in a consistent order and revalidates all eligibility at commit. The invitation state change, membership insert, audit event, and outbox intent are one transaction. A unique active-membership index arbitrates concurrent invitations sent by different Companies to the same User.

Notification dispatch is post-commit and at-least-once. Each outbox event has a stable unique event ID; each inbox item has a unique source event ID, making a retry after an uncertain write harmless. A failed dispatch leaves the outbox row pending for retry and does not touch the committed membership. The proposed retry policy is exponential backoff capped at 30 minutes, alert after five consecutive failures, and retain the event for operator replay. This policy needs human approval before implementation.

### 4.4 Revocation and protected work

Current membership is the authority source. The proposed database contract locks the membership guard through protected writes and locks the Company row for Company-state-dependent writes. Removal and Company suspension use the same guard rows. Authorization is ordered by transaction commit, not token issue time. A denied operation has no partial business effect. For reads, membership and Company state are checked in the same read boundary; a later revocation cannot retract data already returned.

### 4.5 Retained history and Owner protection

An inactive membership period is immutable as history. Rejoining creates a new row and a new `joined_at`; it does not change `LEFT` back to `ACTIVE` or erase the reason and actor for a prior `REMOVED` state. An active Owner cannot leave, be removed, or be changed to another Company Role in this milestone. A future ownership transfer must be a separately approved workflow.

## 5. Reviewed legacy ownership assignment

Existing Companies without an Owner remain in an unassigned state and unavailable to member-facing Company APIs. No record is assigned based on Company ID, tax code, Company contact email, User email, first request, or possession of a URL.

### 5.1 Reviewer, evidence, and operator

1. A Platform Administrator with Company verification authority reviews a case. The applicant must establish both their identity and current legal authority to represent the Company.
2. Acceptable evidence is a current authoritative business-registration record identifying the Company's legal representative, or a current authorization from that legal representative plus an independently checked registration record. The reviewer verifies the applicant's identity against the evidence. An email from a Company domain may corroborate a case but is never sufficient by itself.
3. Conflicting claims, stale or contradictory records, unclear authority, or an existing active Company Membership stop the case. The reviewer rejects or escalates it for independent governance review; no first-come assignment or automatic transfer occurs.
4. After a recorded approval, a separate authorized operations operator assigns the User through a restricted, audited one-Company operation. Reviewer and operator identities must differ. No public endpoint or general-purpose ownership dashboard is introduced.
5. The assignment transaction verifies the Company still has no Owner, the applicant has no active Company Membership, and the evidence-review decision is approved. It creates the active Owner Membership, sets the Company owner reference, writes the audit fact, and commits atomically. Conflict leaves the Company unassigned and unchanged.

The audit record stores a review case reference, evidence type, reviewer, operator, decision, timestamp, and outcome. It does not store full identity documents or invitation secrets. Where evidence is retained, its private storage location, access policy, and retention period require an approved records policy. Assignment preserves the Company's verification and suspension state; it does not verify the Company, overwrite an Owner, or transfer ownership.

## 6. API contracts

All endpoints use HTTPS, `Authorization: Bearer <JWT>` when protected, and `application/json`. Company authority is derived from the current persisted membership. This draft proposes the success and error envelope in the approved AUTH specification and used by the current authentication/security handlers: success uses `ApiResponse<T>`; error uses `{ "status": <HTTP status>, "message": "<code> <constant>: <safe message>", "data": null }`. Paginated lists use the existing `PageResponse<T>` fields. The generic `docs/convention/app-convention.md` separately describes an `ErrorApiResponse` shape with `detailed_message` and `errors`, and its example puts an application code in `status`; current auth/company code does not use that shape. This conflict is unresolved: Thinh must approve which convention applies before Company API implementation. This draft recommends retaining the AUTH envelope for these routes to avoid an unreviewed envelope migration. The proposed route additions and error codes also need named human approval.

### 6.1 Create Company and retrieve Company context

#### `POST /api/companies/verification-request` — authenticated Company creation

This retains the existing path to reduce client route changes, but changes anonymous submission into authenticated Company creation plus atomic Owner membership. `User ID` and role are never accepted in the body.

```json
{
  "name": "Công ty TNHH Giải Pháp Alpha",
  "taxCode": "0101234567",
  "email": "contact@alpha.com.vn",
  "registrationCertificateUrl": "https://storage.guardwork.vn/licenses/alpha.pdf"
}
```

Returns HTTP `201 Created` and the existing Company verification response fields. The Company is `PENDING`; response data does not contain a token or membership secret. Active-membership conflict returns a generic `409` without Company details. Duplicate tax code retains `40901 DUPLICATE_TAX_CODE` behavior.

#### `GET /api/auth/me` — current-user context

Keep the approved AUTH response envelope and `activeMembership` field. Return `null` if no active Company Membership exists; otherwise return `companyId`, `companyName`, `companyRole`, and the membership's `joinedAt`. Resolve this on each request from current membership state; do not embed the result as trusted token authority.

#### `GET /api/companies/me` — current Company summary

For a User with an active Company Membership, return the current Company identity and Company Role. This route is scoped by the principal; it has no Company ID or tax code lookup. A User with no active membership receives HTTP `200 OK` with `data: null` rather than a not-found or forbidden error, matching the optional `activeMembership` in `/api/auth/me`.

```json
{
  "status": 200,
  "message": "Current Company retrieved successfully",
  "data": null
}
```

For an active membership, `data` contains `{ "companyId", "companyName", "companyRole" }`. The HTTP `200` response with `activeMembership: null` from `/api/auth/me` remains the canonical client check for Company context.

### 6.2 Owner verification management

#### `GET /api/companies/me/verification`

Owner-only. Returns `companyId`, `name`, `taxCode`, `email`, `registrationCertificateUrl`, `verificationStatus`, `rejectionReason`, `version`, and timestamps. It is available while status is `PENDING` or `REJECTED`. Recruiters do not receive rejection feedback.

#### `PUT /api/companies/me/verification`

Owner-only correction and resubmission of a rejected Company. Body fields are the permitted editable dossier fields plus required `expectedVersion`; `taxCode`, Company ID, and owner are immutable. Only `REJECTED` is eligible. On success, status becomes `PENDING`, rejection feedback clears, and `version` increments. Existing `40012 COMPANY_NOT_REJECTED` and `40022 CONCURRENT_MODIFICATION` semantics remain.

```json
{
  "name": "Công ty TNHH Giải Pháp Alpha",
  "email": "contact@alpha.com.vn",
  "registrationCertificateUrl": "https://storage.guardwork.vn/licenses/alpha-v2.pdf",
  "expectedVersion": 1
}
```

Returns HTTP `200 OK` with the same Company dossier response shape as `GET /api/companies/me/verification`, showing `verificationStatus: "PENDING"`, `rejectionReason: null`, and the incremented version.

The existing `PUT /api/companies/{id}/resubmit` may remain as an authenticated compatibility alias only if it applies the same current-Owner check and returns the same response. The path ID is never authority.

### 6.3 Owner invitation management

All routes below require the current Owner of the current Company. `GET` uses `page=0`, `size=20` and the existing page response contract.

| Method and path | Behavior |
|---|---|
| `GET /api/companies/me/invitations?status=PENDING&page=0&size=20` | List the Owner's invitation metadata; never return a secret or hash. |
| `POST /api/companies/me/invitations` | Issue a Recruiter invitation. Body: `{ "email": "recruiter@example.com" }`. Only a verified, non-suspended Company is eligible. |
| `POST /api/companies/me/invitations/{invitationId}/resend` | Rotate secret, inviter, issue time, and expiry; make one delivery attempt. |
| `DELETE /api/companies/me/invitations/{invitationId}` | Revoke a pending invitation. |

Issue returns HTTP `201 Created`; resend returns `200 OK`; revoke returns `200 OK` with `status: "REVOKED"`. Issue and resend return only `{ id, email, companyId, companyName, companyRole, status, issuedAt, expiresAt, deliveryStatus }`. `deliveryStatus` is `UNKNOWN`, `PROVIDER_ACCEPTED`, or `FAILED`; `PROVIDER_ACCEPTED` does not claim mailbox delivery. If delivery fails after persistence, the invitation remains pending and the response makes the failure explicit. The Owner retries through resend, which invalidates the previous secret. A repeated create for a pending Company/email returns a generic `409` and does not resend implicitly.

Example issue response data:

```json
{
  "id": 81,
  "email": "recruiter@example.com",
  "companyId": 10,
  "companyName": "Công ty TNHH Giải Pháp Alpha",
  "companyRole": "RECRUITER",
  "status": "PENDING",
  "issuedAt": "2026-10-08T03:00:00Z",
  "expiresAt": "2026-10-15T03:00:00Z",
  "deliveryStatus": "PROVIDER_ACCEPTED"
}
```

Provider `FAILED` or unresolved `UNKNOWN` does not change the persisted invitation status to a terminal state and does not return the secret to the Owner.

### 6.4 Recipient preview, acceptance, and decline

The email link places the secret in the URL fragment, not its path or query. The frontend reads the fragment only after an explicit user action, removes it from browser history, and posts the secret in JSON rather than navigating to a backend URL. The invitation page must not load third-party analytics or assets before removing the fragment and must send `Referrer-Policy: no-referrer`. Preview responses use `Cache-Control: no-store`; the backend redacts secret-bearing request bodies. These routes require an authenticated User whose normalized account email matches the invitation.

| Method and path | Request | Behavior |
|---|---|---|
| `POST /api/company-invitations/preview` | `{ "secret": "<email secret>" }` | Return Company name, `RECRUITER` role, and expiry without consuming the invitation. |
| `POST /api/company-invitations/accept` | `{ "secret": "<email secret>", "confirm": true }` | Explicitly accept and atomically activate membership. |
| `POST /api/company-invitations/decline` | `{ "secret": "<email secret>" }` | Decline without creating membership. |

Preview returns HTTP `200 OK` with `{ "invitationId", "companyId", "companyName", "companyRole": "RECRUITER", "expiresAt" }`. It returns no recipient account, membership, or Company verification details.

Acceptance returns HTTP `201 Created` with `{ "invitationId", "membershipId", "companyId", "companyName", "companyRole": "RECRUITER", "membershipStatus": "ACTIVE", "acceptedAt" }`. A retry by the same authenticated recipient after an uncertain response returns HTTP `200 OK` with the same receipt identifiers and `membershipStatus` read from the current membership row (`ACTIVE`, `LEFT`, or `REMOVED`); it never creates or reactivates membership. The client refetches `/api/auth/me` for current Company access.

Decline returns HTTP `200 OK` with `{ "invitationId", "status": "DECLINED", "decidedAt" }`. Repeating a decline by the same recipient returns the same decision receipt; it does not create a membership or notification.

Invalid, expired, revoked, declined, used-by-another-actor, or wrong-email secrets return the same `40410 INVITATION_UNAVAILABLE` result. Acceptance additionally hides a Company that is no longer eligible behind that unavailable result. A different active Company Membership returns a generic eligibility conflict with no Company identity.

### 6.5 Membership and recipient inbox

| Method and path | Authorization and behavior |
|---|---|
| `GET /api/companies/me/members?page=0&size=20` | Owner lists active Company Memberships, including their own Owner Membership and active Recruiters. |
| `DELETE /api/companies/me/members/{membershipId}` | Owner removes an active Recruiter. Returns the resulting inactive membership metadata; Owner removal is rejected. |
| `POST /api/companies/me/membership/leave` | Recruiter ends their own membership. Owner departure is rejected. |
| `GET /api/me/notifications?page=0&size=20` | Authenticated User reads only their own notification inbox, including after membership removal. |
| `PATCH /api/me/notifications/{notificationId}/read` | Mark only the current User's notification read. Other recipients' IDs return the same not-found response as absent IDs. |

Inbox items expose `id`, `eventType`, `companyId`, `companyName`, `createdAt`, and `readAt`. Resource links are not authority; opening a linked Company route performs fresh authorization. There is no inbox API for another User, delivery preference, or general notification type in this milestone.

The member list returns `PageResponse` content with `membershipId`, `userId`, `firstName`, `lastName`, `email`, `companyRole`, `status`, and `joinedAt`. Removal returns HTTP `200 OK` with `{ "membershipId", "status": "REMOVED", "endedAt" }`; departure returns HTTP `200 OK` with `{ "membershipId", "status": "LEFT", "endedAt" }`. In both cases the user, membership, Company, role, and Company suspension are rechecked at the transaction boundary.

The inbox list returns the existing `ApiResponse<PageResponse<NotificationResponse>>`. Mark-read returns HTTP `200 OK` with `{ "id", "readAt" }`; repeating mark-read returns the same read timestamp. Another User's notification ID returns the same `404` as a missing ID.

### 6.6 Reviewed legacy assignment

There is no public Company claim API. A restricted operational assignment records the reviewed case and separate reviewer/operator identities, then writes the Owner Membership and Company owner reference atomically. The executable commands are documented in the [backend README](../../../backend/README.md#reviewed-legacy-owner-assignment-gw-003). They use separate active Platform Administrator identities and a private, short-lived case input; documents remain outside application storage. The command cannot decide whether the real evidence is sufficient for the reviewer.

### 6.7 Error catalogue

These codes scope Company Membership API responses. They do not establish a project-wide error-envelope convention.

| Code | HTTP | Constant | Safe result |
|---|---:|---|---|
| `40000` | 400 | `BAD_REQUEST` | Request shape or field validation failed. |
| `40012` | 400 | `COMPANY_NOT_REJECTED` | Resubmission is not allowed from the current verification state. |
| `40022` | 409 | `CONCURRENT_MODIFICATION` | Expected Company version is stale. |
| `40100` | 401 | `UNAUTHORIZED` | Authentication is missing or invalid. |
| `40302` | 403 | `NOT_COMPANY_MEMBER` | Caller has no active membership for a non-identifying current-context operation. |
| `40303` | 403 | `INSUFFICIENT_COMPANY_ROLE` | Active Company Role cannot perform the operation. |
| `40304` | 403 | `ACCOUNT_SUSPENDED` | User account is suspended. |
| `40400` | 404 | `COMPANY_NOT_FOUND` | Company is absent or not visible to the caller on an ID-scoped route. |
| `40410` | 404 | `INVITATION_UNAVAILABLE` | Secret is invalid, expired, revoked, declined, used by another actor, or for another email. |
| `40901` | 409 | `DUPLICATE_TAX_CODE` | Tax code already belongs to a Company; no other Company details are returned. |
| `40902` | 409 | `RESUBMISSION_REQUIRED` | Existing rejected Company must be corrected in place. |
| `40910` | 409 | `ACTIVE_COMPANY_MEMBERSHIP_EXISTS` | User is not eligible to create or join another Company; no Company identity is included. |
| `40911` | 409 | `PENDING_INVITATION_EXISTS` | A pending invitation already exists for this Company and normalized email. No secret or account state is returned. |
| `40912` | 409 | `OWNER_MEMBERSHIP_PROTECTED` | Owner cannot leave or be removed in this milestone. |
| `40913` | 409 | `COMPANY_NOT_INVITATION_ELIGIBLE` | Owner issue/resend requires a verified, non-suspended Company. |
| `40914` | 409 | `OWNERSHIP_ALREADY_ASSIGNED` | Legacy assignment cannot replace an existing Owner. |

For target resources not visible to the caller, use the same `404` response as for an absent resource. Never include an invitation secret, another Company's ID/name, recipient account existence, or unrelated membership facts in an error.

## 7. Persistence and migration contract

The implemented PostgreSQL schema uses four additive, immutable Flyway migrations: `V5__company_ownership.sql` adds ownership, membership, and append-only audit; `V6__reviewed_legacy_ownership.sql` adds reviewed case records and audit metadata; `V7__company_invitations.sql` adds invitation history and constraints; and `V8__membership_notifications.sql` adds accepted-period receipts, durable notification intent, and the recipient inbox. No existing migration is rewritten and no legacy Company is automatically assigned.

### 7.1 Tables and constraints

| Table / change | Required fields and invariants |
|---|---|
| `companies` | Add nullable `owner_user_id` FK to `users(id)`. Existing rows remain `NULL` and unassigned. New Companies set it in the creation transaction. A Company with a non-null owner reference is membership-managed; a null reference means legacy-unassigned and no member-facing management. `is_banned` suspends access without changing ownership. Once non-null, the owner reference cannot change in this milestone. Existing verification state, tax-code unique constraint, version, and moderation fields remain. |
| `company_memberships` | `id`, `company_id`, `user_id`, `role`, `status`, `joined_at`, `ended_at`, `ended_by_user_id`, `created_at`. FKs preserve Company/User history. Check role in `OWNER/ADMIN/RECRUITER`, status in `ACTIVE/LEFT/REMOVED`, and consistent active/inactive timestamps. Admin remains a reserved role with no new capability. |
| `company_invitations` | `id`, `company_id`, normalized recipient email, `role`, `invited_by_user_id`, `status`, unique `secret_hash`, `issued_at`, `expires_at`, `accepted_by_user_id`, `created_at`, `updated_at`, and delivery status/last-attempt metadata. Check role is `RECRUITER`, states are from the invitation lifecycle, and `expires_at > issued_at`. |
| `company_membership_audit_events` | Append-only membership and ownership facts with actor, subject, Company, event type, invitation/membership IDs, optional review case reference, safe reason, and timestamp. A database trigger rejects `UPDATE`, `DELETE`, and `TRUNCATE`, matching the existing append-only audit protection. No secret, credential, Candidate data, or full evidence document. |
| `company_ownership_reviews` | Immutable approved review case with reviewer/applicant, evidence type, Company state/version snapshot, decision time, and optional actual evidence expiry. Evidence documents and private storage URLs remain outside the database. |
| `notification_outbox` | Durable, post-commit notification intent with unique event ID, recipient, one of the two allowed event types, minimal payload, attempt count, next-attempt time, and delivery time/error category. |
| `user_notifications` | Recipient-owned inbox item with unique `source_event_id`, recipient User FK, event type, Company identity, created/read time. A unique source event prevents duplicate inbox items on retry. |

Required uniqueness and ownership constraints:

```sql
CREATE UNIQUE INDEX uq_company_membership_one_active_per_user
    ON company_memberships (user_id) WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX uq_company_membership_one_active_owner_per_company
    ON company_memberships (company_id)
    WHERE status = 'ACTIVE' AND role = 'OWNER';

CREATE UNIQUE INDEX uq_company_invitation_one_pending_per_email
    ON company_invitations (company_id, recipient_email_normalized)
    WHERE status = 'PENDING';
```

The invitation secret hash is unique. Acceptance uses a conditional state update so only one transaction can consume a pending, unexpired invitation. `companies.owner_user_id` and membership rows are cross-row facts; a deferred constraint trigger shall verify at commit that a Company with a non-null owner has exactly one active `OWNER` Membership for that same User and that an unassigned Company has no active Owner Membership. The partial unique Owner index prevents a second active Owner. An Owner Membership cannot be ended or reassigned by this milestone. The constraint trigger, row locks, and application transaction together are required; application checks alone do not satisfy the invariant.

The migrations add database checks and a deferred constraint trigger for cross-row Owner consistency. These constraints, row locks, and the application transaction together enforce the ownership and one-active-membership rules.

### 7.2 Migration and compatibility behavior

1. Add the new nullable ownership reference and new tables/indexes without rewriting migration history.
2. Do not backfill ownership from Company ID, tax code, Company email, User email, email-verified state, or a guessed Company/User name match. Existing unowned Companies remain protected and retain all verification and suspension values.
3. A reviewed assignment is its own transaction after review approval. A User with an active membership, a Company that already has an Owner, conflicting evidence, or a changed Company state leaves the case unassigned; it never displaces another membership or Owner.
4. No changes are made to `users.role`, refresh-token claims, verification decision authority, existing audit rows, or historical Company records beyond the nullable new ownership column.
5. Membership removal and departure update the membership state, not delete the row. The old Owner cannot be ended until a separately approved transfer workflow exists.
6. Audit events are append-only, following the immutability expectation already used for platform audit records. Failure to write a required audit fact aborts the business transaction. Notification dispatch after commit does not.

## 8. Authorization and privacy contract

- Authenticate using the approved AUTH Bearer-token flow. Derive the actor from the security principal; ignore any request-supplied User ID, Company Role, owner ID, or inviter ID.
- Load active membership and Company state from current persistence for every protected Company request. Never trust a role cached in a token or client context.
- Owner-only operations: view/correct verification data; issue, list, resend, and revoke Recruiter invitations; list members; remove a Recruiter.
- Recruiter operations: view their current Company context, preview/accept/decline their own invitation, and leave their own membership. A Recruiter cannot administer Company Members or verification.
- Platform Administrator authority remains platform-scoped and does not arise from `OWNER`, `ADMIN`, or `RECRUITER` Company Roles. Existing verification decisions remain governed by the approved Company Verification specification.
- A Company ID, tax code, email address, notification ID, or membership ID is never authority. ID-scoped inaccessible Company or membership records return a non-enumerating not-found response.
- A current suspended User cannot use protected Company routes. A suspended Company cannot issue/resend invitations or accept them. Acceptance rechecks `is_banned` at commit.
- Company Membership never exposes Candidate profiles, CVs, or other Candidate private data.

## 9. Safe transition from anonymous registration and resubmission

The existing backend permitted anonymous Company registration, ID reads, tax-code reads, and rejected-request resubmission. Coordinated Company Verification and AUTH notes record the transition authorized by the user's GW-001 completion confirmation. The protected Company routes now follow this contract:

1. The client transition uses the existing public registration/login flow, then sends a Bearer token when creating or managing a Company. `POST /api/companies/verification-request` now creates the Company and Owner Membership atomically.
2. Owner status and correction use principal-scoped `/api/companies/me/verification` routes. Any retained ID-based resubmission alias still requires the current Owner; the ID is only a selector, never proof.
3. Anonymous Company registration, resubmission, Company-by-ID reads, and Company-by-tax-code reads are disabled. Protected Company reads return the same not-found result for inaccessible and absent records. Do not add a grace period that permits anonymous claims.
4. Deploy the authenticated client flow before or atomically with enabling the protected backend routes. Old anonymous clients receive the normal `401` response and cannot create or claim ownership.
5. All legacy Companies begin unassigned and remain invisible to member-facing management until the reviewed assignment procedure succeeds. Assignment does not alter verification status or bypass moderation.
6. No frontend implementation is included in GW-001 or this seven-ticket backend milestone.

## 10. Email delivery and invitation-secret protection

The backend uses a narrow `CompanyInvitationMailer` port and a Resend adapter implemented with JDK `HttpClient`; no mail dependency or provider SDK was added. The endpoint is fixed to Resend's HTTPS API, redirects are disabled, and network timeouts are configurable. The adapter can be replaced behind the same port if deployment changes provider.

### 10.1 Sender and message configuration

The deployment supplies `COMPANY_INVITATION_FROM`, `COMPANY_INVITATION_PROVIDER_CREDENTIAL`, `COMPANY_INVITATION_BASE_URL`, `COMPANY_INVITATION_ALLOWED_ORIGINS`, `COMPANY_INVITATION_CONNECT_TIMEOUT_SECONDS`, and `COMPANY_INVITATION_REQUEST_TIMEOUT_SECONDS` through environment-backed configuration or a secret store. The frontend base URL must be HTTPS and match its exact allow-listed origin. Never commit credentials. The email contains the Company name, Recruiter role, expiry in `Asia/Ho_Chi_Minh`, and one HTTPS link containing the random secret. It does not include Candidate data, another Company identity, or account-existence hints. See [`backend/.env.example`](../../../backend/.env.example).

### 10.2 Delivery and retry behavior

The minimal contract makes one provider attempt after the invitation transaction commits. Provider acceptance means only that the provider accepted the send request, not that the mailbox received or displayed it. On a provider rejection or timeout, the persisted invitation remains pending, its secret remains hash-only, and the API reports `FAILED` or `UNKNOWN`; the Owner explicitly retries with resend, which rotates the secret and expiry. There is no automatic email queue or password-reset integration in this milestone. A process crash between invitation commit and send is recovered by Owner resend. Provider errors are sanitized and never include a secret or complete invitation URL.

The adapter makes one delivery attempt after the invitation transaction commits. Provider acceptance does not mean that a mailbox received the email. Rejection or timeout leaves the invitation pending and reports `FAILED` or `UNKNOWN`; the Owner explicitly resends to rotate the secret and expiry. Tests use a controlled mail adapter and local HTTP stub. No production credential, verified sender, real mailbox, or live provider call was available during implementation, so delivery setup must be completed in the deployment environment.

## 11. Test strategy and verification

The implemented behavioral boundary is an HTTP request through the Spring Security filter chain, real authentication and authorization, real PostgreSQL persistence with Flyway migrations, and a controlled `CompanyInvitationMailer` test adapter that captures the delivered secret and can return provider success, failure, or timeout. `MockMvc` drives the HTTP boundary without opening a network socket; critical security/concurrency scenarios use real persistence and the security filter chain.

The test classpath activates the `test` profile and uses an environment-backed isolated database named `backend_test_db` or a per-ticket suffix. A test-only Spring startup initializer rejects other database URLs before Flyway or datasource initialization. Real HTTP/security and migration tests use PostgreSQL and deterministic lock/concurrency fixtures. Tests use an injected clock for expiry boundaries and controlled adapters/stubs for external mail; no public mailbox or provider is contacted. Setup, commands, and the PostgreSQL 18 container workflow are documented in [`docs/backend/company-membership-testing.md`](../../../docs/backend/company-membership-testing.md).

The integrated implementation and backend package passed 131 tests against PostgreSQL 18.3; each ticket also ran its applicable checks before merge. No test framework or service dependency was added.

## 12. Review register at implementation start

The named human approver is **Thinh**. The table below preserves the requested review status when this document was published. The user's subsequent GW-001 completion confirmation authorized dependent implementation; neither that confirmation nor an agent's work is represented here as a separate approval from Thinh.

| Review | Decision requiring explicit approval | Status |
|---|---|---|
| Requirements | MVP behavior, seven delivery boundaries, Company verification gate, roles, history, and out-of-scope list | Pending Thinh |
| API and compatibility | Authenticated creation/resubmission, scoped routes, current-user contract, error codes, privacy behavior, anonymous-client cutover, and whether this feature uses the AUTH or generic app-convention error envelope | Pending Thinh |
| Schema and migration | Nullable `owner_user_id`, membership/invitation/audit/outbox/inbox tables, partial indexes, deferred owner constraint, and additive V5 migration | Pending Thinh |
| Authorization and concurrency | Current persisted membership, lock/commit boundary, actor scopes, suspended Company behavior, and race outcomes | Pending Thinh |
| Legacy ownership | Reviewer/operator separation, evidence sufficiency, conflict handling, case reference, evidence retention, and audited assignment procedure | Pending Thinh |
| Email integration | Provider and dependency, sender/domain, configuration, secret format/hash, message content, timeouts, failure reporting, and resend recovery | Pending Thinh |
| Notification contract | Two event types, recipient rules, inbox fields, transactional outbox, retry schedule, alerting, and replay | Pending Thinh |
| Test strategy | HTTP/Security + real PostgreSQL + controlled mail adapter boundary, concurrency tests, and approval of any later test infrastructure | Pending Thinh |

The current approved Company Verification and AUTH specifications retain their approval metadata and include coordination notes for the protected-route transition and Owner-scoped behavior. This implementation does not change their platform verification or authentication authority.

## 13. Verification scenarios for downstream tickets

1. Authenticated creation succeeds for an eligible User; unauthenticated and already-member creation fail without partial Company or membership rows.
2. Concurrent Company creation preserves one active membership and one Owner. Current-user context is null for no membership and reflects current persisted Owner or Recruiter context for active membership.
3. Owner can view pending/rejected status and feedback and resubmit only a rejected Company with its expected version. Recruiter, unrelated User, inactive member, suspended User, and anonymous requests cannot read or change it.
4. Unassigned legacy Companies remain inaccessible. Identifier-only claim attempts fail. Reviewed assignment requires acceptable evidence, separate reviewer/operator, no conflicting active membership or Owner, and an audit record.
5. Invitation issue and resend work only for the current Owner of a verified, non-suspended Company. Pending, rejected, and suspended Companies cannot issue or resend. Duplicate pending invitations and concurrent issue races leave at most one row.
6. Expiry is rejected at `now >= expires_at`; the seven-calendar-day result is correct in `Asia/Ho_Chi_Minh`. Resend makes the previous secret unavailable and changes inviter/expiry.
7. A real invitation email can be captured by the controlled adapter. Provider failure, timeout, or process-recovery resend does not expose the secret or claim successful delivery.
8. Existing and newly registered recipients must authenticate with the invited email and secret. Wrong email, `emailVerified` without secret, missing secret, decline, expiry, revoke, consumed invite, and non-verified/suspended Company never activate membership.
9. Acceptance commits one Recruiter membership and one Owner notification intent. Concurrent invitations to the same User produce at most one active Company Membership. A retry after uncertain success has no duplicate effects.
10. Owner removal and Recruiter departure preserve membership history and notify the required recipient. Owner leave/removal fails. The next protected read/write fails after revocation despite a still-valid JWT.
11. Revocation that commits before a protected mutation prevents all of that mutation's effects. A membership removed from one Company allows later eligible creation or acceptance elsewhere.
12. A removed Recruiter can read only their own removal notification; another User cannot access it. Notification dispatch failure leaves membership and audit facts committed and recovers idempotently.
13. Membership alone cannot read Candidate data, grant Admin authority, reveal unrelated Company existence, or make an old notification link bypass current authorization.

## 14. Approval and implementation status

GW-001 completion was explicitly confirmed by the user on 2026-10-08. Tickets GW-002 through GW-007 are implemented on `feat/company-membership-mvp` from `77ea324`; the original pending-review table records publication history and does not represent an outstanding implementation blocker. Coordinated AUTH/Company Verification documentation records the protected-route transition. Resend provider configuration is implemented; deployment must supply its credential, verified sender, HTTPS frontend URL, and allowed origin.

## 15. Draft amendment: bind departure to a membership period

| Field | Value |
|---|---|
| Amendment ID | GW-SR-REC-02-AMEND-01 |
| Status | Draft — pending Thinh approval |
| Prepared | 2026-10-08 |
| Review branch | `feat/company-membership-review-fixes` |
| Affected contracts | Section 6.1 current-user context; Section 6.5 departure |

This proposed amendment addresses a reproduced stale-operation bug: a Recruiter leaves period A,
joins period B, then a delayed retry of the original bodyless departure ends B. That behavior conflicts
with retained periods in Sections 2.2 and 4.5 and GW-007's stale-operation acceptance criterion. The review branch prepared this proposal for review; the current implementation retains the approved bodyless
API until human approval. Section 6.1, Section 6.5, the coordinated AUTH example and the approved integration
branch retain the original contract until human approval.

### 15.1 Exact API delta

| Contract | Current approved contract | Proposed amendment |
|---|---|---|
| `GET /api/auth/me` active membership | `{ companyId, companyName, companyRole, joinedAt }` | Add `membershipId`, the authenticated User's current membership-period ID. No JWT changes. |
| `POST /api/companies/me/membership/leave` request | No membership-period selector; departure targets current membership. | Require JSON `{ "expectedMembershipId": 81 }`; ID must be positive and owned by the authenticated actor. |
| Same-actor departure retry | No explicit historical receipt contract. | HTTP 200 with the same original `{ membershipId, status: "LEFT", endedAt }`; no new audit, notification or membership write. |
| Old period after another admission | A bodyless retry can target a later period. | Request applies only to the supplied period. An own `LEFT` period returns its historical receipt; a removed, absent or another actor's period returns the same protected 404. A later period is unchanged. |

Proposed request:

```json
{ "expectedMembershipId": 81 }
```

Proposed current-user context (existing envelope and other User fields preserved):

```json
{
  "activeMembership": {
    "membershipId": 81,
    "companyId": 10,
    "companyName": "Công ty TNHH Giải Pháp Alpha",
    "companyRole": "RECRUITER",
    "joinedAt": "2026-10-08T03:00:00Z"
  }
}
```

Proposed first departure and repeated receipt:

```json
{ "membershipId": 81, "status": "LEFT", "endedAt": "2026-10-08T04:00:00Z" }
```

### 15.2 Authorization, serialization and retry behavior

- Authenticate and recheck the actor's active User state on every request, including historical retries.
- Validate the required positive `expectedMembershipId`; omitted/bodyless/invalid input returns the
  existing `40000 BAD_REQUEST` envelope. This is an intentional client request compatibility change.
- Resolve only a scalar Company ID for that actor's requested period, then lock actor, Company and
  exact membership period in the established order. Recheck actor ownership, period status/role and
  Company suspension under those locks. The selector grants no authority by itself.
- Only an active own Recruiter period can transition to `LEFT`. Existing Owner protection (`40912`)
  and reserved Admin-role denial (`40303`) continue to apply. End time, ending actor and one required
  departure audit fact commit atomically; departure creates no notification intent.
- An already `LEFT` period ended by that same actor returns its original receipt before current
  Company eligibility checks. This historical receipt creates no Company access and remains available
  after another admission or suspension of the old Company, provided the User is active.
- A `REMOVED` period, missing period or another User's period has the same existing protected
  `40400 COMPANY_NOT_FOUND` result. Concurrent removal winning first therefore cannot be transformed
  into departure, and concurrent repeated departure returns one unchanged historical receipt.
- Existing membership rows retain the receipt and attribution. No migration, new token claim,
  notification type, dependency or retention rule is proposed.

### 15.3 Client transition and approval gate

Clients fetch `activeMembership.membershipId` from `/api/auth/me` immediately before preparing a
new departure and retain that ID with the request for retries. They must reuse that original ID after
an uncertain response, including after a later admission. An old bodyless request receives HTTP 400;
a client must explicitly prepare a new request with the current period ID to leave that new period.
After a receipt, clients refetch `/api/auth/me` for current Company authority; a historical receipt
describes only its original period. The additive context field can be ignored by older clients, but
the required request body needs a coordinated client update before deployment.

Thinh must approve this API amendment under the root Agent Guide's breaking-API and approved-contract
boundaries before the review branch is merged or deployed. The approved integration remains available
for comparison. Approval applies specifically to the required selector, additive context field,
historical retry result and bodyless-client transition above; agents do not declare that approval.

### 15.4 Review evidence required

Real HTTP/security/PostgreSQL tests must cover an original departure retried after re-admission or
joining another Company; unchanged later active context; own original receipt and one audit fact;
concurrent duplicate departure; wrong-actor/absent/removed period privacy; required-input validation;
Owner/Admin protection; current User suspension; Company suspension before first departure; and
historical receipt after old-Company suspension. The complete backend test/package checks apply.
