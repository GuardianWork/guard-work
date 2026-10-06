# Authentication, Identity & Access Control — Software Requirements Specification

| Field | Value |
|---|---|
| **Specification ID** | GW-SR-AUTH-01 |
| **Project** | GuardWork |
| **Module** | Authentication, Identity & Access Control (AUTH) |
| **File** | `authentication-authorization-spec.md` |
| **Version** | 1.0.0 |
| **Date** | 2026-10-05 |
| **Last Updated** | 2026-10-05 |
| **Owner** | VyTrg |
| **Approver** | VyTrg |
| **Status** | Approved |

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Overall Description](#2-overall-description)
3. [Data Models](#3-data-models)
4. [Functional Requirements](#4-functional-requirements)
5. [Flow Diagrams](#5-flow-diagrams)
6. [Business Rules](#6-business-rules)
7. [Error Catalogue](#7-error-catalogue)
8. [API Specification](#8-api-specification)
9. [Infrastructure Architecture](#9-infrastructure-architecture)
10. [Non-Functional Requirements](#10-non-functional-requirements)

---

## 1. Introduction

### 1.1 Purpose

This specification defines the platform-wide software requirements for **Authentication, Identity, and Authorization (AUTH)** across the GuardWork recruitment platform. It provides a unified, secure foundation matching all domain use cases across:
- **Candidate Workspace**: Account registration, credential management, profile ownership, and CV privacy (`docs/feat/candidates/spec.md`, `docs/feat/candidates/usecase.md`).
- **Company Workspace & Recruiter Operations**: Company-scoped memberships (`OWNER`, `ADMIN`, `RECRUITER`), workspace switching, and immediate access revocation (`docs/architecture/decisions/0001-company-scoped-membership.md`, `docs/feat/recruiters/company-membership-spec.md`).
- **Platform Governance & Administration**: Platform Administrator authorization (`role: "ADMIN"`), KYB verification queue access, and tamper-proof audit trails (`docs/software-requirements/rec/company-verification-spec.md`).

### 1.2 Scope

| Sub-feature | Responsibility |
|---|---|
| **User Identity & Registration** | User onboarding, credential validation, password hashing (BCrypt), and account provisioning. |
| **Authentication & Token Issuance** | Dual-token authentication issuing stateless short-lived JWT Access Tokens and database-persisted Refresh Tokens. |
| **Token Refresh & Rotation** | Sliding-window refresh token rotation with reuse detection and instant family revocation. |
| **Session Invalidation & Logout** | Voluntary session logout (single device) and global session invalidation (all devices). |
| **Platform-Level RBAC** | System role enforcement (`PLATFORM_ADMIN` vs `USER`) protecting administrative governance endpoints. |
| **Company-Scoped Workspace Authorization** | Dynamic membership validation (`OWNER`, `ADMIN`, `RECRUITER`) enforcing immediate revocation without waiting for token expiry. |
| **Password Lifecycle & Account Recovery** | Secure password change and cryptographic reset token generation/consumption. |

### 1.3 Technology Stack

| Layer | Technology |
|---|---|
| Runtime | Java 25 |
| Framework | Spring Boot 4.1.1 (Spring Web MVC) |
| Security | Spring Security (`spring-boot-starter-security`), Spring Security Crypto |
| Token Engine | HMAC-SHA256 (JJWT or Java JWT) |
| Database | PostgreSQL 18 (Flyway migrations) |
| Persistence | Spring JDBC (`NamedParameterJdbcTemplate`) |
| API Docs | SpringDoc OpenAPI 3.1.1 (`/swagger-ui/index.html`) |

---

## 2. Overall Description

### 2.1 High-Level Flow

```text
Client (Candidate / Recruiter / Admin)
   │
   ├── POST /api/auth/login ──────────> [AuthService]
   │   (username/email, password)           ├── Verify credentials (BCrypt)
   │                                        ├── Issue Access Token (JWT, 15 min)
   │                                        └── Issue Refresh Token (UUID hash, 7 days in DB)
   │
   ├── API Request with Bearer Token ──> [JwtAuthenticationFilter]
   │   ("Authorization: Bearer <jwt>")      ├── Validate signature & expiry
   │                                        └── Populate SecurityContext (userId, platformRole)
   │
   ├── Accessing /api/admin/** ────────> Method Security (@PreAuthorize("hasRole('ADMIN')"))
   │
   └── Accessing /api/companies/{id}/** > CompanySecurityService (Dynamic DB check)
                                            ├── Verify User has ACTIVE membership in Company
                                            └── Verify CompanyRole (OWNER / ADMIN / RECRUITER)
```

### 2.2 Lifecycle & State Machine

#### User Account State Machine
```text
[REGISTERED] ──(Auto-activated MVP)──> [ACTIVE] ──(Admin Suspension)──> [SUSPENDED]
                                          │                                 │
                                          └──(Self Deletion)──> [DELETED] <─┘
```

#### Refresh Token Lifecycle
```text
[ISSUED] ──(POST /api/auth/refresh)──> [ROTATED] (New token issued)
   │
   ├──(POST /api/auth/logout)────────> [REVOKED]
   └──(Exceeds 7-day TTL)────────────> [EXPIRED]
```

### 2.3 Response Envelope

All authentication and authorization endpoints adhere to the standard GuardWork response envelope:

```json
{
  "status": 200,
  "message": "Human-readable result message",
  "data": { ... }
}
```

Error responses serialize through custom `AuthenticationEntryPoint` and `AccessDeniedHandler` implementations producing:

```json
{
  "status": 401,
  "message": "40100 UNAUTHORIZED: Missing or expired authentication token",
  "data": null
}
```

### 2.4 Public vs Protected Endpoints

| Category | Endpoint Pattern | Access Rule | Description |
|---|---|---|---|
| **Public** | `/api/auth/register` | Anonymous | Candidate / User account creation |
| **Public** | `/api/auth/login` | Anonymous | Password-based authentication |
| **Public** | `/api/auth/refresh` | Anonymous (Token) | Refresh token exchange |
| **Public** | `/api/auth/forgot-password` | Anonymous | Password reset request |
| **Public** | `/api/auth/reset-password` | Anonymous | Password reset submission |
| **Public** | `/swagger-ui/**`, `/v3/api-docs/**` | Anonymous | Interactive API documentation |
| **Protected (User)** | `/api/auth/me` | Authenticated | Current user profile & memberships |
| **Protected (User)** | `/api/auth/change-password`| Authenticated | In-session password update |
| **Protected (User)** | `/api/auth/logout` | Authenticated | Current session termination |
| **Protected (Admin)**| `/api/admin/**` | `role: 'ADMIN'` | Administrative governance & KYB |
| **Protected (Company)**| `/api/companies/{id}/**` | Active Member | Scoped by Company Membership role |

---

## 3. Data Models

### 3.1 Relational Schema Updates & Additions

#### 3.1.1 `users` Table Evolution (Flyway V3)

Enhances the existing `users` table to support platform-level RBAC and account state:

| Column | Type | Constraints | Description |
|---|---|---|---|
| `role` | VARCHAR(30) | NOT NULL, DEFAULT `'USER'` | Platform role: `'USER'` (Candidate/general) or `'ADMIN'` (Platform Admin) |
| `status` | VARCHAR(30) | NOT NULL, DEFAULT `'ACTIVE'` | Account status: `'ACTIVE'`, `'SUSPENDED'`, `'DELETED'` |
| `email_verified` | BOOLEAN | NOT NULL, DEFAULT `true` | Email confirmation status |

#### 3.1.2 `refresh_tokens` Table (PostgreSQL)

Stores hashed refresh tokens to enable revocation, rotation, and multi-device session management:

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | BIGSERIAL | PK, auto | Unique token record ID |
| `user_id` | BIGINT | NOT NULL, FK -> `users(id)` ON DELETE CASCADE | Associated user |
| `token_hash` | VARCHAR(64) | NOT NULL, UNIQUE | SHA-256 hash of the issued refresh token |
| `family_id` | VARCHAR(36) | NOT NULL | UUID grouping rotated tokens in one session |
| `is_revoked` | BOOLEAN | NOT NULL, DEFAULT `false` | Revocation status flag |
| `expires_at` | TIMESTAMPTZ | NOT NULL | Hard expiration timestamp (7 days) |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` | Creation timestamp |
| `revoked_at` | TIMESTAMPTZ | NULL | Timestamp of revocation |
| `user_agent` | VARCHAR(255) | NULL | Client browser/device identifier |
| `ip_address` | VARCHAR(45) | NOT NULL | Client IP of the login/refresh request |

#### 3.1.3 `password_reset_tokens` Table (PostgreSQL)

Manages short-lived, single-use password recovery tokens:

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | BIGSERIAL | PK, auto | Unique record identifier |
| `user_id` | BIGINT | NOT NULL, FK -> `users(id)` ON DELETE CASCADE | Target user |
| `token_hash` | VARCHAR(64) | NOT NULL, UNIQUE | SHA-256 hash of the recovery token |
| `is_consumed` | BOOLEAN | NOT NULL, DEFAULT `false` | Single-use consumption flag |
| `expires_at` | TIMESTAMPTZ | NOT NULL | Hard expiration timestamp (15 minutes) |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` | Issuance timestamp |

### 3.2 Database Indexes

- `idx_users_role_status`: `ON users (role, status)` for admin indexing.
- `idx_refresh_tokens_lookup`: `ON refresh_tokens (token_hash, is_revoked, expires_at)`.
- `idx_refresh_tokens_family`: `ON refresh_tokens (family_id)`.
- `idx_password_reset_lookup`: `ON password_reset_tokens (token_hash, is_consumed, expires_at)`.

---

## 4. Functional Requirements

### 4.1 Registration & Authentication

| ID | Requirement |
|---|---|
| **REQ-AUTH-01** | The system shall allow users to register with `firstName`, `lastName`, `username`, `email`, and `password`, creating an active user with platform role `USER`. |
| **REQ-AUTH-02** | The system shall securely hash passwords using BCrypt (work factor ≥ 10) before persisting to the database. |
| **REQ-AUTH-03** | The system shall authenticate users via `POST /api/auth/login` accepting either `username` or `email` (case-insensitive) and valid password. |
| **REQ-AUTH-04** | Upon successful authentication, the system shall issue a signed JWT Access Token (15-minute validity) and a secure Refresh Token (7-day validity). |
| **REQ-AUTH-05** | The JWT Access Token shall contain claims: `sub` (userId), `username`, `email`, `role` (platform role), `iat` (issued at), and `exp` (expiration). |

### 4.2 Token Refresh & Rotation

| ID | Requirement |
|---|---|
| **REQ-AUTH-06** | The system shall allow clients to exchange a valid refresh token for a newly minted access token and rotated refresh token via `POST /api/auth/refresh`. |
| **REQ-AUTH-07** | When a refresh token is used, the system shall mark it as revoked/rotated and issue a new token belonging to the same `family_id`. |
| **REQ-AUTH-08** | If a revoked refresh token is presented (reuse attack), the system shall immediately revoke all active refresh tokens in that `family_id` and return HTTP 401 Unauthorized. |
| **REQ-AUTH-09** | The system shall allow an authenticated user to log out via `POST /api/auth/logout`, revoking the presented refresh token. |

### 4.3 Authorization & Access Control

| ID | Requirement |
|---|---|
| **REQ-AUTH-10** | The system shall protect all administrative endpoints (`/api/admin/**`) requiring platform role `ADMIN`. Calls with `USER` role or missing tokens shall be rejected with HTTP 403 Forbidden or HTTP 401 Unauthorized. |
| **REQ-AUTH-11** | The system shall resolve Company Workspace authority by dynamically querying the user's active `CompanyMembership` record. |
| **REQ-AUTH-12** | If a user's company membership is inactive (`LEFT` or `REMOVED`) or the company is banned, all company-scoped actions shall immediately return HTTP 403 Forbidden, regardless of JWT validity. |
| **REQ-AUTH-13** | The system shall enforce role boundaries within Company Workspaces: `OWNER` can transfer ownership; `ADMIN` can manage recruiters; `RECRUITER` can only manage assigned jobs and applications per `company-membership-spec.md`. |
| **REQ-AUTH-14** | Company Members shall be prohibited from applying to Job Postings published by their own Company (`GW-CM-005`). |

### 4.4 Account Management & Recovery

| ID | Requirement |
|---|---|
| **REQ-AUTH-15** | The system shall allow authenticated users to retrieve their profile and active company membership via `GET /api/auth/me`. |
| **REQ-AUTH-16** | The system shall allow authenticated users to change their password via `PUT /api/auth/change-password`, requiring verification of their current password. |
| **REQ-AUTH-17** | The system shall support password recovery via `POST /api/auth/forgot-password` and `POST /api/auth/reset-password` using single-use cryptographic tokens with 15-minute expiry. |

---

## 5. Flow Diagrams

### 5.1 Login & Dual-Token Issuance

```text
Client                           AuthController                 AuthService               Database (JDBC)
  │                                    │                             │                          │
  │── POST /api/auth/login ───────────>│                             │                          │
  │   (usernameOrEmail, password)      │── login() ─────────────────>│                          │
  │                                    │                             │── findUser() ───────────>│
  │                                    │                             │<── User Record ──────────│
  │                                    │                             │                          │
  │                                    │                             │── BCrypt.matches()       │
  │                                    │                             │── Generate JWT (15m)     │
  │                                    │                             │── Generate Refresh Token │
  │                                    │                             │── INSERT refresh_tokens >│
  │                                    │<── TokenPairResponse ───────│                          │
  │<── 200 OK (AccessToken, Refresh) ──│                             │                          │
```

### 5.2 Dynamic Company Workspace Authorization Flow

```text
Client                           JwtAuthenticationFilter       CompanyController          CompanySecurityService
  │                                         │                          │                            │
  │── PUT /api/companies/{id}/jobs ────────>│                          │                            │
  │   Header: "Bearer <jwt>"                │── Parse & Validate JWT   │                            │
  │                                         │── Set SecurityContext    │                            │
  │                                         │   (userId, role: USER)   │                            │
  │                                         │─────────────────────────>│                            │
  │                                         │                          │── hasRole("ADMIN")? ──────>│
  │                                         │                          │   (Check active membership │
  │                                         │                          │    in company {id})        │
  │                                         │                          │<── Authorized: true/false ─│
  │                                         │                          │                            │
  │<── 200 OK / 403 Forbidden ──────────────┴──────────────────────────┘                            │
```

---

## 6. Business Rules

| ID | Rule | Description |
|---|---|---|
| **BR-AUTH-01** | **Password Complexity** | Passwords must contain a minimum of 8 characters, at least one uppercase letter, one lowercase letter, and one digit. Max length is 128 characters. |
| **BR-AUTH-02** | **Credential Uniqueness** | `email` and `username` must be globally unique across all user accounts. Duplicates return HTTP 409 Conflict (`40901 USERNAME_OR_EMAIL_EXISTS`). |
| **BR-AUTH-03** | **Access Token Ephemerality** | Access tokens are stateless and expire strictly after 15 minutes. No token blacklisting is required for access tokens. |
| **BR-AUTH-04** | **Refresh Token Rotation (RTR)** | Every refresh operation consumes the provided refresh token and produces a new one. A consumed token cannot be reused. |
| **BR-AUTH-05** | **Automatic Family Invalidation** | If a previously rotated refresh token is presented, the system detects a token hijacking attempt, revokes all tokens in the `family_id`, and requires re-authentication. |
| **BR-AUTH-06** | **Platform vs Company Role Separation** | Platform role (`role`) is stored in `users.role` (`USER` or `ADMIN`). Company role is strictly entity-scoped (`CompanyMembership.role`). An `ADMIN` of a Company does NOT possess Platform `ADMIN` authority. |
| **BR-AUTH-07** | **Single Active Company Membership** | Per ADR 0001, a user may hold at most one active Company Membership (`ACTIVE`). Accepting a new invitation is blocked until existing membership is terminated. |
| **BR-AUTH-08** | **Immediate Access Revocation** | Deactivating or removing a company membership immediately terminates company access on the subsequent request; company authority is never cached in stateless tokens. |
| **BR-AUTH-09** | **Candidate-Company Conflict of Interest** | An active member of a company is strictly forbidden from applying to job postings published by that same company (`GW-CM-005`). |
| **BR-AUTH-10** | **Timing Attack Mitigation** | Authentication comparison failures must consume constant-time execution to prevent username enumeration via timing analysis. |

---

## 7. Error Catalogue

| Error Code | HTTP | Constant | Trigger Condition |
|---|---|---|---|
| `40001` | 400 | `INVALID_INPUT` | Validation failure on registration or credential change fields. |
| `40002` | 400 | `WEAK_PASSWORD` | Password does not satisfy complexity requirements. |
| `40101` | 401 | `INVALID_CREDENTIALS` | Invalid username/email or password mismatch. |
| `40102` | 401 | `EXPIRED_ACCESS_TOKEN` | Bearer JWT signature has expired (client should refresh). |
| `40103` | 401 | `INVALID_TOKEN` | Malformed, tampered, or unsupported token signature. |
| `40104` | 401 | `REVOKED_REFRESH_TOKEN` | Refresh token has been revoked or reuse attack detected. |
| `40301` | 403 | `FORBIDDEN_PLATFORM_ADMIN` | Caller lacks `ADMIN` platform role for `/api/admin/**`. |
| `40302` | 403 | `NOT_COMPANY_MEMBER` | Caller is not an active member of the targeted company. |
| `40303` | 403 | `INSUFFICIENT_COMPANY_ROLE` | Caller membership role is insufficient for requested operation. |
| `40304` | 403 | `ACCOUNT_SUSPENDED` | Account has been administratively suspended. |
| `40401` | 404 | `USER_NOT_FOUND` | User account does not exist. |
| `40901` | 409 | `USERNAME_OR_EMAIL_EXISTS` | Registration email or username is already taken. |

---

## 8. API Specification

### Base URL: `/api/auth`

---

### 8.1 Register User

#### `POST /api/auth/register`

Creates a new user account with default platform role `USER`.

**Request Body**

```json
{
  "firstName": "Nguyễn",
  "lastName": "Văn An",
  "username": "nguyenvanan",
  "email": "an.nguyen@example.com",
  "password": "SecurePassword123!"
}
```

**Response `201 Created`**

```json
{
  "status": 200,
  "message": "User registered successfully",
  "data": {
    "id": 1,
    "firstName": "Nguyễn",
    "lastName": "Văn An",
    "username": "nguyenvanan",
    "email": "an.nguyen@example.com",
    "role": "USER",
    "createdAt": "2026-10-05T12:00:00Z"
  }
}
```

---

### 8.2 User Login

#### `POST /api/auth/login`

Authenticates credentials and returns access and refresh tokens.

**Request Body**

```json
{
  "usernameOrEmail": "an.nguyen@example.com",
  "password": "SecurePassword123!"
}
```

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Login successful",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIsIn...",
    "refreshToken": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "user": {
      "id": 1,
      "firstName": "Nguyễn",
      "lastName": "Văn An",
      "username": "nguyenvanan",
      "email": "an.nguyen@example.com",
      "role": "USER"
    }
  }
}
```

---

### 8.3 Refresh Access Token

#### `POST /api/auth/refresh`

Exchanges a valid refresh token for a newly rotated token pair.

**Request Body**

```json
{
  "refreshToken": "7c9e6679-7425-40de-944b-e07fc1f90ae7"
}
```

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Token refreshed successfully",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIsIn...",
    "refreshToken": "9d1b54a2-1134-4b5c-897c-a49e5d4e1123",
    "tokenType": "Bearer",
    "expiresIn": 900
  }
}
```

---

### 8.4 User Logout

#### `POST /api/auth/logout`

Revokes the refresh token associated with the current session.

**Request Headers**
- `Authorization: Bearer <jwt>`

**Request Body**

```json
{
  "refreshToken": "9d1b54a2-1134-4b5c-897c-a49e5d4e1123"
}
```

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Logged out successfully",
  "data": null
}
```

---

### 8.5 Get Current User Profile & Context

#### `GET /api/auth/me`

Returns authenticated user details, platform role, and active company membership.

**Request Headers**
- `Authorization: Bearer <jwt>`

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Current user retrieved successfully",
  "data": {
    "id": 1,
    "firstName": "Nguyễn",
    "lastName": "Văn An",
    "username": "nguyenvanan",
    "email": "an.nguyen@example.com",
    "role": "USER",
    "activeMembership": {
      "companyId": 10,
      "companyName": "Công ty TNHH Giải Pháp Alpha",
      "companyRole": "OWNER",
      "joinedAt": "2026-10-04T10:00:00Z"
    }
  }
}
```

---

### 8.6 Change Password

#### `PUT /api/auth/change-password`

Updates user password and revokes all existing refresh tokens for security.

**Request Headers**
- `Authorization: Bearer <jwt>`

**Request Body**

```json
{
  "currentPassword": "SecurePassword123!",
  "newPassword": "NewSecurePassword456!"
}
```

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Password changed successfully. Please log in with your new credentials.",
  "data": null
}
```

---

## 9. Infrastructure Architecture

### 9.1 Spring Security Filter Chain Architecture

```text
HTTP Request
     │
     ▼
[CorsFilter] ───────────────> Handles allowed origins (e.g., localhost:3000)
     │
     ▼
[CsrfFilter] ───────────────> Disabled (Stateless REST API using Bearer tokens)
     │
     ▼
[JwtAuthenticationFilter] ──> Extracts "Bearer <jwt>"
     │                        Validates signature with HMAC-SHA256 secret
     │                        Builds UsernamePasswordAuthenticationToken(userId, role)
     │                        Sets SecurityContextHolder
     │
     ▼
[AuthorizationFilter] ─────> Checks request authorization matcher rules
     │                        • /api/auth/** -> permitAll()
     │                        • /api/admin/** -> hasRole('ADMIN')
     │                        • Any other -> authenticated()
     │
     ▼
[Controller Layer] ─────────> Handles endpoint business logic
```

### 9.2 JWT Payload Structure

```json
{
  "sub": "1",
  "username": "nguyenvanan",
  "email": "an.nguyen@example.com",
  "role": "USER",
  "iat": 1791200000,
  "exp": 1791200900
}
```

### 9.3 Environment Configuration Variables

The following variables must be added to `.env.example` and backend configuration:

| Variable | Description | Example Value |
|---|---|---|
| `JWT_SECRET` | 256-bit base64/hex secret for HMAC-SHA256 signing | `guardwork_super_secret_jwt_key_2026_xyz...` |
| `JWT_ACCESS_EXPIRATION_MS` | Access token lifetime in milliseconds | `900000` (15 minutes) |
| `JWT_REFRESH_EXPIRATION_MS` | Refresh token lifetime in milliseconds | `604800000` (7 days) |

---

## 10. Non-Functional Requirements

| ID | Category | Requirement |
|---|---|---|
| **NFR-AUTH-01** | Performance | Stateless JWT authentication filter processing shall complete in < 2ms per request without hitting database. |
| **NFR-AUTH-02** | Security | Token secrets must never be hardcoded in repository files; secrets must be injected via environment variables. |
| **NFR-AUTH-03** | Resilience | Database transactions updating password or refresh tokens must roll back cleanly on uncaught exceptions. |
| **NFR-AUTH-04** | Privacy | Passwords, password reset tokens, and refresh tokens must never appear in server log files or audit traces. |
| **NFR-AUTH-05** | Localization | All error messages returned to clients must support UTF-8 Vietnamese descriptions while maintaining English code constants. |
