# Kịch bản thuyết trình — Kiểm soát truy cập CV ứng tuyển (Access Grant)

| Trường | Giá trị |
|---|---|
| **Mã tài liệu** | GW-OPS-CV-ACL-02 |
| **Mục đích** | Kịch bản trình bày cơ chế access grant cho CV ứng tuyển |
| **Đối tượng** | Giảng viên hướng dẫn, hội đồng, thành viên nhóm chưa nắm phần backend |
| **Thời lượng** | 15 phút trình bày + 5 phút Q&A |
| **Tài liệu minh họa** | [`candidate-cv-access-grant-diagrams.md`](candidate-cv-access-grant-diagrams.md) |
| **Nguồn chuẩn** | GW-SR-CV-02 (`../software-requirements/can/candidate-cv-access-retention-spec.md`) |
| **Trạng thái** | Tài liệu nội bộ — phục vụ thuyết trình, không thay thế đặc tả |

> **Thông điệp xuyên suốt:** *“Một CV, chỉ hai người được đọc, và quyền đọc luôn có thời hạn.”*

---

## 0. Câu mở đầu 30 giây

> “Trong GuardWork, ứng viên nộp CV cho công ty. Vấn đề bảo mật là: sau khi nộp, ai còn được
> đọc file đó, trong bao lâu, và làm sao chứng minh được điều đó bằng code chứ không chỉ bằng
> lời hứa. Nhóm em giải quyết bằng một bảng `candidate_cv_access_grants` — mỗi dòng là một
> **giấy phép có thời hạn** cho đúng một công ty, đọc đúng một CV, cho đúng một đơn ứng tuyển.
> Mọi endpoint đọc CV đều phải đi qua giấy phép này.”

---

## 1. Phân bổ thời gian

| Phần | Nội dung | Sơ đồ | Thời lượng |
|---|---|---|---|
| 1 | Bài toán và nguyên tắc thiết kế | — | 2 phút |
| 2 | Vòng đời của một grant | Sơ đồ 1 | 3 phút |
| 3 | Cấp grant và công ty đọc CV | Sơ đồ 2 | 3 phút |
| 4 | Ứng viên xóa CV và cửa sổ review | Sơ đồ 3 | 3 phút |
| 5 | `INACTIVE` và mở lại đơn | Sơ đồ 4 | 2 phút |
| 6 | Kết thúc đơn và purge bản sao cũ | Sơ đồ 5 | 2 phút |
| 7 | Các lớp bảo vệ và tổng kết | — | 2 phút |

---

## 2. Chi tiết lời thoại

### Phần 1 — Bài toán và nguyên tắc thiết kế (2 phút)

**Lời thoại:**

> “Cách làm cũ là mỗi lần ứng viên nộp đơn, hệ thống copy file CV thành một bản riêng cho đơn
> đó. Cách này có ba hệ quả: tốn dung lượng, ứng viên xóa CV trong thư viện thì bản copy vẫn
> còn, và công ty có thể đọc vĩnh viễn. Nhóm em đổi sang hướng khác: **không copy nữa**, chỉ lưu
> một tham chiếu đến CV thư viện, và cấp một grant để kiểm soát quyền đọc.”

Ba nguyên tắc cần nhấn:

1. **Grant là nguồn quyền duy nhất.** `application_snapshots.cv_storage_key` chỉ là dữ liệu mô
   tả. Biết storage key không đủ để đọc CV — phải có grant còn hiệu lực. Nếu hội đồng chỉ nhớ
   một ý, hãy nhớ ý này.
2. **Quyền gắn với công ty, không gắn với recruiter.** Recruiter mới được phân công vẫn đọc được
   nếu grant còn hiệu lực và assignment còn hiệu lực; ngược lại, recruiter bị gỡ assignment thì
   mất quyền dù grant chưa hết hạn.
3. **Fail closed và fail im lặng.** Thiếu grant, grant hết hạn, grant bị thu hồi, sai công ty,
   đơn đã kết thúc — tất cả trả về cùng một lỗi `404` (`40478 JOB_NOT_FOUND`). Không được để lộ
   “grant tồn tại nhưng đã hết hạn”, vì như vậy là rò rỉ thông tin.

> *Chuyển ý:* “Để thấy grant sống, chết và được cấp lại như thế nào, mình đi qua sơ đồ vòng đời.”

---

### Phần 2 — Vòng đời của một grant (3 phút) — Sơ đồ 1

**Lời thoại:**

> “Đây là state diagram của grant. Có thể đọc theo ba nhóm trạng thái: **đang sống**, **đang chờ
> hết hạn**, và **đã chết**.”

- **Issued → Live:** ứng viên nộp đơn. Grant epoch 1 được tạo trong cùng transaction với
  application, snapshot và status history.
- **Live → Window:** ứng viên xóa CV khỏi thư viện. Grant không bị thu hồi ngay mà được đặt
  `expires_at`. Đây là **cửa sổ review** — công ty vẫn đọc được trong thời gian cấu hình. Cần nói
  rõ: *cửa sổ bắt đầu từ lúc ứng viên xóa, không phải từ lúc nộp đơn.*
- **Live → Inactive:** 30 ngày không có chuyển trạng thái nào. Grant bị thu hồi với lý do
  `APPLICATION_INACTIVE`.
- **Live → Closed:** `REJECTED` hoặc `WITHDRAWN` thu hồi ngay với lý do `APPLICATION_CLOSED`.
  Riêng `HIRED` + ứng viên xóa CV thì thu hồi ngay với lý do `CANDIDATE_CV_DELETED` — đơn đã
  tuyển thì không có cửa sổ review.
- **Inactive → Live:** mở lại **chỉ qua `UNDER_REVIEW`**, và **chỉ tạo epoch mới nếu CV thư viện
  vẫn còn**. Nếu ứng viên đã xóa CV, đơn mở lại được nhưng không có quyền đọc CV. Đây là chi
  tiết dễ làm sai: mở lại đơn không đồng nghĩa khôi phục quyền đọc file.

**Câu chốt:** “Grant không bao giờ được sửa. Nó chỉ có thể bị giới hạn thời gian một lần, hoặc
bị thu hồi một lần. Mọi thay đổi khác đều sinh epoch mới.”

> *Chuyển ý:* “Bây giờ mình xem grant được cấp và được dùng ở tầng API như thế nào.”

---

### Phần 3 — Cấp grant và công ty đọc CV (3 phút) — Sơ đồ 2

**Lời thoại (nửa đầu — luồng nộp đơn):**

> “Ứng viên gọi `POST /api/candidate/applications` với `cvId`. Service làm bốn việc trong **một
> transaction**:
>
> 1. Khóa row `candidate_cvs` để tránh trường hợp ứng viên xóa CV đúng lúc đang nộp;
> 2. Tạo application;
> 3. Tạo snapshot với `cv_storage_mode = 'SHARED'` — storage key ở đây chỉ để mô tả;
> 4. Tạo grant epoch 1, **không có `expires_at`**.
>
> Không có byte nào được copy. Và mọi thông tin về company, storage key đều do server quyết
> định — client không được gửi lên.”

**Lời thoại (nửa sau — luồng đọc):**

> “Khi công ty gọi `/cv` hoặc `/cv/download-url`, hệ thống kiểm tra theo thứ tự: membership còn
> active → vai trò `OWNER` đúng chủ hoặc `RECRUITER` → công ty đã `VERIFIED` → job không bị treo
> → recruiter có assignment hiện tại → application thuộc đúng job và công ty → trạng thái đơn
> không phải `REJECTED`/`WITHDRAWN`/`INACTIVE` → cuối cùng mới tìm **live grant** theo
> `(application_id, company_id)`.”

**Định nghĩa live grant — nói chậm, rõ:**

> “Live grant là `revoked_at IS NULL` **và** (`expires_at IS NULL` **hoặc**
> `expires_at > now()`).”

- Có grant → đọc file, hoặc tạo signed URL sống **5 phút**. Inline chỉ nhận PDF, DOC/DOCX trả
  `406`. Inline có ghi audit `APPLICATION_CV_VIEWED`; phát URL thì không ghi.
- Không có grant → `404` ẩn, không giải thích lý do.

**Nhấn mạnh:** “Kể cả khi snapshot có `cv_storage_key` hợp lệ, nếu grant không live thì **không
có nhánh fallback**. Đây là yêu cầu `CVACL-032`, không phải chỉ là quy ước.”

> *Chuyển ý:* “Vậy khi ứng viên xóa CV thì chuyện gì xảy ra với quyền của công ty?”

---

### Phần 4 — Ứng viên xóa CV và cửa sổ review (3 phút) — Sơ đồ 3

**Lời thoại:**

> “Ứng viên gọi `DELETE /api/candidate/cvs/{cvId}`. Hệ thống **không xóa row** — row trở thành
> tombstone bằng cách set `deleted_at`. Lý do: grant đang tham chiếu tới CV này qua khóa ngoại,
> và worker purge cần một row còn tồn tại để retry.”

Bốn bước trong service:

1. Set `deleted_at`, bỏ cờ default → CV biến mất khỏi danh sách, quota và endpoint tải của chính
   ứng viên **ngay lập tức**.
2. Với các grant đang live mà đơn ở giai đoạn hoạt động (`SUBMITTED`, `UNDER_REVIEW`,
   `SHORTLISTED`, `INTERVIEWING`): set `expires_at = now + TTL`. Chỉ set khi `expires_at` đang
   null — không bao giờ gia hạn grant đã có hạn.
3. Với đơn `HIRED`: thu hồi ngay, lý do `CANDIDATE_CV_DELETED`. Không có cửa sổ.
4. Nếu không còn grant nào cần giữ file → xóa object trên Cloudinary ngay và ghi
   `storage_deleted_at`. Nếu Cloudinary lỗi → **không làm fail request của ứng viên**, để worker
   retry.

**Điểm kỹ thuật đáng nói:**

> “Trong cửa sổ review, công ty vẫn đọc được vì grant còn live. Nhưng ứng viên thì không — quyền
> của chủ sở hữu và quyền của công ty là hai đường độc lập.”

**Về retention worker:**

> “Worker tìm các CV đã tombstone, chưa xóa storage, và **không còn grant nào live**. Xử lý theo
> batch có giới hạn, khóa row bằng `FOR UPDATE ... SKIP LOCKED`, commit từng cái. Xóa xong thì set
> `storage_deleted_at`; lỗi thì giữ nguyên để tick sau retry.”

**Câu chốt quan trọng:** “Purge chỉ là vệ sinh dung lượng. **Cơ chế kiểm soát truy cập là grant,
được enforce lúc đọc.** Vì vậy purge chậm vài phút cũng không làm quyền bị kéo dài.”

> *Chuyển ý:* “Còn một trạng thái đặc biệt: đơn bị bỏ quên.”

---

### Phần 5 — `INACTIVE` và mở lại đơn (2 phút) — Sơ đồ 4

**Lời thoại:**

> “Nếu công ty không đổi trạng thái đơn trong 30 ngày, đơn chuyển `INACTIVE`. Hệ thống đo từ
> `status_changed_at` — tức lần chuyển trạng thái gần nhất, **không phải** từ lần xem CV hay lần
> nộp đơn.”

Khi chuyển `INACTIVE`, trong cùng transaction:

- Đổi status, ghi status history với `actor = null` (do hệ thống làm);
- Thu hồi grant với lý do `APPLICATION_INACTIVE`;
- Gửi notification `APPLICATION_INACTIVE` **chỉ cho owner và recruiter đang được phân công**. Ứng
  viên thấy trạng thái nhưng **không** nhận thông báo.

**Mở lại:**

> “Công ty chỉ được mở lại qua `UNDER_REVIEW` — không được nhảy thẳng sang `HIRED`. Service kiểm
> tra CV nguồn còn active không:
>
> - Còn → tạo grant **epoch N+1**, quyền đọc được khôi phục;
> - Đã bị xóa → đơn mở lại thành công nhưng **không tạo grant**, và API trả về
>   `canViewCv = false`.”

**Vì sao không dùng lại epoch cũ:** “Epoch cũ đã bị thu hồi là bằng chứng lịch sử. Tái sử dụng nó
sẽ làm mất khả năng audit.”

> *Chuyển ý:* “Cuối cùng là lúc đơn kết thúc và dữ liệu cũ được dọn.”

---

### Phần 6 — Kết thúc đơn và purge bản sao cũ (2 phút) — Sơ đồ 5

**Lời thoại:**

> “Có hai kết thúc: công ty từ chối, hoặc ứng viên rút đơn.”

- **`REJECTED`:** thu hồi grant ngay (`APPLICATION_CLOSED`). Với các đơn **cũ** dùng
  `LEGACY_COPY`, worker sẽ xóa object riêng của đơn đó và set `cv_purged_at`. Metadata snapshot —
  tên file, loại file, kích thước, storage key — **vẫn giữ** để tra cứu.
- **`WITHDRAWN`:** thu hồi grant, nhưng **giữ** bản copy cũ. Đây là quyết định có chủ đích trong
  amendment.
- **`HIRED`:** vẫn đọc được nếu các kiểm tra khác pass; chỉ mất quyền khi ứng viên xóa CV.

**Hai lớp an toàn của worker purge bản sao cũ:**

1. Chỉ xóa key nằm dưới prefix `applications/{applicationId}/cv/`. Nếu key không khớp prefix →
   bỏ qua và log cảnh báo. Mục đích: **không bao giờ xóa nhầm object thư viện của ứng viên từ
   nhánh này.**
2. Xóa lỗi → để `cv_purged_at` null → tick sau retry.

**Câu chốt:** “Bản sao cũ không được backfill grant. Chúng tiếp tục đọc theo đường legacy cho đến
khi bị purge. Đây là lựa chọn tương thích, không phải thiếu sót.”

> *Chuyển ý:* “Cuối cùng, mình tóm lại những gì bảo vệ cơ chế này khỏi bị vô hiệu hóa.”

---

### Phần 7 — Các lớp bảo vệ và tổng kết (2 phút)

Bốn lớp bảo vệ:

| Lớp | Cơ chế | Chống được gì |
|---|---|---|
| 1. Tầng ứng dụng | `findLive(applicationId, companyId, now)` trước mọi lần đọc | Đọc trái phép qua endpoint |
| 2. Tầng dữ liệu | Unique `(application_id, epoch)`; partial unique `(application_id) WHERE revoked_at IS NULL` | Hai grant cùng sống một lúc |
| 3. Trigger DB | Cấm `DELETE`, cấm sửa identity/`granted_at`, cấm sửa `expires_at`/`revoked_at` sau khi đã set | Sửa grant để kéo dài quyền |
| 4. Vận hành | Worker batch có giới hạn, retry, cờ bật/tắt, log chỉ dùng ID | Purge/inactivity lỗi mà không mở lại quyền |

**Câu kết:**

> “Tóm lại: quyền đọc CV không nằm ở việc biết đường dẫn file, mà nằm ở một bản ghi grant có thời
> hạn. Ứng viên xóa CV thì mất quyền ngay; công ty có thêm một cửa sổ ngắn để hoàn tất review; đơn
> bị bỏ quên thì tự động mất quyền; và khi không còn ai được đọc, file được dọn. Toàn bộ hành vi
> này đã có test ở bốn tầng: service, HTTP contract, migration/DB, và worker.”

---

## 3. Câu hỏi dự phòng và trả lời

| Câu hỏi | Trả lời gợi ý |
|---|---|
| Vì sao không xóa hẳn row CV khi ứng viên xóa? | Grant còn khóa ngoại tới CV và worker purge cần row để retry idempotent. Tombstone giữ lịch sử và tránh mất dấu vết audit. |
| Nếu ứng viên xóa CV, công ty có đọc được mãi không? | Không. Chỉ trong cửa sổ review, và chỉ khi đơn còn ở giai đoạn hoạt động. `HIRED` mất ngay; `INACTIVE`/terminal đã bị thu hồi trước đó. |
| Nếu worker purge chết thì sao? | Quyền vẫn bị chặn lúc đọc vì grant đã hết hạn. File có thể tồn tại lâu hơn, nhưng đó là rủi ro dung lượng, không phải rủi ro truy cập. |
| Tại sao trả `404` mà không trả `403`? | Để không cho phép dò đoán. Nếu phân biệt “không có quyền” và “có grant nhưng hết hạn”, người ngoài có thể suy ra thông tin về đơn. |
| Grant gắn công ty hay recruiter? | Công ty. Recruiter chỉ cần assignment hiện tại. Nhờ vậy bàn giao việc giữa các recruiter không phải cấp lại grant. |
| Nếu hai request đồng thời? | Submission khóa row CV; các worker dùng `FOR UPDATE ... SKIP LOCKED`; partial unique index đảm bảo tối đa một grant chưa thu hồi cho mỗi đơn. |
| Vì sao có cả `LEGACY_COPY`? | Đơn nộp trước thay đổi đã có bản copy riêng. Nhóm không backfill grant cho chúng; chúng đọc theo đường legacy và bị purge khi bị từ chối. |

---

## 4. Các con số cần nhớ

| Thông số | Giá trị | Nguồn |
|---|---|---|
| Cửa sổ review sau khi ứng viên xóa CV | **60 ngày**, cấu hình `CANDIDATE_CV_ACCESS_GRANT_TTL` | `backend/src/main/resources/application.properties` |
| Ngưỡng inactivity | **30 ngày**, `APPLICATION_REVIEW_INACTIVITY_TTL` | `backend/src/main/resources/application.properties` |
| Signed URL cho công ty | **5 phút** | `JobApplicationService` |
| Signed URL cho ứng viên | **15 phút** | `CandidateCvService` |
| Batch purge thư viện | **20** | `CandidateCvRetentionService` |
| Batch inactivity / purge legacy | **100** (1–1000) | cấu hình worker |
| Inline viewer | Chỉ **PDF**; DOC/DOCX → `406` | `CVACL-033` |
| Mã lỗi ẩn | `40478 JOB_NOT_FOUND` | Ma trận ACL |

---

## 5. Ghi chú nội bộ trước khi trình bày

Phần này **không đưa lên slide**. Có ba điểm lệch giữa tài liệu và code, cần thống nhất trước:

1. **Thời hạn cửa sổ review:** sơ đồ ghi **60 ngày**, code mặc định **60 ngày**, nhưng đặc tả
   `GW-SR-CV-02` §9 lại ghi mặc định **30 ngày**. Khi trình bày nên nói “60 ngày, có thể cấu
   hình” và ghi nhận đặc tả đang lệch.
2. **Mã trả về của `DELETE /api/candidate/cvs/{id}`:** sơ đồ ghi `204`, implementation trả
   `200 OK` (có body envelope). Nên sửa sơ đồ hoặc nói theo code.
3. **Trạng thái đặc tả:** `GW-SR-CV-02` vẫn là *“Draft — formal approval pending”*. Nên nói **“đã
   triển khai, đang chờ phê duyệt chính thức”**, không nói “đã được phê duyệt”.

Rủi ro còn lại nên chủ động nêu nếu bị hỏi:

- Object Cloudinary cũ upload ở chế độ public vẫn truy cập được nếu biết URL (đã chấp nhận; upload
  mới dùng `authenticated`).
- Purge là eventual — byte có thể tồn tại lâu hơn cửa sổ review, nhưng quyền đã bị chặn tại thời
  điểm đọc.
- Không có job quét định kỳ để rewrite grant hết hạn; expiry được enforce trong câu truy vấn.

---

## 6. Checklist trước khi lên sóng

- [ ] Mở sẵn 5 sơ đồ trong `candidate-cv-access-grant-diagrams.md` theo đúng thứ tự.
- [ ] Chuẩn bị sẵn 2 endpoint để demo nếu có mạng: `GET .../cv/download-url` và
      `DELETE /api/candidate/cvs/{id}`.
- [ ] Kiểm tra lại giá trị TTL đang cấu hình trong môi trường demo.
- [ ] Xác nhận với nhóm câu chữ về trạng thái phê duyệt của `GW-SR-CV-02`.
- [ ] Chuẩn bị sẵn câu trả lời cho “tại sao không copy CV?” và “tại sao trả 404?”.
