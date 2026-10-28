# Invitation acceptance with account-email proof

GW-SR-AUTH-03 UREG-009 applies the new account-email prerequisite inside the
invitation acceptance transaction. A newly registered User signs in through the
shared registration flow, explicitly requests and confirms account-email proof,
then accepts the invitation. Neither receiving nor previewing an invitation
verifies the account email. No phone proof or separate Company dossier is needed.

`POST /api/company-invitations/accept` keeps `{secret,confirm:true}` and its existing
receipt shape. A first acceptance from a new User without verified account email
returns 403 `40335 EMAIL_VERIFICATION_REQUIRED`. It grants no membership, consumes
no invitation and creates no acceptance notification. The recipient can still
preview or decline the invitation under their existing secret and matching-email
checks. An unrelated account or invalid secret retains the non-enumerating 404
response. Email proof alone grants no Company authority.

Acceptance reads the current persisted User under the existing actor lock, then
locks the Company and invitation. It preserves explicit confirmation, normalized
account/invitation email matching, invitation state and expiry, verified and
non-suspended Company eligibility, and one active Company Membership per User.
The membership, accepted invitation, audit and notification intent commit
atomically. Competing invitations for the same User cannot grant two memberships.

Users whose persisted rollout marker is false retain their existing email
treatment, including accounts with historical `email_verified=false`. The
new prerequisite applies only to first acceptance. A retry of a previously
accepted invitation returns the historical 200 receipt and does not re-admit a
departed or removed member, issue another notification or grant current access.
Suspended Users are still denied before that receipt is returned.

Clients should retain the intended invitation while guiding a new User through
shared sign-in and explicit email verification, then retry explicit acceptance.
Use the current-User capability fields to show the missing prerequisite. Workspace
switching and portal headers do not grant Company or Staff authority. These HTTP
examples are in `backend/http/company-membership.http`; no frontend source is
present in this checkout.

The implementation adds no dependency, schema change or delivery mechanism.
See the [approved contract](../software-requirements/auth/unified-registration-implementation-contract.md)
and [account-email workflow](unified-registration.md) for proof lifecycle and rollout.
