# ADR 0010: Shared personal identity and voluntary account lifecycle

- Date: 2026-10-31
- Status: Accepted by the requesting human in the GW-95 session; personal name not supplied
- Scope: GW-95 backend account control

## Context

Candidate names currently diverge from User identity; Staff bans and self-closure have different recovery authority. Recovery cannot use a normal authenticated session for an inactive account. Existing records policies do not define permanent erasure.

## Decision

The human approved [GW-SR-AUTH-04 and its implementation contract](../../software-requirements/auth/account-control-implementation-contract.md) after the account-control interview. User firstName/lastName and canonical optional phone are the shared personal identity. Remove duplicate Candidate names and coordinate the structured-name request change with clients. Preserve historical snapshots.

Store voluntary ACTIVE/DEACTIVATED/DELETION_PENDING independently from existing Staff/legacy enforcement status. Normal access requires both dimensions active. Reuse persisted sessionVersion/all-refresh invalidation, the existing email delivery adapter and expiring action-bound hashed proofs. Recovery verifies credentials and email proof without granting an inactive User normal access. Pending deletion retains data; cancellation is self-service, while Staff processing/permanent erasure remains a separate feature.

## Consequences

A migration and breaking Candidate client upgrade are required. User names resolve legacy conflicts; no ambiguous full-name splitting is performed. Staff unban cannot revive voluntary closure. Recovery endpoints require explicit tests for races, proof replay and enforcement. No new dependencies or infrastructure are introduced. Owners remain blocked from closure, and pending deletion has no finalization processor in GW-95.
