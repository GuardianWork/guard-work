# So sánh luồng CV ứng tuyển: mô hình cũ và mô hình access grant

| Trường | Giá trị |
|---|---|
| **Mã tài liệu** | GW-OPS-CV-FLOW-COMPARE |
| **Dự án** | GuardWork |
| **Phạm vi** | So sánh luồng CV ứng tuyển trước và sau thay đổi access grant |
| **Trạng thái** | Tài liệu kỹ thuật nội bộ — phản ánh implementation hiện tại |
| **Nguồn chuẩn** | GW-SR-CV-02, GW-SR-APP-01 |
| **Cập nhật** | 2026-11-20 |

> Tài liệu này dùng để so sánh và đối chiếu khi review. Khi có mâu thuẫn, GW-SR-CV-02 là
> nguồn chuẩn cho vòng đời CV; GW-SR-APP-01 là nguồn chuẩn cho vòng đời đơn ứng tuyển.

## 1. Bảng so sánh tổng quan

| Khía cạnh | Luồng cũ — bản sao theo đơn | Luồng đã triển khai — grant dùng chung |
|---|---|---|
| **Lưu trữ CV** | Mỗi đơn có bản sao CV riêng dưới `applications/{applicationId}/cv/`. | Đơn tham chiếu CV thư viện duy nhất của ứng viên; không tạo bản sao byte mới. |
| **Nguồn cấp quyền** | Quyền truy cập Application cộng với bản sao được ghi trong snapshot. Không có bảng grant riêng. | Quyền truy cập Application cộng với một grant còn hiệu lực cho đúng cặp `(application_id, company_id)`. |
| **Vai trò của `cv_storage_key`** | Là địa chỉ của bản sao CV mà công ty được đọc. | Chỉ mang tính mô tả; không cấp quyền. Grant là nguồn cấp quyền duy nhất. |
| **Công ty đọc CV** | Kiểm tra membership, vai trò, assignment, trạng thái đơn rồi đọc bản sao của đơn. | Các kiểm tra đó cộng thêm grant còn hiệu lực; hết hạn hoặc bị thu hồi thì từ chối. |
| **Recruiter mới được phân công** | Đọc bản sao nếu có assignment hiện tại. | Đọc được khi grant còn hiệu lực và có assignment hiện tại; grant gắn với công ty, không gắn với recruiter ban đầu. |
| **Ứng viên xóa CV thư viện** | Không ảnh hưởng bản sao đã nộp; công ty vẫn đọc bản sao. | CV thư viện thành tombstone; đơn active nhận cửa sổ review 30 ngày; đơn `HIRED` bị thu hồi ngay; ứng viên mất quyền đọc CV đã nộp ngay. |
| **Ứng viên tải CV đã nộp** | Bản sao độc lập nên vẫn còn sau khi xóa CV thư viện. | Chỉ còn khi CV nguồn đang hoạt động (`SHARED`) hoặc bản sao cũ chưa purge (`LEGACY_COPY`). |
| **Đơn `REJECTED`** | Chặn quyền đọc ở endpoint nhưng byte vẫn được lưu. | Thu hồi grant ngay; worker xóa bản sao cũ; giữ metadata snapshot và đánh dấu `cv_purged_at`. |
| **Đơn `WITHDRAWN`** | Chặn quyền đọc; giữ bản sao. | Thu hồi grant; giữ bản sao cũ. |
| **Đơn `HIRED`** | Công ty đọc bản sao chừng nào đơn còn tồn tại. | Grant còn hiệu lực; nếu ứng viên xóa CV thì thu hồi ngay, không có cửa sổ 30 ngày. |
| **Không hoạt động** | Đơn giữ nguyên trạng thái vô thời hạn. | Sau 30 ngày không đổi trạng thái, đơn active chuyển `INACTIVE`, grant bị thu hồi. |
| **Mở lại đơn** | Không có. | Chỉ `INACTIVE -> UNDER_REVIEW`; tạo epoch mới nếu CV thư viện còn tồn tại. |
| **Lịch sử grant** | Không có. | Epoch append-only; tối đa một epoch chưa thu hồi cho mỗi đơn. |
| **Thông báo** | Chủ yếu thông báo thay đổi trạng thái cho ứng viên. | Giữ thông báo cho ứng viên; thêm `APPLICATION_INACTIVE` chỉ cho chủ công ty và recruiter đang được phân công. |
| **Lưu trữ sau kết thúc** | Bản sao có thể tồn tại vô thời hạn. | Purge bản sao cũ của đơn `REJECTED`; purge CV thư viện sau khi không còn grant hiệu lực. |
| **Tải xuống** | Chỉ xem inline PDF; không có thao tác tải riêng. | Inline PDF cộng với tải file đính kèm cho PDF/DOC/DOCX qua backend đã xác thực. |
| **Đơn cũ** | Toàn bộ đơn dùng mô hình bản sao. | Đơn cũ giữ `LEGACY_COPY`, không backfill grant; đơn mới dùng `SHARED`. |
| **Độ phức tạp vận hành** | Thấp hơn: copy khi nộp, đọc theo snapshot. | Cao hơn: grant, epoch, worker inactivity, worker purge, đối soát invariant. |

## 2. Thay đổi cốt lõi

**Luồng cũ — copy-and-own**

```text
Đơn ứng tuyển sở hữu một bản sao CV.
Quyền đọc gắn với bản sao đó.
Xóa CV thư viện không ảnh hưởng đến bản sao đã nộp.
```

**Luồng mới — share-and-grant**

```text
Ứng viên sở hữu một CV thư viện.
Đơn ứng tuyển sở hữu một grant để đọc CV đó.
Quyền đọc tường minh, có thời hạn, thu hồi được và có audit.
Xóa CV thư viện ảnh hưởng đến tất cả đơn thông qua grant.
```

## 3. Điểm giữ nguyên

- Pipeline trạng thái đơn và quy tắc vai trò/assignment.
- Snapshot đơn ứng tuyển bất biến.
- Che dữ liệu terminal cho `REJECTED`/`WITHDRAWN`.
- Chỉ xem inline PDF; lỗi không liệt kê tài nguyên.
- Quyền sở hữu CV của ứng viên.
- Audit truy cập nhạy cảm.

## 4. Đánh đổi

### Lợi ích của luồng mới

- Không nhân bản byte CV cho từng đơn.
- Xóa CV thư viện có hiệu lực thực sự lên quyền của công ty.
- Đơn bị từ chối xóa được bản sao cũ; giảm dữ liệu tồn đọng.
- Có audit grant và epoch: biết ai được đọc CV nào, khi nào.
- Hỗ trợ `INACTIVE` và mở lại đơn.
- Thu hồi quyền dựa trên dữ liệu, không chỉ dựa vào việc endpoint từ chối.

### Chi phí của luồng mới

- Schema và invariant nhiều hơn.
- Thêm worker inactivity, worker purge thư viện và worker purge bản sao cũ.
- Suy luận grant/epoch phức tạp hơn “đọc key trong snapshot”.
- Xóa CV ảnh hưởng đến quyền của công ty — đây là quyết định sản phẩm có chủ đích.
- Tồn tại mô hình hybrid: đơn cũ đọc bản sao, đơn mới đọc grant.

## 5. Cầu nối với đơn cũ

Đơn `LEGACY_COPY` vẫn đọc bản sao lịch sử và không có grant. Khi đơn `REJECTED`, byte của bản sao
bị purge nhưng metadata snapshot được giữ nguyên. Khi đơn `WITHDRAWN`, bản sao được giữ lại. Vì vậy
hệ thống không chuyển đổi toàn bộ dữ liệu cũ sang grant; thay vào đó, mô hình cũ được giữ có kiểm soát
cho đến khi đơn kết thúc.

## 6. Rủi ro còn lại

- Response tải trả byte CV cho người đã được cấp quyền; backend không phát hành URL bearer cho
  công ty, nhưng không ngăn được người nhận lưu hoặc sao chép file.
- Object Cloudinary cũ có thể vẫn ở chế độ public; grant không sửa được rủi ro cấp storage.
- Grant hết hạn không được sweep định kỳ; chỉ bị bỏ qua khi đọc.
- Thỏa thuận lưu hồ sơ pháp lý vẫn deferred; mặc định là xóa bản sao cũ khi `REJECTED`.
- Worker chậm có thể trì hoãn purge hoặc thu hồi, nhưng không kéo dài quyền đọc vì kiểm tra được
  thực hiện tại thời điểm đọc.
