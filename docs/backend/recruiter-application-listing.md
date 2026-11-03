# Recruiter Application listing

`GET /api/companies/me/jobs/{jobId}/applications` lists submitted Applications for one Job Posting. Send an access token in the `Authorization: Bearer <accessToken>` header.

The Company Owner can view Applications for any Job Posting in their Company. A Recruiter needs an assignment for that Job Posting in their current active Company Membership period. Company Admin is excluded. Each request checks the live account, verified account email, active Company Membership, Company eligibility, and current Recruiter assignment. A former membership or assignment loses access immediately. A later membership period does not restore an assignment from an earlier period.

The Company must be verified and unsuspended. A suspended Job Posting is concealed from every Company Member, including the Owner, with the same `404` response used for an absent, other-Company, or unassigned Job Posting. These responses contain no Applications or result totals. Paused, closed, and expired Job Postings remain available for review while the other access checks pass.

## Query parameters

| Parameter | Default | Contract |
|---|---:|---|
| `search` | None | Optional partial match against the submitted Candidate name in the immutable Application Snapshot. Matching ignores case and Vietnamese accents, so `Nguyen` matches `Nguyễn`. Surrounding whitespace is trimmed; blank input means no filter. Up to 150 Unicode code points are accepted after trimming. `%` and `_` are treated as literal characters. |
| `status` | None | Optional exact status filter. The only accepted value is `SUBMITTED`; any other supplied value returns `400`. |
| `page` | `0` | Zero-based page number. Must be nonnegative and must not produce a pagination offset above `Integer.MAX_VALUE`. |
| `size` | `20` | Page size from `1` through `100`. |

Search and status filters combine before database pagination. Results are ordered by `appliedAt` descending, then Application ID descending. An empty result or a page beyond the last result returns `200` with an empty `content` array and the accurate matching total.

## Success response

The response uses the existing `{ status, message, data }` envelope and `PageResponse` fields. Each row contains only the Application identifier, submitted Candidate name, current status, and submission time:

```json
{
  "status": 200,
  "message": "Applications retrieved successfully",
  "data": {
    "content": [
      {
        "id": "b0dba9a4-857f-4d1a-8dd4-23f5a5dc5e35",
        "candidateName": "Nguyễn Văn An",
        "status": "SUBMITTED",
        "appliedAt": "2026-10-31T12:00:00Z"
      }
    ],
    "pageNumber": 0,
    "pageSize": 20,
    "totalElements": 1,
    "totalPages": 1,
    "first": true,
    "last": true
  }
}
```

For example, search by an unaccented name and retain only submitted Applications with:

```text
GET /api/companies/me/jobs/42/applications?search=Nguyen&status=SUBMITTED&page=0&size=20
```

Invalid search, status, or pagination values return `400` with the existing Company API error envelope. Requests without authentication return `401`. Missing active Company Membership and excluded Company roles retain their existing scoped `403` errors. Absent, inaccessible, unassigned, or suspended Job Postings return a non-enumerating `404`.

The list does not expose email, phone, cover letter, CV information, Candidate profile identity, or storage identifiers. Reading Applications sends no notifications and records no business audit event. The list does not log Candidate names or search text; request summaries use the existing route template and omit query parameters as defined in the [backend logging standard](../convention/backend-logging.md).
