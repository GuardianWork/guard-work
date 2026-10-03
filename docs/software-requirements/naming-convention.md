# Feature Naming & Management Convention

This guide defines the standard naming conventions for feature planning, brainstorming documents, software requirements specifications (SR), and related Git branches in the GuardWork project.

---

## 1. GuardWork Domain Prefixes

Features must be categorized using one of the standard GuardWork domain prefixes:

| Domain Prefix | Module / Category        | Scope & Examples |
|---------------|--------------------------|---|
| `AUTH`        | Authentication & Access  | Login, Registration, JWT, OAuth, RBAC |
| `CAND`        | Candidate & Profile      | Profiles, Skills, Experience, CV Management |
| `REC`         | Recruiter & Verification | Company profile, Business license verification |
| `JOB`         | Job Posting & Discovery  | Job creation, moderation queue, search filters |
| `APP`         | Applications & Hiring    | Application pipeline, interview scheduling, offers |
| `NOTIF`       | Notifications & Alerts   | Email notifications, push alerts, in-app updates |
| `ADM`         | Admin, Audit & Appeals   | Admin dashboard, moderation audit log, user appeals |

---

## 2. File Naming Rules (`.md`)

All brainstorm and requirement documents must use lowercase `kebab-case` prefixed with their domain:

- **Brainstorm Documents:**  
  `docs/software-requirements/brainstorm/<domain>/<short-feature-slug>.md`  
  *Examples:*
  - `docs/software-requirements/brainstorm/rec/license-verification.md`
  - `docs/software-requirements/brainstorm/cand/cv-parser.md`
  - `docs/software-requirements/brainstorm/job/search-filters.md`

- **Formal SR Specifications:**  
  `docs/software-requirements/<domain>/<short-feature-slug>-spec.md`  
  *Example:* `docs/software-requirements/rec/license-verification-spec.md`

---

## 3. In-Document Title & Stable ID Format

Inside each brainstorm or specification document, use a standardized title header and ID:

```markdown
# [GW-BS-REC-01] Employer License Verification
```

### ID Structure:
- `GW` = Project (**GuardWork**)
- `BS` = Lifecycle Stage (**BS** for Brainstorm, **SR** for Formal Software Requirements)
- `REC` = Domain Prefix
- `01` = Sequential Feature Number

---

## 4. Requirement & Business Rule Prefixes

Within feature tables, prefix individual requirement and business rule items with the domain code:

- **Requirements:** `REQ-<DOMAIN>-01` (e.g., `REQ-REC-01`)
- **Business Rules:** `BR-<DOMAIN>-01` (e.g., `BR-REC-01`)

---

## 5. Traceability Across Workflow

Maintaining consistent names ensures end-to-end traceability from planning to code:

| Artifact | Naming Format | Example                       |
|---|---|-------------------------------|
| **Brainstorm File** | `<domain>-<feature>.md` | `rec-license-verification.md` |
| **Brainstorm ID** | `GW-BS-<DOMAIN>-<NUM>` | `GW-BS-REC-01`                |
| **Formal Spec ID** | `GW-SR-<DOMAIN>-<NUM>` | `GW-SR-REC-01`                |
| **Requirement ID** | `REQ-<DOMAIN>-<NUM>` | `REQ-REC-01`                  |
| **Business Rule ID** | `BR-<DOMAIN>-<NUM>` | `BR-REC-01`                   |


---

*Last Updated: 2026-10-02*
