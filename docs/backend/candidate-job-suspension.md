# GW-130 Candidate JD suspension integration

Requirement: [GW-SR-CAN-JOB-130](../software-requirements/can/job-suspension-candidate-spec.md).

`GET /api/candidate/applications` and `GET /api/candidate/applications/{id}`
retain Candidate-owned records during individual Staff JD suspension. Both add
`jobSuspended: boolean`, computed from the current Job Posting status, alongside
the unchanged Application `status`. Filtering and pagination count suspended-job
Applications normally. The separate Company-ban flag still conceals rows/details.

Show “Tin tuyển dụng đang bị tạm ngưng” beside the Application status when true.
During suspension, show only the job/company labels, application status/time and an
eligible Withdraw action. Summary/detail responses conceal submitted details, history,
feedback and CV metadata; `canViewCv` is false. The Application CV download route
returns the existing non-enumerating 404. Detail and withdrawal responses retain only
the minimal receipt fields needed to identify the Application and its current version.
Unrelated library CV management retains its existing authorization. Public JD links still
return non-enumerating 404; do not require public JD retrieval to open an Application.
Never render Staff reasons or recruiter-only history. Account and ownership checks remain.

`POST /api/candidate/applications/{id}/withdraw` keeps its `{version}` request,
optimistic conflict handling, terminal restrictions and idempotent retries. Suspension
alone does not block withdrawal. Withdrawal is the only available action on the affected
Application; existing terminal-state rules remain. Company-side Application listing/details/actions
remain inaccessible while the JD is suspended.

Staff hide/restore now sends `JOB_SUSPENDED`/`JOB_RESTORED` notices to all eligible
existing applicants, including rejected, withdrawn, hired and inactive Applications.
Pass `audience=CANDIDATE` to the existing inbox APIs and follow each notice's
`actionHref` (`/applications/{applicationId}`). Company notices continue to use
`RECRUITER` and `/companies/me/jobs/{jobId}`. Existing polling/retry behavior applies.
Notices describe an event; refresh the Application to determine its current suspension
flag. No push channel or past-event backfill is added. Restoration does not change
Application status or override deadline, Company, CV or account restrictions.

The API fields are additive; older clients can ignore them but cannot show the new label.
There is no Candidate UI or saved-job feature in this repository; this guide supplies
the integration contract for those clients.

## Migration and local restart

Apply `V2026.11.24.130__candidate_job_suspension_notifications.sql` through Flyway
before enabling Candidate job notices. It extends the outbox/inbox context checks to
require an Application reference on CANDIDATE JOB_SUSPENDED/JOB_RESTORED, preserving
RECRUITER shapes and retained rows. No columns, event types, dependencies or row rewrites.
An older database rejects the new intent and rolls back suspension/restoration.

Flyway runs when the application context starts. This project includes DevTools;
a classpath-triggered restart runs Flyway again, but saving a SQL file alone does not
invoke it. Rebuild/copy resources into `target/classes` and restart the backend, or
apply the same migration using Flyway directly. Never edit an applied migration or
change Flyway history manually. Existing signed CV URLs keep their original TTL;
suspension prevents issuing new Application CV URLs.
