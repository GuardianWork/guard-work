# Requirements Guide

Use this guide for features, user-visible behavior, business rules, APIs, schemas, and software-requirements documents.

## Product boundary

The thesis scope includes:

- candidate onboarding, profiles, and CVs;
- job discovery and applications;
- employer verification and moderated job publishing;
- application stages through interviews and offers; and
- notifications, reporting, appeals, consent, retention, and auditing.

Design user-facing behavior for Vietnamese users, including Vietnamese copy, `vi-VN` conventions, Vietnam time zones, and Unicode names and addresses. Keep user-facing text out of backend business logic.

## Specification gate

An approved specification is required before implementing a new feature or changing user-visible behavior, an API contract, a schema, or a business rule. Clearly scoped bug fixes, tests, refactors, and documentation corrections may proceed without a new specification when they preserve intended behavior.

Specifications live under `docs/software-requirements/`. Use `template.md` to draft one focused specification per feature or cohesive workflow. `example.md` demonstrates formatting from another project; it is not a GuardWork requirement or architecture reference.

Each specification must have a stable ID, lifecycle status, owner, named human approver, and last-updated date. Agents may draft or propose edits but cannot set the status to `Approved`. A material behavior change requires human approval and an updated specification before, or in the same reviewed change as, the implementation.

When code, tests, and a specification conflict, apply the root source-of-truth order and surface the conflict. Never silently redefine the requirement to match the implementation.

## Contracts and compatibility

Preserve API compatibility unless an approved specification authorizes a break. An approved breaking change must document migration and client impact.

Authentication, authorization, validation, pagination, error envelopes, notification delivery, and frontend or infrastructure architecture remain undecided until approved. Preserve scoped existing behavior; establish a shared contract only through an approved requirement or architecture decision.

Requirement work is complete when every changed behavior maps to an approved requirement, the specification metadata is current, and contract, migration, localization, and client impacts are explicit.

