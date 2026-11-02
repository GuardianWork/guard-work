# [GW-SR-REC-03] Company Onboarding and Verification Enhancement

> Unified registration coordination (2026-10-28): the user explicitly approved
> [GW-SR-AUTH-03 and its implementation contract](../auth/unified-registration-implementation-contract.md) with “Approve”.
> Separate employer registration, restricted Candidate access, mandatory phone proof and provisional Company updates are superseded by shared active-User registration, explicit requested account-email proof for new Users and complete atomic multipart dossier submission. Legacy routes and JSON dossier contracts retire with documented 410 responses after replacement callers migrate. Existing Users remain exempt from new email proof; incomplete employer registrations are neither promoted nor deleted. Company review, private documents, declarations, immutable attempts and decision delivery remain. This later scoped approval supersedes conflicting earlier wording below; historical approval evidence remains intact.

| Field | Value |
|---|---|
| **Specification ID** | GW-SR-REC-03 |
| **Project** | GuardWork |
| **Module** | Employer registration, email verification, and Company verification |
| **File** | `company-onboarding-verification-enhancement-spec.md` |
| **Version** | 0.2.1 |
| **Date** | 2026-10-18 |
| **Last Updated** | 2026-10-27 |
| **Owner** | VyTrg (proposed, following existing Company Verification ownership) |
| **Approver** | VyTrg (proposed human reviewer; formal review-register reconciliation pending) |
| **Status** | Draft |

## 1. Decision record and scope

The user previously approved the 2026-10-18 journey and its concrete implementation contract. On 2026-10-27, the user explicitly confirmed interview decisions Q1–Q5 and Q7–Q9: immediate account creation with restricted sign-in, email-only verification, unique declared phone/email, invitation-based Recruiter activation, atomic Owner assignment at initial dossier submission, and removal of SMS gates for already-enrolled accounts. Q6 was explicitly left undecided. This records session authorization; agents do not set the lifecycle to Approved or independently identify the human reviewer.

The user subsequently confirmed Q10–Q17: backend-only delivery, a shared canonical account phone, coordinated client changes, persisted accounts on email-delivery failure, retirement of old pending-proof/SMS routes with 410, blocking migration on unresolved phone conflicts, Candidate contact edits against the shared source, and authenticated cross-device email proof without continuation tokens. The [current gap and implementation plan](company-onboarding-email-only-implementation-plan.md) separates these confirmations from the proposed exact schema/API.

This revision supersedes the earlier email-before-account-creation, SMS-before-dossier, verified-phone-only uniqueness, and acceptance-before-email-proof directions. The user subsequently approved the concrete REC-03 plan sections 3–5; the backend now implements this journey, including authenticated email binding, scoped eligibility and retired pending-proof routes. See the [implementation contract](company-onboarding-implementation-contract.md), [implementation inventory](../../backend/employer-onboarding.md), and [ADR 0007](../../architecture/decisions/0007-email-only-employer-onboarding.md).

In scope: employer registration, restricted workspace, email proof, invitation acceptance prerequisites, new-Company legal submission, Staff moderation, rejection correction, and affected-account transition requirements. The platform role remains USER; Employer Applicant is a journey term, not a new platform or Company role.

Out of scope: new frontend delivery; Candidate-to-employer conversion; post-approval Candidate capability expansion; ownership transfer; and selecting new shared authentication, authorization, notification, frontend, or infrastructure architecture. Production retention, dispute handling and rollout details remain subject to review.

## 2. Journey and access

```text
Register all required fields with unique email and normalized phone
    -> Create official account, email unverified; no Company or membership
    -> Sign in to restricted employer workspace
    -> Verify email using account-bound, single-use email proof
         -> Accept a valid invitation for a verified Company
              -> ACTIVE RECRUITER membership in the invited Company
         -> Prepare and submit a complete new-Company dossier
              -> Atomically create Company PENDING + ACTIVE OWNER + submission attempt
              -> Staff checks dossier: CHECKED
                   -> VERIFIED: recruitment operations eligible within existing permissions
                   -> REJECTED: Owner reads feedback, corrects same Company and resubmits
```

The branches describe an applicant without an existing active Company membership. Q6, transition from an already-ACTIVE Recruiter to a new Company's Owner, remains undecided; this revision defines no path for that case and does not authorize changing existing membership constraints.

Entering a Company name during registration does not join, claim, create, or verify a Company. A valid invitation determines the Company joined; typed Company information cannot substitute for invitation acceptance. Successful initial legal submission creates ownership immediately, before Staff approval. Owner membership and Company verification are independent.

| Stage | Permitted access | Locked actions |
|---|---|---|
| Registered, email unverified | Sign-in, workspace shell, email instructions, email confirmation/resend, logout, and necessary account recovery | Invitation acceptance, Company edits/document uploads/dossier submission, recruitment operations, and Candidate profile/CV/Application operations |
| Email verified, no membership | Employer onboarding, provisional Company editing, legal preparation/submission, own eligible invitation actions; job search/view where implemented | Recruitment operations and Candidate profile/CV/Application operations |
| Owner, Company PENDING | Own verification status and submitted information | Recruitment operations and invitation issue/resend |
| Owner, Company REJECTED | Own rejection feedback, legal correction and resubmission | Recruitment operations and invitation issue/resend |
| Email verified, active membership, Company VERIFIED | Relevant account/Company settings and operations allowed by current authorization | Operations outside the member's permissions |

UI controls shall reflect these gates; backend checks shall deny direct requests before side effects. Existing suspension, membership and operation-specific authorization still apply. Public discovery remains public; its availability is not evidence that job discovery is implemented. This enhancement does not grant new Candidate capabilities after approval.

## 3. Information requirements

### 3.1 Initial registration

| Information | Requirement |
|---|---|
| Full name | Required; preserve Vietnamese Unicode names verbatim. |
| Mobile number | Required Vietnamese mobile number; normalize local `0…` and `+84…` forms to one identity; unique across existing accounts, including email-unverified accounts. It is a declared contact number, not verified possession. |
| Company name | Required provisional information; grants no Company association or authority. |
| Gender | Required: Male, Female, Other, or Prefer not to disclose; no effect on review or permissions. |
| Work address | Required provisional information; editable after email verification. |
| Account email | Required, normalized under the existing email convention and unique across existing accounts, including email-unverified accounts; used for login, proof and decision email. |
| Password | Required; preserve existing applicable password-security requirements. |
| Password confirmation | Required and equal to password; never persisted. |

System-wide phone uniqueness includes Candidate account contact data, not only employer profiles. One canonical phone is shared by Candidate and employer contexts. Eligible Candidate profile updates may change or clear it; responses use normalized +84 values and existing access restrictions remain. Existing phone-less accounts remain valid; no new employer phone-change endpoint is selected. Conflicting/invalid old data blocks conversion pending reviewed correction. The physical schema, data inventory and coordinated migration are detailed as a technical proposal in the implementation plan; no silent contact winner or database reset is authorized.

No separate Company contact email is requested. Existing historical Company contact data remains compatible; verified account email is the decision destination for the enhanced journey.

### 3.2 Legal dossier

After email verification, the applicant may edit provisional Company name/address and prepare a dossier containing Company name, work address, tax code (MST), business registration/license document, submitter position, legal/authorized-representative selection, and explicit accuracy/representation declaration. No SMS or authorization-letter upload is required.

Retain private document bytes, each submission snapshot, declaration, actor, review decision/reason and timestamps under the existing retained-evidence contract. Retention periods and controlled disposal still require a separate reviewed policy. A declaration is an assertion of authority, not independent proof.

## 4. Functional requirements

Local IDs retain traceability to the earlier revision; changed entries are amended by the confirmed interview. Retired SMS IDs must not be interpreted as active requirements.

| ID | Requirement |
|---|---|
| REQ-REC-01 | Successful registration shall collect section 3.1 information and immediately create one official account without marking email or phone verified, creating a Company, or assigning a Company role. |
| REQ-REC-02 | Email proof shall be bound to the exact account, email and attempt. A declared or historical phone flag shall not constitute mailbox proof or a new phone-possession claim. |
| REQ-REC-03 | A registered email-unverified applicant shall be able to sign in to the restricted employer workspace in section 2. |
| REQ-REC-04 | The system shall enforce the section 2 access matrix in both UI and backend, including existing sessions and direct requests. |
| REQ-REC-05 | Retired: SMS OTP verification and the phone-proof prerequisite are removed from the target journey. |
| REQ-REC-06 | An email-verified applicant shall be able to edit provisional Company information and prepare the legal dossier without phone verification. |
| REQ-REC-07 | Dossier submission/correction shall require actual email proof, complete legal information, retained document and explicit declaration acceptance; SMS shall not be required. |
| REQ-REC-08 | Successful initial submission shall atomically create the Company PENDING, the submitter's ACTIVE OWNER membership and retained submission attempt. Failed submissions shall create none of those records. |
| REQ-REC-09 | Only authorized Platform Staff shall decide a pending legal dossier. |
| REQ-REC-10 | Review responses shall derive CHECKED with VERIFIED or REJECTED outcome from existing eligibility states. Rejection requires a 10–1000 character reason. |
| REQ-REC-11 | A committed review decision shall initiate email to the submitter's verified account email and expose rejection feedback in Owner settings. |
| REQ-REC-12 | An eligible Owner shall be able to correct and resubmit the same rejected Company, retaining Company ID, immutable MST and prior review history. |
| REQ-REC-13 | Recruitment operations shall require actual email proof, eligible active membership, VERIFIED and non-suspended Company, active User and operation-specific permission. Phone proof shall not be an eligibility condition. |
| REQ-REC-14 | A Recruiter shall join through a valid, matching-email invitation and explicit acceptance. Actual email proof is required before acceptance creates an ACTIVE RECRUITER membership. A Recruiter joining an existing Company does not submit another legal dossier for it. |
| REQ-REC-15 | An MST already assigned to another Company shall block new-Company submission and show support guidance without disclosing another Company's private records. Authorized correction of the same rejected Company remains allowed. |
| REQ-REC-16 | Submission, correction and review shall preserve private evidence, declarations, actor, decision, reason and time without overwriting prior attempts. |
| REQ-REC-17 | Registration shall reject an email or normalized phone already assigned to any existing account, verified or unverified. Concurrent registrations shall allow at most one account per identifier. |
| REQ-REC-18 | Existing accounts already subject to enhanced onboarding shall no longer require SMS once this revision is implemented; their email, membership, Company and permission checks still apply. Unrelated legacy accounts shall not be newly enrolled by this change alone. |
| REQ-REC-19 | Candidate and employer contexts shall share one canonical normalized phone per User. Eligible Candidate profile updates shall update/clear that source atomically with profile changes, reject duplicate/invalid new values and return +84 or NULL. Existing phone-less accounts remain valid. |
| REQ-REC-20 | The old pending-registration proof and SMS endpoints shall be retired with 410 under applicable authentication. Pending records shall remain retained but shall not be bulk-promoted or transfer data; affected applicants register afresh. |
| REQ-REC-21 | Failure to submit the initial verification email after successful account creation shall not undo the account. Report delivery acceptance separately; permit restricted sign-in and authenticated resend. |

## 5. Business and verification rules

| ID | Rule |
|---|---|
| BR-REC-01 | Email and declared normalized phone are unique across existing accounts at account creation; uniqueness does not prove phone possession. |
| BR-REC-02 | Unverified official accounts retain their identifiers. Expiry of email proof shall not automatically release the phone, delete the account, or transfer identifiers/data to another claimant. Phone disputes require manual support under a separately defined process. |
| BR-REC-03 | Email proof shall be account/email/attempt-bound, single-use and replay-safe. An unsolicited link alone shall not establish an authenticated session or transfer control of an existing account. |
| BR-REC-04 | Email verification links expire after 24 hours. |
| BR-REC-05 | Retired: five-minute SMS OTP expiry and five-incorrect-attempt rules do not apply to the email-only target. |
| BR-REC-06 | Email resend requires at least 60 seconds; replacement invalidates the previous email proof. |
| BR-REC-07 | Expired email proof shall preserve entered information and allow another verification attempt. |
| BR-REC-08 | Retired: phone verification shall not gate Company onboarding, invitation acceptance or recruitment operations. |
| BR-REC-09 | Initial Company, Owner and attempt creation succeed or fail together; no orphan records. |
| BR-REC-10 | MST uniqueness blocks new duplicate Companies; matching names/filenames alone do not prove duplication or grant ownership. |
| BR-REC-11 | Only a rejected dossier may use rejection correction; retain optimistic concurrency, audit and review safeguards. |
| BR-REC-12 | Decision delivery occurs after commit; failure cannot undo the review decision. Preserve scoped retry/idempotency behavior. |
| BR-REC-13 | Gender and submitter job title grant no privileges and do not determine approval. |
| BR-REC-14 | User-facing forms and messages support Vietnamese, vi-VN, Unicode and Asia/Ho_Chi_Minh display time. |

Account recovery, verified-email changes, phone changes/recycling and support dispute handling require reviewed policies; unique storage alone does not establish a person's identity. Preserve existing email abuse protections; removing SMS does not authorize removing email throttling.

## 6. User-facing copy intent

Vietnamese copy below is draft wording; the declaration still requires legal/human review.

- Before email proof: “Vui lòng xác thực email để gửi hồ sơ xác thực công ty hoặc chấp nhận lời mời tham gia công ty.”
- Company pending: “Hồ sơ công ty đang chờ xét duyệt. Các chức năng tuyển dụng sẽ được mở sau khi công ty được xác thực.”
- Duplicate MST: “Mã số thuế này đã tồn tại trong hệ thống. Vui lòng liên hệ bộ phận hỗ trợ để được hướng dẫn: [Thông tin hỗ trợ sẽ được cập nhật].”

Representation declaration:

> Tôi cam kết thông tin và giấy tờ cung cấp là chính xác. Tôi là người đại diện theo pháp luật hoặc người được ủy quyền hợp lệ để thực hiện thủ tục này cho công ty. Tôi hiểu rằng hồ sơ và nội dung cam kết sẽ được lưu trong hệ thống để phục vụ việc xác minh và đối soát.

Do not invent a live support address. Supply a real system-owned contact before release.

## 7. Compatibility and approval boundaries

| Source / existing contract | Confirmed target amendment |
|---|---|
| Earlier REC-03 and ADR 0005: pending registration, official account only after email proof | Official account is created at successful registration; email-unverified sign-in is restricted to the workspace shell and verification/recovery actions. |
| Earlier REC-03: verified-only phone ownership and SMS prerequisite | Email/declared phone must be unique across all existing accounts; phone possession is not verified or required. |
| Earlier contract: invitation acceptance before personal proof | Actual email proof is required before Recruiter invitation acceptance/activation for the scoped journey. |
| GW-SR-AUTH-01 | New employer accounts start email-unverified but may sign in with restricted capabilities. Ordinary Candidate registration is not otherwise redesigned; global phone uniqueness needs coordinated Candidate data enforcement. |
| GW-SR-REC-01 | Keep complete-dossier submission, atomic Company/Owner creation, Staff moderation, immutable MST, private retained evidence and rejection correction. |
| GW-SR-REC-02 | Keep Company-scoped roles and invitation binding; Company name never grants membership. Already-ACTIVE Recruiter-to-new-Owner transition remains undecided (Q6); no membership constraint change is approved. |
| Existing enhanced accounts / legacy Companies | Remove phone gates for already-enrolled applicants/members, preserving actual email proof and current authorization. Preserve old proof/history; do not enable new gates for unrelated legacy accounts. |

Product confirmation does not approve an unspecified replacement API, schema, migration, global identifier architecture or new dependency. The [implementation contract](company-onboarding-implementation-contract.md) identifies the technical amendments still needing concrete review.

## 8. Security, privacy and data implications

Restricted sign-in must not accidentally grant Candidate, Company, invitation-acceptance or recruitment effects. Eligibility must use current persisted state, including for old tokens and refresh sessions. Email verification must not silently activate an attacker's credentials from an unsolicited link or disclose another applicant's data.

Collect phone as declared contact data. SMS removal trades possession evidence for a simpler flow; strict uniqueness still permits malicious reservation, so manual dispute support must be defined before production. Duplicate-identifier errors must not disclose another account's profile, verification status or Company context. Registration, contact updates and migration must not introduce duplicate normalized values through races or alternate write paths.

Retain existing private document access and immutable history. Do not erase old phone proof, rewrite migrations, merge accounts, mass-revoke sessions or fabricate actual mailbox proof from legacy auto-verified flags.

## 9. Acceptance scenarios

1. Valid registration creates one official account immediately with email unverified and no Company/membership; mismatched passwords fail and confirmation is not stored.
2. Duplicate email or phone fails even when the existing account is unverified. Local/+84 phone forms collide; concurrent claims produce one account with no partial registration.
3. An unverified account can sign in and view the shell, resend/confirm email and log out, but direct Company edits/uploads/submission, invitation acceptance, Candidate profile/CV/Application and recruitment requests fail without side effects.
4. Email proof expires at 24 hours, resend requires 60 seconds, replacement invalidates old proof, and consumed/wrong-account/wrong-email proof fails. Proof expiry preserves the account and its identifier reservations.
5. After actual email proof, Company preparation/upload/submission and eligible invitation actions need no SMS request, OTP or phone-verification flag.
6. A name entered at registration creates no Company/membership. Accepting a valid invitation activates RECRUITER only in that invitation's eligible Company and only after actual email proof; wrong-recipient, expired, revoked or suspended cases retain existing safeguards.
7. Complete new-Company submission atomically creates PENDING Company, ACTIVE OWNER and retained attempt. Missing fields/declaration, duplicate MST, stale state or transaction failure produces no orphan Company/Owner/attempt. A successful Owner still cannot recruit before Company approval.
8. Staff decisions retain current authorization, version and reason constraints, derived CHECKED mapping and after-commit decision email. Delivery failure cannot undo the decision.
9. Rejected correction retains Company ID/MST and every earlier document/declaration/decision. Only the authorized Owner may correct it after email proof, without SMS.
10. Recruitment requires actual email proof, current authorized membership and eligible VERIFIED Company; approval alone does not grant Candidate-data access.
11. An already-enrolled account stuck at phone verification proceeds after actual email proof with no SMS, while proof/history remain retained. Unrelated legacy accounts are not newly gated by this revision.
12. Another claimant cannot obtain an existing account or its data by disputing a declared phone. Email expiry does not automatically release it. Final support procedures must be reviewed before this scenario can be operationally completed.

13. Eligible Candidate GET/PUT uses the shared phone source. Updating/clearing it preserves current authorization and other profile behavior; duplicate or invalid changes fail without partial updates. Historical phone proof does not verify a new number.
14. Old registration-proof/SMS endpoints return 410 under applicable authentication without revealing or promoting old records. Fresh registration is required for abandoned attempts, and existing official accounts remain intact.
15. Account creation succeeds independently of provider email acceptance; failed submission is reported separately and authenticated resend remains available after cooldown. Cross-device proof requires the correct signed-in account, not an old continuation token.

## 10. Open decisions and implementation readiness

| Item | Decision / boundary |
|---|---|
| Q6: already-ACTIVE Recruiter becoming a new Company's Owner | — (explicitly deferred by the user). Do not infer mandatory departure, simultaneous memberships, ownership transfer or permission to change existing constraints. |
| Immediate-account registration and email proof API/schema | The [implementation plan](company-onboarding-email-only-implementation-plan.md) records the selected registration/state/proof schemas and responses. The user approved sections 3–5; backend implementation and coordinated client/data cutover are documented in the current backend contract. |
| Global phone data ownership and collisions | One canonical account phone is confirmed. Inventory Candidate/employer values; same-User differing numbers, invalid values and cross-User conflicts block conversion until reviewed correction. No automatic winner or silent contact deletion. Physical migration remains subject to technical review. |
| Manual phone dispute/recovery | Define responsible support actors, evidence, privacy, audit and allowed outcomes before production; do not claim email ownership proves phone ownership. |
| Rollout | Remove SMS for enrolled accounts; prepare pending-registration/session compatibility and keep unrelated legacy scope unchanged. |
| Retention and production readiness | Review controlled disposal, declaration text, Staff checklist, real support contact, configured email delivery and aggregate email abuse controls. |

Existing job discovery integration and interactive browser/accessibility/provider checks remain delivery dependencies. No SMS vendor selection is required for this target.

## 11. Change history

- 2026-10-18: Created initial staged email/SMS onboarding direction, later confirmed by the user with the original implementation contract.
- 2026-10-27: Recorded user-confirmed Q1–Q5/Q7–Q9; replaced pending-account promotion and SMS gates with immediate restricted accounts and email-only gates; added strict account identifier uniqueness, clarified Applicant/Recruiter/Owner transitions, and explicitly deferred Q6.
- 2026-10-27 (0.2.1): Recorded Q10–Q17 for backend scope, canonical phone/Candidate edits, failure-safe email submission, coordinated client upgrade, 410 retirement, blocking data conversion and authenticated email proof. Added REQ-REC-19–21 and the current implementation-plan proposal. Documentation only; runtime behavior is unchanged.
