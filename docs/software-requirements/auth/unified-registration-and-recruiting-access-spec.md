# Unified Registration and Recruiting Access — Software Requirements Specification

| Field | Value |
|---|---|
| **Specification ID** | GW-SR-AUTH-03 |
| **Project** | GuardWork |
| **Module** | Registration, Candidate access, and recruiting eligibility |
| **File** | `unified-registration-and-recruiting-access-spec.md` |
| **Version** | 0.2.0 |
| **Date** | 2026-10-27 |
| **Last Updated** | 2026-10-30 |
| **Owner** | User |
| **Approver** | User |
| **Status** | Approved |

## 1. Purpose and scope

Use one User registration flow for Candidate and recruiting workspaces. Keep account email verification, Company verification, and Company-scoped authority separate. The [implementation contract](unified-registration-implementation-contract.md) defines the API, persistence, concurrency, and verification details.

## 2. Requirements

| ID | Requirement |
|---|---|
| UREG-001 | Candidate and recruiting experiences use one account and the same registration endpoint. No permanent Candidate or Recruiter account type is selected. Workspace switching grants no authority; Company roles remain membership-scoped. |
| UREG-002 | An active newly registered User may sign in and use Candidate profile, CV, and Application capabilities before email verification, subject to their applicable requirements. This change removes an onboarding restriction; it does not implement missing Candidate features. |
| UREG-003 | Account email verification is the personal prerequisite for recruiting, including Owners and invited Recruiters. Remove mandatory phone OTP and separate employer registration. Opening the recruiting workspace to receive setup/verification instructions is allowed before verification. |
| UREG-004 | Company setup is separate from account registration. Platform Staff approval remains required before Company members can publish jobs or handle Applications. Owners of pending/rejected Companies can inspect status and feedback and correct eligible submissions. |
| UREG-005 | Company dossier submission requires legal name, tax code (MST), business address, registration/license document, submitter job title, legal/authorized representative selection, and an accuracy/representation declaration. Review messages use the Owner's verified account email; no separate Company email is required. Logo, website and Company description are optional. Gender and phone OTP are not prerequisites. |
| UREG-006 | Complete initial dossier submission creates the pending Company and first Owner membership atomically. Opening Company setup neither establishes the Company nor reserves ownership. |
| UREG-007 | Preserve one active Company Membership per User across Owner and Recruiter roles. Candidate access coexists with membership. Existing Owner departure restrictions remain; an active member cannot establish or join another Company. |
| UREG-008 | Verified account email is required for private recruiting features, including reading, creating and editing job drafts. After email verification, authorized members may prepare drafts while Company approval is pending. Submission for job moderation and publication require Company approval. Verification instructions, Company setup/status/correction, and account recovery remain reachable under their respective rules. |
| UREG-009 | Require verified account email before initial Company dossier submission or Recruiter invitation acceptance. Invitation acceptance requires the authenticated User's verified email to match the invitation address; an invitation alone is not email proof. Unverified Users may open setup and verification instructions. |
| UREG-010 | Pending submitted dossiers cannot be edited. Rejected dossiers may be corrected and resubmitted for the same Company, retaining its immutable tax code and previous documents, declarations and review history. Block duplicate initial submissions for an existing tax code. Expose rejection feedback in Company settings and send the decision to the submitting Owner's verified account email. |
| UREG-011 | Apply the same recruiting email-verification requirement to every User. Recruiting eligibility requires active status and stored `emailVerified=true`. Candidate access requires an active account but not email verification. Preserve existing verification values and records. `/api/auth/me` exposes `emailVerified` and `recruitingEmailEligible`. |
| UREG-012 | Shared registration retains first name, last name, username, email and password, without Company information, phone number, gender or an account type. Create an active User immediately and allow sign-in. Newly registered Users remain email-unverified until genuine mailbox proof. |
| UREG-013 | Send a six-digit email-verification code only when the User explicitly requests one. Do not automatically send verification email during registration. Codes expire after 10 minutes, permit at most five failed attempts, resends require 60 seconds, and delivery failure grants no proof. Invitation links remain unchanged. See the implementation contract for exact behavior. |
| UREG-014 | Do not provide Save draft or autosave for initial Company setup. No unfinished Company information is persisted by the backend. Submit the complete dossier explicitly for Company verification; create Company and first Owner together as specified in UREG-006. The provisional-only `PUT /api/me/employer-onboarding/company` has no purpose in the replacement flow and is retired. Retain complete dossier submission and rejected-dossier correction. |
| UREG-015 | Retire the separate employer registration API and update its clients to use shared registration followed by Company setup. Remove obsolete employer-registration and phone-verification steps while retaining Company review, private documents, declarations and review history. Existing development accounts and Company records remain intact; uncompleted employer registrations do not automatically become Users. |

## 3. Related requirements

- [GW-SR-AUTH-01](authentication-authorization-spec.md): shared registration creates an active, email-unverified User. Account-email proof is requested separately.
- [GW-SR-AUTH-02](portal-routing-contract.md): Candidate and recruiting workspaces share the public portal. Workspace selection grants no authority.
- [GW-SR-REC-03](../rec/company-onboarding-verification-enhancement-spec.md) and [ADR 0005](../../architecture/decisions/0005-staged-employer-onboarding.md): the separate employer registration, phone proof, and restricted Candidate flow are replaced by this shared-registration flow. Existing evidence and Company review history remain retained.
- [GW-SR-REC-02](../rec/company-membership-mvp-spec.md): Company creation, Owner assignment, invitations, and membership eligibility retain their Company-scoped rules.
- [GW-SR-REC-01](../rec/company-verification-spec.md): Staff review and rejected-dossier correction remain required.
- [GW-78](../rec/job-publishing-and-assignment-mvp-spec.md): private job operations require verified account email; phone proof is not required. Company verification, suspension, membership, role, and assignment rules remain applicable.

## 4. Product flow

1. Register through the shared endpoint with personal account fields only. Create an active User immediately; new Users start email-unverified and can sign in.
2. Use Candidate features and switch to the Recruiting Workspace without changing account type or gaining Company authority.
3. When the User explicitly requests email verification, send a code for manual entry. Do not send automatically at registration. All unverified Users must complete proof before private recruiting operations, initial Company submission, or invitation acceptance.
4. A prospective Owner fills out the complete Company dossier. No Save draft or autosave request exists and the backend stores no unfinished Company form.
5. Complete submission atomically creates a pending Company and its first Owner. Company setup does not require phone OTP or a separate employer registration.
6. Platform Staff review the retained dossier. The Owner can inspect status and feedback. Rejection permits correction/resubmission of the same Company while preserving its tax code and earlier review evidence.
7. Email-verified authorized members may prepare job drafts while Company approval is pending. Job moderation submission/publication and Application handling require Company approval and the applicable role/assignment permissions.
8. Invited Recruiters join through the existing Company invitation workflow, with verified matching account email for all Users. Preserve one active Company Membership per User.
9. Apply the same recruiting email gate to every User, preserving existing stored verification values. Existing unverified Users must complete proof. Do not delete existing accounts or Company records.

## 5. Contracts and deployment

The [implementation contract](unified-registration-implementation-contract.md) specifies email lifecycle, complete multipart dossier submission, retired routes, recruiting eligibility, schema, deployment, and test boundaries. Account-email changes are outside scope; verification must not carry over to another address.

Migration `V2026.10.28.84__unified_registration_email_proof.sql` creates account-email proof storage without a cohort marker. Existing verification values and account/Company records remain intact. Disposable databases with an earlier checksum for migration 84 or the removed migration 85 require rebuilding before startup. Stop older account/Company writers and deploy compatible backend and clients together.

## 6. Implementation and verification

The backend provides shared registration, requested account-email proof, atomic complete Company dossiers, rejected correction, verified-email invitation acceptance, recruiting access checks, and HTTP 410 responses for retired onboarding routes. Owner status projections include `companySuspended` for authorized suspension/status reads. Incomplete employer registrations are not automatically promoted.

Verify HTTP contracts, service eligibility, migration behavior, atomic rollback, and concurrency using the existing PostgreSQL test setup. Run the backend test and package commands documented in the README. Browser verification requires the separate client source. No dependency, infrastructure, or retention-policy change is included.

See [registration](../../backend/unified-registration.md), [Company setup](../../backend/company-setup.md), [invitations](../../backend/verified-email-invitations.md), and [retired onboarding](../../backend/employer-onboarding.md) for operational details.

The User approved the account-only code-entry amendment on 2026-10-30; see contract section 9 for migration, client and invitation compatibility.
