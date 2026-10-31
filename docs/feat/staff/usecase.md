# Staff Use Cases

| Field | Value |
|---|---|
| Document ID | GW-ADM-UC |
| Work item | GW-81 — Scope Staff documentation |
| Status | Draft — UC-11–21 plus scoped personal-history addition UC-22 |
| Owner | GuardWork thesis team |
| Human approver | Not recorded; formal approval remains pending |
| Last updated | 2026-10-30 |
| Primary actor | Platform Staff (`STAFF`) |
| Source | Existing UC-11–21 from GW-81; UC-22 personal-history scope confirmed in session on 2026-10-30 |

## 1. Scope and common conditions

This document retains eleven use cases in four areas: account management, Company verification dossier review, receipt and inspection of user reports, and Job Posting moderation. UC-22 adds a read-only personal activity-history view across account management, Job Posting management, and Company verification. Existing UC-11–21 and requirement identifiers are retained for traceability. Technical prose is English; Vietnamese titles are retained. Login is a prerequisite, and opening a posting is contextual navigation for moderation and history.

All use cases describe an authenticated Platform Staff member with Platform role `STAFF`. Company membership roles, including Company `ADMIN`, do not independently grant Platform Staff authority. Every operation must enforce authorization on the server. The user approved runtime adoption in GW-81: Platform role `STAFF` and `/api/staff/**` replace the former Platform role and routes. See [migration and compatibility](spec.md#43-gw-81-runtime-adoption-and-compatibility) for the implemented boundary and client impact. Company `ADMIN` membership remains unchanged.

Read operations do not change business state. Private evidence is available only within the authorized review context. Passwords, password hashes, tokens, and unrelated private communications must not be exposed.

The use-case descriptions remain Draft. The user separately approved the GW-81 role and route migration for the implemented verification APIs; other product behavior remains governed by existing approved requirements. See [the Staff specification](spec.md) for UC-11–20 requirements and implementation boundaries. UC-21 is a draft addition in this document; its detailed requirements and implementation contract remain to be aligned and approved.

## 2. Use-case inventory

| User number | Stable ID | Vietnamese title | Existing requirement family |
|---|---|---|---|
| 11 | UC-11 | Xem danh sách báo cáo | UC-ADM-02-V / ADM-REP |
| 12 | UC-12 | Xem chi tiết báo cáo | UC-ADM-02-D / ADM-REP |
| 13 | UC-13 | Ẩn tin tuyển dụng | UC-ADM-04-H / ADM-JOB |
| 14 | UC-14 | Xem lịch sử đăng tin | UC-ADM-04-L / ADM-JOB |
| 15 | UC-15 | Xem chi tiết hồ sơ xác minh công ty | UC-ADM-01-V / ADM-VER |
| 16 | UC-16 | Phê duyệt hồ sơ xác minh công ty | UC-ADM-01-A / ADM-VER |
| 17 | UC-17 | Từ chối hồ sơ xác minh công ty | UC-ADM-01-R / ADM-VER |
| 18 | UC-18 | Xem thông tin tài khoản | UC-ADM-03-V / ADM-ACC |
| 19 | UC-19 | Khóa tài khoản | UC-ADM-03-B / ADM-BAN |
| 20 | UC-20 | Mở khóa tài khoản | UC-ADM-03-U / ADM-BAN |
| 21 | UC-21 | Công khai lại tin tuyển dụng đã ẩn | ADM-JOB / draft restoration workflow |
| 22 | UC-22 | Xem lịch sử hoạt động của bản thân | ADM-ACT / GW-SR-ADM-03 |

Legacy `UC-ADM-*` workflow identifiers are retained as references, not reassigned to different capabilities. Report details extend report-list viewing; approval and rejection extend dossier viewing; ban and unban extend account viewing. Hide, posting history, and restoration of public visibility are available from an authorized job context without adding a standalone job-view use case here.

## 3. Use-case descriptions

### UC-11 — View Report List

- **Actor:** Platform Staff.
- **Description:** Allow the Staff to view violation reports submitted by users.
- **Pre-condition:** The common conditions in Section 1 hold.
- **Post-condition:** Matching reports are displayed; report-processing states remain unchanged.
- **Event flow:**
  - **Main flow:**
    1. The Staff opens the report list.
    2. The system displays report summaries identifying the reported Company, Job Posting, or Candidate, complaint content, submission time, and current status.
    3. The Staff may filter by report status or reported-target type.
    4. The system displays matching results.
  - **Alternative flows:**
    - **2a.** No reports exist. The system displays an empty list.
    - **4a.** No reports match the filters. The system displays a no-results message.
    - **2b.** Data cannot be loaded. The system displays an error and permits retry.

### UC-12 — View Report Details

- **Actor:** Platform Staff.
- **Description:** Allow the Staff to inspect complaint content, evidence, and related information for a violation report.
- **Pre-condition:** The common conditions hold; the report exists and is within the authorized access scope.
- **Post-condition:** Report details are displayed; viewing does not automatically apply sanctions or change the processing outcome.
- **Event flow:**
  - **Main flow:**
    1. The Staff selects a report from the list.
    2. The system retrieves complaint content, permitted reporter information, the reported target, evidence, and current status.
    3. The system displays an existing processing outcome if the report has already been handled.
    4. The Staff inspects the information and may return to the list.
  - **Alternative flows:**
    - **2a.** The report does not exist or is inaccessible. The system refuses to display it.
    - **2b.** An evidence attachment is unavailable. The system informs the Staff and displays the remaining retrievable information.

### UC-13 — Hide Job Posting

- **Actor:** Platform Staff.
- **Description:** Allow the Staff to forcibly hide a violating Job Posting from public access.
- **Pre-condition:** The target posting is currently public (`PUBLISHED`).
- **Post-condition:** The posting becomes `SUSPENDED` and is no longer public; its content and history remain available for authorized tracing.
- **Event flow:**
  - **Main flow:**
    1. The Staff selects Hide Job Posting.
    2. The system requests a reason.
    3. The Staff enters the reason and submits the request.
    4. The system checks authority and the posting’s current state.
    5. The system changes the posting to `SUSPENDED` and records the actor, time, and reason.
    6. The system removes the posting from public display, search results, and related caches, and records the audit entry.
  - **Alternative flows:**
    - **3a.** No reason is supplied. The system requests one.
    - **4a.** The posting is no longer `PUBLISHED`. The system refuses the action and reports an invalid state.
    - **6a.** A Candidate follows the hidden posting’s link. The system indicates that the posting is suspended or unavailable.

### UC-14 — View Posting History

- **Actor:** Platform Staff.
- **Description:** Allow the Staff to inspect recorded publication, status-change, and administrative events for a Job Posting.
- **Pre-condition:** The common conditions hold; the Staff is viewing the posting and is authorized to read its history.
- **Post-condition:** History is displayed without changing events or posting content.
- **Event flow:**
  - **Main flow:**
    1. The Staff selects View Posting History.
    2. The system retrieves the recorded history events.
    3. The system displays each event’s time, actor, action, status, and reason where available.
    4. The Staff inspects the history and returns to the posting.
  - **Alternative flows:**
    - **2a.** No history is available. The system displays a no-data message.
    - **2b.** History cannot be retrieved. The system reports an error rather than displaying an empty history.

### UC-15 — View Company Verification Dossier

- **Actor:** Platform Staff.
- **Description:** Allow the Staff to inspect Company information, legal documents, and submission history for verification review.
- **Pre-condition:** The common conditions hold; the Company dossier exists.
- **Post-condition:** Dossier information is displayed; verification status is unchanged.
- **Event flow:**
  - **Main flow:**
    1. The Staff opens the Company verification list.
    2. The system defaults to `PENDING` dossiers, with pagination and ascending creation-time order.
    3. The Staff selects a dossier.
    4. The system displays Company name, tax code, stored contact information, verification status, and any existing outcome and rejection reason.
    5. For the enhanced onboarding journey, the system displays retained submissions, work address, submitter position, representation, declaration, and legal evidence.
    6. The Staff may download documents for inspection and choose Approve or Reject if the dossier is `PENDING`.
  - **Alternative flows:**
    - **2a.** The Staff filters by `VERIFIED`, `REJECTED`, or `ALL`. The system displays the corresponding results.
    - **2b.** No dossiers match. The system displays an empty list.
    - **5a.** A legacy dossier has no retained-document history from enhanced onboarding. The system displays only actually stored data.
    - **6a.** A document is missing or inaccessible. The system refuses the download.
    - **6b.** The dossier has already been reviewed. The system displays its outcome and does not offer another decision in its current state.

### UC-16 — Approve Company Verification Dossier

- **Actor:** Platform Staff.
- **Description:** Allow the Staff to accept a dossier and confirm that the Company is verified.
- **Pre-condition:** The dossier is awaiting review (`PENDING`).
- **Post-condition:** The dossier becomes `VERIFIED`; a decision notification is to be sent to the user who submitted the verification request, subject to the delivery scope in Section 4.
- **Event flow:**
  - **Main flow:**
    1. Within the dossier, the Staff inspects the submitted information and documents.
    2. The Staff selects Approve.
    3. The system checks authority, existence, `PENDING` status, and dossier version.
    4. The system changes verification status to `VERIFIED`.
    5. The system records the audit entry in the same transaction as the decision.
    6. The system returns the result and the interface reloads the dossier list.
  - **Alternative flows:**
    - **3a.** The dossier does not exist. The system reports not found.
    - **3b.** The dossier is no longer `PENDING`. The system refuses the decision with an invalid-state error.
    - **3c.** The version differs or a concurrent update occurs. The system returns a conflict and does not overwrite the decision.

### UC-17 — Reject Company Verification Dossier

- **Actor:** Platform Staff.
- **Description:** Allow the Staff to reject an unsuitable dossier and retain explanatory feedback.
- **Pre-condition:** The common conditions hold; the dossier is awaiting review (`PENDING`).
- **Post-condition:** The dossier becomes `REJECTED` with the rejection reason; a decision notification is to be sent to the submitting user, subject to the delivery scope in Section 4.
- **Event flow:**
  - **Main flow:**
    1. Within the dossier, the Staff selects Reject.
    2. The Staff enters a rejection reason.
    3. The system verifies that the reason, after trimming leading and trailing whitespace, contains 10–1000 characters.
    4. The system checks authority, existence, current status, and dossier version.
    5. The system changes verification status to `REJECTED`, saves the reason and review time, and increments the version.
    6. The system records the audit entry and updates the retained submission outcome where such a submission exists.
    7. The system returns the result and the interface reloads the dossier list.
  - **Alternative flows:**
    - **3a.** The reason is invalid. The system refuses the decision and requests corrected input.
    - **4a.** The dossier does not exist. The system reports not found.
    - **4b.** The dossier is no longer `PENDING`. The system refuses the action.
    - **4c.** The version differs or a concurrent update occurs. The system returns a conflict without overwriting data.

### UC-18 — View Account Information

- **Actor:** Platform Staff.
- **Description:** Allow the Staff to inspect identifying information and current status for a user account or Company for governance purposes.
- **Pre-condition:** The target account exists.
- **Post-condition:** Information is displayed without changing the target’s status or access rights.
- **Event flow:**
  - **Main flow:**
    1. The Staff searches for and selects the target.
    2. The system identifies whether the target is a user account or Company.
    3. The system displays permitted identifying information, current status, and ban information where available.
    4. The Staff may select Ban or Unban when their conditions are met.
  - **Alternative flows:**
    - **2a.** The target is missing or inaccessible. The system refuses to display it.
    - **3a.** Data cannot be loaded. The system displays an error and permits retry.
- **Constraint:** Passwords, password hashes, tokens, and unrelated private communications must not be displayed.

### UC-19 — Ban Account

- **Actor:** Platform Staff.
- **Description:** Allow the Staff to suspend platform access for a violating user account or Company.
- **Pre-condition:** The target exists and is active (`ACTIVE` for a user, or the corresponding non-banned Company state).
- **Post-condition:** The target is banned; related sessions are revoked under the enforcement policy; the action is recorded in the audit trail.
- **Event flow:**
  - **Main flow:**
    1. Within account information, the Staff selects Ban Account.
    2. The Staff enters a reason and selects a temporary or permanent ban.
    3. The system checks authority, target, current state, and ban duration.
    4. The system records the banned state, reason, and duration.
    5. The system revokes related access sessions.
    6. The system records the audit entry and reports the result.
  - **Alternative flows:**
    - **3a.** The Staff attempts to ban their own account. The system refuses the action.
    - **3b.** The reason is missing or the duration is invalid. The system requests corrected input.
    - **3c.** The target is already banned. The system reports its current state.
    - **4a.** The target is a Company. The system also suspends its public postings, revokes related recruiters’ sessions, and hides the Company from public search.
    - **5a.** Required session revocation cannot be completed. The system does not confirm a successful ban.

### UC-20 — Unban Account

- **Actor:** Platform Staff.
- **Description:** Allow the Staff to restore platform access for a banned account or Company.
- **Pre-condition:** The target is banned (`BANNED` for a user, or the corresponding banned Company state).
- **Post-condition:** The active ban ends, active status and the applicable account permissions are restored, and the user must sign in again.
- **Event flow:**
  - **Main flow:**
    1. Within the banned account’s information, the Staff selects Unban.
    2. The Staff enters an unban reason and submits the request.
    3. The system checks authority and the current ban state.
    4. The system ends the ban and restores the applicable active state.
    5. The system ensures that sessions issued before or during the ban cannot be reused.
    6. The system records the audit entry and reports the result.
  - **Alternative flows:**
    - **2a.** No reason is supplied. The system requests one.
    - **3a.** The target does not exist or is no longer banned. The system refuses the action and reports the current state.
    - **5a.** Invalidation of old sessions cannot be ensured. The system does not confirm a successful unban.

### UC-21 — Republish Hidden Job Posting

- **Actor:** Platform Staff.
- **Description:** Allow the Staff to restore public visibility of a Job Posting previously hidden through administrative moderation after the hiding reason has been resolved.
- **Pre-condition:** The posting is currently suspended (`SUSPENDED`).
- **Post-condition:** The posting returns to `PUBLISHED` and becomes publicly visible again; the time and reason are recorded. Posting content, Applications, and prior hiding history are preserved.
- **Event flow:**
  - **Main flow:**
    1. The Staff reviews the posting content, hiding reason, and moderation history, then selects Republish Hidden Job Posting.
    2. The system requests a restoration reason.
    3. The Staff enters the reason and submits the request.
    4. The system checks authority, the posting’s current state, and publication eligibility.
    5. The system changes the posting to `PUBLISHED` and records the actor, time, and reason while preserving the prior hiding history.
    6. The system restores public visibility, updates relevant search results and caches, and records the audit entry.
    7. The system reports the result and displays the posting’s updated status.
  - **Alternative flows:**
    - **3a.** No reason is supplied. The system requests one.
    - **4a.** The posting does not exist, the actor lacks authority, or the posting’s state has changed. The system refuses the action without overwriting the current changes.
    - **4b.** The hiding reason remains unresolved or the posting does not meet publication eligibility conditions. The system refuses the action and explains the unmet condition.
    - **6a.** A mandatory visibility update or audit step cannot be completed. The system reports an error and does not confirm successful republication.

### UC-22 — View Own Activity History

- **Actor:** Authenticated, currently active Platform Staff.
- **Description:** Let the Staff view and find their own successful data-changing
  activities in account management, Job Posting management, and Company verification.
- **Pre-condition:** The caller satisfies existing Staff authentication, current-role,
  session-validity, and portal rules.
- **Post-condition:** Only the caller's matching retained activities are displayed;
  no business state or audit history is changed by the view.
- **Event flow:**
  - **Main flow:**
    1. The Staff opens personal activity history.
    2. The system selects the actor from trusted authentication context.
    3. The system lists the actor's in-scope committed changes, newest first,
       covering the most recent 30 Vietnam calendar dates including today.
    4. Each record shows time, action, target type/code, recorded before/after
       state, and an applicable recorded reason.
    5. The Staff may filter by inclusive date range, business group, action,
       and exact target code within its group/type, or move between 20-row pages.
    6. The system returns only matching records belonging to that actor.
  - **Alternative flows:**
    - **2a.** Authentication/authority fails: deny access and return no history.
    - **3a.** No matches: display an empty state and permit filter changes.
    - **3b.** Retrieval fails: display an error and permit retry.
    - **4a.** An old record lacks a fact: show “Không có dữ liệu”; do not infer it
      from the target's current state or fabricate history.
    - **5a.** A filter is invalid: request corrected input without broadening access.
- **Constraints:** Read/search/filter/paginate only; no edit, delete, hide, export,
  raw-audit viewer, or navigation to the target. Failed attempts, reads, and
  authentication/session events are excluded. Existing authorized Company/Job
  history may still identify the Staff who made a decision.
- **Specification:** [GW-SR-ADM-03](../../software-requirements/adm/personal-activity-history-spec.md)
  and its [API proposal](../../software-requirements/adm/personal-activity-history-implementation-contract.md).
  Product scope was confirmed on 2026-10-30; technical implementation approval is pending.

## 4. Interpretation and implementation boundaries

- **Publication spelling:** The supplied UC 13 precondition says `PUBLISHING`, while its alternative 4a says `PUBLISHED`. This document consistently uses `PUBLISHED`, matching the existing Staff lifecycle; it does not introduce a new status or migration.
- **Restoration boundary:** UC-21 reverses administrative hiding only. It does not resume a Company-paused posting, reopen a closed/expired posting, approve an unreviewed content revision, or lift another enforcement restriction. The Job Publishing Draft models Platform Removal as an independent enforcement condition, whereas this Staff draft uses `SUSPENDED`; reconcile that representation, publication eligibility, concurrency, and search/cache recovery with UC-21 in the Staff specification before implementation. No API, schema, or new state is selected here.
- **Company enforcement state:** User status `ACTIVE/BANNED` and Company flag `is_banned` express the corresponding enforcement states; a Company does not acquire a new user-status column through this document.
- **Restored permissions:** UC 20 restores access permitted by existing roles, membership, and verification state. It does not grant a new role, verify a Company, or automatically republish suspended postings.
- **Verification compatibility:** The concise UC 16–17 flows do not remove the approved reviewer/time, `expectedVersion`, version-increment, or atomic audit requirements. Both decisions still require the current version. Non-pending state and a stale version remain distinct failures; the current backend checks non-pending state first.
- **Decision delivery:** Notifications are a required outcome, not a promise of immediate mailbox arrival. Enhanced retained submissions currently create an email delivery intent addressed to the submitter in the decision transaction; a worker sends after commit and retries. Delivery failure does not undo a committed decision. Legacy URL-only dossiers without a retained submission do not currently create that intent. The remaining legacy notification gap is not hidden by this specification.
- **Dossier presentation:** Existing responses derive `processingStatus = CHECKED` for both `VERIFIED` and `REJECTED`; `CHECKED` does not itself mean approved. The interface reloads the list after a successful decision, but currently reports errors without automatically reloading on conflict.
- **Read-only report scope:** UC 11–12 do not begin review, acquire a review lock, resolve/reject a report, or apply a sanction. Existing outcomes may be displayed. Former report-resolution requirements are outside this revision’s selected scope, not silently removed from the wider product.
- **Coverage and delivery:** Login, standalone job viewing, job closure, report adjudication, and general cross-Staff audit-log search remain outside scope. UC-22 adds only the authenticated caller's personal history; it does not change existing audit recording or target-history permissions.
- **Current implementation:** Company verification, retained dossier history/private documents, decision audit, and enhanced-submission email intents have implementation support. This document does not claim implemented Staff report, job moderation/history, or account governance APIs.
- **Unselected infrastructure:** Search/cache removal, verification-decision delivery, and session revocation describe required effects. They do not select Redis, Kafka, or Elasticsearch as new approved architecture.

## 5. Related sources

- [Personal activity history specification](../../software-requirements/adm/personal-activity-history-spec.md)
- [Personal activity history API proposal](../../software-requirements/adm/personal-activity-history-implementation-contract.md)

- [Aligned Staff requirements](spec.md)
- [Company verification reference](company_verification.md)
- [Approved Company verification requirements](../../software-requirements/rec/company-verification-spec.md)
- [Approved Authentication requirements](../../software-requirements/auth/authentication-authorization-spec.md)
- [Confirmed onboarding implementation contract](../../software-requirements/rec/company-onboarding-implementation-contract.md)
- [Current onboarding implementation and delivery limitations](../../backend/employer-onboarding.md)

The Company verification reference describes wider checks and infrastructure; approved verification and onboarding contracts remain authoritative. The approved separation of Platform authority from Company membership is preserved. Private-field visibility, search/cache integration, and enforcement infrastructure still need concrete approved contracts before implementation.
