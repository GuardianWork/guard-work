# System Design Document: System Administrator (Admin)

| Field | Value |
|---|---|
| Document ID | GW-ADM-UC |
| Work item | GW-62 — Admin site use cases |
| Status | Draft — aligned with the user-supplied UC 9–22 |
| Owner | GuardWork thesis team |
| Human approver | Not recorded; formal approval remains pending |
| Last updated | 2026-10-19 |
| Primary actor | System Administrator (Platform Admin) |
| Source | User-revised Vietnamese use cases 9–20, requested UC 21, and separate background-task monitoring/triggering flows |

## 1. Scope and common conditions

This document describes the fourteen use cases requested by the user, preserving existing identifiers and business flows. UC-10 now covers background-task monitoring; UC-22 covers manual triggering so that UC-11–21 keep their identifiers. Technical prose is English under the repository documentation convention; Vietnamese titles are retained for traceability. Login, standalone job-detail viewing, and job closure are not separate use cases in this revision. Opening a job remains contextual navigation for hiding it, reading its history, or restoring public visibility.

All use cases require an authenticated System Administrator with Platform role `ADMIN`. This common condition also applies where an individual precondition only mentions target existence or status. Company membership roles do not independently grant Platform administration rights. Every operation must enforce authorization on the server.

Read operations do not change business state. Private evidence is available only within the authorized review context. Passwords, password hashes, tokens, and unrelated private communications must not be exposed.

These are draft product descriptions, not claims of implementation or approval of new architecture, dependencies, schemas, or API changes. Existing approved requirements remain authoritative. See [the Admin specification](spec.md) for UC 9–20 and UC-22 requirements and implementation boundaries. UC-21 is a draft addition in this document; its detailed requirements and implementation contract remain to be aligned and approved.

## 2. Use-case inventory

| User number | Stable ID | Vietnamese title | Existing requirement family |
|---|---|---|---|
| 9 | UC-09 | Giám sát hệ thống và lưu lượng truy cập | UC-ADM-05 / ADM-MON |
| 10 | UC-10 | Theo dõi tác vụ chạy nền | UC-ADM-06 / ADM-CRON |
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
| 22 | UC-22 | Kích hoạt tác vụ chạy nền | UC-ADM-06 / ADM-CRON |

Legacy `UC-ADM-*` workflow identifiers are retained as references, not reassigned to different capabilities. Report details extend report-list viewing; approval and rejection extend dossier viewing; ban and unban extend account viewing. Hide, posting history, and restoration of public visibility are available from an authorized job context without adding a standalone job-view use case here.

## 3. Use-case descriptions

### UC-09 — Monitor System Health and Traffic

- **Actor:** System Administrator.
- **Description:** Allow the Admin to monitor online users, requests per minute, error rates, and system health to maintain stable operation.
- **Pre-condition:** The Admin is viewing the system monitoring dashboard.
- **Post-condition:** System and traffic metrics are displayed visually without disrupting the primary transactional database.
- **Event flow:**
  - **Main flow:**
    1. The system automatically collects hardware, memory, database connection, and user-traffic metrics from dedicated monitoring buffers.
    2. The system displays metrics and charts, including online-user counts and database connection health.
    3. The Admin observes resource changes in real time or selects time ranges to inspect trends.
  - **Alternative flows:**
    - **2a.** A resource metric exceeds a configured safety threshold, such as database connection overload. The dashboard immediately changes its warning color to draw the Admin’s attention.

### UC-10 — Monitor Background Tasks

- **Actor:** System Administrator.
- **Description:** Allow the Admin to view background tasks, their operational states, schedules, and execution history.
- **Pre-condition:** The Admin is authenticated and has system-management authority under the common conditions in Section 1.
- **Post-condition:** Task status and execution-history information are displayed successfully without changing task configuration or requesting execution.
- **Event flow:**
  - **Main flow:**
    1. The Admin opens background-task monitoring.
    2. The system displays the task list with operational states, schedules, and latest execution results.
    3. The Admin selects a task to view its details.
    4. The system displays task information and execution history, including start time, end time, and execution result.
  - **Alternative flows:**
    - **2a.** No tasks exist. The system displays an empty-list message.
    - **4a.** The task has no execution history. The system displays a no-execution-data message.
    - **4b.** An execution failed. The system displays the failure status and recorded error information.

### UC-11 — View Report List

- **Actor:** System Administrator.
- **Description:** Allow the Admin to view violation reports submitted by users.
- **Pre-condition:** The common conditions in Section 1 hold.
- **Post-condition:** Matching reports are displayed; report-processing states remain unchanged.
- **Event flow:**
  - **Main flow:**
    1. The Admin opens the report list.
    2. The system displays report summaries identifying the reported Company, Job Posting, or Candidate, complaint content, submission time, and current status.
    3. The Admin may filter by report status or reported-target type.
    4. The system displays matching results.
  - **Alternative flows:**
    - **2a.** No reports exist. The system displays an empty list.
    - **4a.** No reports match the filters. The system displays a no-results message.
    - **2b.** Data cannot be loaded. The system displays an error and permits retry.

### UC-12 — View Report Details

- **Actor:** System Administrator.
- **Description:** Allow the Admin to inspect complaint content, evidence, and related information for a violation report.
- **Pre-condition:** The common conditions hold; the report exists and is within the authorized access scope.
- **Post-condition:** Report details are displayed; viewing does not automatically apply sanctions or change the processing outcome.
- **Event flow:**
  - **Main flow:**
    1. The Admin selects a report from the list.
    2. The system retrieves complaint content, permitted reporter information, the reported target, evidence, and current status.
    3. The system displays an existing processing outcome if the report has already been handled.
    4. The Admin inspects the information and may return to the list.
  - **Alternative flows:**
    - **2a.** The report does not exist or is inaccessible. The system refuses to display it.
    - **2b.** An evidence attachment is unavailable. The system informs the Admin and displays the remaining retrievable information.

### UC-13 — Hide Job Posting

- **Actor:** System Administrator.
- **Description:** Allow the Admin to forcibly hide a violating Job Posting from public access.
- **Pre-condition:** The target posting is currently public (`PUBLISHED`).
- **Post-condition:** The posting becomes `SUSPENDED` and is no longer public; its content and history remain available for authorized tracing.
- **Event flow:**
  - **Main flow:**
    1. The Admin selects Hide Job Posting.
    2. The system requests a reason.
    3. The Admin enters the reason and submits the request.
    4. The system checks authority and the posting’s current state.
    5. The system changes the posting to `SUSPENDED` and records the actor, time, and reason.
    6. The system removes the posting from public display, search results, and related caches, and records the audit entry.
  - **Alternative flows:**
    - **3a.** No reason is supplied. The system requests one.
    - **4a.** The posting is no longer `PUBLISHED`. The system refuses the action and reports an invalid state.
    - **6a.** A Candidate follows the hidden posting’s link. The system indicates that the posting is suspended or unavailable.

### UC-14 — View Posting History

- **Actor:** System Administrator.
- **Description:** Allow the Admin to inspect recorded publication, status-change, and administrative events for a Job Posting.
- **Pre-condition:** The common conditions hold; the Admin is viewing the posting and is authorized to read its history.
- **Post-condition:** History is displayed without changing events or posting content.
- **Event flow:**
  - **Main flow:**
    1. The Admin selects View Posting History.
    2. The system retrieves the recorded history events.
    3. The system displays each event’s time, actor, action, status, and reason where available.
    4. The Admin inspects the history and returns to the posting.
  - **Alternative flows:**
    - **2a.** No history is available. The system displays a no-data message.
    - **2b.** History cannot be retrieved. The system reports an error rather than displaying an empty history.

### UC-15 — View Company Verification Dossier

- **Actor:** System Administrator.
- **Description:** Allow the Admin to inspect Company information, legal documents, and submission history for verification review.
- **Pre-condition:** The common conditions hold; the Company dossier exists.
- **Post-condition:** Dossier information is displayed; verification status is unchanged.
- **Event flow:**
  - **Main flow:**
    1. The Admin opens the Company verification list.
    2. The system defaults to `PENDING` dossiers, with pagination and ascending creation-time order.
    3. The Admin selects a dossier.
    4. The system displays Company name, tax code, stored contact information, verification status, and any existing outcome and rejection reason.
    5. For the enhanced onboarding journey, the system displays retained submissions, work address, submitter position, representation, declaration, and legal evidence.
    6. The Admin may download documents for inspection and choose Approve or Reject if the dossier is `PENDING`.
  - **Alternative flows:**
    - **2a.** The Admin filters by `VERIFIED`, `REJECTED`, or `ALL`. The system displays the corresponding results.
    - **2b.** No dossiers match. The system displays an empty list.
    - **5a.** A legacy dossier has no retained-document history from enhanced onboarding. The system displays only actually stored data.
    - **6a.** A document is missing or inaccessible. The system refuses the download.
    - **6b.** The dossier has already been reviewed. The system displays its outcome and does not offer another decision in its current state.

### UC-16 — Approve Company Verification Dossier

- **Actor:** System Administrator.
- **Description:** Allow the Admin to accept a dossier and confirm that the Company is verified.
- **Pre-condition:** The dossier is awaiting review (`PENDING`).
- **Post-condition:** The dossier becomes `VERIFIED`; a decision notification is to be sent to the user who submitted the verification request, subject to the delivery scope in Section 4.
- **Event flow:**
  - **Main flow:**
    1. Within the dossier, the Admin inspects the submitted information and documents.
    2. The Admin selects Approve.
    3. The system checks authority, existence, `PENDING` status, and dossier version.
    4. The system changes verification status to `VERIFIED`.
    5. The system records the audit entry in the same transaction as the decision.
    6. The system returns the result and the interface reloads the dossier list.
  - **Alternative flows:**
    - **3a.** The dossier does not exist. The system reports not found.
    - **3b.** The dossier is no longer `PENDING`. The system refuses the decision with an invalid-state error.
    - **3c.** The version differs or a concurrent update occurs. The system returns a conflict and does not overwrite the decision.

### UC-17 — Reject Company Verification Dossier

- **Actor:** System Administrator.
- **Description:** Allow the Admin to reject an unsuitable dossier and retain explanatory feedback.
- **Pre-condition:** The common conditions hold; the dossier is awaiting review (`PENDING`).
- **Post-condition:** The dossier becomes `REJECTED` with the rejection reason; a decision notification is to be sent to the submitting user, subject to the delivery scope in Section 4.
- **Event flow:**
  - **Main flow:**
    1. Within the dossier, the Admin selects Reject.
    2. The Admin enters a rejection reason.
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

- **Actor:** System Administrator.
- **Description:** Allow the Admin to inspect identifying information and current status for a user account or Company for governance purposes.
- **Pre-condition:** The target account exists.
- **Post-condition:** Information is displayed without changing the target’s status or access rights.
- **Event flow:**
  - **Main flow:**
    1. The Admin searches for and selects the target.
    2. The system identifies whether the target is a user account or Company.
    3. The system displays permitted identifying information, current status, and ban information where available.
    4. The Admin may select Ban or Unban when their conditions are met.
  - **Alternative flows:**
    - **2a.** The target is missing or inaccessible. The system refuses to display it.
    - **3a.** Data cannot be loaded. The system displays an error and permits retry.
- **Constraint:** Passwords, password hashes, tokens, and unrelated private communications must not be displayed.

### UC-19 — Ban Account

- **Actor:** System Administrator.
- **Description:** Allow the Admin to suspend platform access for a violating user account or Company.
- **Pre-condition:** The target exists and is active (`ACTIVE` for a user, or the corresponding non-banned Company state).
- **Post-condition:** The target is banned; related sessions are revoked under the enforcement policy; the action is recorded in the audit trail.
- **Event flow:**
  - **Main flow:**
    1. Within account information, the Admin selects Ban Account.
    2. The Admin enters a reason and selects a temporary or permanent ban.
    3. The system checks authority, target, current state, and ban duration.
    4. The system records the banned state, reason, and duration.
    5. The system revokes related access sessions.
    6. The system records the audit entry and reports the result.
  - **Alternative flows:**
    - **3a.** The Admin attempts to ban their own account. The system refuses the action.
    - **3b.** The reason is missing or the duration is invalid. The system requests corrected input.
    - **3c.** The target is already banned. The system reports its current state.
    - **4a.** The target is a Company. The system also suspends its public postings, revokes related recruiters’ sessions, and hides the Company from public search.
    - **5a.** Required session revocation cannot be completed. The system does not confirm a successful ban.

### UC-20 — Unban Account

- **Actor:** System Administrator.
- **Description:** Allow the Admin to restore platform access for a banned account or Company.
- **Pre-condition:** The target is banned (`BANNED` for a user, or the corresponding banned Company state).
- **Post-condition:** The active ban ends, active status and the applicable account permissions are restored, and the user must sign in again.
- **Event flow:**
  - **Main flow:**
    1. Within the banned account’s information, the Admin selects Unban.
    2. The Admin enters an unban reason and submits the request.
    3. The system checks authority and the current ban state.
    4. The system ends the ban and restores the applicable active state.
    5. The system ensures that sessions issued before or during the ban cannot be reused.
    6. The system records the audit entry and reports the result.
  - **Alternative flows:**
    - **2a.** No reason is supplied. The system requests one.
    - **3a.** The target does not exist or is no longer banned. The system refuses the action and reports the current state.
    - **5a.** Invalidation of old sessions cannot be ensured. The system does not confirm a successful unban.

### UC-21 — Republish Hidden Job Posting

- **Actor:** System Administrator.
- **Description:** Allow the Admin to restore public visibility of a Job Posting previously hidden through administrative moderation after the hiding reason has been resolved.
- **Pre-condition:** The posting is currently suspended (`SUSPENDED`).
- **Post-condition:** The posting returns to `PUBLISHED` and becomes publicly visible again; the time and reason are recorded. Posting content, Applications, and prior hiding history are preserved.
- **Event flow:**
  - **Main flow:**
    1. The Admin reviews the posting content, hiding reason, and moderation history, then selects Republish Hidden Job Posting.
    2. The system requests a restoration reason.
    3. The Admin enters the reason and submits the request.
    4. The system checks authority, the posting’s current state, and publication eligibility.
    5. The system changes the posting to `PUBLISHED` and records the actor, time, and reason while preserving the prior hiding history.
    6. The system restores public visibility, updates relevant search results and caches, and records the audit entry.
    7. The system reports the result and displays the posting’s updated status.
  - **Alternative flows:**
    - **3a.** No reason is supplied. The system requests one.
    - **4a.** The posting does not exist, the actor lacks authority, or the posting’s state has changed. The system refuses the action without overwriting the current changes.
    - **4b.** The hiding reason remains unresolved or the posting does not meet publication eligibility conditions. The system refuses the action and explains the unmet condition.
    - **6a.** A mandatory visibility update or audit step cannot be completed. The system reports an error and does not confirm successful republication.

### UC-22 — Trigger Background Task

- **Actor:** System Administrator.
- **Description:** Allow the Admin to manually request execution of a background task without waiting for its periodic schedule.
- **Pre-condition:** The Admin is authenticated, has system-management authority under the common conditions in Section 1, and the target task exists.
- **Post-condition:** The execution request is accepted, queued for asynchronous processing, and attributed to the triggering Admin.
- **Event flow:**
  - **Main flow:**
    1. The Admin opens the background-task list.
    2. The system displays tasks and their operational states.
    3. The Admin selects a task and requests execution.
    4. The system checks the task’s execution conditions.
    5. The system accepts the request, queues the task for asynchronous processing, and records the triggering Admin.
    6. The system confirms that the execution request has been accepted. Acceptance does not mean execution is already running or complete.
  - **Alternative flows:**
    - **4a.** The task is running and concurrent execution is not permitted. The system rejects the request and informs the Admin that the task is being processed.
    - **4b.** The task does not meet execution conditions. The system explains the reason and does not accept the request.
    - **5a.** The system cannot queue the task. The system reports triggering failure and records the error.

## 4. Interpretation and implementation boundaries

- **Publication spelling:** The supplied UC 13 precondition says `PUBLISHING`, while its alternative 4a says `PUBLISHED`. This document consistently uses `PUBLISHED`, matching the existing Admin lifecycle; it does not introduce a new status or migration.
- **Restoration boundary:** UC-21 reverses administrative hiding only. It does not resume a Company-paused posting, reopen a closed/expired posting, approve an unreviewed content revision, or lift another enforcement restriction. The Job Publishing Draft models Platform Removal as an independent enforcement condition, whereas this Admin draft uses `SUSPENDED`; reconcile that representation, publication eligibility, concurrency, and search/cache recovery with UC-21 in the Admin specification before implementation. No API, schema, or new state is selected here.
- **Task acknowledgement:** UC-22 confirms acceptance only after the request is queued and the triggering Admin is recorded. A queued task is not claimed to be running or complete before execution begins.
- **Task scope:** UC-10 is read-only monitoring; UC-22 is manual triggering. Editing schedules and pausing/resuming tasks are outside these supplied flows.
- **Company enforcement state:** User status `ACTIVE/BANNED` and Company flag `is_banned` express the corresponding enforcement states; a Company does not acquire a new user-status column through this document.
- **Restored permissions:** UC 20 restores access permitted by existing roles, membership, and verification state. It does not grant a new role, verify a Company, or automatically republish suspended postings.
- **Verification compatibility:** The concise UC 16–17 flows do not remove the approved reviewer/time, `expectedVersion`, version-increment, or atomic audit requirements. Both decisions still require the current version. Non-pending state and a stale version remain distinct failures; the current backend checks non-pending state first.
- **Decision delivery:** Notifications are a required outcome, not a promise of immediate mailbox arrival. Enhanced retained submissions currently create an email delivery intent addressed to the submitter in the decision transaction; a worker sends after commit and retries. Delivery failure does not undo a committed decision. Legacy URL-only dossiers without a retained submission do not currently create that intent. The remaining legacy notification gap is not hidden by this specification.
- **Dossier presentation:** Existing responses derive `processingStatus = CHECKED` for both `VERIFIED` and `REJECTED`; `CHECKED` does not itself mean approved. The interface reloads the list after a successful decision, but currently reports errors without automatically reloading on conflict.
- **Read-only report scope:** UC 11–12 do not begin review, acquire a review lock, resolve/reject a report, or apply a sanction. Existing outcomes may be displayed. Former report-resolution requirements are outside this revision’s selected scope, not silently removed from the wider product.
- **Coverage and delivery:** Login, standalone job viewing, job closure, report adjudication, and audit-log search are outside this fourteen-case inventory. Audit recording remains required for the mutations described here. Monitoring and custom task-management delivery retain their former P2/P3 planning labels; inclusion here does not promise MVP delivery.
- **Current implementation:** Company verification, retained dossier history/private documents, decision audit, and enhanced-submission email intents have implementation support. This document does not claim implemented Admin report, job moderation/history, account governance, monitoring-dashboard, or task-management APIs. Existing scheduled delivery workers are not an Admin task-management interface.
- **Unselected infrastructure:** Dedicated monitoring buffers, search/cache removal, asynchronous execution, and session revocation describe required effects. They do not select Redis, Kafka, Elasticsearch, read replicas, or a scheduler as new approved architecture.

## 5. Related sources

- [Aligned Admin requirements](spec.md)
- [Existing RBAC matrix](rbac_matrix.md)
- [Company verification reference](company_verification.md)
- [Approved Company verification requirements](../../software-requirements/rec/company-verification-spec.md)
- [Approved Authentication requirements](../../software-requirements/auth/authentication-authorization-spec.md)
- [Confirmed onboarding implementation contract](../../software-requirements/rec/company-onboarding-implementation-contract.md)
- [Current onboarding implementation and delivery limitations](../../backend/employer-onboarding.md)

The older RBAC matrix describes inherited Platform/Company privileges, and the verification reference describes wider checks and infrastructure. This revision does not amend those documents or override the approved separation of Platform authority from Company membership. Private-field visibility, monitoring thresholds/history retention, scheduler operations, search/cache integration, and enforcement infrastructure still need concrete approved contracts before implementation.
