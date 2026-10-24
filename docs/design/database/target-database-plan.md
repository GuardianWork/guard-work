# GuardWork target database plan Draft

| Field | Value |
| --- | --- |
| Design ID | GW-DB-TARGET-01 |
| Status | Draft proposal for review; not an approved software requirement |
| Owner | GuardWork thesis team |
| Human approver | Not assigned; explicit approval required before schema or contract implementation |
| Updated | 2026-10-24 |
| Scope | Current persistence plus Candidate, Recruiter and Staff planned use cases |
| Implementation impact | Documentation and PlantUML only; no migration, runtime, dependency or API change |

The target database supports the planned hiring journey from account creation to hiring confirmation, with governance and privacy operations. Extend the existing PostgreSQL schema in reviewed increments. Preserve existing identity keys and the separation between platform roles and Company membership roles. Recruitment submissions use immutable content versions rather than pointers to mutable CV or job content.

There are 18 migrated application tables. This proposal adds 34 potential tables for a target inventory of 52, including conditional extensions and late operational features. This is a review inventory, not a requirement to implement 34 tables at once. Each proposed table has a purpose, source and delivery gate below. A use case can be a query over existing tables; it does not automatically require a new table. The current-state `3.4.*.puml` files remain separate from [the proposed target ERD](target-database.puml).

The user narrowed this target design on 2026-10-19: Interview is tracked only as an Application status and status-history events. Dedicated Interview scheduling, time slots, Candidate schedule responses and Interview reminders are excluded from this proposal. Offer and Hiring Confirmation remain in scope. The source Interview Draft is retained as wider planning context; this scoped design does not rewrite it or claim full Interview use-case delivery.

## Source authority and coverage

| Source | Use in this plan |
| --- | --- |
| [Flyway migrations](../../../backend/src/main/resources/db/migration) and [backend guide](../../../backend/README.md) | Physical current schema, types, existing constraints and current commands. |
| [Approved authentication](../../software-requirements/auth/authentication-authorization-spec.md) | Official User identity and platform role boundary. |
| [Company Membership MVP](../../software-requirements/rec/company-membership-mvp-spec.md) | Confirmed current membership contract and explicitly limited scope. |
| [Employer onboarding contract](../../software-requirements/rec/company-onboarding-implementation-contract.md) and [ADR 0005](../../architecture/decisions/0005-staged-employer-onboarding.md) | Current pending registration, personal proof, retained legal evidence and decision delivery. |
| [Candidate use cases](../../feat/candidates/usecase.md) and [Candidate Draft](../../feat/candidates/spec.md) | UC-CAN-01 through UC-CAN-18; proposed future requirements, including their unresolved conflicts. |
| [Recruiter capability map](../../feat/recruiters/recruiter-capability-map.md) | UC-REC-01 through UC-REC-23, priorities and deferred discovery capabilities. |
| [Job Publishing Draft](../../feat/recruiters/job-publishing-spec.md) | Job assignments, separate revisions, moderation and publication. |
| [Application Pipeline Draft](../../feat/recruiters/application-pipeline-spec.md) | Snapshots, transitions, reapplication, notes, sensitive access and retention dependencies. |
| [Interview and Offer Draft](../../feat/recruiters/interview-offer-spec.md) | Offer responses and separate Hiring Confirmation; dedicated Interview scheduling is excluded by the selected target-design scope. |
| [Staff use cases](../../feat/staff/usecase.md) and [Staff Draft](../../feat/staff/spec.md) | UC-09 through UC-22; UC-21 restoration is in the use-case document but awaits alignment in the detailed specification. |

The design makes proposals where those documents do not select physical storage. It does not promote Draft requirements to Approved. Earlier Candidate infrastructure examples do not authorize Kafka, Redis, Elasticsearch, S3 or MinIO. Retain the existing Java/Spring/PostgreSQL/Flyway stack; keep current Cloudinary CV behavior and PostgreSQL legal documents until an explicitly approved change.

## Decisions requiring reconciliation

| Gate | Conflict or missing contract | Proposed planning direction | Implementation condition |
| --- | --- | --- | --- |
| G1 | Candidate Draft uses SUBMITTED/REVIEWED/INVITED_FOR_INTERVIEW/ACCEPTED; Recruiter Draft uses APPLIED/UNDER_REVIEW/SHORTLISTED/INTERVIEW/OFFER/HIRED/REJECTED/WITHDRAWN. | One canonical application status; use the richer Recruiter vocabulary in the proposed model and map Vietnamese labels in responses. ACCEPTED must not silently become HIRED. | Human approval of the shared status contract and revised conflicting Drafts. |
| G2 | Candidate Draft forbids all reapplication with lifetime UNIQUE(candidate,job); Recruiter Draft allows a new submission with one-use permission. | Keep history and one active application per Candidate/job; enforce one-use reapplication grants and idempotency. First/terminal resubmission eligibility must be explicit. | Approve one shared duplicate/reapplication rule before creating the unique indexes. |
| G3 | Staff uses PUBLISHED/SUSPENDED; Job Draft separates platform removal from publication lifecycle. | Store Company lifecycle independently from administrative restrictions. A public SUSPENDED label may be a derived projection. Restore only the addressed restriction; never resume paused/closed/expired jobs automatically. | Align UC-21 and Staff/Job requirements, including multiple restrictions and Company bans. |
| G4 | Staff/Candidate drafts use BANNED; users currently permits ACTIVE/SUSPENDED/DELETED. | Preserve current user status values and record explicit ban history/duration in enforcement_actions. Whether BANNED is a presentation label or new stored value remains undecided. | Approve state mapping, session invalidation, temporary-ban expiry and rollback semantics. |
| G5 | Candidate Draft declares UUID users and S3 CV URLs; current users/companies use BIGINT and CV metadata uses Cloudinary public_id. | Preserve existing BIGINT identity FKs and UUID Candidate/CV keys. Add immutable CV versions and server-validated ownership mapping. No public permanent CV URLs. | Approve new CV FK/version contract and legacy ownership repair before migration. |
| G6 | Retention/deletion periods and exceptions are unresolved; current evidence/audit triggers prohibit deletion. | Plan scoped consent evidence, privacy requests, retryable disposal items and optional holds. Immutable means protected from ordinary edits, not authorization for indefinite PII retention. | Human/legal policy approval and an approved controlled disposal/anonymization design; do not weaken existing triggers ad hoc. |
| G7 | Candidate describes notifications as Phase 2; Recruiter requires essential in-app notifications in P0. Existing outbox accepts only two membership events. | Preserve current membership behavior, then propose scoped extensions for required business events, resource routing and idempotent reminders. | Approve the shared notification contract and backward-compatible CHECK/DTO/worker changes. |
| G8 | Candidate use cases allow CV rename/replace/delete/default; current implementation supports registration/list/download only and has no CV owner FK. | Personal CV library plus immutable versions; application-held version is independently retained while policy allows. | Approve CV lifecycle/default/deletion rules and legacy data remediation. |
| G9 | Company-review six-month duplicate wording permits overwrite or editing; moderation is optional. | Keep one logical review workflow with optimistic versioning; decide time-window and review-history semantics first. | Approve review eligibility, duplicate/edit policy, moderation and retention; no automatic employer-experience inference. |
| G10 | Report adjudication is outside current Staff read-only selection; appeals have only a cross-cutting dependency. | Support report evidence/read models; reserve appeals as conditional. Opening a report never begins review or applies sanctions. | Separate approval for submission, resolution, appeal evidence and decision contracts. |
| G11 | Background task queue/scheduler and monitoring source/retention remain unselected. | Plan task registry/run records; monitoring consumes a dedicated source, not business-table scans. | Approve adapter, acknowledgement, concurrency, recovery and metric-source contracts. |
| G12 | Capability map lists ownership transfer P1, while approved membership milestone and trigger prohibit reassignment. | Preserve the current Owner contract. Transfer is a later design gate, not an ordinary Company update. | Approve transfer protocol, acceptance/cancellation/audit and replacement for the current trigger before adding transfer persistence. |
| G13 | Candidate Draft suggests AI masking, PUBLIC/HIDDEN/DRAFT discovery and storage infrastructure beyond current behavior. | Preserve current profile privacy and explicit-submission model; no AI processing or searchable talent pool is implied by adding a profile table. | Separate approval for profile visibility/discovery and sensitive-content processing. |

## Existing tables and proposed changes

Retain all migrated tables. The target diagram shows their key columns and proposed additions; the current-state dictionary remains the full 209-column baseline.

| Existing group | Tables | Target treatment |
| --- | --- | --- |
| Identity | users, refresh_tokens, password_reset_tokens | Preserve platform roles and IDs. Propose tokens_valid_after only under G4; access and refresh flows must enforce it so old access tokens do not revive after unban. Revoke refresh families transactionally. |
| Candidate | candidate_profiles, candidate_cvs | Preserve profile fields. Add a validated candidate_cvs.candidate_id FK to candidate_profiles, title, current_version_id and deleted_at after G5/G8. Preserve old public_id metadata during a compatible version backfill. |
| Company | companies, company_memberships, company_ownership_reviews, company_membership_audit_events, company_invitations | Preserve verification states, membership periods, Owner consistency and append-only guards. Do not change invitation roles or implement transfers as a side effect. |
| Platform audit | audit_logs | Preserve administrative audit contract. Company actors/sensitive file access use their dedicated histories rather than fabricating Platform Staff audit identities. Plan policy-controlled pseudonymization separately. |
| Employer onboarding | employer_registrations, employer_profiles, company_verification_documents, company_verification_attempts, company_decision_deliveries | Preserve pending-vs-official identity, proof, legal evidence, submitter-addressed decision delivery and derived CHECKED. Remediate the rejection_reason NULL gap only through a new approved migration and data audit. |
| Notifications | notification_outbox, user_notifications | Preserve existing columns and events. Under G7 propose scope_type, scope_reference and event_sequence, additional event types, idempotency identity and nullable Company scope for non-Company events. These additions are not authorized architecture changes yet. |

All existing BIGSERIAL keys remain BIGINT in referencing columns. New standalone recruitment records propose UUID; new composite join tables use parent key types. Use TIMESTAMPTZ for instants and retain the scheduling IANA zone where the user entered local time. Migrate candidate_cvs timestamps without time zone only after confirming the timezone used for existing values; never assume a UTC interpretation silently.

## Proposed table catalog

P0/P1/P2/P3 below are this proposed dependency/delivery order. Candidate Medium/Low use cases remain post-MVP under their own Draft; this plan does not reclassify them as approved MVP work. Conditional means the feature or policy is not defined sufficiently to implement. Fields are a review-level data dictionary, not executable DDL: NULL annotations describe permitted incomplete drafts or controlled purge paths, and final lengths/defaults/enum values require the corresponding contract review. Every unmarked reference is mandatory during an ordinary retained record's lifecycle; disposal-specific tombstones are governed by G6.

### Company and Candidate

#### company_public_profiles

Delivery: **P1**. Traceability: **UC-CAN-10**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `company_id` | `BIGINT` | PK FK companies.id |
| `description` | `TEXT` | Ordinary attribute |
| `industry` | `VARCHAR(150)` | Ordinary attribute |
| `size_band` | `VARCHAR(50)` | Ordinary attribute |
| `website` | `VARCHAR(512)` | NULL |
| `logo_storage_key` | `VARCHAR(512)` | NULL |
| `public_address` | `TEXT` | NULL |
| `updated_at` | `TIMESTAMPTZ` | Ordinary attribute |

One optional public profile per Company; never expose private legal dossiers.
#### candidate_cv_versions

Delivery: **P0**. Traceability: **UC-CAN-03..06; GW-AP-004/005**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `cv_id` | `UUID` | FK candidate_cvs.id |
| `version_no` | `INTEGER` | Ordinary attribute |
| `storage_key` | `VARCHAR(512)` | Ordinary attribute |
| `storage_provider` | `VARCHAR(30)` | Ordinary attribute |
| `file_name` | `VARCHAR(255)` | Ordinary attribute |
| `file_size` | `BIGINT` | Ordinary attribute |
| `content_type` | `VARCHAR(100)` | Ordinary attribute |
| `content_hash` | `VARCHAR(64)` | NULL for legacy backfill |
| `created_at` | `TIMESTAMPTZ` | Ordinary attribute |

UNIQUE(cv_id,version_no); immutable bytes per version; 0 < size <= 10485760. Deletion follows approved retention, not permanent immutable PII.

### Job publishing

#### job_categories

Delivery: **P0**. Traceability: **UC-CAN-07/08; GW-JP-012**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `code` | `VARCHAR(80)` | UK |
| `name_vi` | `VARCHAR(150)` | Ordinary attribute |
| `is_active` | `BOOLEAN` | Ordinary attribute |

Controlled Vietnamese industry/occupation labels; exact taxonomy requires product review.
#### job_postings

Delivery: **P0**. Traceability: **UC-REC-03..07/17; GW-JP-001..046**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `company_id` | `BIGINT` | FK companies.id |
| `created_by_membership_id` | `BIGINT` | FK company_memberships.id |
| `lifecycle_status` | `VARCHAR(30)` | Ordinary attribute |
| `published_revision_id` | `UUID` | NULL FK job_posting_revisions.id |
| `version` | `BIGINT` | Ordinary attribute |
| `created_at` | `TIMESTAMPTZ` | Ordinary attribute |
| `updated_at` | `TIMESTAMPTZ` | Ordinary attribute |

DRAFT/PUBLISHED/PAUSED/CLOSED/EXPIRED/ARCHIVED proposed from Job Draft. Published pointer must reference an approved revision of the SAME job; Company enforcement independently gates visibility.
#### job_posting_revisions

Delivery: **P0**. Traceability: **GW-JP-010..026; UC-CAN-09**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `job_id` | `UUID` | FK job_postings.id |
| `revision_no` | `INTEGER` | Ordinary attribute |
| `created_by_membership_id` | `BIGINT` | FK company_memberships.id |
| `moderation_status` | `VARCHAR(30)` | Ordinary attribute |
| `title` | `VARCHAR(200)` | NULL while draft |
| `description` | `TEXT` | NULL while draft |
| `responsibilities` | `TEXT` | NULL while draft |
| `required_qualifications` | `TEXT` | NULL while draft |
| `preferred_qualifications` | `TEXT` | NULL |
| `experience_min_years` | `NUMERIC(4,1)` | NULL |
| `experience_max_years` | `NUMERIC(4,1)` | NULL |
| `employment_type` | `VARCHAR(30)` | NULL while draft |
| `work_arrangement` | `VARCHAR(30)` | NULL while draft |
| `locations` | `JSONB` | structured address list; draft may be empty |
| `compensation_disclosed` | `BOOLEAN` | NULL while draft |
| `salary_min` | `NUMERIC(18,2)` | NULL |
| `salary_max` | `NUMERIC(18,2)` | NULL |
| `currency` | `VARCHAR(3)` | NULL |
| `pay_period` | `VARCHAR(30)` | NULL |
| `benefits` | `TEXT` | NULL while draft |
| `application_instructions` | `TEXT` | NULL while draft |
| `deadline_at` | `TIMESTAMPTZ` | NULL while draft |
| `deadline_time_zone` | `VARCHAR(100)` | NULL while draft |
| `headcount` | `INTEGER` | NULL while draft |
| `submitted_at` | `TIMESTAMPTZ` | NULL |
| `version` | `BIGINT` | Ordinary attribute |
| `created_at` | `TIMESTAMPTZ` | Ordinary attribute |
| `updated_at` | `TIMESTAMPTZ` | Ordinary attribute |

UNIQUE(job_id,revision_no); draft incompleteness is permitted. On submission validate required content, future deadline, min<=max, salary disclosure consistency and <=10 questions. Submitted revision content and children freeze; decisions use events.
#### job_revision_categories

Delivery: **P0**. Traceability: **UC-CAN-07/08**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `revision_id` | `UUID` | PK FK job_posting_revisions.id |
| `category_id` | `UUID` | PK FK job_categories.id |

Composite PK(revision_id,category_id); category membership freezes with its revision.
#### job_assignments

Delivery: **P0**. Traceability: **GW-JP-001..006; UC-REC-03/20**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `job_id` | `UUID` | FK job_postings.id |
| `membership_id` | `BIGINT` | FK company_memberships.id |
| `assigned_by_user_id` | `BIGINT` | FK users.id |
| `assigned_at` | `TIMESTAMPTZ` | Ordinary attribute |
| `ended_at` | `TIMESTAMPTZ` | NULL |
| `ended_by_user_id` | `BIGINT` | NULL FK users.id |

UNIQUE(job_id,membership_id) WHERE ended_at IS NULL. Validate same Company, active Recruiter membership; retain ended assignment periods and recheck current membership on access.
#### job_posting_events

Delivery: **P0**. Traceability: **UC-ADM-13/14/21; GW-JP-020..046**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `job_id` | `UUID` | FK job_postings.id |
| `revision_id` | `UUID` | NULL FK job_posting_revisions.id |
| `actor_user_id` | `BIGINT` | NULL FK users.id |
| `event_type` | `VARCHAR(50)` | Ordinary attribute |
| `from_state` | `VARCHAR(30)` | NULL |
| `to_state` | `VARCHAR(30)` | NULL |
| `reason` | `TEXT` | NULL only where allowed |
| `occurred_at` | `TIMESTAMPTZ` | Ordinary attribute |
| `sequence_no` | `BIGINT` | Ordinary attribute |

Append-only non-secret business history; UNIQUE(job_id,sequence_no); system actor must be distinguished from anonymous access; one terminal moderation decision per revision.
#### screening_questions

Delivery: **P0**. Traceability: **GW-JP-015/016/016A**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `revision_id` | `UUID` | FK job_posting_revisions.id |
| `position` | `INTEGER` | Ordinary attribute |
| `question_type` | `VARCHAR(30)` | Ordinary attribute |
| `prompt` | `TEXT` | Ordinary attribute |
| `is_required` | `BOOLEAN` | Ordinary attribute |

SHORT_TEXT/SINGLE_CHOICE/YES_NO/NUMERIC; UNIQUE(revision_id,position); position 1..10; freeze with revision, no automated hiring decision.
#### screening_options

Delivery: **P0**. Traceability: **GW-JP-015**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `question_id` | `UUID` | FK screening_questions.id |
| `position` | `INTEGER` | Ordinary attribute |
| `label` | `VARCHAR(500)` | Ordinary attribute |

UNIQUE(question_id,position); allowed only for SINGLE_CHOICE; freeze with revision.

### Applications

#### applications

Delivery: **P0**. Traceability: **UC-CAN-11..14; UC-REC-08/09/11/12/13/17**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `candidate_id` | `UUID` | FK candidate_profiles.id |
| `job_id` | `UUID` | FK job_postings.id |
| `submitted_revision_id` | `UUID` | FK job_posting_revisions.id |
| `status` | `VARCHAR(30)` | Ordinary attribute |
| `current_offer_id` | `UUID` | NULL FK offers.id; same Application only |
| `previous_application_id` | `UUID` | NULL FK applications.id |
| `reapplication_permission_id` | `UUID` | NULL UK FK reapplication_permissions.id |
| `idempotency_key` | `VARCHAR(100)` | Ordinary attribute |
| `request_hash` | `VARCHAR(64)` | Ordinary attribute |
| `version` | `BIGINT` | Ordinary attribute |
| `applied_at` | `TIMESTAMPTZ` | Ordinary attribute |
| `updated_at` | `TIMESTAMPTZ` | Ordinary attribute |

Proposal: UNIQUE(candidate_id,idempotency_key), partial UNIQUE(candidate_id,job_id) for active statuses; same idempotency key with another request hash fails. Terminal reapplication needs unused permission. Status vocabulary remains a cross-spec approval gate.
#### application_snapshots

Delivery: **P0**. Traceability: **GW-AP-003..005; UC-CAN-11/12**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `application_id` | `UUID` | PK FK applications.id |
| `source_cv_version_id` | `UUID` | NULL FK candidate_cv_versions.id |
| `submitted_content` | `JSONB` | NULL only after approved purge |
| `cv_storage_key` | `VARCHAR(512)` | NULL only after approved purge |
| `cv_storage_provider` | `VARCHAR(30)` | NULL only after approved purge |
| `cv_content_hash` | `VARCHAR(64)` | NULL only after approved purge |
| `cv_file_name` | `VARCHAR(255)` | NULL only after approved purge |
| `cv_content_type` | `VARCHAR(100)` | NULL only after approved purge |
| `cv_file_size` | `BIGINT` | NULL only after approved purge |
| `snapshot_schema_version` | `INTEGER` | Ordinary attribute |
| `captured_at` | `TIMESTAMPTZ` | Ordinary attribute |
| `purged_at` | `TIMESTAMPTZ` | NULL |

Capture intentional profile fields, cover letter, exact question/answer text and option identifiers. Validate answers against submitted revision. Stable immutable CV object version or independent copy must survive personal CV replacement; application CV access must not depend on a mutable library file. Approved privacy purge is the only controlled mutation exception.
#### application_status_history

Delivery: **P0**. Traceability: **UC-CAN-14; GW-AP-020..038**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `application_id` | `UUID` | FK applications.id |
| `sequence_no` | `BIGINT` | Ordinary attribute |
| `actor_user_id` | `BIGINT` | NULL FK users.id |
| `from_status` | `VARCHAR(30)` | NULL for first event |
| `to_status` | `VARCHAR(30)` | Ordinary attribute |
| `event_type` | `VARCHAR(40)` | Ordinary attribute |
| `reason_category` | `VARCHAR(80)` | NULL |
| `internal_reason` | `TEXT` | NULL |
| `candidate_feedback` | `TEXT` | NULL |
| `occurred_at` | `TIMESTAMPTZ` | Ordinary attribute |

UNIQUE(application_id,sequence_no); append status event and update current status atomically. Candidate projections exclude internal_reason. Private free text needs purge support under approved policy.
#### reapplication_permissions

Delivery: **P1**. Traceability: **GW-AP-040..043; UC-REC-13**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `previous_application_id` | `UUID` | FK applications.id |
| `granted_by_membership_id` | `BIGINT` | FK company_memberships.id |
| `reason` | `TEXT` | Ordinary attribute |
| `granted_at` | `TIMESTAMPTZ` | Ordinary attribute |
| `consumed_at` | `TIMESTAMPTZ` | NULL |
| `revoked_at` | `TIMESTAMPTZ` | NULL |

Candidate/job derived from previous application; at most one usable grant per prior application; consume and create replacement application in one transaction. One consumed grant cannot create two applications.
#### application_notes

Delivery: **P1**. Traceability: **GW-AP-050..053; UC-REC-10**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `application_id` | `UUID` | FK applications.id |
| `author_membership_id` | `BIGINT` | FK company_memberships.id |
| `version` | `BIGINT` | Ordinary attribute |
| `created_at` | `TIMESTAMPTZ` | Ordinary attribute |

Owner object for versioned private notes; no Candidate-facing exposure.
#### application_note_versions

Delivery: **P1**. Traceability: **GW-AP-052**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `note_id` | `UUID` | FK application_notes.id |
| `version_no` | `INTEGER` | Ordinary attribute |
| `text` | `TEXT` | NULL only after approved purge |
| `edited_at` | `TIMESTAMPTZ` | Ordinary attribute |
| `purged_at` | `TIMESTAMPTZ` | NULL |

UNIQUE(note_id,version_no); authors correct their own note by appending versions; approved deletion may erase note PII across all versions.

### Offer and Hiring Confirmation

#### offers

Delivery: **P1**. Traceability: **GW-IO-020..044; UC-REC-15**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `application_id` | `UUID` | FK applications.id |
| `created_by_membership_id` | `BIGINT` | FK company_memberships.id |
| `replaces_offer_id` | `UUID` | NULL FK offers.id |
| `status` | `VARCHAR(30)` | Ordinary attribute |
| `position_title` | `VARCHAR(200)` | NULL while draft |
| `compensation` | `NUMERIC(18,2)` | NULL while draft |
| `currency` | `VARCHAR(3)` | NULL while draft |
| `pay_period` | `VARCHAR(30)` | NULL while draft |
| `expected_start_date` | `DATE` | NULL while draft |
| `terms` | `TEXT` | NULL while draft |
| `benefits` | `TEXT` | NULL |
| `workplace_details` | `TEXT` | NULL |
| `attachment_storage_key` | `VARCHAR(512)` | NULL |
| `attachment_storage_provider` | `VARCHAR(30)` | NULL |
| `attachment_file_name` | `VARCHAR(255)` | NULL |
| `attachment_content_type` | `VARCHAR(100)` | NULL |
| `attachment_size` | `BIGINT` | NULL |
| `attachment_hash` | `VARCHAR(64)` | NULL |
| `sent_at` | `TIMESTAMPTZ` | NULL |
| `response_deadline_at` | `TIMESTAMPTZ` | NULL |
| `response_time_zone` | `VARCHAR(100)` | NULL |
| `previous_application_status` | `VARCHAR(30)` | NULL until send |
| `responded_at` | `TIMESTAMPTZ` | NULL |
| `version` | `BIGINT` | Ordinary attribute |
| `created_at` | `TIMESTAMPTZ` | Ordinary attribute |

Partial UNIQUE(application_id) WHERE status=SENT. Drafts incomplete; sent content freezes. Validate 24-hour..30-calendar-day deadline in scheduling zone. Attachment allowlist/limit/storage requires approval; accepted correction links a replacement and needs fresh acceptance.
#### offer_events

Delivery: **P1**. Traceability: **GW-IO-027..044/060**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `offer_id` | `UUID` | FK offers.id |
| `actor_user_id` | `BIGINT` | NULL FK users.id |
| `event_type` | `VARCHAR(40)` | Ordinary attribute |
| `reason` | `TEXT` | NULL |
| `occurred_at` | `TIMESTAMPTZ` | Ordinary attribute |

Record sending, Candidate response, expiry, withdrawal and exceptional correction without overwriting prior offers.
#### hiring_confirmations

Delivery: **P1**. Traceability: **GW-IO-050..054; UC-REC-16**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `application_id` | `UUID` | PK FK applications.id |
| `offer_id` | `UUID` | UK FK offers.id |
| `confirmed_by_membership_id` | `BIGINT` | FK company_memberships.id |
| `confirmed_at` | `TIMESTAMPTZ` | Ordinary attribute |

Offer must belong to same application and be its current ACCEPTED offer; insert, status HIRED, history and notification intent commit atomically; repeat returns existing result.

### Candidate extensions

#### saved_jobs

Delivery: **P1**. Traceability: **UC-CAN-15/16**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `candidate_id` | `UUID` | PK FK candidate_profiles.id |
| `job_id` | `UUID` | PK FK job_postings.id |
| `saved_at` | `TIMESTAMPTZ` | Ordinary attribute |

Composite PK(candidate_id,job_id); jobs retained rather than hard deleted, unavailable jobs remain removable bookmarks.
#### company_reviews

Delivery: **P2 conditional**. Traceability: **UC-CAN-17; EC-CAN-REV-01**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `candidate_id` | `UUID` | FK candidate_profiles.id |
| `company_id` | `BIGINT` | FK companies.id |
| `review_context` | `VARCHAR(30)` | Ordinary attribute |
| `rating` | `SMALLINT` | Ordinary attribute |
| `content` | `TEXT` | Ordinary attribute |
| `publication_status` | `VARCHAR(30)` | Ordinary attribute |
| `created_at` | `TIMESTAMPTZ` | Ordinary attribute |
| `updated_at` | `TIMESTAMPTZ` | Ordinary attribute |
| `version` | `BIGINT` | Ordinary attribute |

rating 1..5; interview/work contexts. Six-month duplicate handling and optional moderation must be approved. Rolling windows need transactional checks, not time-dependent unique-index predicates; editing/history policy remains open.

### Governance

#### abuse_reports

Delivery: **P1 conditional**. Traceability: **UC-ADM-11/12; UC-REC-21**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `reporter_user_id` | `BIGINT` | NULL FK users.id |
| `target_company_id` | `BIGINT` | NULL FK companies.id |
| `target_candidate_id` | `UUID` | NULL FK candidate_profiles.id |
| `target_job_id` | `UUID` | NULL FK job_postings.id |
| `category` | `VARCHAR(80)` | Ordinary attribute |
| `complaint` | `TEXT` | Ordinary attribute |
| `status` | `VARCHAR(30)` | Ordinary attribute |
| `existing_outcome` | `TEXT` | NULL |
| `submitted_at` | `TIMESTAMPTZ` | Ordinary attribute |
| `version` | `BIGINT` | Ordinary attribute |

Exactly one typed target FK is non-null; reporter must be authenticated at submission unless separately approved otherwise. Nullable reporter supports a future approved anonymization process, not anonymous reporting. List/detail reads never change status; report adjudication is outside current Staff selection.
#### report_evidence

Delivery: **P1 conditional**. Traceability: **UC-ADM-12; UC-REC-21**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `report_id` | `UUID` | FK abuse_reports.id |
| `uploaded_by_user_id` | `BIGINT` | NULL FK users.id |
| `storage_key` | `VARCHAR(512)` | Ordinary attribute |
| `storage_provider` | `VARCHAR(30)` | Ordinary attribute |
| `file_name` | `VARCHAR(255)` | Ordinary attribute |
| `content_type` | `VARCHAR(100)` | Ordinary attribute |
| `file_size` | `BIGINT` | Ordinary attribute |
| `content_hash` | `VARCHAR(64)` | Ordinary attribute |
| `created_at` | `TIMESTAMPTZ` | Ordinary attribute |

Private evidence with explicit ownership, file-validation and retention contracts; missing stored bytes must not make the report itself unreadable.
#### enforcement_actions

Delivery: **P0 contract gate**. Traceability: **UC-ADM-13/19/20/21; GW-JP-027**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `target_user_id` | `BIGINT` | NULL FK users.id |
| `target_company_id` | `BIGINT` | NULL FK companies.id |
| `target_job_id` | `UUID` | NULL FK job_postings.id |
| `actor_user_id` | `BIGINT` | FK users.id |
| `source_report_id` | `UUID` | NULL FK abuse_reports.id |
| `action_type` | `VARCHAR(40)` | Ordinary attribute |
| `reason` | `TEXT` | Ordinary attribute |
| `starts_at` | `TIMESTAMPTZ` | Ordinary attribute |
| `expires_at` | `TIMESTAMPTZ` | NULL for permanent |
| `ended_at` | `TIMESTAMPTZ` | NULL |
| `ended_by_user_id` | `BIGINT` | NULL FK users.id |
| `end_reason` | `TEXT` | NULL |
| `version` | `BIGINT` | Ordinary attribute |

Exactly one typed target; partial unique active action per target/type, with ended_at IS NULL. Expiry is processed transactionally. Projection to users.status / companies.is_banned and access-token invalidation requires approved contract. Lifting one restriction cannot undo another or resume Company-paused jobs.

### Privacy

#### consent_documents

Delivery: **P0 contract gate**. Traceability: **GW-AP-003; REQ-CAN-DATA-02**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `purpose_code` | `VARCHAR(80)` | Ordinary attribute |
| `version` | `VARCHAR(40)` | Ordinary attribute |
| `locale` | `VARCHAR(10)` | Ordinary attribute |
| `text` | `TEXT` | Ordinary attribute |
| `text_hash` | `VARCHAR(64)` | Ordinary attribute |
| `effective_at` | `TIMESTAMPTZ` | Ordinary attribute |

UNIQUE(purpose_code,version,locale); preserve exact consent wording. Recruitment and optional marketing are separate purposes; this does not establish a lawful basis or legal policy.
#### consent_events

Delivery: **P0 contract gate**. Traceability: **GW-AP-003/038; REQ-CAN-DATA-02/05**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `user_id` | `BIGINT` | NULL FK users.id |
| `document_id` | `UUID` | FK consent_documents.id |
| `application_id` | `UUID` | NULL FK applications.id |
| `action` | `VARCHAR(30)` | Ordinary attribute |
| `occurred_at` | `TIMESTAMPTZ` | Ordinary attribute |
| `evidence_reference` | `VARCHAR(255)` | NULL |

GRANTED/WITHDRAWN; append events with controlled pseudonymization, scoped to purpose/resource; do not store raw IP/device data without an approved necessity and retention contract.
#### privacy_requests

Delivery: **P1 contract gate**. Traceability: **GW-AP-038; REQ-CAN-DATA-05**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `subject_user_id` | `BIGINT` | NULL FK users.id |
| `request_type` | `VARCHAR(30)` | Ordinary attribute |
| `status` | `VARCHAR(30)` | Ordinary attribute |
| `requested_at` | `TIMESTAMPTZ` | Ordinary attribute |
| `completed_at` | `TIMESTAMPTZ` | NULL |
| `policy_version` | `VARCHAR(40)` | Ordinary attribute |
| `version` | `BIGINT` | Ordinary attribute |

Orchestrate withdrawal, erasure, export or consent actions only as approved. Request completion means every required database/object-storage action is confirmed or covered by an explicit exception.
#### privacy_request_items

Delivery: **P1 contract gate**. Traceability: **GW-AP-038; REQ-CAN-DATA-05**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `request_id` | `UUID` | FK privacy_requests.id |
| `resource_type` | `VARCHAR(60)` | Ordinary attribute |
| `resource_reference` | `VARCHAR(255)` | Ordinary attribute |
| `operation` | `VARCHAR(40)` | Ordinary attribute |
| `status` | `VARCHAR(30)` | Ordinary attribute |
| `attempt_count` | `INTEGER` | Ordinary attribute |
| `next_attempt_at` | `TIMESTAMPTZ` | NULL |
| `completed_at` | `TIMESTAMPTZ` | NULL |
| `error_category` | `VARCHAR(80)` | NULL |

UNIQUE(request_id,resource_type,resource_reference,operation); scoped non-FK work reference supports retry after deletion. Minimize and purge identifying storage references in completed work items.
#### sensitive_access_events

Delivery: **P0**. Traceability: **GW-AP-055/057; UC-REC-20**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `actor_user_id` | `BIGINT` | NULL FK users.id |
| `actor_membership_id` | `BIGINT` | NULL FK company_memberships.id |
| `application_id` | `UUID` | NULL FK applications.id |
| `offer_id` | `UUID` | NULL FK offers.id |
| `access_type` | `VARCHAR(40)` | Ordinary attribute |
| `outcome` | `VARCHAR(30)` | Ordinary attribute |
| `occurred_at` | `TIMESTAMPTZ` | Ordinary attribute |

Record individual CV view/download and Offer attachment access; no secrets, file bytes, or sensitive free text. Preserve only approved audit facts after pseudonymization.

### Operations

#### background_tasks

Delivery: **P3 contract gate**. Traceability: **UC-ADM-10/22**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `task_key` | `VARCHAR(100)` | PK |
| `display_name` | `VARCHAR(200)` | Ordinary attribute |
| `schedule_description` | `VARCHAR(255)` | NULL |
| `allows_concurrent_runs` | `BOOLEAN` | Ordinary attribute |
| `registered_at` | `TIMESTAMPTZ` | Ordinary attribute |

Registry/read model, not arbitrary executable commands. Schedule display only; task editing or pause/resume is out of selected scope. No scheduler technology chosen.
#### background_task_runs

Delivery: **P3 contract gate**. Traceability: **UC-ADM-10/22**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `task_key` | `VARCHAR(100)` | FK background_tasks.task_key |
| `triggered_by_user_id` | `BIGINT` | NULL FK users.id |
| `trigger_type` | `VARCHAR(30)` | Ordinary attribute |
| `status` | `VARCHAR(30)` | Ordinary attribute |
| `requested_at` | `TIMESTAMPTZ` | Ordinary attribute |
| `started_at` | `TIMESTAMPTZ` | NULL |
| `finished_at` | `TIMESTAMPTZ` | NULL |
| `error_category` | `VARCHAR(80)` | NULL |
| `lease_until` | `TIMESTAMPTZ` | NULL |
| `version` | `BIGINT` | Ordinary attribute |

Distinguish QUEUED/RUNNING/SUCCEEDED/FAILED, eligibility and recovery; acknowledge only persisted/actually queued work under the approved adapter contract. Serialize non-concurrent tasks via registry-row locking and recoverable leases; viewing schedules never mutates runs.
#### notification_preferences

Delivery: **P2 conditional**. Traceability: **UC-REC-22; GW-IO-061**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `user_id` | `BIGINT` | PK FK users.id |
| `event_group` | `VARCHAR(80)` | PK |
| `email_enabled` | `BOOLEAN` | Ordinary attribute |
| `updated_at` | `TIMESTAMPTZ` | Ordinary attribute |

Optional delivery copies only; cannot opt out of mandatory in-app business notifications. Integration with existing scoped outbox requires approval.

### Privacy extensions

#### legal_holds

Delivery: **Conditional**. Traceability: **Recruiter specs consent/retention dependency**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `subject_user_id` | `BIGINT` | NULL FK users.id |
| `application_id` | `UUID` | NULL FK applications.id |
| `policy_basis_reference` | `VARCHAR(255)` | Ordinary attribute |
| `imposed_by_user_id` | `BIGINT` | NULL FK users.id |
| `starts_at` | `TIMESTAMPTZ` | Ordinary attribute |
| `ends_at` | `TIMESTAMPTZ` | NULL |
| `released_at` | `TIMESTAMPTZ` | NULL |

No retention duration or legal exception is invented; actual legal hold scope and authority require human/legal approval. Granularity beyond an application is unresolved.

### Governance extensions

#### appeals

Delivery: **Conditional**. Traceability: **GW-JP-027; shared abuse/appeal dependency**.

| Column | Proposed type | Key or qualification |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `enforcement_action_id` | `UUID` | FK enforcement_actions.id |
| `appellant_user_id` | `BIGINT` | NULL FK users.id |
| `statement` | `TEXT` | Ordinary attribute |
| `status` | `VARCHAR(30)` | Ordinary attribute |
| `reviewer_user_id` | `BIGINT` | NULL FK users.id |
| `decision_reason` | `TEXT` | NULL |
| `submitted_at` | `TIMESTAMPTZ` | Ordinary attribute |
| `decided_at` | `TIMESTAMPTZ` | NULL |
| `version` | `BIGINT` | Ordinary attribute |

Reserved conditional extension; eligibility, evidence storage, authority, deadlines and review outcomes need a separate approved appeal specification.

## Relationships and consistency boundaries

1. **Official identity:** users has zero or one Candidate profile and zero or one employer profile. Profiles are capabilities/extensions, not mutually exclusive global role rows. Preserve the explicit new-applicant Candidate-route restrictions in the approved onboarding journey.
2. **Company authority:** users and companies relate through retained company_memberships. Keep the existing one-active-membership-per-User and one-declared-active-Owner constraints. A historical membership does not grant current access.
3. **CV ownership:** a Candidate profile has multiple logical CVs; each CV has immutable versions. The current_version_id must reference a version of that same CV, using a same-parent composite FK or deferred constraint. Current CV library changes never rewrite application_snapshots. CV quota/default checks serialize on the Candidate profile row; a partial unique default index additionally prevents two defaults among undeleted CVs. The delete/default fallback when the last CV is removed remains G8.
4. **Published content:** a Company has many jobs, each with revisions. published_revision_id must identify an APPROVED revision of that same job. Add UNIQUE(job_id,id) as the composite reference target if that implementation is approved. Store the initial job with a null pointer and set it after revision insertion; do not create an unsatisfiable mandatory FK cycle. Draft nullability does not permit incomplete submitted content.
5. **Assignment:** a job assignment references the exact membership period. Enforce job.Company = membership.Company, allowed role, current membership and active assignment on protected operations, with guard locks held until commit. Company Owner/Admin access does not require a Recruiter assignment. Losing the final Recruiter preserves the job and flags it for reassignment, derivable from current assignments.
6. **Application:** an Application references a Candidate, a job and its exact submitted revision. Composite references or deferred checks enforce revision.job = Application.job. A snapshot records exact intentionally submitted answers/content and immutable CV bytes. JSONB answers are a deliberate compact snapshot design: application code validates schema, types, required answers and question/option membership; PostgreSQL does not enforce an FK inside the JSON.
7. **Submission transaction:** recheck authenticated Candidate eligibility, active bans/memberships, current Published Revision, current CV ownership/version, future deadline, prior submissions and consent. Atomically insert Application, snapshot, first history event, consent evidence, any grant consumption and notification intents. The same idempotency key with the same request returns the existing result; a different payload using that key fails. File-byte retention/copy must be confirmed before success, with cleanup/retry for external storage failures; database transactions alone cannot atomically commit a Cloudinary copy.
8. **Application history:** one current status plus ordered transition events. Initial status and transitions follow the approved G1/G2 contract; internal rejection reasons and Candidate feedback are separate fields and response projections. Terminal WITHDRAWN cannot be reopened by Company actors. Closing or hiding a job never silently changes existing Application status.
9. **Reapplication:** a permission derives Candidate/job from a rejected/withdrawn previous Application. Serialize on the grant and relevant Candidate/job submission boundary; consume only in the successful replacement transaction. The new application points back to its predecessor and its one-use grant. Candidate and job must match across the chain; an initial Application must not fabricate a grant.
10. **Interview stage:** INTERVIEW is recorded in applications.status and application_status_history. An authorized status transition records actor, source/destination, time and applicable reason, with optimistic concurrency. No Interview object, selected slot, schedule response or calendar prerequisite is required by this scoped design. Any later scheduling feature requires a separate approved extension.
11. **Offer:** multiple Offers belong to an Application, with at most one SENT. applications.current_offer_id must reference an Offer of that same Application. Update this pointer deliberately when sending an eligible replacement; retain prior accepted/corrected Offers. Validate all required terms before send, preserve pre-offer Application state, and validate future response eligibility again at commit. Draft saves do not move the Application. Accepted Offer does not imply Hiring Confirmation.
12. **Hiring:** one Hiring Confirmation per Application, referring to its current ACCEPTED Offer and an authorized member. All linked Company/Application identities must match. HIRED transition, unique confirmation, status history and notification intent commit atomically.
13. **Enforcement:** XOR typed targets avoid unenforced universal target IDs. Ban duration and reason/history are explicit. Company ban and per-job hide can coexist; ending either cannot lift the other. Expiry cannot be implemented with a partial-index predicate containing now(); index unended restrictions, then explicitly end expired ones transactionally. Active-token revocation and later unban never reactivate old sessions.
14. **Evidence and privacy:** legal evidence, report evidence, CVs, Offer attachments, snapshots and note versions all have explicit access/deletion ownership. Do not introduce a blanket CASCADE from users that destroys audit or legal evidence. Add controlled purge, anonymization, tombstone and object-storage retry paths only after G6. No retention period, archive forever rule or automatic lawful exception is selected in this plan.
15. **Read models:** Candidate applied-job lists, saved-job lists, Staff report lists and job history are queries over owned records. Company review average is derived from visible reviews; maintain a cached aggregate only after performance evidence and an approved consistency rule.

## Required indexes and validation strategy

These are candidate indexes to validate against representative PostgreSQL queries, not an instruction to create all indexes immediately.

| Query or invariant | Proposed index or guard |
| --- | --- |
| Candidate CV library/default | candidate_cvs(candidate_id,created_at DESC); partial unique(candidate_id) WHERE is_default AND deleted_at IS NULL; serializing quota/default transaction. |
| CV version identity | candidate_cv_versions(cv_id,version_no) UNIQUE; same-CV current-version reference. |
| Public jobs | job_postings(company_id,lifecycle_status); published revision FK lookup; no direct reads of unapproved draft content. |
| Search/filter | job revision title/location/compensation predicates and category join indexes after query profiling. Consider PostgreSQL built-in full-text/GIN only after approved Vietnamese tokenization and performance checks; no extension or external engine selected. |
| Revision ordering | UNIQUE(job_id,revision_no); same-parent composite key UNIQUE(job_id,id); review queue by moderation_status/submitted_at. |
| Recruiter work queue | job_assignments(membership_id,job_id) WHERE ended_at IS NULL; unique active(job_id,membership_id). |
| Job history | UNIQUE(job_id,sequence_no); job_posting_events(job_id,occurred_at DESC); one APPROVED/REJECTED terminal review decision per revision. |
| Questions/options | UNIQUE(revision_id,position), question position 1..10; UNIQUE(question_id,position). |
| Candidate application list | applications(candidate_id,applied_at DESC,id); applications(job_id,status,applied_at,id). |
| Submission duplicates | G2-approved partial active UNIQUE(candidate_id,job_id); UNIQUE(candidate_id,idempotency_key); request_hash comparison. |
| One-use grant | UNIQUE applications(reapplication_permission_id) when non-null; lock the permission on consume; same Candidate/job checks. |
| Status ordering | UNIQUE(application_id,sequence_no); history index(application_id,occurred_at). |
| Offer work | offers(application_id,status); partial unique(application_id) WHERE status='SENT'. |
| Reminders/expiry | offers(response_deadline_at) WHERE status='SENT'; idempotent notification key includes resource/event/scheduled instant. |
| Hiring idempotence | hiring_confirmations application PK; offer unique reference; current accepted Offer check in transaction. |
| Bookmarks | saved_jobs(candidate_id,job_id) composite PK; candidate/saved_at listing index if needed. |
| Reports | abuse_reports(status,submitted_at,id) plus typed target indexes; report_evidence(report_id). |
| Ban lookup | separate partial active indexes for non-null typed targets; unique active target/action type; exact expiry/ended-state checks. |
| Consent and disposal | consent_events(user_id,document_id,occurred_at); privacy_requests(status,requested_at); privacy_request_items(status,next_attempt_at). |
| Task observation | background_task_runs(task_key,requested_at DESC); status/lease polling index; task registry row locks for non-concurrent runs. |
| Notification inbox | extend current recipient/id index and source-event uniqueness; extend event CHECK only under G7. |

Use NOT NULL and CHECK together for required reasons and coherent status-dependent fields. A CHECK that evaluates to NULL is not rejection: fix the existing rejected-verification reason gap only after examining legacy rows. Positive sizes, valid ranges, typed target XOR, nonnegative attempts and paired start/end timestamps are row checks. Counts across rows, same-parent relationships, current authorization and transition prerequisites need composite FKs, appropriate deferred constraints or serialized service transactions. Do not treat JPA validation or an unlocked count query as a concurrency guarantee.

## Use case traceability

Staff use-case IDs below are qualified as UC-ADM-09 etc. for clarity; the source's stable spelling is UC-09 etc. No identifiers are renamed in the authoritative use-case documents.

| Candidate use case | Target persistence or decision |
| --- | --- |
| UC-CAN-01 | users, candidate_profiles; consent_events/privacy_requests for specifically approved privacy actions. |
| UC-CAN-02 | Aggregate CV-management screen over candidate_cvs/candidate_cv_versions; no extra table. |
| UC-CAN-03 | candidate_cvs, candidate_cv_versions; current storage registration retained until G5/G8 approval. |
| UC-CAN-04 | CV title/current-version update; append immutable candidate_cv_versions. |
| UC-CAN-05 | Remove from personal library under approved deletion rules; retained application CV version/copy remains only while G6 permits. |
| UC-CAN-06 | CV is_default with partial unique index and atomic swap; last-CV rule under G8. |
| UC-CAN-07 | Published approved job_posting_revisions plus categories; no separate job-search-history table. |
| UC-CAN-08 | Typed revision compensation/work arrangement/experience fields and structured locations; category joins. |
| UC-CAN-09 | job_postings plus its approved published revision; current Company/restriction eligibility. |
| UC-CAN-10 | companies, company_public_profiles and currently visible Company jobs. |
| UC-CAN-11 | applications, application_snapshots, application_status_history, consent_events, notification intents. |
| UC-CAN-12 | Owned CV/current version captured into application_snapshots. |
| UC-CAN-13 | Candidate-scoped applications joined to retained job/revision references. |
| UC-CAN-14 | Application status/history with Candidate-safe feedback projection. |
| UC-CAN-15 | Insert/delete saved_jobs; idempotent composite key. |
| UC-CAN-16 | saved_jobs plus current job visibility; unavailable retained jobs stay removable. |
| UC-CAN-17 | company_reviews; eligibility, six-month edits and moderation under G9. |
| UC-CAN-18 | user_notifications; scoped outbox extension under G7. Suggested-job notification generation requires a separate approved matching policy. |

| Recruiter reference use case | Target persistence or decision |
| --- | --- |
| UC-REC-01 | Existing company_invitations/company_memberships and acceptance audit. |
| UC-REC-02 | Existing auth/session records and dynamically resolved Company context. |
| UC-REC-03 | job_assignments, job_postings and current membership guards. |
| UC-REC-04 | job_postings, job_posting_revisions, screening_questions/options and revision categories. |
| UC-REC-05 | Immutable revision submission and job_posting_events. |
| UC-REC-06 | New revision/review attempt; preserve rejected revision and prior decision. |
| UC-REC-07 | Job lifecycle/version, job_posting_events; archive only with no active process. |
| UC-REC-08 | applications and frozen snapshots within assigned-job scope. |
| UC-REC-09 | Current application status and ordered history, including INTERVIEW as a status only; supporting Offer records when required. |
| UC-REC-10 | application_notes and application_note_versions. |
| UC-REC-11 | Rejection history with separate internal category/notes and Candidate feedback. |
| UC-REC-12 | Reopen rejected Application with history only while G6 permits; never reopen WITHDRAWN. |
| UC-REC-13 | reapplication_permissions and a newly linked Application/snapshot. |
| UC-REC-14 | Scoped down to the INTERVIEW Application status and its transition history. Scheduling, proposed slots, Candidate schedule acceptance/decline, rescheduling and Interview reminders are excluded; this is not full delivery of the original scheduling use case. |
| UC-REC-15 | offers, offer_events and current Offer pointer. |
| UC-REC-16 | hiring_confirmations plus Application HIRED transition. |
| UC-REC-17 | Close job only; existing Application processing and Offers continue as permitted. |
| UC-REC-18 | Candidate discovery/talent pools explicitly deferred; no target implementation table. |
| UC-REC-19 | Proactive application invitations explicitly deferred; Company team invitations are a different existing concept. |
| UC-REC-20 | Frozen submitted CV access and sensitive_access_events; individual access only. |
| UC-REC-21 | abuse_reports/report_evidence under approved shared reporting contract. |
| UC-REC-22 | Mandatory inbox events; notification_preferences only for optional copies after G7. |
| UC-REC-23 | Derived job-scoped counts/transition durations; optional aggregate read model only if performance warrants it. View counts need a minimal collection/deduplication policy before claiming accurate statistics. |

| Staff use case | Target persistence or decision |
| --- | --- |
| UC-ADM-09 | Dedicated monitoring source/buffer contract; no primary business-table metric scans or new monitoring database technology selected. |
| UC-ADM-10 | background_tasks/background_task_runs; viewing never triggers a run. |
| UC-ADM-11 | Read-only abuse_reports list filtered by target/status. |
| UC-ADM-12 | Read-only report detail/evidence/outcome; no automatic adjudication. |
| UC-ADM-13 | enforcement_actions plus job_posting_events, current public visibility checks and atomic audit. |
| UC-ADM-14 | job_posting_events with revision/moderation/enforcement history; existing audit where appropriate. |
| UC-ADM-15 | Existing companies, retained verification attempts/documents, permitted private contacts; no fabricated legacy evidence. |
| UC-ADM-16 | Existing verification decision/reviewer/version/audit and enhanced company_decision_deliveries. |
| UC-ADM-17 | Existing rejection reason/attempt/audit/delivery; explicit NOT NULL checks after the remediation gate. |
| UC-ADM-18 | Permitted users/companies/membership state and enforcement history; exclude credentials/tokens. |
| UC-ADM-19 | enforcement_actions, status projections, session invalidation and audit under G4. |
| UC-ADM-20 | End addressed ban, retain history, require new sign-in; no automatic Company verification or job republication. |
| UC-ADM-21 | Lift addressed administrative job restriction, retain reason/history and re-evaluate eligibility under G3; detailed Staff spec alignment remains pending. |
| UC-ADM-22 | Queue eligible background_task_runs attributed to the authenticated Staff member, with concurrency guard and actual queue acknowledgement under G11. |

Ownership transfer, appeal adjudication, consent/retention policy, telemetry collection and recommendation generation are cross-cutting or conditional requirements rather than hidden extra use cases. Ownership transfer is tracked by G12 and has no proposed physical table yet: transfer acceptance/expiry/rollback and history must be specified first. Appeals/legal_holds are conditional table placeholders, not claims of complete approved workflows. Bulk rejection needs a preview/version-confirmation contract and can operate over applications/history without a dedicated bulk table unless recoverable asynchronous batch processing is later approved.

## Migration and delivery sequence

| Wave | Review and implementation scope after approval | Exit evidence |
| --- | --- | --- |
| 0 | Reconcile G1..G13, freeze the selected release scope, approve physical schema per feature, record consequential architecture decisions. G6/G7 block recruitment PII submission/shared notification rollout; operational extras need not block job drafting. | Named human approval, aligned requirements and ADRs; no schema implementation before this gate. |
| 1 | Audit current data and CV ownership, backfill CV version handles, add compatible CV FK/default/quota guarantees and public Company profile fields as selected. Preserve all 18 current tables. | Orphan/duplicate/timezone report; no guessed ownership or destructive cleanup; existing and new paths agree. |
| 2 | Job categories, job/revision/assignment/history/question tables, moderation and independent restriction model after G3 approval. Account governance follows its own G4 acceptance gate. | Published content cannot change while an edit is pending; same-Company assignments and concurrent revocation are proven. |
| 3 | Approved consent evidence plus applications/snapshots/status history and mandatory inbox extensions. Storage copy/retention consistency must be ready before accepting real submissions. | Duplicate/idempotent submissions, deadline race, CV ownership, immutable bytes, private reads and notification retry tests pass. |
| 4 | P1 notes/reapplication/Offer/hiring and selected post-MVP bookmarks/public Company details. G6 still governs all PII history/reopen behavior. | Status/Offer/confirmation races, exactly-once grant use, current accepted Offer, withdrawal and close-with-active-process scenarios pass. |
| 5 | Selected reports/account governance completion, privacy disposal work and optional copies/reviews. Scope decisions determine ordering; a stated P0 governance capability is not silently reclassified as optional. | Ban/session atomicity, controlled disposal, reports remaining read-only and review eligibility tests pass. |
| 6 | Operational P2/P3 metrics/task monitoring/manual trigger; optional appeal/legal-hold specifications and ownership-transfer design if selected. | Dedicated metric-source checks, task acceptance/recovery/concurrency evidence and separately approved extension contracts. |

Implement additive expand/backfill/validate/contract steps. Keep committed Flyway migrations immutable and allocate new versions using the repository convention; do not preassign a long sequence that collides with teammates' migrations. For large FK/constraint additions, consider NOT VALID then VALIDATE after data checks where PostgreSQL supports it; unique indexes require duplicate remediation rather than that shortcut. Keep compatibility columns until clients and code have migrated and explicit approval covers removal.

For candidate CVs, report orphan candidate_id values first. Determine ownership from authenticated records or a human-reviewed mapping; the current UUID alone is insufficient. Do not silently delete orphan CVs or attach them to a guessed User. Backfill immutable version handles and verify stored bytes/hashes where obtainable. Use legacy-null hashes as an explicitly tracked exception until integrity is verified. Repair multiple defaults before adding the partial unique index. Confirm timestamp interpretation before converting legacy CV timestamps.

Enforcement has an optional source_report FK while reporting is delivered later: either create report persistence in the same approved foundation wave or defer that nullable column/FK to the report wave. Applications/current Offer, job/published revision, and CV/current version introduce FK cycles: create parent tables with nullable pointers, add child tables and scoped constraints, then enable writes. Consent references to applications similarly require appropriate migration ordering; do not rely on diagram layout as creation order.

Current mock seeds are development fixtures. Add job/application/Offer demo data only when their respective migrations are approved and implemented, using valid histories and private synthetic content. Preserve separate test database isolation; target diagrams do not change production schema or broaden seed access.

## Acceptance and verification plan

- Migration tests against PostgreSQL 18 prove keys, same-parent references, partial uniqueness, conditional required fields, immutable revision children and controlled-purge rules. Legacy rows are tested before validation; no existing migration checksum changes.
- Concurrent requests prove a maximum of five personal CVs, one default, one active membership, one active application per approved contract, one pending/sent Offer, one-use reapplication and idempotent Hiring Confirmation.
- Job deadline vs submission, Company/membership/assignment revocation vs protected mutations, and Offer expiry vs response races commit one valid outcome or fail atomically. Mutable-state guards remain held until commit.
- Snapshot tests replace/delete personal CVs and edit jobs/profiles after submission, then confirm unchanged submitted CV bytes and content while policy permits retention. Approved purge removes those bytes and marks the tombstone without misleading successful-read responses.
- Privacy tests cover all versioned CV objects, snapshots, notes, Offer data, evidence, audit facts, legal holds, retrying external deletion and signed-link invalidation. Success cannot be claimed while required external deletion is pending. No blanket indefinite retention or unapproved legal exception is inferred.
- Security/controller tests exercise Candidate ownership, same-Company/assigned-job scope, immediate access loss, Platform Staff separation, token invalidation across ban/unban and Candidate-safe history projections. Old notification links recheck current authorization.
- Notification tests prove business transaction plus intent consistency, duplicate/retry idempotence, scheduled reminder identity and after-commit dispatch; delivery failure does not undo a committed business action.
- Staff tests prove report/task monitoring reads have no business side effects; manual-task acceptance distinguishes queued from running/completed, concurrent execution denial and recoverable failed leases.
- Query plans and representative data establish useful indexes and bounded lists/search. No performance or full-feature completion claim follows from static diagram validation.

The current changes are documentation only. Validate local source links, the 52-table inventory, every diagram reference and the complete Candidate/Recruiter/Staff use-case traceability. Runtime and migration tests belong to the approved feature implementations; they are not claimed as executed by this plan.
