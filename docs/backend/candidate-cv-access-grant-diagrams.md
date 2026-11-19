# Sơ đồ access grant cho CV ứng tuyển

Tài liệu này chứa Mermaid source cho vòng đời access grant và các trình tự chính của luồng CV ứng
tuyển. Sơ đồ chỉ mô tả hành vi đã triển khai; GW-SR-CV-02 là nguồn chuẩn khi có mâu thuẫn.


## 1. Vòng đời access grant

```mermaid
%%{init: {'theme': 'base', 'themeVariables': {'primaryColor': '#ffffff', 'primaryTextColor': '#333333', 'primaryBorderColor': '#333333', 'secondaryColor': '#ffffff', 'tertiaryColor': '#ffffff', 'mainBkg': '#ffffff', 'nodeBorder': '#333333', 'textColor': '#333333', 'lineColor': '#333333', 'clusterBkg': '#ffffff', 'clusterBorder': '#333333', 'edgeLabelBackground': '#ffffff', 'noteBkgColor': '#ffffff', 'noteBorderColor': '#333333', 'noteTextColor': '#333333', 'actorBkg': '#ffffff', 'actorBorder': '#333333', 'actorTextColor': '#333333', 'actorLineColor': '#333333', 'signalColor': '#333333', 'signalTextColor': '#333333', 'labelBoxBkgColor': '#ffffff', 'labelBoxBorderColor': '#333333', 'labelTextColor': '#333333', 'loopTextColor': '#333333', 'activationBkgColor': '#ffffff', 'activationBorderColor': '#333333', 'sequenceNumberColor': '#333333', 'fontFamily': 'Helvetica, Arial, sans-serif', 'fontSize': '13px'}, 'themeCSS': '.node rect, .statediagram-state rect, .statediagram-note rect, .note, .actor, .labelBox { rx: 0px !important; ry: 0px !important; }'}}%%
stateDiagram-v2

    state "Grant issued<br/>(epoch 1)" as Issued
    state "Live grant" as Live
    state "Review window<br/>(expires_at = deletion + 30d)" as Window
    state "INACTIVE<br/>(grant revoked)" as Inactive
    state "Expired<br/>(inert audit row)" as Expired
    state "Closed<br/>(terminal, grant revoked)" as Closed

    [*] --> Issued: application submitted
    Issued --> Live: company authorized
    Live --> Window: candidate deletes CV<br/>(active review stage)
    Live --> Inactive: 30 days without transition
    Live --> Closed: REJECTED / WITHDRAWN
    Live --> Closed: HIRED + CV deleted
    Window --> Expired: review window elapses
    Window --> Inactive: inactivity before expiry
    Window --> Closed: terminal before expiry
    Inactive --> Live: reactivate via UNDER_REVIEW<br/>(library CV exists, new epoch)
    Inactive --> Closed: REJECTED / WITHDRAWN
    Expired --> [*]
    Closed --> [*]

    note right of Window
        Review window applies only to
        SUBMITTED, UNDER_REVIEW,
        SHORTLISTED, INTERVIEWING.
        HIRED deletion revokes immediately.
    end note

    note right of Inactive
        Reactivation without a library CV
        changes application status only:
        the grant stays revoked and no epoch is added.
    end note


```

## 2. Trình tự: cấp grant và công ty đọc CV

```mermaid
%%{init: {'theme': 'base', 'themeVariables': {'primaryColor': '#ffffff', 'primaryTextColor': '#333333', 'primaryBorderColor': '#333333', 'secondaryColor': '#ffffff', 'tertiaryColor': '#ffffff', 'mainBkg': '#ffffff', 'nodeBorder': '#333333', 'textColor': '#333333', 'lineColor': '#333333', 'clusterBkg': '#ffffff', 'clusterBorder': '#333333', 'edgeLabelBackground': '#ffffff', 'noteBkgColor': '#ffffff', 'noteBorderColor': '#333333', 'noteTextColor': '#333333', 'actorBkg': '#ffffff', 'actorBorder': '#333333', 'actorTextColor': '#333333', 'actorLineColor': '#333333', 'signalColor': '#333333', 'signalTextColor': '#333333', 'labelBoxBkgColor': '#ffffff', 'labelBoxBorderColor': '#333333', 'labelTextColor': '#333333', 'loopTextColor': '#333333', 'activationBkgColor': '#ffffff', 'activationBorderColor': '#333333', 'sequenceNumberColor': '#333333', 'fontFamily': 'Helvetica, Arial, sans-serif', 'fontSize': '13px'}, 'themeCSS': '.node rect, .statediagram-state rect, .statediagram-note rect, .note, .actor, .labelBox { rx: 0px !important; ry: 0px !important; }'}}%%
sequenceDiagram
    autonumber
    actor Candidate
    actor Company
    participant API as Application API
    participant DB as PostgreSQL
    participant Storage as Cloudinary

    Candidate->>API: POST /api/candidate/applications<br/>{ cvId }
    API->>DB: lock CV, insert snapshot (SHARED), insert grant epoch 1
    DB-->>API: committed
    API-->>Candidate: 201 application created

    Company->>API: GET .../applications/{id}/cv<br/>(or /cv/download-url)
    API->>DB: check membership, role, assignment, status
    API->>DB: find live grant (application_id, company_id)

    alt live grant exists
        DB-->>API: live grant
        API->>Storage: read CV or create 5-minute URL
        Storage-->>API: bytes or signed URL
        API-->>Company: PDF inline or short-lived URL
    else missing, expired, revoked, or terminal
        DB-->>API: no live grant
        API-->>Company: 404 (concealed)
    end

    Note over API,DB: snapshot cv_storage_key is descriptive,<br/>the grant is the only authority
```

## 3. Trình tự: ứng viên xóa CV và cửa sổ review

```mermaid
%%{init: {'theme': 'base', 'themeVariables': {'primaryColor': '#ffffff', 'primaryTextColor': '#333333', 'primaryBorderColor': '#333333', 'secondaryColor': '#ffffff', 'tertiaryColor': '#ffffff', 'mainBkg': '#ffffff', 'nodeBorder': '#333333', 'textColor': '#333333', 'lineColor': '#333333', 'clusterBkg': '#ffffff', 'clusterBorder': '#333333', 'edgeLabelBackground': '#ffffff', 'noteBkgColor': '#ffffff', 'noteBorderColor': '#333333', 'noteTextColor': '#333333', 'actorBkg': '#ffffff', 'actorBorder': '#333333', 'actorTextColor': '#333333', 'actorLineColor': '#333333', 'signalColor': '#333333', 'signalTextColor': '#333333', 'labelBoxBkgColor': '#ffffff', 'labelBoxBorderColor': '#333333', 'labelTextColor': '#333333', 'loopTextColor': '#333333', 'activationBkgColor': '#ffffff', 'activationBorderColor': '#333333', 'sequenceNumberColor': '#333333', 'fontFamily': 'Helvetica, Arial, sans-serif', 'fontSize': '13px'}, 'themeCSS': '.node rect, .statediagram-state rect, .statediagram-note rect, .note, .actor, .labelBox { rx: 0px !important; ry: 0px !important; }'}}%%
sequenceDiagram
    autonumber
    actor Candidate
    actor Company
    participant API as Application API
    participant DB as PostgreSQL
    participant Worker as Retention worker
    participant Storage as Cloudinary

    Candidate->>API: DELETE /api/candidate/cvs/{cvId}
    API->>DB: set candidate_cvs.deleted_at (tombstone)
    API->>DB: set expires_at = now + 30d for active-stage grants
    API->>DB: revoke HIRED grants immediately
    API-->>Candidate: 204

    Note over API,DB: candidate loses own submitted-CV access immediately,<br/>object is kept while a live grant remains

    Company->>API: GET .../cv inside review window
    API->>DB: find live grant (expires_at > now)
    DB-->>API: live grant
    API->>Storage: read CV
    Storage-->>API: bytes
    API-->>Company: PDF

    Worker->>DB: find tombstoned CV with no live grant
    DB-->>Worker: purge candidate
    Worker->>Storage: delete library object
    Worker->>DB: set storage_deleted_at

    Note over Worker,DB: an expired grant does not block purge,<br/>storage failure is retried on the next tick
```

## 4. Trình tự: INACTIVE và mở lại đơn

```mermaid
%%{init: {'theme': 'base', 'themeVariables': {'primaryColor': '#ffffff', 'primaryTextColor': '#333333', 'primaryBorderColor': '#333333', 'secondaryColor': '#ffffff', 'tertiaryColor': '#ffffff', 'mainBkg': '#ffffff', 'nodeBorder': '#333333', 'textColor': '#333333', 'lineColor': '#333333', 'clusterBkg': '#ffffff', 'clusterBorder': '#333333', 'edgeLabelBackground': '#ffffff', 'noteBkgColor': '#ffffff', 'noteBorderColor': '#333333', 'noteTextColor': '#333333', 'actorBkg': '#ffffff', 'actorBorder': '#333333', 'actorTextColor': '#333333', 'actorLineColor': '#333333', 'signalColor': '#333333', 'signalTextColor': '#333333', 'labelBoxBkgColor': '#ffffff', 'labelBoxBorderColor': '#333333', 'labelTextColor': '#333333', 'loopTextColor': '#333333', 'activationBkgColor': '#ffffff', 'activationBorderColor': '#333333', 'sequenceNumberColor': '#333333', 'fontFamily': 'Helvetica, Arial, sans-serif', 'fontSize': '13px'}, 'themeCSS': '.node rect, .statediagram-state rect, .statediagram-note rect, .note, .actor, .labelBox { rx: 0px !important; ry: 0px !important; }'}}%%
sequenceDiagram
    autonumber
    actor Company
    participant API as Application API
    participant DB as PostgreSQL
    participant Worker as Inactivity worker
    participant Notify as Notification outbox

    Worker->>DB: find applications idle for 30 days
    DB-->>Worker: due application ids
    Worker->>DB: set INACTIVE, append history, revoke grant
    Worker->>Notify: APPLICATION_INACTIVE
    Notify-->>Company: owner and currently assigned recruiters

    Note over Company,Notify: the candidate sees INACTIVE<br/>but receives no inactivity notification

    Company->>API: PATCH status UNDER_REVIEW (reactivate)
    API->>DB: lock application, verify owner or assigned recruiter
    API->>DB: check source library CV is still active

    alt shared CV exists
        API->>DB: insert grant epoch N+1
        DB-->>API: new live grant
        API-->>Company: 200 reactivated with CV access
    else CV was deleted
        API-->>Company: 200 reactivated without CV access
    end
```

## 5. Trình tự: kết thúc đơn và purge bản sao cũ

```mermaid
%%{init: {'theme': 'base', 'themeVariables': {'primaryColor': '#ffffff', 'primaryTextColor': '#333333', 'primaryBorderColor': '#333333', 'secondaryColor': '#ffffff', 'tertiaryColor': '#ffffff', 'mainBkg': '#ffffff', 'nodeBorder': '#333333', 'textColor': '#333333', 'lineColor': '#333333', 'clusterBkg': '#ffffff', 'clusterBorder': '#333333', 'edgeLabelBackground': '#ffffff', 'noteBkgColor': '#ffffff', 'noteBorderColor': '#333333', 'noteTextColor': '#333333', 'actorBkg': '#ffffff', 'actorBorder': '#333333', 'actorTextColor': '#333333', 'actorLineColor': '#333333', 'signalColor': '#333333', 'signalTextColor': '#333333', 'labelBoxBkgColor': '#ffffff', 'labelBoxBorderColor': '#333333', 'labelTextColor': '#333333', 'loopTextColor': '#333333', 'activationBkgColor': '#ffffff', 'activationBorderColor': '#333333', 'sequenceNumberColor': '#333333', 'fontFamily': 'Helvetica, Arial, sans-serif', 'fontSize': '13px'}, 'themeCSS': '.node rect, .statediagram-state rect, .statediagram-note rect, .note, .actor, .labelBox { rx: 0px !important; ry: 0px !important; }'}}%%
sequenceDiagram
    autonumber
    actor Candidate
    actor Company
    participant API as Application API
    participant DB as PostgreSQL
    participant Worker as Legacy purge worker
    participant Storage as Cloudinary

    Company->>API: PATCH status REJECTED
    API->>DB: set REJECTED, revoke grant (APPLICATION_CLOSED)

    Worker->>DB: find REJECTED LEGACY_COPY rows
    DB-->>Worker: purge candidates
    Worker->>Storage: delete application-specific copy
    Worker->>DB: set application_snapshots.cv_purged_at

    Note over Worker,DB: snapshot metadata is retained,<br/>a storage failure leaves cv_purged_at null for retry

    Candidate->>API: POST /api/candidate/applications/{id}/withdraw
    API->>DB: set WITHDRAWN, revoke grant

    Note over API,DB: WITHDRAWN retains the legacy copy,<br/>the shared library object is never deleted by this path
```

## 6. Quy ước đọc sơ đồ

- `SHARED`: đơn tham chiếu CV thư viện; công ty đọc qua grant.
- `LEGACY_COPY`: đơn cũ có bản sao riêng; không backfill grant.
- `Live grant`: `revoked_at IS NULL` và `expires_at` còn hiệu lực.
- `Expired`: grant hết hạn nhưng vẫn là audit row; không cấp quyền.
- `INACTIVE`: đơn bị thu hồi quyền CV; mở lại qua `UNDER_REVIEW` chỉ tạo epoch mới khi CV thư viện
  vẫn tồn tại. Nếu CV đã bị xóa, đơn có thể mở lại nhưng không có quyền đọc CV.
- Mọi trường hợp thiếu grant, grant hết hạn/bị thu hồi, đơn terminal hoặc `INACTIVE` đều trả
  `404` không liệt kê tài nguyên.
