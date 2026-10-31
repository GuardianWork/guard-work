# GW-95 frontend integration guide

Backend behavior and rollout are defined in [account control](account-control.md) and the [implementation contract](../software-requirements/auth/account-control-implementation-contract.md). This checkout contains backend endpoints and documentation; the frontend must implement the screens below. Permanent erasure and Staff deletion processing are deferred.

## 1. Transport and access

Use the existing public frontend proxy, preserving `/api`, with `X-Guardian-Portal: public` supplied by the proxy. Send `Authorization: Bearer <accessToken>` for account settings, Candidate profile, email verification, password changes and closure. Do not change the existing sessionStorage token strategy. Recovery calls use username/email plus password and do not need a Bearer token. Do not send stale Bearer tokens on recovery calls.

All account-control success responses use the existing `{status,message,data}` envelope with HTTP 200. Errors use the corresponding HTTP status and message code. Nullable fields can be omitted from JSON: treat missing/null phoneNumber and optional timestamps equivalently. Do not use English API message text directly as Vietnamese UI copy; map codes and action context to Vietnamese messages.

Only platform USER participates, including Candidate and Company OWNER/ADMIN/RECRUITER. STAFF must not receive the self-service account interface. UI gating supplements server checks.

## 2. Settings view and shared identity

Call `GET /api/me/account` on entry. An illustrative response is:

```json
{
  "status": 200,
  "message": "Success",
  "data": {
    "firstName": "Minh",
    "lastName": "Nguyễn",
    "fullName": "Minh Nguyễn",
    "phoneNumber": "+84901234567",
    "username": "minh_nguyen",
    "email": "minh@example.vn",
    "emailVerified": false,
    "selfServiceState": "ACTIVE",
    "closureEligible": false,
    "closureBlockers": ["EMAIL_VERIFICATION_REQUIRED"]
  }
}
```

Treat the envelope message as descriptive, not a fixed success constant. Display username/email read-only with no change controls. Offer editable firstName, lastName and phoneNumber; unverified Users may edit them.

Save with `PUT /api/me/account`:

```json
{
  "firstName": "Minh",
  "lastName": "Nguyễn",
  "phoneNumber": "0901234567"
}
```

Both names are required, trimmed, maximum 100 characters each. Vietnamese Unicode names are supported. Phone is optional; send null to clear it. Blank is invalid. Omitted phone also clears it because PUT is a full identity update. Local Vietnamese mobile and canonical +84 forms normalize to one globally unique account phone. Do not send username/email or other fields: unsupported fields are rejected.

The response returns the refreshed account projection. Invalidate cached Candidate profile and account/current-user identity after either editing surface saves. Do not split fullName to recover firstName/lastName or maintain separate Candidate/account names.

### Required Candidate request migration

Keep `GET` and `PUT /api/v1/candidates/profile`. Replace writable fullName with firstName/lastName in PUT; fullName is now response-only. For example:

```json
{
  "firstName": "Minh",
  "lastName": "Nguyễn",
  "phoneNumber": null,
  "avatarUrl": "https://example.vn/avatar.png",
  "skills": ["Java", "Spring"],
  "experienceSummary": "Kinh nghiệm phát triển phần mềm",
  "educationSummary": "Đại học",
  "isPublic": false
}
```

Preserve the existing semantics of the other Candidate fields; do not submit a partial Candidate form assuming omitted fields are all preserved. Responses add firstName/lastName and retain derived fullName, which can be up to 201 characters. Historical CV/application snapshots are not rewritten. Deploy this client change with migration V2026.10.31.94 and stop older writers; the old fullName request is rejected, not silently converted.

## 3. Verify the fixed email

Show “Xác minh email” for an unverified User. Use the existing endpoints:

1. `POST /api/me/email-verification/request` with no body.
2. Read `data.deliveryAccepted`, `alreadyVerified`, `nextRequestAt` and `expiresAt`.
3. Submit `POST /api/me/email-verification/confirm` with `{"emailSecret":"012345"}`.
4. Refresh account and `/api/auth/me` projections on success.

Never request a destination email; the server always uses the registered address. Keep codes as strings so leading zeroes survive. Use the existing 10-minute validity, five-failure budget and 60-second resend cooldown. Respect Retry-After. These verification codes cannot authorize closure or recovery.

## 4. Password change

Enable changing password only after verified email; show verification guidance otherwise. Call existing `PUT /api/auth/change-password`:

```json
{"currentPassword":"CurrentPassword123!","newPassword":"NewPassword123!"}
```

The existing new-password policy requires 8–128 characters, uppercase, lowercase and a digit. On success, clear access/refresh tokens and sensitive form state, invalidate cached private account data and navigate to sign-in. All previous access/refresh sessions are invalid; do not attempt to refresh using the old token. No additional email code is required for password change.

## 5. Deactivation and deletion request

Use distinct actions and consequence dialogs: “Tạm ngừng tài khoản” and “Yêu cầu xóa tài khoản”. Deletion is a pending request, not immediate erasure. Explain that normal access stops immediately, Candidate visibility is hidden and history remains; permanent processing has no promised completion date in GW-95.

Read closureEligible/closureBlockers but expect the server to recheck on confirmation:

| Blocker | Frontend handling |
|---|---|
| EMAIL_VERIFICATION_REQUIRED | Offer existing-email verification first. |
| COMPANY_OWNERSHIP_EXISTS | Explain that ownership prevents closure and no transfer/Company-closure flow is currently available. |
| ACTIVE_COMPANY_MEMBERSHIP_EXISTS | For RECRUITER, link to the existing permitted leave flow. OWNER remains blocked. ADMIN has no supported leave or removal flow in the current implementation; explain the restriction rather than showing an unusable action. Use existing `/api/auth/me` membership context for the role. |

For either action:

1. Collect the current password and show the consequences. Do not persist credentials in browser storage.
2. For deactivation, POST `/api/me/account/deactivation/request-code`; for deletion, POST `/api/me/account/deletion/request-code`. Body: `{"currentPassword":"CurrentPassword123!"}`.
3. The response has `data.deliveryAccepted`, `nextRequestAt`, `expiresAt`; no code is returned. If deliveryAccepted is false, explain that delivery was not accepted and permit retry after cooldown. Do not show success for the lifecycle action.
4. Collect the six-digit string from the purpose-specific email and explicit consequence acknowledgement.
5. POST to the matching `/deactivation/confirm` or `/deletion/confirm`:

```json
{
  "currentPassword": "CurrentPassword123!",
  "emailSecret": "012345",
  "acknowledgeConsequences": true
}
```

6. Success returns `data.selfServiceState` as DEACTIVATED or DELETION_PENDING; deletion's success message explicitly says processing is pending. Clear all stored tokens and sensitive state and exit authenticated screens. Preserve no usable session for recovery.

Requesting a code alone does not close an account. Proofs are account/email/action-bound. Resend replaces the previous code for that action; every completed lifecycle transition invalidates all outstanding lifecycle proofs. Failed confirmation leaves account state unchanged, but wrong-code attempts count. Concurrent membership or enforcement changes may block confirmation even after the form was initially eligible.

## 6. Reactivation and deletion cancellation

Provide a public recovery entry with “Kích hoạt lại tài khoản” and “Hủy yêu cầu xóa tài khoản”. Ordinary sign-in rejects inactive accounts with 40304 ACCOUNT_SUSPENDED; this error alone does not distinguish a ban from voluntary closure. Offer recovery as an explicit user choice and handle its response; do not automatically assume a ban is recoverable or automatically send mail.

First POST `/api/auth/account-recovery/request-code` without Bearer authentication:

```json
{
  "usernameOrEmail": "minh@example.vn",
  "password": "CurrentPassword123!",
  "action": "REACTIVATE"
}
```

Use CANCEL_DELETION for a pending deletion request. Successful request-code returns deliveryAccepted/nextRequestAt/expiresAt and no tokens. The User must use the account's current password and fixed email. Then POST `/api/auth/account-recovery/confirm`:

```json
{
  "usernameOrEmail": "minh@example.vn",
  "password": "CurrentPassword123!",
  "action": "REACTIVATE",
  "emailSecret": "012345",
  "acknowledgeConsequences": true
}
```

Success returns `data.selfServiceState: "ACTIVE"` and no tokens. Clear the form and navigate to normal sign-in. Do not consider the recovery response an authenticated session. Prior Candidate visibility preference returns; previously left Company memberships, assignments and sessions do not. A current ban blocks recovery. Cancellation is possible only while a matching request remains pending; this backend provides no finalization operation.

## 7. Error handling and UI checks

| HTTP / message prefix | Meaning and UI behavior |
|---|---|
| 400 / 40001 | Invalid input, missing acknowledgement or incorrect current password on a closure/settings action; preserve editable form state and ask for correction. |
| 400 / 40002 | New password fails existing policy. |
| 400 / 40030 | Existing email-verification proof invalid/expired/exhausted. |
| 400 / 40094 | Lifecycle proof invalid, expired, consumed or exhausted; show an action-specific code error and allow bounded resend. |
| 401 / 40101 on recovery | Invalid credentials; do not infer whether the account exists. |
| 401 on protected calls | Clear invalid session credentials and return to sign-in; avoid refresh loops after deliberate logout/closure/password change. |
| 403 / 40300 | Wrong portal or excluded Staff role. |
| 403 / 40304 | Account unavailable under current enforcement/lifecycle; recovery must independently validate eligibility. |
| 403 / 40335 | Verify registered email before the restricted action. |
| 409 / 40930 | Phone unavailable; do not disclose another account. |
| 409 / 40994 | Company ownership blocks closure. |
| 409 / 40910 | Active Company membership blocks closure. |
| 409 / 40995 | Action no longer matches current lifecycle/request; do not display successful recovery/closure. |
| 429 / 42994 | Lifecycle resend cooldown; use Retry-After and nextRequestAt. Existing email verification uses 42930 instead. |
| 5xx | Do not report success; permit a safe retry/reload. On an ambiguous network failure, re-read state where authorized rather than issuing uncontrolled repeated confirmations. |

Disable repeated submit while a request is in progress, but rely on the server for correctness. Never put passwords/codes in URLs, analytics, logs or persistent state. Label fields and errors accessibly. Use vi-VN and Asia/Ho_Chi_Minh for timestamps while comparing server ISO instants correctly. Integration checks should exercise unverified editing, Candidate/account cache synchronization, immutable email/username, each membership blocker, token clearing, wrong/expired/resend codes, each recovery action and bans.
