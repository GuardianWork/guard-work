# Spring Boot Enterprise Application Conventions & Coding Standards

> **Generic & Reusable Framework Template**  
> This specification defines the enterprise architectural standards, coding conventions, shared utility patterns, resource configurations, global exception handling, data migration guidelines, and API Body DTO specs for Spring Boot applications. It can be adapted to any domain or project workspace by replacing placeholder tokens like `<project-name>` and `<base.package>`.

---

## Table of Contents

1. [Spring Boot Application Logic Conventions](#1-spring-boot-application-logic-conventions)
    - [1.1 Package-by-Feature Architecture](#11-package-by-feature-architecture)
    - [1.2 Dependency Injection & Lombok Standards](#12-dependency-injection--lombok-standards)
    - [1.3 Controller Layer Standards](#13-controller-layer-standards)
    - [1.4 Service Layer & Business Logic](#14-service-layer--business-logic)
    - [1.5 DTOs & Input Validation](#15-dtos--input-validation)
    - [1.6 Entity-to-DTO Mapping (MapStruct)](#16-entity-to-dto-mapping-mapstruct)
    - [1.7 Pagination & Dynamic Filtering (RSQL)](#17-pagination--dynamic-filtering-rsql)
    - [1.8 Security Context Access](#18-security-context-access)
    - [1.9 Caching Layer Pattern (`CacheService`)](#19-caching-layer-pattern-cacheservice)
    - [1.10 Asynchronous Messaging & Event Publishing](#110-asynchronous-messaging--event-publishing)
2. [API Body DTO Specifications & JSON Payloads](#2-api-body-dto-specifications--json-payloads)
    - [2.1 Single Resource Success Response (`ApiResponse<T>`)](#21-single-resource-success-response-apiresponset)
    - [2.2 Paginated Resource Response (`ApiResponse<PageResponse<T>>`)](#22-paginated-resource-response-apiresponsepageresponset)
    - [2.3 Error Response Envelope (`ErrorApiResponse`)](#23-error-response-envelope-errorapiresponse)
    - [2.4 Concrete DTO Request Body & Validation Spec](#24-concrete-dto-request-body--validation-spec)
    - [2.5 Concrete DTO Response Body Spec](#25-concrete-dto-response-body-spec)
    - [2.6 Field Validation Error JSON Payload](#26-field-validation-error-json-payload)
3. [Reusable Shared Utils & Components](#3-reusable-shared-utils--components)
    - [3.1 API Response Envelopes Java Code](#31-api-response-envelopes-java-code)
    - [3.2 Pagination Utilities (`PaginationUtils`)](#32-pagination-utilities-paginationutils)
    - [3.3 Security Utilities (`SecurityUtils`)](#33-security-utilities-securityutils)
    - [3.4 Date & Time Utilities (`DateUtils` & `AppDate`)](#34-date--time-utilities-dateutils--appdate)
    - [3.5 Standard Role Enumeration (`PredefinedRole`)](#35-standard-role-enumeration-predefinedrole)
4. [Resource Files & Configuration Architecture](#4-resource-files--configuration-architecture)
    - [4.1 Multi-Profile Configuration Pattern](#41-multi-profile-configuration-pattern)
    - [4.2 Common Application Properties (`application-common.yaml`)](#42-common-application-properties-application-commonyaml)
    - [4.3 Environment Variable Conventions](#43-environment-variable-conventions)
    - [4.4 Logging Configuration (`logback-spring.xml`)](#44-logging-configuration-logback-springxml)
5. [Global Exception Handling & Error System](#5-global-exception-handling--error-system)
    - [5.1 Architecture & Interception Flow](#51-architecture--interception-flow)
    - [5.2 Exception Handling Matrix](#52-exception-handling-matrix)
    - [5.3 Custom Exception (`AppException`) Usage](#53-custom-exception-appexception-usage)
    - [5.4 Error Code Taxonomy Template](#54-error-code-taxonomy-template)
6. [Data Migration & Schema Conventions (Flyway)](#6-data-migration--schema-conventions-flyway)
    - [6.1 Migration File Naming Conventions](#61-migration-file-naming-conventions)
    - [6.2 Immutability Rule](#62-immutability-rule)
    - [6.3 Schema & Table Design Guidelines](#63-schema--table-design-guidelines)
    - [6.4 Spatial & PostGIS Integration Pattern](#64-spatial--postgis-integration-pattern)
    - [6.5 Collation & Full-Text Search Support](#65-collation--full-text-search-support)

---

## 1. Spring Boot Application Logic Conventions

### 1.1 Package-by-Feature Architecture
Organize source code by domain features under `<base.package>` rather than technical layers to promote encapsulation and high cohesion.

```
<base.package>/
├── <feature_a>/        # Feature slice A (Controllers, Services, Repositories, Entities, DTOs)
├── <feature_b>/        # Feature slice B
├── auth/               # Authentication & Authorization domain slice
├── user/               # User management domain slice
├── events/             # Domain Event POJOs (Lombok @Builder)
├── infras/             # External adapters (cache, messaging, storage, notifications)
│   ├── cache/
│   ├── messaging/
│   └── notification/
├── shared/             # Cross-cutting building blocks (api, exception, utils, constant)
│   ├── api/
│   ├── constant/
│   ├── exception/
│   └── utils/
└── config/             # Framework & infrastructure configurations
```

### 1.2 Dependency Injection & Lombok Standards
- **Rule**: Do NOT use field or constructor `@Autowired`.
- **Rule**: Every Spring component (`@Service`, `@RestController`, `@Component`, `@Repository`) MUST use `@RequiredArgsConstructor` and `@FieldDefaults`:

```java
@Service
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = lombok.AccessLevel.PRIVATE)
public class <Domain>Service {
    <Domain>Repository repository;
    <Domain>Mapper mapper;
    CacheService cacheService;
}
```

### 1.3 Controller Layer Standards
- Keep Controllers thin. Validate inputs and delegate core execution to Services.
- Wrap all single item and mutation responses in `ApiResponse<T>`.
- Wrap paginated responses in `ApiResponse<PageResponse<T>>`.

```java
@RestController
@RequestMapping("/api/v1/<resources>")
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = lombok.AccessLevel.PRIVATE)
public class <Domain>Controller {

    <Domain>Service service;

    @GetMapping("/{id}")
    public ApiResponse<<Domain>Response> getById(@PathVariable UUID id) {
        return ApiResponse.success(service.findById(id), "Resource fetched successfully");
    }

    @PostMapping
    public ApiResponse<<Domain>Response> create(@Valid @RequestBody <Domain>CreateRequest request) {
        return ApiResponse.created(service.create(request), "Resource created successfully");
    }

    @GetMapping
    public ApiResponse<PageResponse<<Domain>Response>> getAll(
            @RequestParam(required = false) String filter,
            Pageable pageable) {
        return ApiResponse.success(service.findAll(filter, pageable), "Resources fetched successfully");
    }
}
```

### 1.4 Service Layer & Business Logic
- Encapsulate all transaction boundaries, domain rules, cache eviction, and event emissions within `@Service` classes.
- Throw custom `AppException` with an appropriate `ErrorCode` whenever validation or entity retrieval fails.
- Pass incoming `Pageable` parameters through `PaginationUtils.validateAndBound(pageable)`.

### 1.5 DTOs & Input Validation
- Implement DTOs (Requests and Responses) as immutable Java **Records**.
- Apply `jakarta.validation` annotations (`@NotNull`, `@NotBlank`, `@Size`, `@Email`, `@Min`, `@Max`, `@DecimalMin`, `@DecimalMax`) to fields in request records.

### 1.6 Entity-to-DTO Mapping (MapStruct)
- Convert between Entities and DTO Records using MapStruct mappers (`componentModel = "spring"`).

```java
@Mapper(componentModel = "spring")
public interface <Domain>Mapper {
    <Domain>Response toResponse(<Domain>Entity entity);
    <Domain>Entity toEntity(<Domain>CreateRequest request);
}
```

### 1.7 Pagination & Dynamic Filtering (RSQL)
- Enforce standard page size boundaries to prevent database overhead.
- Use `PaginationUtils` to bound page sizes (Default: **5**, Max: **20**).
- Support dynamic RSQL queries via JPA Specifications (`RSQLJPASupport`):

```java
public PageResponse<<Domain>Response> findAll(String filter, Pageable pageable) {
    Pageable bounded = PaginationUtils.validateAndBound(pageable);
    
    Page<<Domain>Entity> page = StringUtils.hasText(filter)
        ? repository.findAll(RSQLJPASupport.toSpecification(filter), bounded)
        : repository.findAll(bounded);
        
    return PaginationUtils.toPageResponse(page, mapper::toResponse);
}
```

### 1.8 Security Context Access
- Extract principal user details exclusively through helper utilities like `SecurityUtils.getCurrentUserId()`.

[//]: # ()
[//]: # (### 1.9 Caching Layer Pattern &#40;`CacheService`&#41;)

[//]: # (- Interact with Redis exclusively through `CacheService`. Direct injection of `RedisTemplate` in domain services is forbidden.)

[//]: # ()
[//]: # (### 1.10 Asynchronous Messaging & Event Publishing)

[//]: # (- Store domain event POJOs in `events/` using Lombok `@Builder`.)

[//]: # (- Publish events through `infras.messaging.KafkaEventPublisher`.)

---

## 2. API Body DTO Specifications & JSON Payloads

### 2.1 Single Resource Success Response (`ApiResponse<T>`)

#### Java Definition:
```java
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
    T data,
    String message,
    int status
) {}
```

#### HTTP Response JSON Payload (`HTTP 200 OK`):
```json
{
  "status": 200,
  "message": "Resource fetched successfully",
  "data": {
    "id": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
    "name": "Sample Item",
    "createdAt": "2026-10-06T18:00:00+07:00"
  }
}
```

---

### 2.2 Paginated Resource Response (`ApiResponse<PageResponse<T>>`)

#### Java Definition (`PageResponse<T>`):
```java
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageResponse<T>(
    List<T> content,
    int pageNumber,
    int pageSize,
    long totalElements,
    int totalPages,
    boolean last,
    boolean first
) {}
```

#### Query Request URL:
`GET /api/v1/resources?page=0&size=5&sort=name,asc&filter=name=='Sample*'`

#### HTTP Response JSON Payload (`HTTP 200 OK`):
```json
{
  "status": 200,
  "message": "Resources fetched successfully",
  "data": {
    "content": [
      {
        "id": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
        "name": "Sample Item 1",
        "price": 45000.00
      },
      {
        "id": "b1eebc99-9c0b-4ef8-bb6d-6bb9bd380a22",
        "name": "Sample Item 2",
        "price": 60000.00
      }
    ],
    "pageNumber": 0,
    "pageSize": 5,
    "totalElements": 2,
    "totalPages": 1,
    "first": true,
    "last": true
  }
}
```

---

### 2.3 Error Response Envelope (`ErrorApiResponse`)

#### Java Definition:
```java
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorApiResponse(
    String message,
    int status,
    @JsonProperty("detailed_message")
    String detailMessage,
    Map<String, String> errors
) {}
```

#### HTTP Response JSON Payload for Business Exception (`HTTP 400 Bad Request`):
```json
{
  "status": 40030,
  "message": "The resource could not be found.",
  "detailed_message": "Resource with ID a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11 does not exist"
}
```

---

### 2.4 Concrete DTO Request Body & Validation Spec

#### Java Record Definition:
```java
public record ResourceCreateRequest(
    @NotBlank(message = "Name is required")
    @Size(max = 150, message = "Name must not exceed 150 characters")
    String name,

    @Size(max = 255, message = "Description line must be under 255 characters")
    String description,

    @NotNull(message = "Category ID is required")
    UUID categoryId,

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Price must be greater than 0")
    BigDecimal price,

    @DecimalMin(value = "-90.0", message = "Latitude must be >= -90")
    @DecimalMax(value = "90.0", message = "Latitude must be <= 90")
    double latitude,

    @DecimalMin(value = "-180.0", message = "Longitude must be >= -180")
    @DecimalMax(value = "180.0", message = "Longitude must be <= 180")
    double longitude
) {}
```

#### HTTP Request JSON Payload (`POST /api/v1/resources`):
```json
{
  "name": "Fresh Garden Salad",
  "description": "Crispy lettuce, cherry tomatoes, olive oil dressing",
  "categoryId": "c39e858f-2878-43d7-83d7-efbc00624001",
  "price": 55000.00,
  "latitude": 10.7769,
  "longitude": 106.7009
}
```

---

### 2.5 Concrete DTO Response Body Spec

#### Java Record Definition:
```java
public record ResourceResponse(
    UUID id,
    String name,
    String description,
    BigDecimal price,
    boolean available,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
```

#### HTTP Response JSON Payload (`HTTP 201 Created`):
```json
{
  "status": 201,
  "message": "Resource created successfully",
  "data": {
    "id": "d40e858f-2878-43d7-83d7-efbc00624999",
    "name": "Fresh Garden Salad",
    "description": "Crispy lettuce, cherry tomatoes, olive oil dressing",
    "price": 55000.00,
    "available": true,
    "createdAt": "2026-10-06T18:35:00+07:00",
    "updatedAt": "2026-10-06T18:35:00+07:00"
  }
}
```

---

### 2.6 Field Validation Error JSON Payload

When validation fails on a `@Valid @RequestBody` parameter, `GlobalExceptionalHandler` catches `BindException` and produces:

#### HTTP Response JSON Payload (`HTTP 400 Bad Request`):
```json
{
  "message": "Invalid Request Data",
  "detailed_message": "Invalid input. Please check your data",
  "errors": {
    "name": "Name is required",
    "price": "Price must be greater than 0",
    "categoryId": "Category ID is required"
  }
}
```

---

## 3. Reusable Shared Utils & Components

Located under `<base.package>.shared`.

### 3.1 API Response Envelopes Java Code
- `ApiResponse<T>`: Static builder helper methods (`success(data, message)`, `created(data, message)`).
- `PageResponse<T>`: Static mapping factory method (`PageResponse.of(Page<T> page, List<R> content)`).
- `ErrorApiResponse`: Standardized error structure.

### 3.2 Pagination Utilities (`PaginationUtils`)
```java
public final class PaginationUtils {
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 20;

    public static Pageable validateAndBound(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, DEFAULT_PAGE_SIZE);
        }
        int size = Math.min(Math.max(1, pageable.getPageSize()), MAX_PAGE_SIZE);
        int page = Math.max(0, pageable.getPageNumber());
        return PageRequest.of(page, size, pageable.getSort());
    }

    public static <T, R> PageResponse<R> toPageResponse(Page<T> page, Function<T, R> mapper) {
        if (page == null) return null;
        List<R> content = page.getContent().stream().map(mapper).toList();
        return PageResponse.of(page, content);
    }
}
```

### 3.3 Security Utilities (`SecurityUtils`)
```java
public class SecurityUtils {
    public static String getCurrentUserId() {
        SecurityContext context = SecurityContextHolder.getContext();
        Authentication authentication = context.getAuthentication();
        if (authentication == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        if (authentication.getPrincipal() instanceof UserDetails userDetails) {
            return userDetails.getUsername();
        } else if (authentication.getPrincipal() instanceof String s) {
            return s;
        }
        throw new AppException(ErrorCode.UNAUTHENTICATED);
    }
}
```

### 3.4 Date & Time Utilities (`DateUtils` & `AppDate`)
- **`AppDate.now()`**: Returns current `OffsetDateTime` configured to application zone (`Asia/Ho_Chi_Minh` / UTC+7).
- **`DateUtils.toOffsetDateTime(Instant instant)`**: Converts `Instant` to `OffsetDateTime`.

### 3.5 Standard Role Enumeration (`PredefinedRole`)
```java
public enum PredefinedRole {
    USER("USER"),
    OWNER("OWNER"),
    ADMIN("ADMIN");
}
```

---

## 4. Resource Files & Configuration Architecture

Located under `src/main/resources`.

### 4.1 Multi-Profile Configuration Pattern
Organize configuration files using Spring Boot profile grouping and environment-specific property files:

- **Profile File Structure**:
  - `application.yaml`: Root loader that sets the default active profile and defines profile groups.
  - `application-common.yaml`: Base shared settings (e.g., JPA, Flyway, Jackson, pagination defaults) inherited across all profiles.
  - `application-{profile}.yaml` (e.g., `application-local.yaml`, `application-dev.yaml`, `application-prod.yaml`): Environment-specific overrides (database URLs, credentials, logging levels, caching/messaging adapters).
- **Profile Grouping**: Configured via `spring.profiles.group` so that activating an environment profile (e.g., `local`, `dev`, `prod`) automatically loads `common` properties.
- **Activation & Overrides**:
  - Default profile fallback is `local` using `${SPRING_PROFILES_ACTIVE:local}`.
  - Override via environment variable `SPRING_PROFILES_ACTIVE=<profile>` or JVM argument `-Dspring.profiles.active=<profile>` at runtime without rebuilding the application artifact.

```yaml
# application.yaml (Root loader)
spring:
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:local}
    group:
      local: common
      dev: common
      prod: common
```

### 4.2 Common Application Properties (`application-common.yaml`)
Template for property settings:

```yaml
spring:
  application:
    name: <project-name>
  datasource:
    url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    driver-class-name: org.postgresql.Driver

  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true

  jpa:
    hibernate:
      ddl-auto: none
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true

  jwt:
    secret: ${JWT_SECRET}
    expiration:
      access-token: 900000     # 15 minutes
      refresh-token: 604800000 # 7 days
```

### 4.3 Environment Variable Conventions
Environment variables MUST use `UPPER_SNAKE_CASE`:
- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`
- `REDIS_HOST`, `REDIS_PORT`
- `KAFKA_BOOTSTRAP_SERVERS`
- `JWT_SECRET`

---

## 5. Global Exception Handling & Error System

### 5.1 Architecture & Interception Flow
Centralized error handling is structured into `@RestControllerAdvice` classes annotated with `@Hidden`:
1. `GlobalExceptionalHandler`: Catches application business exceptions (`AppException`), bean validation failures (`BindException`), and missing endpoints (`NoResourceFoundException`).
2. `InternalServerExceptionHandler`: Serves as a fallback for unhandled exceptions (`RuntimeException`, `Exception`), logging full stack traces and returning HTTP `500`.

### 5.2 Exception Handling Matrix

| Exception Class | Interceptor Method | HTTP Status | Response Object |
|---|---|---|---|
| `AppException` | `handleAppException()` | `400 BAD_REQUEST` | `ErrorApiResponse` |
| `BindException` | `handlingBindException()` | `400 BAD_REQUEST` | `ErrorApiResponse` (with `errors` map) |
| `NoResourceFoundException` | `handleNoResourceFoundException()` | `404 NOT_FOUND` | `ErrorApiResponse` |
| `RuntimeException` / `Exception` | `handleException()` | `500 INTERNAL_SERVER_ERROR` | `ErrorApiResponse` |

### 5.3 Custom Exception (`AppException`) Usage
Throw `AppException` with an `ErrorCode`:

```java
throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
throw new AppException(ErrorCode.INVALID_INPUT, "Detailed description of validation failure");
```

### 5.4 Error Code Taxonomy Template

| Numeric Range | Domain Category | HTTP Status | Example Codes |
|---|---|---|---|
| `40000` | General Input Validation | 400 BAD REQUEST | `INVALID_INPUT` |
| `40001–40019` | Authentication & User Management | 400 BAD REQUEST | `INVALID_CREDENTIALS`, `USER_ALREADY_EXISTS`, `USER_NOT_FOUND`, `INVALID_OTP` |
| `40020–40099` | Feature Domain Business Rules | 400 BAD REQUEST | `<DOMAIN>_NOT_FOUND`, `ACTION_NOT_ALLOWED`, `RESOURCE_STATE_INVALID` |
| `40100–40199` | Unauthenticated / Token Issues | 401 UNAUTHORIZED | `UNAUTHENTICATED`, `INVALID_TOKEN`, `EXPIRED_TOKEN` |
| `40300–40399` | Forbidden / Permission Denied | 403 FORBIDDEN | `FORBIDDEN`, `ACCOUNT_NOT_VERIFIED` |
| `40400–40499` | Resource Not Found | 404 NOT FOUND | `RESOURCE_NOT_FOUND` |
| `42900–42999` | Rate Limiting | 429 TOO MANY REQUESTS | `TOO_MANY_REQUESTS` |

---

## 6. Data Migration & Schema Conventions (Flyway)

Located under `src/main/resources/db/migration/`.

### 6.1 Migration File Naming Conventions
- **Format**: `V{Version}__{short_description}.sql`
- **Rule**: Use a **double underscore** (`__`) between version and description.

### 6.2 Immutability Rule
- Migration scripts are **immutable** once committed. Always append a new version script for schema changes.

### 6.3 Schema & Table Design Guidelines
- Primary Keys: Use UUIDs generated via `gen_random_uuid()`.
- Timestamps: Use `TIMESTAMPTZ` with `DEFAULT now()`.
- Foreign Keys: Include explicit constraint names and ON DELETE directives.

```sql
CREATE TABLE <table_name> (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name        VARCHAR(150) NOT NULL,
  status      VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```
