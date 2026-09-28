# Company Membership — Software Requirements Specification

| Field | Value |
|---|---|
| **Specification ID** | GW-SR-COMPANY-MEMBERSHIP |
| **Project** | GuardWork |
| **Module** | Company Membership |
| **File** | `company-membership-spec.md` |
| **Version** | 0.1.0 |
| **Date** | 2026-09-27 |
| **Last Updated** | 2026-09-27 |
| **Owner** | Thinh |
| **Approver** | Thinh |
| **Status** | Draft |

## 1. Purpose and scope

This specification defines how a User gains, uses, changes, and loses authority to act for one Company. It establishes the Company Workspace and the fixed Owner, Admin, and Recruiter Company Roles.

It covers Company creation authority, Company Invitations, Company Memberships, role administration, ownership transfer constraints, Company Workspace access, and immediate access revocation.

It does not select an authentication technology, authorization framework, physical schema, API envelope, email provider, Company verification workflow, billing workflow, or account-deletion workflow.

## 2. Conceptual model

| Concept | Responsibility |
|---|---|
| User | A person who can use a personal Candidate workspace and, when authorized, one Company Workspace |
| Company | The employer on whose behalf Company Members recruit |
| Company Invitation | A revocable, expiring invitation for one email address, Company, and Company Role |
| Company Membership | The retained relationship between one User and one Company |
| Company Role | One of `OWNER`, `ADMIN`, or `RECRUITER` |
| Company Workspace | The product area in which a User acts for the Company |

The conceptual User–Company association supports retained inactive memberships. Current business behavior permits at most one active Company Membership per User.

### 2.1 Company Invitation lifecycle

```text
PENDING -> ACCEPTED
        -> DECLINED
        -> EXPIRED
        -> REVOKED
```

An invitation is not a Company Membership. Accepting a valid invitation creates or activates the membership and consumes the invitation.

### 2.2 Company Membership lifecycle

```text
ACTIVE -> LEFT
       -> REMOVED
```

`LEFT` and `REMOVED` memberships are inactive and retained for historical attribution. User suspension and Company suspension restrict access externally; they are not membership states.

## 3. Functional requirements

### 3.1 Company creation and workspace access

| ID | Requirement |
|---|---|
| GW-CM-001 | A User without an active Company Membership may create a Company and become its Owner. |
| GW-CM-002 | A User with an active Company Membership shall not create another Company. |
| GW-CM-003 | An authenticated User may switch between their personal Candidate workspace and their Company Workspace. |
| GW-CM-004 | GuardWork shall not display the Company Workspace after the User's Company Membership becomes inactive. |
| GW-CM-005 | A Company Member may continue using Candidate capabilities, but shall not apply to a Job Posting belonging to their own Company. |
| GW-CM-006 | Company Membership shall not expose a User's Candidate data to other Company Members. |

### 3.2 Invitations

| ID | Requirement |
|---|---|
| GW-CM-010 | Owner may invite Users as Admin or Recruiter. |
| GW-CM-011 | Admin may invite Users as Recruiter only. |
| GW-CM-012 | Recruiter shall not invite or administer Company Members. |
| GW-CM-013 | A Company Invitation shall identify one Company, one invited email address, one Company Role, an expiry, and its inviter. |
| GW-CM-014 | GuardWork may send an invitation to an email address that has no existing User. |
| GW-CM-015 | A recipient shall authenticate or create a User with the invited email address before acceptance. |
| GW-CM-016 | Acceptance shall require explicit confirmation after GuardWork displays the Company and proposed Company Role. |
| GW-CM-017 | An expired, revoked, declined, or consumed invitation shall not create a Company Membership. |
| GW-CM-018 | A User with an active Company Membership may receive another invitation but shall not accept it until the active membership ends. |
| GW-CM-019 | A second-Company conflict response shall not disclose private membership details to the inviting Company. |
| GW-CM-019A | A Company Invitation shall expire seven calendar days after issuance. |
| GW-CM-019B | GuardWork shall permit at most one pending Company Invitation for one Company and email address. |
| GW-CM-019C | Resending a pending Company Invitation shall invalidate its earlier token, record the new inviter and expiry, and may replace its proposed Company Role. |

### 3.3 Roles and ownership

| ID | Requirement |
|---|---|
| GW-CM-020 | Every active Company shall have exactly one active Owner. |
| GW-CM-021 | Owner may change an active Company Member between Admin and Recruiter. |
| GW-CM-022 | Admin may manage Recruiters but shall not create, remove, promote, or demote an Owner or Admin. |
| GW-CM-023 | Owner shall transfer ownership through a distinct confirmed operation, not ordinary role editing. |
| GW-CM-024 | Ownership transfer shall atomically make the selected active Company Member the Owner and assign the prior Owner an explicitly selected non-owner role. |
| GW-CM-025 | Owner shall not leave, be removed, or complete account deletion while still owning the Company. |
| GW-CM-026 | Recruiter shall not manage verification, ownership, Company billing, or Company Memberships. |

### 3.4 Leaving, removal, and revocation

| ID | Requirement |
|---|---|
| GW-CM-030 | A non-owner Company Member may leave the Company. |
| GW-CM-031 | An authorized Company Member may remove a lower-authority Company Member as permitted by GW-CM-020 through GW-CM-026. |
| GW-CM-032 | Leaving or removal shall revoke Company Workspace and private recruitment-data access immediately. |
| GW-CM-033 | Authorization shall use current membership, role, Company state, and relevant Job Assignment when a protected operation commits. |
| GW-CM-034 | An authorization change during an operation shall cause the operation to fail without a partial business change. |
| GW-CM-035 | Inactive memberships shall retain historical authorship of prior Job Posting, Application, Interview, Offer, note, and status actions. |
| GW-CM-036 | After one membership becomes inactive, its User may accept an invitation to or create another Company, subject to all other rules. |

### 3.5 Company suspension

| ID | Requirement |
|---|---|
| GW-CM-040 | A suspended Company shall not accept Company Invitations or perform recruitment actions. |
| GW-CM-041 | Company suspension shall block Company Members from newly accessing Candidate Recruitment Data while preserving records for the approved enforcement and retention processes. |
| GW-CM-042 | Candidate-facing suspension information shall follow the shared platform-enforcement specification. |
| GW-CM-043 | GuardWork shall create mandatory in-app notifications for Company Invitation acceptance, Company Membership removal, Company Role change, ownership transfer, and applicable security or enforcement events. |
| GW-CM-044 | Notification delivery failure shall not reverse an otherwise committed membership or ownership action. |

## 4. Business rules

| ID | Rule |
|---|---|
| GW-CM-BR-01 | A User may have at most one active Company Membership. |
| GW-CM-BR-02 | A Company has exactly one Owner. |
| GW-CM-BR-03 | Company Role is scoped to one Company Membership and is never a global User role. |
| GW-CM-BR-04 | Invitation acceptance is single-use and requires an exact invited-email match. |
| GW-CM-BR-05 | Knowing a Company or resource identifier grants no authority. |
| GW-CM-BR-06 | Candidate and Company contexts remain private from one another except through an explicit Application. |
| GW-CM-BR-07 | A Company and email address have at most one pending Company Invitation. |

## 5. Failure catalogue

Exact transport status and error-envelope fields depend on the shared API specification.

| Condition | Semantic result |
|---|---|
| Invitation expired, revoked, declined, or used | Invitation cannot be accepted |
| Authenticated email does not match | Invitation cannot be accepted |
| User already has an active Company Membership | Second membership cannot activate |
| Unauthorized role-management attempt | Operation denied without disclosing unrelated private data |
| Owner attempts to leave without transfer | Operation denied; ownership transfer required |
| Company or User is suspended | Protected Company operation denied under enforcement policy |
| Access changes before commit | Operation fails without partial change |

## 6. Verification scenarios

1. A new User accepts a valid Recruiter invitation and enters the Company Workspace.
2. A User with an active membership cannot accept another Company's invitation.
3. An Owner transfers ownership and then leaves without removing historical authorship.
4. An Admin can manage a Recruiter but cannot manage another Admin or the Owner.
5. A removed Recruiter's next private read and next write both fail.
6. A Company Member uses Candidate features but cannot apply to their own Company's Job Posting.
7. Concurrent membership activation attempts cannot produce two active memberships.
8. Company suspension blocks recruitment access without converting memberships to inactive states.
9. Notification delivery failure does not roll back a committed membership change.
10. Resending a pending invitation invalidates the earlier token and leaves one pending invitation.

## 7. Dependencies and unresolved contracts

- Platform authentication and session invalidation
- Platform API, pagination, and error-envelope conventions
- Company verification, suspension, appeal, and closure workflows
- Notification delivery
- Audit storage and retention
- Physical schema and uniqueness enforcement
