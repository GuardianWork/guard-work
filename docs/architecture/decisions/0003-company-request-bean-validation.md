# Scoped Bean Validation for Company Membership requests

Status: Accepted within the scope authorized by the user on 2026-10-13.

The user requested annotation-based backend validation for the last three commits
(`d604723`, `a74819c`, `6c66300`), following discussion of adding the validation starter.

Use the Spring Boot managed `spring-boot-starter-validation` dependency for Company
registration/correction and invitation request DTO constraints. Services invoke the
injected Jakarta Validator at the existing validation checkpoints. Do not introduce
controller `@Valid` or a global exception handler for this scope: these would move
validation ahead of business authorization and change recipient privacy responses.

Normalize email by trimming before format validation and keep lowercase persistence.
Retain the dotted-domain constraint alongside `@Email`; the provider adds mailbox
syntax checks beyond the old regex. Required fields and explicit acceptance use
standard constraints; acceptance confirmation has a separate validation group.
Keep Company length checks counting Unicode code points, and optional correction
fields and workflow eligibility in their existing service implementation.

Map violations to existing sanitized responses without exposing rejected values.
Invalid secrets retain generic 404 responses, and confirmation is checked only after
secret/identity/eligibility. No schema, payload, dependency version override, shared
validation architecture, or AUTH/password-recovery behavior change is introduced.

The additional dependency supplies the validation provider; regression HTTP tests
use the existing real security/Flyway/PostgreSQL strategy. Future features must make
an explicit choice about their own validation timing and error contract.
