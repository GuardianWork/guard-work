# Unified Registration and Recruiting Access — Software Requirements Specification

| Field | Value |
|---|---|
| **Specification ID** | GW-SR-AUTH-03 |
| **Project** | GuardWork |
| **Module** | Registration, Candidate access, and recruiting eligibility |
| **File** | `unified-registration-and-recruiting-access-spec.md` |
| **Version** | 0.1.0 |
| **Date** | 2026-10-27 |
| **Last Updated** | 2026-10-28 |
| **Owner** | User (name requested; pending response) |
| **Approver** | User (name requested; pending response) |
| **Status** | Approved — explicit contract approval recorded on 2026-10-28 |

## 1. Purpose and scope

Replace separate employer registration and mandatory personal onboarding with one User registration flow and freely accessible Candidate and recruiting workspaces. Preserve the distinction between account email proof, Company verification, and Company-scoped authority.

This document records the approved product direction and the [approved implementation contract](unified-registration-implementation-contract.md). The user explicitly approved the concrete contract on 2026-10-28. Approval is attributed to the User; their name has been requested and remains pending. This document defines behavior; runtime implementation is tracked separately.

## 2. Confirmed product decisions

On 2026-10-27, the user answered “Yes. agree all of those” to interview Q1–Q4:

| ID | Confirmed direction |
|---|---|
| UREG-001 | Candidate and recruiting experiences use one account and the same registration endpoint. No permanent Candidate or Recruiter account type is selected. Workspace switching grants no authority; Company roles remain membership-scoped. |
| UREG-002 | An active newly registered User may sign in and use Candidate profile, CV, and Application capabilities before email verification, subject to their applicable requirements. This change removes an onboarding restriction; it does not implement missing Candidate features. |
| UREG-003 | Account email verification is the personal prerequisite for recruiting, including Owners and invited Recruiters. Remove mandatory phone OTP and separate employer registration. Opening the recruiting workspace to receive setup/verification instructions is allowed before verification. |
| UREG-004 | Company setup is separate from account registration. Platform Staff approval remains required before Company members can publish jobs or handle Applications. Owners of pending/rejected Companies can inspect status and feedback and correct eligible submissions. |

The user subsequently answered “Yes” to Q5–Q8:

| ID | Confirmed direction |
|---|---|
| UREG-005 | Company dossier submission requires legal name, tax code (MST), business address, registration/license document, submitter job title, legal/authorized representative selection, and an accuracy/representation declaration. Review messages use the Owner's verified account email; no separate Company email is required. Logo, website and Company description are optional. Gender and phone OTP are not prerequisites. |
| UREG-006 | Complete initial dossier submission creates the pending Company and first Owner membership atomically. Opening Company setup neither establishes the Company nor reserves ownership. |
| UREG-007 | Preserve one active Company Membership per User across Owner and Recruiter roles. Candidate access coexists with membership. Existing Owner departure restrictions remain; an active member cannot establish or join another Company. |
| UREG-008 | Replace GW-78's personal-email bypass: verified account email is required for private recruiting features, including reading, creating and editing job drafts. After email verification, authorized members may prepare drafts while Company approval is pending. Submission for job moderation and publication require Company approval. Verification instructions, Company setup/status/correction, and account recovery remain reachable under their respective rules. |

For Q9–Q12, the user clarified that the system has not been deployed, selected new-User-only enforcement for Q11, and agreed with the other recommendations:

| ID | Confirmed direction |
|---|---|
| UREG-009 | Require verified account email before initial Company dossier submission or Recruiter invitation acceptance. Invitation acceptance requires the authenticated User's verified email to match the invitation address; an invitation alone is not email proof. Unverified Users may open setup and verification instructions. |
| UREG-010 | Pending submitted dossiers cannot be edited. Rejected dossiers may be corrected and resubmitted for the same Company, retaining its immutable tax code and previous documents, declarations and review history. Block duplicate initial submissions for an existing tax code. Expose rejection feedback in Company settings and send the decision to the submitting Owner's verified account email. |
| UREG-011 | Apply the new personal email-verification requirement to newly registered Users only. The system is not deployed. Do not reclassify existing automatic verification flags or require existing Users to reverify as part of this change. Existing accounts remain unchanged; no development-data reset was requested. |
| UREG-012 | Shared registration retains first name, last name, username, email and password, without Company information, phone number, gender or an account type. Create an active User immediately and allow sign-in. Newly registered Users remain email-unverified until genuine mailbox proof. |

UREG-011 scopes the new email gates in UREG-003, UREG-008 and UREG-009; they do not retroactively impose proof on existing accounts. Existing active-User, membership, Company, role and assignment requirements remain applicable.

In the next round, the user changed Q13 to request-only email delivery, asked for clarification of Q14, and accepted Q15:

| ID | Confirmed direction |
|---|---|
| UREG-013 | Send an email-verification link only when the User explicitly requests one. Do not automatically send verification email during registration. Link expiry, resend and delivery-failure recommendations remain to be confirmed separately. |
| UREG-015 | Retire the separate employer registration API and update its clients to use shared registration followed by Company setup. Remove obsolete employer-registration and phone-verification steps while retaining Company review, private documents, declarations and review history. Existing development accounts and Company records remain intact; uncompleted employer registrations do not automatically become Users. |

After clarification that saving requires a client request, the user accepted Q14: no Save draft or autosave for Company setup. The client sends Company information only when the User selects Submit for verification; the backend saves no unfinished Company form.

| ID | Confirmed direction |
|---|---|
| UREG-014 | Do not provide Save draft or autosave for initial Company setup. No unfinished Company information is persisted by the backend. Submit the complete dossier explicitly for Company verification; create Company and first Owner together as specified in UREG-006. The provisional-only `PUT /api/me/employer-onboarding/company` has no purpose in the replacement flow and is included in the proposed legacy-route retirement. Retain complete dossier submission and rejected-dossier correction. |

## 3. Existing requirements requiring coordination

- [GW-SR-AUTH-01](authentication-authorization-spec.md): ordinary registration currently automatically treats email as verified. The replacement must distinguish actual proof from legacy default values.
- [GW-SR-REC-03](../rec/company-onboarding-verification-enhancement-spec.md) and [ADR 0005](../../architecture/decisions/0005-staged-employer-onboarding.md): separate pending employer registrations, phone proof, and restricted Candidate access conflict with UREG-001–003. Preserve historical approval evidence; prepare explicit supersession after the interview.
- [GW-SR-REC-02](../rec/company-membership-mvp-spec.md): coordinate Company creation, Owner assignment, invitations, and membership eligibility without granting authority through workspace selection.
- [GW-SR-REC-01](../rec/company-verification-spec.md): preserve Staff Company review; confirm submission information and correction rules.
- [GW-78, approved onboarding separation dated 2026-10-27](../rec/job-publishing-and-assignment-mvp-spec.md#approved-onboarding-separation--2026-10-27): known Company job operations explicitly bypass personal email/phone proof. In Q8, the user explicitly agreed to replace the email bypass with UREG-008 while retaining removal of the phone gate. Synchronize that requirement and its implementation contract after consolidated design confirmation.
- [GW-SR-AUTH-02](portal-routing-contract.md): existing authentication portal contexts are public/Staff. Candidate/recruiting workspace terminology must not imply a new authentication boundary.

## 4. Consolidated product flow

1. Register through the shared endpoint with personal account fields only. Create an active User immediately; new Users start email-unverified and can sign in.
2. Use Candidate features and switch to the Recruiting Workspace without changing account type or gaining Company authority.
3. When the User explicitly requests email verification, send a link. Do not send automatically at registration. New Users must complete proof before private recruiting operations, initial Company submission, or invitation acceptance.
4. A prospective Owner fills out the complete Company dossier. No Save draft or autosave request exists and the backend stores no unfinished Company form.
5. Complete submission atomically creates a pending Company and its first Owner. Company setup does not require phone OTP or a separate employer registration.
6. Platform Staff review the retained dossier. The Owner can inspect status and feedback. Rejection permits correction/resubmission of the same Company while preserving its tax code and earlier review evidence.
7. Email-verified authorized members may prepare job drafts while Company approval is pending. Job moderation submission/publication and Application handling require Company approval and the applicable role/assignment permissions.
8. Invited Recruiters join through the existing Company invitation workflow, with verified matching account email for new Users. Preserve one active Company Membership per User.
9. Apply new personal email gates only to newly registered Users; do not reinterpret existing flags or force existing Users to reverify. Do not delete existing accounts or Company records.

After reviewing the consolidated product summary, the user stated “LGTM” and invoked `to-tickets`. This confirms the consolidated product direction and authorizes preparation of implementation tickets. It does not settle the outstanding technical contracts, approve a ticket breakdown not yet presented, or request implementation. Named human ownership and approver metadata remain to be supplied; agents do not assign those identities.

On 2026-10-28, after approving local Markdown tracker configuration, the user stated “Yes i approve the proposed tickets”. The six approved tickets are published in the [local tracker](../../../.scratch/unified-registration-and-recruiting-access/spec.md), with blocking edges matching the reviewed breakdown. Ticket 01 resolves the outstanding concrete contracts and approval metadata; publication does not start implementation or authorize unapproved schema/dependency changes, commits or pushes.

## 5. Contract decisions and rollout

The [implementation contract](unified-registration-implementation-contract.md) resolves email expiry/resend/failure behavior, complete multipart dossier submission, legacy-route retirement, persisted rollout boundaries, additive migration, deployment/rollback and verification seams. The user approved those choices on 2026-10-28. Account-email changes remain outside scope and must not inherit proof for another address. Human approval is attributed to the User pending their requested name.

## 6. Contracts, data, and verification

Endpoint payloads, errors, schema/migration design, rollout and verification cases are specified in the approved implementation contract. Preserve current privacy controls, private Company evidence, retained review history, account suspension and membership/role/assignment checks. No dependency is added. Implementation and verification are required by tickets 02–06; approval does not claim they are already complete.

## Explicit concrete contract approval — 2026-10-28

Ticket 01 produced the [implementation contract](unified-registration-implementation-contract.md) for human review. After presentation of the document and approval choices, the user replied “Approve”. This explicitly approves its email lifecycle, persisted rollout marker and additive migration, complete multipart dossier, breaking retirement behavior, operation matrix and HTTP/service/PostgreSQL verification seams. The User is the human owner/approver attribution pending their requested name. Historical planning statements above are retained as decision history, superseded by this later approval.

## Backend implementation — 2026-10-28

The approved backend scope is implemented: shared registration and requested account-email proof, atomic complete Company dossiers and retained rejected correction, verified-email invitation acceptance, persisted private-recruiting gates, and HTTP 410 retirement of the obsolete onboarding routes. Own Company/status projections add `companySuspended` for the approved suspended-Owner read exception. Existing Users, memberships, Companies, jobs and private review evidence remain intact; incomplete employer registrations are not automatically promoted.

Migration `V2026.10.28.84__unified_registration_email_proof.sql` adds the persisted new-User cohort and hashed account-email proofs without reinterpreting existing flags. Stop older account/Company writers before coordinated backend/client deployment; no mixed-fleet or destructive rollback is authorized. See [registration](../../backend/unified-registration.md), [Company setup](../../backend/company-setup.md), [invitations](../../backend/verified-email-invitations.md), and [retirement and decision delivery](../../backend/employer-onboarding.md) for the implemented APIs, configuration and recovery.

The complete backend suite passed 308 tests with no failures, errors or skips after the replacement journeys and compatibility fixtures were verified. Packaging and final integration review are recorded by the integration handoff. Browser implementation/checks require the separate client source; no frontend source exists in this checkout, and the approved contract limits this implementation to backend APIs and HTTP examples. No dependency, new infrastructure, data reset or retention policy was introduced.
