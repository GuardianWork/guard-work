# Ma trận kiểm soát truy cập — luồng CV ứng tuyển (đã triển khai)

| Trường | Giá trị |
|---|---|
| **Mã tài liệu** | GW-OPS-CV-ACL-01 |
| **Dự án** | GuardWork |
| **Phạm vi** | Quyền đọc/ghi CV thư viện và CV đã nộp theo cơ chế access grant |
| **Trạng thái** | Tài liệu kỹ thuật nội bộ — phản ánh implementation hiện tại |
| **Nguồn chuẩn** | GW-SR-CV-02 (`candidate-cv-access-retention-spec.md`), GW-SR-APP-01 và các amendment 2026-11-18 |
| **Cập nhật** | 2026-11-18 |

> Ghi chú: tài liệu này mô tả hành vi **đã triển khai**, dùng làm checklist kiểm thử và
> đối chiếu khi review. Khi mâu thuẫn, đặc tả yêu cầu (GW-SR-CV-02/GW-SR-APP-01) là nguồn
> chuẩn. Mã định danh kỹ thuật (endpoint, mã lỗi, tên bảng/cột) giữ nguyên tiếng Anh để
> khớp với code và log.

## 1. Mục đích

Luồng CV ứng tuyển chỉ cho phép **hai chủ thể** đọc một tệp CV:

1. **Ứng viên sở hữu** CV trong thư viện của mình; và
2. **Công ty nhận đơn** — công ty nhận đúng đơn ứng tuyển đã dùng CV đó.

Mọi chủ thể khác đều bị từ chối. Quyền truy cập của công ty được xác định bằng một bản ghi
`candidate_cv_access_grants` còn hiệu lực, **không** dựa vào `cv_storage_key` của snapshot.

## 2. Chủ thể

| Ký hiệu | Chủ thể | Mô tả |
|---|---|---|
| **A** | Ứng viên (chủ sở hữu) | Ứng viên có `candidate_profiles.id` sở hữu CV trong thư viện. |
| **B** | Ứng viên khác | Bất kỳ ứng viên nào không sở hữu CV hoặc không sở hữu đơn ứng tuyển. |
| **C** | Công ty nhận đơn | Công ty khớp `candidate_cv_access_grants.company_id` và `application_id` của đơn tương ứng. |
| **D** | Công ty khác | Công ty khác, hoặc thành viên công ty không được phân công đơn/tin tuyển dụng. |
| **E** | Không xác thực | Không có token, token sai/hết hạn. |

## 3. Trạng thái tài nguyên

| Trạng thái | Điều kiện |
|---|---|
| **CV đang hoạt động** | `candidate_cvs.deleted_at IS NULL`. |
| **CV tombstone** | `deleted_at` đã được đặt; CV biến mất khỏi thư viện ứng viên và không còn tải được. |
| **Grant còn hiệu lực** | `revoked_at IS NULL` và (`expires_at IS NULL` hoặc `expires_at > now()`). |
| **Grant có thời hạn** | `expires_at` được đặt khi ứng viên xóa CV **và đơn đang ở giai đoạn đánh giá hoạt động**: `deleted_at + TTL` (mặc định 30 ngày). |
| **Grant hết hạn** | `expires_at <= now()`; không còn hiệu lực dù chưa bị thu hồi. |
| **Grant đã thu hồi** | `revoked_at` được đặt khi đơn chuyển `REJECTED`, `WITHDRAWN`, `INACTIVE`, hoặc khi ứng viên xóa CV trong đơn `HIRED` (`CANDIDATE_CV_DELETED`). |
| **Grant epoch** | Mỗi lần cấp quyền là một `epoch`. Một đơn có thể có nhiều epoch lịch sử nhưng **tối đa một epoch chưa thu hồi** (`UNIQUE (application_id) WHERE revoked_at IS NULL`). |
| **INACTIVE** | Đơn không được công ty cập nhật trạng thái trong 30 ngày (mặc định). Quyền CV hiện tại bị thu hồi; có thể mở lại qua `UNDER_REVIEW`, tạo epoch mới nếu CV thư viện còn tồn tại. |
| **Bản sao cũ (`LEGACY_COPY`)** | `application_snapshots.cv_storage_mode = 'LEGACY_COPY'` (đơn nộp trước thay đổi), có object riêng. |
| **Bản sao cũ đã xóa** | `application_snapshots.cv_purged_at IS NOT NULL`; byte đã xóa, metadata còn lại. Chỉ áp dụng cho `LEGACY_COPY` bị `REJECTED` (hoặc backfill). |

## 4. Thứ tự kiểm tra quyền (fail closed)

### 4.1 Công ty đọc CV (`/cv` hoặc `/cv/download-url`)

| Bước | Kiểm tra | Thất bại |
|---:|---|---|
| 1 | Tài khoản đang hoạt động và có `CompanyMembership` `ACTIVE` | `40302` hoặc lỗi tài khoản hiện có |
| 2 | Vai trò `OWNER` đúng chủ sở hữu hoặc `RECRUITER`; `ADMIN` và Owner không phải chủ sở hữu bị loại | `40303` |
| 3 | Công ty `VERIFIED` | `40305` |
| 4 | Job thuộc công ty, không bị staff treo/`SUSPENDED` | `40478` (ẩn) |
| 5 | Recruiter có assignment **hiện tại** cho job trong đúng membership period | `40478` (ẩn) |
| 6 | Application thuộc đúng job và công ty | `40478` (ẩn) |
| 7 | Trạng thái đơn không phải `REJECTED`, `WITHDRAWN`, `INACTIVE` | `40478` (ẩn) |
| 8a | `SHARED`: có grant còn hiệu lực cho `(application_id, company_id)` | `40478` (ẩn) |
| 8b | `LEGACY_COPY`: chưa bị purge (`cv_purged_at IS NULL`) | `40478` (ẩn) |
| 9 | Định dạng: inline chỉ PDF; tải xuống nhận PDF/DOC/DOCX | `40600` (inline) |
| 10 | Đọc storage và ghi audit `APPLICATION_CV_VIEWED` (inline và tải xuống) | `50300` nếu provider lỗi |

**Recruiter mới được phân công** đi qua đúng các bước trên. Grant gắn với **công ty**, không gắn
với recruiter gốc, nên recruiter mới có assignment hiện tại đọc được khi grant còn hiệu lực. Ngược
lại, mất assignment hoặc rời membership sẽ chặn recruiter đó dù grant còn hiệu lực.

### 4.2 Ứng viên đọc CV của mình

| Bước | Kiểm tra | Thất bại |
|---:|---|---|
| 1 | Tài khoản đang hoạt động | `40100` / lỗi tài khoản |
| 2 | CV hoặc Application thuộc ứng viên đang đăng nhập | `40405` hoặc `40478` (ẩn) |
| 3a | `SHARED` + CV thư viện đang hoạt động | `40405` |
| 3b | `LEGACY_COPY` + chưa purge | `40405` |
| 4 | Sinh URL tải 15 phút | `50300` nếu provider lỗi |

## 5. Ma trận hành động × chủ thể

| Hành động / Endpoint | A — Ứng viên sở hữu | B — Ứng viên khác | C — Công ty nhận đơn | D — Công ty khác | E — Không xác thực |
|---|---|---|---|---|---|
| **Đăng ký CV** (`POST /api/candidate/cvs`) | ✅ đúng thư mục của mình, `access_mode=authenticated` | ❌ `40300` / `40023` | ❌ `403` | ❌ `403` | ❌ `40100` |
| **Liệt kê CV** (`GET /api/candidate/cvs`) | ✅ chỉ các CV đang hoạt động | ❌ `40300` | ❌ `403` | ❌ `403` | ❌ `40100` |
| **Tải CV của mình** (`GET /api/candidate/cvs/{id}/download-url`) | ✅ chỉ khi CV đang hoạt động; ❌ `40405` nếu tombstone | ❌ `40405` | ❌ `403` | ❌ `403` | ❌ `40100` |
| **Xóa CV** (`DELETE /api/candidate/cvs/{id}`) | ✅ tạo tombstone; đơn active nhận cửa sổ 30 ngày, đơn `HIRED` bị thu hồi ngay | ❌ `40405` | ❌ `403` | ❌ `403` | ❌ `40100` |
| **Đặt CV mặc định** (`PATCH …/{id}/default`) | ✅ chỉ khi CV đang hoạt động | ❌ `40405` / `40300` | ❌ `403` | ❌ `403` | ❌ `40100` |
| **CV đã nộp của ứng viên** (`GET /api/candidate/applications/{id}/cv/download-url`) | ✅ khi CV nguồn đang hoạt động (`SHARED`) hoặc bản sao cũ chưa purge (`LEGACY_COPY`); ❌ `40405` nếu CV đã xóa/bản sao đã purge | ❌ `40478` | — | — | ❌ `40100` |
| **Công ty xem trực tiếp** (`GET /api/companies/me/jobs/{jobId}/applications/{id}/cv`) | — | — | ✅ chỉ khi **grant còn hiệu lực** (`SHARED`) hoặc **bản sao cũ chưa xóa** (`LEGACY_COPY`); ❌ `40478` nếu hết hạn/đã thu hồi/`INACTIVE`/bản sao cũ đã xóa; ❌ `40600` nếu không phải PDF | ❌ `40478` | ❌ `40100` |
| **Công ty tải CV** (`…/cv/download-url`) | — | — | ✅ cùng điều kiện grant/bản sao cũ; PDF/DOC/DOCX; trả file đính kèm và ghi audit; ❌ `40478` | ❌ `40478` | ❌ `40100` |

## 6. Trạng thái CV `SHARED` × kết quả đọc của công ty nhận đơn

| Trạng thái đơn | Trạng thái CV thư viện | Trạng thái grant | Công ty đọc được? |
|---|---|---|---|
| `SUBMITTED`, `UNDER_REVIEW`, `SHORTLISTED`, `INTERVIEWING` | Đang hoạt động | Còn hiệu lực, không thời hạn | ✅ Có |
| `SUBMITTED`, `UNDER_REVIEW`, `SHORTLISTED`, `INTERVIEWING` | Đang hoạt động | Còn hiệu lực, có thời hạn | ✅ Có trong thời hạn |
| `SUBMITTED`, `UNDER_REVIEW`, `SHORTLISTED`, `INTERVIEWING` | Tombstone | Còn hiệu lực, trong `deleted_at + TTL` | ✅ Có |
| `SUBMITTED`, `UNDER_REVIEW`, `SHORTLISTED`, `INTERVIEWING` | Tombstone | Còn hiệu lực, quá `deleted_at + TTL` | ❌ `40478` (ẩn) |
| `HIRED` | Đang hoạt động | Còn hiệu lực | ✅ Có |
| `HIRED` | Tombstone (ứng viên xóa CV) | Đã thu hồi ngay (`CANDIDATE_CV_DELETED`) | ❌ `40478` (ẩn) |
| `INACTIVE` | Bất kỳ | Đã thu hồi (`APPLICATION_INACTIVE`) | ❌ `40478` (ẩn) |
| `REJECTED`, `WITHDRAWN` | Bất kỳ | Đã thu hồi | ❌ `40478` (ẩn) |
| Bất kỳ | Bất kỳ | Hết hạn hoặc đã thu hồi | ❌ `40478` (ẩn) |
| Bất kỳ | Bất kỳ | Grant của công ty khác | ❌ `40478` (ẩn) |

## 7. Bản sao cũ `LEGACY_COPY` × kết quả đọc

| Trạng thái đơn | Trạng thái bản sao | Công ty đọc được? | Ứng viên tải CV đã nộp? |
|---|---|---|---|
| `SUBMITTED`, `UNDER_REVIEW`, `SHORTLISTED`, `INTERVIEWING` | Chưa purge | ✅ Có | ✅ Có |
| `HIRED` | Chưa purge | ✅ Có | ✅ Có |
| `INACTIVE` | Chưa purge | ❌ `40478` (ẩn) | ✅ Có |
| `WITHDRAWN` | Chưa purge | ❌ `40478` (ẩn) | ✅ Có |
| `REJECTED` | Chưa purge (trước khi worker chạy) | ❌ `40478` (ẩn) | ✅ Có |
| `REJECTED` | Đã purge (`cv_purged_at` khác null) | ❌ `40478` (ẩn) | ❌ `40405` |
| Bất kỳ | Purge thất bại, `cv_purged_at` còn null | Theo trạng thái đơn | Theo trạng thái đơn |

`SHARED` application không có bản sao công ty; object thư viện `candidates/...` **không** bị xóa bởi
rejection purge.

## 8. Ứng viên × CV đã nộp của chính mình

| Trạng thái CV nguồn | Ứng viên tải CV đã nộp? |
|---|---|
| `SHARED`, CV thư viện đang hoạt động | ✅ Có |
| `SHARED`, CV thư viện tombstone | ❌ `40405` — mất quyền ngay lập tức |
| `LEGACY_COPY`, chưa purge | ✅ Có |
| `LEGACY_COPY`, đã purge | ❌ `40405` — byte không còn |

Trạng thái đơn (`INACTIVE`, `REJECTED`, `WITHDRAWN`…) không chặn ứng viên tải CV đã nộp của chính
mình; điều kiện là CV nguồn còn tồn tại (hoặc bản sao cũ chưa purge).

## 9. Quy tắc chính được mã hóa trong ma trận

- **Đặc quyền tối thiểu:** quyền của công ty gắn với cặp `(application_id, company_id)`;
  không phải quyền truy cập CV nói chung.
- **Fail closed và không liệt kê (non-enumerating):** thiếu grant, hết hạn, đã thu hồi, `INACTIVE`,
  bản sao đã purge và công ty khác đều trả cùng một `404` (`40478`), nên caller không phân biệt được.
- **Bản sao trong snapshot không phải nguồn cấp quyền:** với `SHARED`, `cv_storage_key` chỉ
  mang tính mô tả; công ty đọc qua grant còn hiệu lực.
- **Bản sao cũ:** đơn `LEGACY_COPY` đọc tệp copy lịch sử, không bị chuyển sang grant. Ngoại lệ duy
  nhất là `REJECTED`: byte bị xóa, metadata giữ nguyên; `WITHDRAWN` giữ bản sao.
- **Bất biến:** định danh grant (gồm `epoch`) chỉ ghi thêm; `expires_at` được đặt một lần (khi ứng
  viên xóa CV trong đơn active), `revoked_at` được đặt một lần (khi đơn kết thúc/inactive hoặc
  `HIRED` + xóa CV).
- **Một epoch còn hiệu lực:** partial unique index trên `(application_id) WHERE revoked_at IS NULL`
  bảo đảm không có hai epoch cùng cấp quyền.
- **Xóa CV không xóa grant:** với đơn active, xóa chỉ đặt tombstone + thời hạn; với `HIRED`, grant bị
  thu hồi ngay. Tệp thư viện được xóa bởi retention worker sau khi không còn grant hiệu lực.
- **Kiểm soát ở thời điểm đọc:** purge chậm hoặc lỗi không kéo dài quyền truy cập.
- **Inactivity:** sau 30 ngày không đổi trạng thái (cấu hình `APPLICATION_REVIEW_INACTIVITY_TTL`), đơn
  active chuyển `INACTIVE` và grant hiện tại bị thu hồi (`APPLICATION_INACTIVE`). `HIRED`, `REJECTED`,
  `WITHDRAWN` không bị quét.
- **Reactivation/epoch:** mở lại chỉ qua `UNDER_REVIEW`; tạo epoch mới nếu CV thư viện còn hoạt động.
  Không có epoch nào bị "hồi sinh"; nếu CV đã bị xóa, mở lại đơn không khôi phục quyền CV.
- **Recruiter mới:** grant là company-scoped; recruiter được phân công hiện tại đọc được khi grant
  còn hiệu lực, không phụ thuộc recruiter nhận đơn ban đầu.
- **Rejection purge:** đơn `REJECTED` xóa bản sao `LEGACY_COPY` (không xóa object `candidates/...`);
  metadata snapshot giữ nguyên, `cv_purged_at` đánh dấu. `WITHDRAWN` giữ bản sao.
- **Thông báo:** `INACTIVE` chỉ gửi cho chủ công ty và recruiter đang được phân công; ứng viên thấy
  trạng thái nhưng không nhận thông báo.

## 10. Mã lỗi liên quan

| Mã | HTTP | Ý nghĩa trong luồng này |
|---|---|---|
| `40100` | 401 | Thiếu hoặc sai token. |
| `40300` | 403 | Ứng viên yêu cầu tài nguyên không thuộc mình (một số endpoint quản lý CV). |
| `40302` | 403 | Không có tư cách thành viên công ty đang hoạt động. |
| `40303` | 403 | Vai trò công ty không đủ (Admin, Owner không phải chủ sở hữu, vai trò khác). |
| `40305` | 403 | Công ty chưa `VERIFIED`. |
| `40405` | 404 | CV không tồn tại, đã xóa, đã purge, hoặc thuộc ứng viên khác. |
| `40478` | 404 | Đơn/tin tuyển dụng/công ty không truy cập được **hoặc** grant hết hạn/đã thu hồi/`INACTIVE`/bản sao cũ đã xóa — cố ý ẩn. |
| `40600` | 406 | Xem trực tiếp CV không phải PDF. |
| `50300` | 503 | Provider không hỗ trợ hoặc lỗi truy cập kho lưu trữ. |

`APPLICATION_CV_COPY_FAILED` (`50301`) không còn được sinh ra vì không còn bước copy CV; hằng
số được giữ để tương thích client cũ.

## 11. Ghi chú kiểm thử

Ma trận này được chứng minh bởi:

- `CandidateCvServiceTest` — đăng ký/đọc/xóa theo chủ sở hữu, tombstone, cửa sổ review cho đơn
  active, thu hồi ngay cho `HIRED`;
- `CandidateCvRetentionServiceTest` — purge trì hoãn và retry;
- `ApplicationInactivityServiceTest` — biên 30 ngày, bỏ qua `HIRED`/terminal, thu hồi grant và
  người nhận thông báo;
- `ApplicationCvPurgeServiceTest` — xóa bản sao cũ `REJECTED`, chặn key ngoài thư mục, retry;
- `JobApplicationServiceTest` — tạo grant, đọc `SHARED`/`LEGACY_COPY`, thu hồi, mất quyền của ứng viên;
- `JobApplicationHttpTest` — luồng HTTP end-to-end: grant khi nộp, mất quyền sau xóa, công ty đọc
  trực tiếp và lấy URL, thu hồi khi `REJECTED`, `INACTIVE`/reactivation, recruiter mới, purge bản
  sao cũ, đơn cũ;
- `JobApplicationMigrationTest` — ràng buộc/bất biến grant, epoch, trigger purge marker, truy vấn
  grant còn hiệu lực, inactivity và purge.

Khi thêm endpoint hoặc đổi quy tắc, cập nhật ma trận này cùng GW-SR-CV-02 và test tương ứng.

## 12. Sai lệch đã biết

| Sai lệch | Trạng thái |
|---|---|
| "Trừ khi có thỏa thuận lưu hồ sơ" chưa được triển khai; `REJECTED` luôn purge bản sao cũ. | Deferred; cần luồng consent/retention riêng. |
| Object `SHARED` của đơn `REJECTED` không bị xóa vì thuộc thư viện ứng viên. | Đúng theo phạm vi; ứng viên có thể xóa qua tombstone. |
| Grant hết hạn không bị sweep định kỳ; chỉ bị loại khi đọc. | Chấp nhận; index `expires_at` hỗ trợ bảo trì sau này. |
| `INACTIVE` là giá trị status mới; client chỉ biết status cũ sẽ không xử lý được. | Đã được duyệt 2026-11-18; client phải cập nhật. |
| Metadata snapshot của bản sao cũ vẫn còn sau purge. | Chủ ý để truy vết; cần legal sign-off. |
