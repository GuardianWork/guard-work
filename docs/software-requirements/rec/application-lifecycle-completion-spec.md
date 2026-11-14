# Application Lifecycle Completion

| Field | Value |
|---|---|
| Specification ID | GW-SR-APPLICATION-LIFECYCLE |
| Issue | GW-117 |
| Owner | Thinh |
| Approver | Thinh |
| Status | Draft metadata; human product and testing-boundary approval recorded below |
| Last Updated | 2026-11-10 |

## Problem Statement

Company Members can review and shortlist Applications but cannot communicate an interview stage or record a successful hire. Full Interview scheduling and Offer management would exceed the desired scope.

## Solution

Add INTERVIEWING and HIRED to Application Status. Interviews and hiring agreements occur outside GuardWork. GuardWork records progress, notifies Candidates, and preserves Application Status History. No scheduling or Offer module is required.

## User Stories

1. As an assigned Recruiter, I want to mark an Application INTERVIEWING, so that the Candidate knows it has progressed.
2. As a Company Owner, I want the same lifecycle actions within my Company, so that I can manage recruitment.
3. As a Candidate, I want a standard Vietnamese interview-stage notification, so that I know the Company will contact me externally.
4. As a Company Member, I want to arrange interviews externally, so that I need not enter times or locations in GuardWork.
5. As a Company Member, I want to record a hire directly from UNDER_REVIEW, so that I need not invent intermediate steps.
6. As a Company Member, I want to record a hire from SHORTLISTED or INTERVIEWING, so that the outcome reflects the actual process.
7. As a Company Member, I want explicit confirmation before recording a hire, so that I avoid accidental final decisions.
8. As a Candidate, I want notification of a recorded hire, so that I can see the outcome.
9. As a Candidate, I want my Application list and details to show the new statuses, so that I can track progress.
10. As a Company Member, I want to filter Applications by the new statuses, so that I can find current work and completed hires.
11. As a Company Member, I want to reject an Application before hiring, so that unsuccessful recruitment can conclude.
12. As a Candidate, I want to withdraw before hiring, so that I control my participation.
13. As a Company Member, I want hired Applications to retain accessible contact details, cover letters and CVs, so that I can follow up with the hire.
14. As a Candidate, I want that access limited to currently authorized Company Members, so that a prior assignment grants no permanent access.
15. As a Company Member, I want append-only history, so that status decisions remain attributable.
16. As a Company Member, I want concurrent decisions to reject stale writes, so that another person's decision is not overwritten.
17. As a Candidate, I want notification retries to avoid duplicate delivery, so that one action produces one notification.
18. As a Company Member, I want processing to continue after job closure or expiry, so that existing Applications can conclude.
19. As a Candidate, I want rejected and withdrawn Applications to retain their existing privacy restrictions, so that the hiring extension does not expose them.
20. As a Company Member, I want HIRED to be final, so that recorded outcomes cannot silently change.

## Implementation Decisions

- Extend the existing Application workflow, Company and Candidate reads, status filters, history and durable in-app notification mechanisms. Preserve SUBMITTED terminology and current role rules; Company ADMIN gains no access.
- Preserve existing transitions: SUBMITTED to UNDER_REVIEW or SHORTLISTED; UNDER_REVIEW to SHORTLISTED; each active status to REJECTED or WITHDRAWN through its authorized actor.
- Add UNDER_REVIEW or SHORTLISTED to INTERVIEWING. Add UNDER_REVIEW, SHORTLISTED or INTERVIEWING to HIRED. No direct SUBMITTED to INTERVIEWING or HIRED; no backward transitions.
- HIRED, REJECTED and WITHDRAWN are terminal. HIRED cannot be rejected, withdrawn, reopened or corrected in this release. Preserve current same-status retry and stale-version behavior; a retry is not a new transition.
- HIRED means the Company confirms that the Candidate agreed to join outside GuardWork. The approved additive request contract is optional Boolean `hiringConfirmed` on the existing versioned status mutation: HIRED requires Boolean `true`, including a same-status retry; any non-null value is invalid for another target. Omitted fields preserve existing requests. No in-app Candidate acceptance is required.
- INTERVIEWING signifies progression to externally arranged interviews; it does not assert that a meeting is scheduled or completed. Use standard Vietnamese notification content, with no custom message, times or locations. Suggested copy: “Hồ sơ của bạn đã chuyển sang vòng phỏng vấn. Nhà tuyển dụng sẽ liên hệ để trao đổi chi tiết.”
- Both new transitions append attributed history and notify the Candidate using existing atomic history/outbox and retry semantics. Notifications contain no CV, contact data or internal rejection details.
- HIRED retains Company access to submitted contact details, cover letter and CV under current live authorization. Account, email, membership, assignment, Company and suspended-job restrictions continue to apply. Removed or unassigned Recruiters retain no access by virtue of the hire.
- Preserve rejection/withdrawal privacy restrictions. Introduce no retention period, automatic deletion or modification of immutable Application Snapshots.
- Closed or expired Job Postings permit otherwise authorized processing of existing Applications; they do not alter Application Status.
- Reuse existing status mutation and read contracts where compatible. Extend accepted/returned enums and persistence constraints through reviewed additive changes; preserve historical rows and migrations. Thinh approved migration `V2026.11.10.104__application_hiring_confirmation.sql` to add HIRED to the Application status constraint, allow UNDER_REVIEW/SHORTLISTED/INTERVIEWING to HIRED in history, and add `APPLICATION_HIRED` to both notification event constraints. The Boolean field is additive and does not break existing callers; hiring-capable clients must send `hiringConfirmed: true` and handle HIRED as terminal. No dependency or other schema/API changes are approved.
- A hire notification uses event type `APPLICATION_HIRED` and standard Vietnamese copy: “Công ty đã xác nhận bạn đồng ý gia nhập. Nhà tuyển dụng sẽ liên hệ với bạn về các bước tiếp theo.” It contains no contact details, CV, or internal data. The notification link continues through live authorization.
- This approved product direction supersedes the earlier scoped deferral of interview-stage and hiring-result tracking and replaces the broader draft scheduling/Offer-dependent hiring plan for GW-117. Existing unrelated requirements remain in force.

## Testing Decisions

- Human-confirmed primary seam: existing Application HTTP integration tests with real Spring Security and PostgreSQL, supplemented by existing migration tests. Thinh explicitly confirmed this testing boundary on 2026-11-10.
- Verify observable HTTP responses and durable outcomes, not private methods: all allowed/forbidden transitions, confirmation validation, terminal guards, current-version no-op retries, stale conflicts, owner/assigned-Recruiter access, ADMIN exclusion and cross-Company isolation.
- Cover Company/Candidate lists, detail history and filters for both statuses; verify retained hired contact/cover-letter/CV access and immediate revocation after assignment or membership changes. Preserve rejected/withdrawn privacy regressions.
- Verify atomic status/history/outbox writes, Candidate recipient selection, safe notification content and retry deduplication using existing notification integration patterns.
- Use existing migration-test infrastructure for additive enum/constraint compatibility and preservation of existing statuses, snapshots and history.
- Verify closed/expired-job processing and suspended-job denial. Verify Vietnamese notification content through the existing presentation mechanism.

## Out of Scope

Interview scheduling, meeting records, calendar/video integration, interview response/completion tracking, custom interview messages, Offer drafts/sending/acceptance/decline/expiry/attachments, employment contracts, Candidate in-app hiring acceptance, backward corrections, reopening, reapplication, bulk actions, new retention policies, AI features, and changes to Typst.

## Further Notes

Thinh explicitly confirmed the product decisions through the conversation, including direct hiring from UNDER_REVIEW and retained hired-Application data access under live authorization. On 2026-11-10, Thinh also approved the concrete optional Boolean request field and additive HIRED/application-history/notification migration described above. Shared understanding was confirmed before this synthesis. Lifecycle metadata remains Draft under repository policy; this records human approval rather than an agent setting Approved.

This specification records the approved contract and migration for GW-117 implementation. The local Markdown tracker carries the delivery label; its testing boundary was explicitly confirmed by Thinh on 2026-11-10. No dependency or Typst change is included.
