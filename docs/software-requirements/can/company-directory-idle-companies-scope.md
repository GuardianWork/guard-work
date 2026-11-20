# Candidate Company Directory — Include Companies Without Live Postings

| Field | Value |
|---|---|
| **Related specification** | `company-review-api-spec.md` (GW-FEAT-CAN-COMPANY-REVIEW-01, v1.2.0, Approved) |
| **Proposed amendment** | v1.3.0 |
| **File** | `company-directory-idle-companies-scope.md` |
| **Date** | 2026-11-16 |
| **Owner** | Backend — Candidate Company Review |
| **Approver** | Miku (task-session approval, 2026-11-16) |
| **Status** | **Implemented — folded into `company-review-api-spec.md` v1.3.0** |

---

## 1. Problem

`GET /api/companies/public`, `GET /api/companies/public/{companyId}` and
`GET /api/companies/public/{companyId}/roles` currently treat "is publicly visible" as
"has at least one publicly visible job posting". The company row itself is never queried:

- `JobPostingRepository.PUBLIC_POSTING_FROM` drives both the directory
  (`searchPublicCompanyIds`, `GROUP BY c.id`) and the dossier/roles
  (`searchPublicJobIdsForCompanies`).
- `PublicCompanyService.visiblePostings` throws `40400 COMPANY_NOT_FOUND` when a company has
  no visible posting, and `PublicCompanyService.info` reads `postings.getFirst().company()`,
  so the dossier has no other data source.

Consequences:

1. A `VERIFIED`, non-banned employer with no live posting (never published, all drafts,
   all expired, all staff-suspended) is invisible in discovery and its dossier is `404`.
2. Verified employers disappear the moment their last posting expires, even though the
   company still exists and may publish again.
3. Existing approved requirements 01–08 lock this in
   (GW-CAN-COMPANY-02 "at least one publicly visible posting", GW-CAN-COMPANY-06
   "no visible posting ⇒ company-not-found"), so the behaviour is intentional, not a bug.

The user wants candidate-facing companies to be discoverable and openable even with no live
roles. This requires an approved requirement change, not a code fix.

## 2. Decision (recommended)

Split the two ideas that are currently conflated:

| Concept | Rule |
|---|---|
| **Publicly listable company** (new) | `companies.verification_status = 'VERIFIED'` AND `companies.is_banned = FALSE` |
| **Visible posting** (unchanged) | Existing `PUBLIC_POSTING_FROM` predicate: `PUBLISHED`, not staff-suspended, deadline absent or future, company verified and not banned |

- The directory lists publicly listable companies, whether or not they have visible postings.
- The dossier returns `200` for a publicly listable company, with a summary derived from its
  visible postings (empty summary when there are none).
- The roles endpoint returns `200` with an empty `PageResponse` for a publicly listable
  company with no visible postings.
- `40400 COMPANY_NOT_FOUND` is narrowed to: unknown id, or a company that is not publicly
  listable (unverified, rejected, banned, or not yet verified).
- No new company facts are exposed. Public identity stays `id` + `name`; `taxCode`, `email`,
  `registration_certificate_url`, `ownerUserId`, verification actors and reasons remain
  internal (GW-CAN-COMPANY-08 / BR-06 preserved).

Retrieval scope confirmed in task session (2026-11-16): idle companies are retrievable in the
**directory, dossier and roles** endpoints, i.e. the broadest option. Companies that are
`PENDING`, `REJECTED` or banned stay `404` everywhere.

### Alternatives considered

| Option | Verdict |
|---|---|
| **A. Companies-driven page + per-company visible posting aggregate** (recommended) | Only option that satisfies the whole scope (empty dossier/roles included) without inventing facts. Requires reshaping the directory query. |
| B. Keep postings-driven directory; only allow direct dossier URL for idle companies | Smaller change, but discovery still hides idle employers — does not solve problem 1. |
| C. Show idle companies as a synthetic posting group with `roleCount = 0` | Reuses the existing query shape but produces fake posting-derived rows and an unstable `GROUP BY` contract; rejected. |
| D. Sort directory by name only (drop "most roles first") | Simpler SQL, but silently changes the existing ordering rule; unnecessary. |

## 3. Requirement deltas

### 3.1 Functional requirements

| ID | Current | Proposed |
|---|---|---|
| GW-CAN-COMPANY-02 | Search returns companies with at least one publicly visible posting. | Search returns **publicly listable** companies, regardless of live postings. Includes companies whose postings are all absent, expired, draft, paused or staff-suspended. |
| GW-CAN-COMPANY-03 | Per-company summary derived from visible postings. | Unchanged, except that `roleCount` may be `0` and all derived collections/counters may be empty/zero. |
| GW-CAN-COMPANY-04 | Dossier returns one public company with summary from visible postings. | Dossier returns `200` for any publicly listable company; summary is empty when there are no visible postings. |
| GW-CAN-COMPANY-05 | Roles paginate visible roles. | Unchanged, plus: empty page for a publicly listable company with no visible postings (out-of-range pages must be empty, not an error). |
| GW-CAN-COMPANY-06 | Company with no visible posting ⇒ company-not-found. | **Replaced.** Only unknown companies and non-publicly-listable companies return `40400 COMPANY_NOT_FOUND`. |
| *new* GW-CAN-COMPANY-09 | — | The directory shall expose a company's public name from the company record, not only from posting content, so idle companies have a stable name. |

### 3.2 Business rules

| ID | Proposed rule |
|---|---|
| BR-08 (new) | A company appears in public company reads only when `verification_status = 'VERIFIED'` and `is_banned = FALSE`. |
| BR-09 (new) | `roleCount` equals the number of visible postings for the company and may be `0`; summaries with zero visible postings are `roleCount: 0`, empty collections, `disclosedSalaryCount: 0`, `headcountTotal: 0`, `deadlines: []`. |
| BR-10 (new) | Directory ordering is visible role count descending, then company name (case-insensitive) ascending, then id ascending. Idle companies therefore sort after companies with roles. |
| BR-01, BR-02 | Unchanged: they govern which **postings** and posting-derived facts are visible. BR-02's company clause remains consistent with BR-08. |
| BR-03 | Unchanged mechanics; for idle companies the keyword can only match the company name. |
| BR-04, BR-05 | Unchanged: summaries and role content still use *all* visible postings, never only the keyword-matching ones. |
| BR-06 | Unchanged and reinforced: no invented or mock company facts. |

## 4. API contract deltas

### 4.1 `GET /api/companies/public`

- Inclusion: publicly listable companies (BR-08).
- Ordering: BR-10.
- `totalElements` / `totalPages` now count eligible companies, not companies with postings.
- `summary.roleCount` may be `0`; other summary fields empty as in BR-09.
- `keyword` still matches (OR) company name, role title, role location, role industry. For a
  company with no visible postings, only the company name can match.
- Unchanged: `Cache-Control: no-store`, pagination normalisation, `PageResponse` envelope.

### 4.2 `GET /api/companies/public/{companyId}`

- `200` for any publicly listable company, including idle companies:

```json
{
  "data": {
    "id": 42,
    "name": "Example Logistics",
    "summary": {
      "roleCount": 0,
      "locations": [],
      "industries": [],
      "employmentTypes": [],
      "workArrangements": [],
      "salaryBands": [],
      "disclosedSalaryCount": 0,
      "headcountTotal": 0,
      "deadlines": []
    }
  }
}
```

- `404` `40400 COMPANY_NOT_FOUND` only for unknown / non-publicly-listable ids.
- `name` now comes from `companies.name`. This is already public through posting-derived
  responses, so no new disclosure.

### 4.3 `GET /api/companies/public/{companyId}/roles`

- `200` with an empty page for an idle publicly listable company:

```json
{
  "data": {
    "content": [],
    "pageNumber": 0,
    "pageSize": 20,
    "totalElements": 0,
    "totalPages": 0,
    "first": true,
    "last": true
  }
}
```

- `404` `40400 COMPANY_NOT_FOUND` only for unknown / non-publicly-listable ids.

### 4.4 Error catalogue

| Error Code | HTTP | Constant | Trigger after this change |
|---|---:|---|---|
| `40400` | `404` | `COMPANY_NOT_FOUND` | Unknown company id, or company not `VERIFIED`/`is_banned = TRUE`. |
| `40000` | `400` | `BAD_REQUEST` | Still reserved; pagination stays normalised. |

## 5. Implementation notes (not a design mandate)

- `PublicCompanyService` becomes the place that resolves the company: read the `companies`
  row (publicly listable predicate), then load visible postings for summaries.
- Directory query: companies-driven page, e.g. `companies c` with a lateral/aggregate over
  visible postings for `COUNT` and keyword existence, keeping `PUBLIC_POSTING_FROM`'s
  predicate as the single visibility source. Do **not** re-derive visibility SQL; extract a
  shared predicate fragment if the current `FROM ... WHERE` string cannot be reused as-is.
  A naive `LEFT JOIN` on postings would degrade to `INNER JOIN` because visibility
  predicates live in `WHERE`, so the count and keyword checks must run in a subquery/lateral.
- Fix `PublicCompanyService.roles`: `roles.subList(offset, Math.min(roles.size(), offset + size))`
  currently throws for any page beyond the last (`subList(from > to)`), e.g. `page=1` on a
  single-role company. Empty-page support requires a guarded offset clamp. This is an
  adjacent, verified defect on an existing endpoint; treat it as part of this change and add
  a regression test.
- `PublicCompanyService.info` / `dossier` must stop using `postings.getFirst()` for identity.
- No schema migration and no dependency change. A supporting index
  (`companies(verification_status, is_banned)` or a partial name index) is **not** required
  for correctness, is a schema change, and is out of scope unless separately approved.

## 6. Tests

Controller/integration (`PublicCompanyHttpTest`):

- directory includes a verified, non-banned company with zero postings, with
  `summary.roleCount = 0` and empty summary fields;
- directory excludes pending, rejected and banned companies regardless of postings;
- directory keyword matches an idle company by name and returns it once (no duplicates);
- directory ordering: role count desc, then name, then id;
- dossier of an idle company returns `200` with an empty summary and the company name;
- roles of an idle company returns `200` empty `PageResponse`;
- roles `page` beyond the last returns `200` empty content (regression for the subList defect);
- unknown id, pending company, banned company still return
  `40400 COMPANY_NOT_FOUND` on both dossier and roles;
- existing visible/hidden posting assertions (suspended, expired, unverified) unchanged;
- `Cache-Control: no-store` and anonymous access unchanged.

Seed: `backend/docker/dev-seed/seed.sql` already contains verified bulk companies; confirm
`DevelopmentJobSeedTest` expectations still hold and that the dev directory shows idle
companies as intended.

## 7. Documentation and compatibility

- `company-review-api-spec.md` → v1.3.0 with the deltas above, plus amendment history;
  requirement owners must approve before `Status: Approved`.
- `backend/README.md` §"Candidate Public Company Discovery and Dossier (GW-123)" currently
  states "at least one publicly visible job posting" and "companies without visible postings
  return `40400 COMPANY_NOT_FOUND`"; both sentences change.
- Client impact (must be confirmed with the GW-123 UI owner):
  - directory pages may contain entries with `roleCount = 0`;
  - dossier/roles no longer `404` for idle companies, so an empty state is required;
  - `totalElements` reflects eligible companies, not companies with roles.
  All are additive from a schema/serialisation standpoint, but they are observable behaviour
  changes and must be verified in the candidate UI.
- Security/privacy: no new fields, no new endpoints, anonymous access unchanged; the change
  only widens exposure from "verified employers with live jobs" to "verified employers",
  using already-public company names.

## 8. Out of scope

- Company addresses, headquarters, size, benefits, logos, reviews or any other profile facts.
- Company self-service profile fields or new company-management APIs.
- Personalising or re-ranking search (featured, boosted, paid placement).
- Hiding companies with only expired postings behind a separate flag/filter (candidate-facing
  filters for "has open roles" are a separate requirement).
- Schema migrations, new dependencies, notification changes.

## 9. Approval checklist

- [x] Approve replacing GW-CAN-COMPANY-02 and GW-CAN-COMPANY-06 as described.
- [x] Approve `404` narrowing (unknown / not publicly listable only).
- [x] Approve empty dossier + empty roles page (`200`) instead of `404`.
- [x] Approve directory ordering BR-10.
- [ ] Confirm the candidate UI handles `roleCount = 0` entries and empty states.
- [x] Approve the `roles` out-of-range `subList` fix as in-scope.
- [x] Confirm no index migration is required for now.
