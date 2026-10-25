# [GW-SR-REC-03] Company Onboarding and Verification Enhancement

| Field | Value |
|---|---|
| **Specification ID** | GW-SR-REC-03 |
| **Project** | GuardWork |
| **Module** | Employer onboarding, personal verification, and Company verification |
| **File** | `company-onboarding-verification-enhancement-spec.md` |
| **Version** | 0.1.0 |
| **Date** | 2026-10-18 |
| **Last Updated** | 2026-10-24 |
| **Owner** | VyTrg (proposed, following the existing Company Verification ownership) |
| **Approver** | VyTrg (proposed human reviewer; formal approval pending) |
| **Status** | Draft |

The user confirmed the consolidated product direction after a design interview on 2026-10-18, subsequently stated that they had read and approved this document, requested implementation, and confirmed the [concrete technical contract](company-onboarding-implementation-contract.md) with “chốt”. Those later confirmations authorize the documented implementation choices; SMS vendor selection and the explicitly unresolved production-readiness policies remain outstanding. The Draft metadata is retained as the original review register; it does not override the recorded human authorization. Agents do not independently declare human approval.

## 1. Introduction

### 1.1 Problem and goal

Current registration automatically treats email as verified. Current Company onboarding accepts a complete dossier and creates the Company and its first Owner together. This enhancement introduces staged onboarding: prove mailbox access, prove possession of a Vietnamese mobile number, submit Company documents and a representation declaration, then obtain a Platform Staff decision before employer operations become available.

Personal verification and Company verification are separate. A verified phone does not establish Company legitimacy; a verified Company does not prove possession of every member's email or phone.

### 1.2 Scope

In scope:

- A new Company applicant registration form and limited onboarding experience.
- Email-link verification and SMS OTP verification, including verified-identifier uniqueness.
- Editable provisional Company information after phone verification.
- Legal dossier submission, a mandatory representation declaration, duplicate tax-code handling, manual review, rejection correction, and decision email.
- Personal verification prerequisites for invited Recruiters, without another Company dossier.

Outside this enhancement:

- The transition of an existing job-seeker account into Company onboarding; record this case for later work.
- Internal Recruiter roles, invitations, delegation, Job Assignments, and recruitment-operation permissions. Existing membership authorization remains the dependency.
- International mobile numbers, OCR, government-registry integration, automatic Company approval, and mandatory authorization-letter upload.
- SMS/email provider selection, physical schema, endpoint definitions, deployment, and existing-data migration implementation.
- Broader Candidate capabilities beyond the explicitly agreed search/view access during onboarding. This feature does not grant CV management or Application access.

## 2. Overall description

### 2.1 Applicant journey

```text
Register personal details and provisional Company information
    -> Verify email using a link
    -> Sign in with limited access: search/view jobs and phone verification
    -> Verify Vietnamese mobile number using SMS OTP
    -> Edit provisional information and complete the legal dossier
    -> Accept the representation and accuracy declaration
    -> Submit dossier: PENDING
    -> Platform Staff checks dossier: CHECKED
         -> VERIFIED: employer eligibility, subject to existing authorization
         -> REJECTED: reason in settings and account email
              -> Correct the same Company dossier and resubmit: PENDING
```

Registration alone does not create a Company in the moderation queue. Company creation and first Owner assignment occur together when a complete initial dossier is submitted. Provisional Company information must remain distinguishable from a submitted Company dossier; its storage design is not selected here.

### 2.2 Access matrix

This matrix describes the new applicant journey, not a platform-wide replacement for existing Candidate permissions.

| Applicant stage | Search/view jobs | Verification/settings access | Employer operations |
|---|---|---|---|
| Email unverified | Existing public discovery only; no authenticated session | Email verification instructions and resend | Locked |
| Email verified, phone unverified | Allowed after sign-in | Phone verification | Locked |
| Email and phone verified, before dossier submission | Allowed | Edit provisional information and prepare legal dossier | Locked |
| Dossier pending | Allowed | View submitted status | Locked |
| Dossier rejected | Allowed | Read reason, correct dossier, resubmit | Locked |
| Company verified | Allowed | Relevant account/Company settings | Eligible only within existing Company authorization |

Before Company approval, CV management, Applications, and Candidate functions other than job search/view remain unavailable to this onboarding account. Post-approval Candidate capability expansion is not defined by this enhancement and must not be inferred from employer approval.

Logout and the recovery/verification actions necessary to complete onboarding must remain available. These do not grant marketplace or employer capabilities.

### 2.3 Review lifecycle

| Processing status | Review outcome | Meaning |
|---|---|---|
| `PENDING` | None | Submitted and awaiting Platform Staff review |
| `CHECKED` | `VERIFIED` | Checked and approved |
| `CHECKED` | `REJECTED` | Checked and rejected, with a mandatory reason |

`CHECKED` alone never grants employer access. The outcome must be `VERIFIED`, and personal verification, active membership, User status, Company status, and operation-specific authorization must also permit the action.

These are conceptual statuses, not approved database columns. Resubmission creates a new pending review attempt while retaining earlier submissions, declarations, and decisions.

## 3. Information requirements

### 3.1 Initial registration

| Information | Requirement |
|---|---|
| Full name | Required; preserve Vietnamese Unicode names. Mapping to existing first/last-name fields remains a design task. |
| Mobile number | Required; Vietnamese mobile number; normalize local `0…` and international `+84…` representations to one identity. |
| Company name | Required provisional information; editable after phone verification. |
| Gender | Required selection: Male, Female, Other, or Prefer not to disclose. No effect on verification decisions or permissions. |
| Work address | Required provisional information; editable after phone verification. |
| Account email | Required; login, personal verification, and decision-email destination. |
| Password | Required; preserve applicable existing password-security requirements. |
| Password confirmation | Required; must match; must not be persisted. |

No separate Company contact email is requested in this journey. Removing the current required Company email is an explicit contract change requiring coordinated approval.

### 3.2 Legal dossier

After phone verification, the applicant can update provisional information and submit:

- Company name and work address, confirmed or corrected from registration.
- Company tax code (MST).
- Business registration/license document.
- Submitter's position/job title.
- A selection identifying the submitter as legal representative or an authorized representative.
- An explicit accuracy and representation commitment.

The system retains submitted documents, the declaration, and their association with the submitting account and review history. Exact retention periods and deletion handling remain subject to a separate approved privacy/retention policy; retention must not silently mean indefinite storage.

No authorization letter is mandatory. A declaration records an assertion of authority and is not independent proof of authority. The submitter's job title is not a Company authorization role.

## 4. Functional requirements

Requirement IDs below are local to GW-SR-REC-03.

| ID | Requirement |
|---|---|
| REQ-REC-01 | Registration shall collect the information in section 3.1 without automatically marking email or phone verified. |
| REQ-REC-02 | The system shall maintain independent email-verification and phone-verification flags bound to the verified identifiers. |
| REQ-REC-03 | Account sign-in shall require successful email-link verification for this journey. |
| REQ-REC-04 | After email verification, the applicant shall have only authenticated job search/view and the onboarding actions permitted by section 2.2. |
| REQ-REC-05 | Phone verification shall use an SMS OTP and shall unlock legal-dossier preparation only after success. |
| REQ-REC-06 | The applicant shall be able to edit provisional Company information after phone verification and submit it with the legal dossier. |
| REQ-REC-07 | Submission shall require email and phone verification, complete dossier information, and explicit acceptance of the representation/accuracy declaration. |
| REQ-REC-08 | Initial submission shall create the Company and first Owner atomically and place a complete dossier in the pending review queue. |
| REQ-REC-09 | Only a Platform Staff member shall decide a pending legal dossier. |
| REQ-REC-10 | A completed review shall be represented as CHECKED with a VERIFIED or REJECTED outcome; rejection shall require a reason. Preserve the current 10–1000 character rejection-reason constraint. |
| REQ-REC-11 | The system shall email the decision to the submitting account's verified email and expose rejection feedback in the account's verification settings. |
| REQ-REC-12 | A rejected applicant shall be able to correct and resubmit the existing Company dossier, preserving Company identity, immutable MST, and previous review history. |
| REQ-REC-13 | Employer eligibility shall require personal verification and an approved Company; existing authorization determines permitted operations. |
| REQ-REC-14 | An invited Recruiter shall verify their own email and phone before employer operations, without submitting another Company dossier. Invitation/delegation implementation is outside this enhancement. |
| REQ-REC-15 | A new dossier whose MST already belongs to an existing Company shall be blocked and display system-support guidance. Authorized correction of that same rejected Company is not a new duplicate request. |
| REQ-REC-16 | Submission and review shall retain declarations, reviewed content, actor, decision, reason, and time without overwriting prior attempts. |

## 5. Business and verification rules

| ID | Rule |
|---|---|
| BR-REC-01 | A verified email or normalized phone shall belong exclusively to one account. Registration checks existing verified ownership, and verification rechecks it atomically at completion. |
| BR-REC-02 | An unverified claim shall not permanently reserve an identifier or let an applicant deny its real owner's verification. Concurrent completion shall grant at most one verified owner. |
| BR-REC-03 | Verification shall bind proof to the exact account, identifier, and verification attempt. A verified flag shall not verify a different value or transfer another account's data. |
| BR-REC-04 | Email verification links expire after 24 hours. |
| BR-REC-05 | Phone OTPs expire after 5 minutes and allow at most 5 incorrect submissions per issued code. |
| BR-REC-06 | Resending an email link or SMS OTP requires at least 60 seconds. A newly issued link/code invalidates the previous one. |
| BR-REC-07 | Expired verification attempts shall not erase entered onboarding information; the applicant may request another attempt. |
| BR-REC-08 | Phone verification alone shall never unlock employer operations. |
| BR-REC-09 | Initial Company submission and first Owner creation shall succeed or fail together. A duplicate or failed submission shall leave no orphan Company or membership. |
| BR-REC-10 | Existing MST uniqueness shall block new duplicate Companies. Company-name similarity or identical uploaded filenames alone shall not establish a duplicate. |
| BR-REC-11 | Rejected dossiers may return to PENDING through correction; pending or verified dossiers do not use this rejection-correction path. Preserve existing concurrency protection and audit requirements. |
| BR-REC-12 | Decision-email delivery shall occur after the decision commits; a delivery failure shall not undo the decision. Delivery/retry implementation needs a reviewed contract. |
| BR-REC-13 | Gender and the self-declared job title shall not grant privileges or determine approval. |
| BR-REC-14 | User-facing forms, declarations, errors, and messages shall support Vietnamese, vi-VN conventions, Unicode, and Asia/Ho_Chi_Minh display time. |

Changing an already verified email or phone, including number recycling and recovery, requires a separate reviewed policy. Implementers must not preserve verification on a changed value by default.

## 6. User-facing copy intent

Final wording needs human review. The following Vietnamese text is a draft, not approved legal language.

**Representation and accuracy declaration:**

> Tôi cam kết thông tin và giấy tờ cung cấp là chính xác. Tôi là người đại diện theo pháp luật hoặc người được ủy quyền hợp lệ để thực hiện thủ tục này cho công ty. Tôi hiểu rằng hồ sơ và nội dung cam kết sẽ được lưu trong hệ thống để phục vụ việc xác minh và đối soát.

**Duplicate legal information:**

> Mã số thuế này đã tồn tại trong hệ thống. Bạn không thể tạo yêu cầu xác thực mới. Vui lòng liên hệ bộ phận hỗ trợ để được hướng dẫn: [Thông tin hỗ trợ sẽ được cập nhật].

The temporary support text must not invent an email address, phone number, or active contact destination. An actual system-owned support contact is a release-readiness item.

## 7. Compatibility and requirement amendments

This draft proposes changes to the following sources; it does not silently replace them.

| Existing source or behavior | Enhancement impact requiring coordinated approval |
|---|---|
| [GW-SR-AUTH-01](../auth/authentication-authorization-spec.md): automatic activation and verified email at registration | Introduce real email proof before sign-in for the new journey. Preserve unrelated authentication behavior until an amendment is approved. |
| AUTH registration uses first/last name and username; User storage has unique email | The new form uses full name and no user-entered username. Mapping, login compatibility, and verified-only identifier ownership need an explicit API/schema design. A flag alone cannot resolve existing uniqueness constraints. |
| [GW-SR-REC-02](company-membership-mvp-spec.md): members retain Candidate capabilities | The new onboarding account is limited to job search/view before approval. This is a deliberate proposed exception; existing Candidate accounts are not globally restricted by this draft. |
| [GW-SR-REC-01](company-verification-spec.md): Company plus complete dossier and Owner created during onboarding | Preserve atomic Company/Owner creation at complete dossier submission; add provisional information before submission. |
| Current Company submission requires a separate Company email and checks its duplication | Use account email only in the new flow; existing schema, DTOs, clients, and historical Company contact data need a migration/compatibility decision. |
| Existing Company verification is PENDING / VERIFIED / REJECTED | Introduce conceptual processing status PENDING / CHECKED plus outcome. Existing clients, membership checks, queries, and audit records require an explicit mapping. |
| Verification-decision notifications were deferred in the current verification specification | Decision email becomes required in this enhancement; provider, delivery, retry, and failure visibility need approved design. |
| Existing legacy ownership assignment requires reviewed representation evidence | New-company submission accepts a declaration without an authorization letter. This does not weaken or replace the separate legacy ownership-assignment process. |

Reference architecture: [Company-scoped Membership ADR](../../architecture/decisions/0001-company-scoped-membership.md). This enhancement does not introduce a global Recruiter user type or make a legal job title into a membership role.

## 8. Security, privacy, and data implications

- New data includes phone, gender, work address, submitter position, verification evidence, and declaration history. Access to private dossiers must remain scoped to the submitting Owner and authorized Platform Staff members.
- Employer gates must be enforced by the backend, including submission-time and operation-time checks; hiding controls is insufficient.
- Verification material must not be logged or exposed to other applicants. Design must address challenge binding, single use, replay, concurrent claims, and pending-registration recovery without account takeover or cross-account disclosure.
- The agreed per-attempt limits do not define all abuse controls. SMS budgets, aggregate throttling, brute-force protection, and delivery-failure behavior need approval before implementation.
- A self-declared authorization claim leaves an impersonation risk. The user explicitly selected a retained declaration instead of mandatory authorization evidence for this journey; the Staff review checklist and dispute handling still need definition.
- Email/SMS possession is not proof of legal identity. Company approval does not grant unrestricted Candidate-data access.

## 9. Acceptance scenarios

1. Registration preserves required information, rejects mismatched passwords, and leaves email/phone unverified.
2. An unverified applicant cannot sign in; a valid email link permits limited sign-in. Expired, replaced, or consumed proof cannot verify another attempt.
3. Before phone verification, direct requests to dossier submission, CV management, Application submission, and employer operations are denied; job search/view remains available after email verification.
4. Correct SMS OTP verifies the exact normalized phone and opens dossier preparation, without employer access.
5. Local and +84 forms of one phone cannot become verified ownership on two accounts. Concurrent email/phone claims yield at most one verified owner.
6. An abandoned unverified claim does not permanently block the real mailbox/phone owner, and recovery does not reveal or transfer the earlier applicant's data.
7. Resends respect 60 seconds and replace old proof; email expires at 24 hours, OTP at 5 minutes, and a sixth incorrect OTP attempt cannot succeed for that code.
8. An applicant edits provisional Company information after phone verification and submits complete documents and an explicit declaration. Missing declaration blocks submission.
9. A duplicate MST blocks a new request, shows temporary support guidance, and creates no extra Company, Owner, or review request. Matching names/filenames alone do not trigger this rule.
10. Platform Staff approves a pending dossier: it becomes CHECKED/VERIFIED, records the decision, and initiates an account-email notification. Only existing authorized employer operations become eligible.
11. Rejection without a valid reason fails. A valid rejection produces CHECKED/REJECTED, feedback in settings, and decision email; employer operations remain locked.
12. Owner correction of a rejected dossier preserves Company ID and MST, creates a pending review attempt, and retains earlier documents, declarations, decisions, and reasons.
13. Concurrent Staff decisions or stale resubmission cannot overwrite each other; decision-email failure does not reverse a committed decision.
14. An invited Recruiter must complete personal verification but does not submit the Company's legal dossier again; Company access still depends on existing membership authorization.
15. Gender selection is required, including an option not to disclose, and never changes permissions or the review outcome.

## 10. Implementation readiness and follow-up

Before implementation:

- Obtain a named human's formal specification approval and coordinate the amendments in section 7.
- Design registration/full-name/username compatibility, verified identifier ownership and pending-claim recovery, challenge persistence, and Company processing/outcome mapping. Approve schema and any breaking API changes explicitly.
- Define existing-account and existing-data migration. Existing job-seeker-to-employer onboarding is deliberately deferred, not silently supported.
- Decide verified-identifier change/recovery policy and post-approval Candidate capability policy before delivering those paths.
- Select and approve SMS/email integrations, delivery/retry behavior, aggregate abuse limits, and operational costs. No provider or dependency is selected by this draft.
- Review the declaration, dossier-access controls, retention/deletion policy, Staff review checklist, and dispute route.
- Replace temporary support information before release and define document upload/storage validation using an approved contract.
- Derive implementation tasks and backend/frontend checks from section 9. No endpoint, migration, frontend framework, or shared notification architecture is selected here.

## 11. Change history

- 2026-10-18: Created a separate Draft from the user-confirmed design interview. Existing approved specifications and application code remain unchanged.
