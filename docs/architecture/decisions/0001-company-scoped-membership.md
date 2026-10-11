---
status: proposed
---

# Use company-scoped membership with one active membership per User

GuardWork will represent authority through a Company Membership between a User and a Company, with fixed Owner, Admin, and Recruiter Company Roles. Current business behavior permits a User to hold at most one active Company Membership, while inactive memberships remain available for authorship and audit history. This preserves a path to future multi-Company membership without making Recruiter a global User type or granting current Users access to multiple Companies.

The user confirmed GW-001 completed on 2026-10-08 and requested implementation of its dependent tickets. The implemented ownership contract uses a nullable Company Owner reference, a partial unique index for each User's active membership, a partial unique index for each Company's active Owner, and deferred consistency triggers. Deferring the cross-row check allows Company and first Owner creation in one transaction while rejecting a mismatched or missing Owner at commit. Unassigned legacy Companies retain a null reference and require the reviewed assignment procedure.

Protected Company operations resolve current User, Company, and membership state inside their business transaction and hold the guard locks through commit. Removal uses the same guard so a revoked membership cannot commit a later protected write. Membership periods are retained as `ACTIVE`, `LEFT`, or `REMOVED`; a later admission creates a new period. This deliberately keeps Company authority out of cached JWT claims and preserves historical attribution.
