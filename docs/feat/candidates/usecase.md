# Sơ đồ usecase tổng quan & Mô tả usecase (Bản chi tiết)

## BẢNG PHÂN PHỐI ƯU TIÊN

| Use case | Nhóm | Ưu tiên |
| :--- | :--- | :--- |
| **UC-CAN-01** Cập nhật thông tin cá nhân | Hồ sơ | Cao |
| **UC-CAN-03** Tải lên CV mới | CV | Cao |
| **UC-CAN-07** Tìm kiếm việc làm | Tìm kiếm | Cao |
| **UC-CAN-09** Xem chi tiết công việc | Tìm kiếm | Cao |
| **UC-CAN-11** Ứng tuyển công việc | Ứng tuyển | Cao |
| **UC-CAN-12** Chọn / Đính kèm CV | Ứng tuyển | Cao |
| **UC-CAN-13** Xem DS việc đã ứng tuyển | Ứng tuyển | Cao |
| **UC-CAN-14** Theo dõi trạng thái hồ sơ | Ứng tuyển | Cao |
| **UC-CAN-08** Lọc kết quả tìm kiếm | Tìm kiếm | Trung bình |
| **UC-CAN-10** Xem thông tin công ty | Tìm kiếm | Trung bình |
| **UC-CAN-15** Lưu / Bỏ lưu công việc | Ứng tuyển | Trung bình |
| **UC-CAN-16** Xem DS việc đã lưu | Ứng tuyển | Trung bình |
| **UC-CAN-18** Xem thông báo hệ thống | Tiện ích | Trung bình |
| **UC-CAN-04** Chỉnh sửa / Cập nhật CV | CV | Thấp |
| **UC-CAN-05** Xóa CV | CV | Thấp |
| **UC-CAN-06** Đặt CV mặc định | CV | Thấp |
| **UC-CAN-17** Đánh giá công ty | Đánh giá | Thấp |
| **UC-CAN-02** Quản lý CV (tổng) | CV | — |

> **Ghi chú:** Các use case liên quan đến Quản lý tài khoản & Xác thực (Đăng ký, Đăng nhập, Đăng xuất, Đổi mật khẩu...) thuộc về module Authentication dùng chung của hệ thống và đã được tách khỏi phạm vi riêng của vai trò Ứng viên.

---

## NHÓM 1: QUẢN LÝ HỒ SƠ & CV

### UC-CAN-01: Cập nhật thông tin cá nhân
* **Tác nhân:** Ứng viên (đang đăng nhập)
* **Mô tả:** Cho phép ứng viên chỉnh sửa các thông tin hồ sơ cá nhân như họ tên, số điện thoại, kỹ năng, kinh nghiệm làm việc, học vấn.
* **Pre-condition:** Ứng viên đã đăng nhập và đang ở trang Hồ sơ cá nhân.
* **Post-condition:** Thông tin cá nhân được cập nhật và lưu vào hệ thống.
* **Luồng sự kiện:**
  * **Luồng chính:**
    1. Ứng viên chọn "Chỉnh sửa hồ sơ".
    2. Hệ thống hiển thị form với thông tin hiện tại (họ tên, SĐT, kỹ năng, kinh nghiệm, học vấn...).
    3. Ứng viên chỉnh sửa các trường thông tin cần thiết.
    4. Ứng viên nhấn "Lưu".
    5. Hệ thống kiểm tra tính hợp lệ dữ liệu và cập nhật vào hồ sơ.
    6. Hệ thống thông báo cập nhật thành công.
  * **Luồng thay thế:**
    * **5a.** Dữ liệu nhập không hợp lệ (định dạng SĐT sai...) $\rightarrow$ hệ thống báo lỗi, yêu cầu sửa lại.

---

### UC-CAN-02: Quản lý CV
* **Tác nhân:** Ứng viên (đang đăng nhập)
* **Mô tả:** Use case tổng, là điểm truy cập cho các thao tác quản lý CV của ứng viên, bao gồm tải lên, chỉnh sửa, xóa và đặt CV mặc định.
* **Pre-condition:** Ứng viên đã đăng nhập và đang ở trang Quản lý CV.
* **Post-condition:** Danh sách CV được hiển thị/cập nhật theo thao tác của ứng viên.
* **Luồng sự kiện:**
  * **Luồng chính:**
    1. Ứng viên truy cập mục "Quản lý CV".
    2. Hệ thống hiển thị danh sách CV hiện có của ứng viên.
    3. Ứng viên chọn một thao tác: `<<include>>` **UC-CAN-03: Tải lên CV mới**, `<<include>>` **UC-CAN-04: Chỉnh sửa/Cập nhật CV**, `<<include>>` **UC-CAN-05: Xóa CV**, hoặc `<<include>>` **UC-CAN-06: Đặt CV mặc định**.
    4. Hệ thống thực hiện thao tác tương ứng và cập nhật danh sách CV.
  * **Luồng thay thế:**
    * **2a.** Ứng viên chưa có CV nào $\rightarrow$ hệ thống hiển thị thông báo trống và gợi ý tải CV lên.

---

### UC-CAN-03: Tải lên CV mới
* **Tác nhân:** Ứng viên
* **Mô tả:** Cho phép ứng viên tải lên một file CV mới (định dạng PDF/Word) để lưu vào hồ sơ (use case con được include từ UC-CAN-02).
* **Pre-condition:** Ứng viên đã đăng nhập và đang ở màn hình Quản lý CV.
* **Post-condition:** File CV mới được lưu vào tài khoản ứng viên và xuất hiện trong danh sách CV.
* **Luồng sự kiện:**
  * **Luồng chính:**
    1. Ứng viên chọn "Tải lên CV mới".
    2. Hệ thống hiển thị hộp thoại chọn file.
    3. Ứng viên chọn file CV (PDF/Word) từ thiết bị.
    4. Hệ thống kiểm tra định dạng và dung lượng file.
    5. Hệ thống tải file lên máy chủ và thêm vào danh sách CV.
    6. Hệ thống thông báo tải lên thành công.
  * **Luồng thay thế:**
    * **4a.** File sai định dạng hoặc vượt dung lượng cho phép $\rightarrow$ hệ thống báo lỗi, yêu cầu chọn file khác.

---

### UC-CAN-04: Chỉnh sửa / Cập nhật CV
* **Tác nhân:** Ứng viên
* **Mô tả:** Cho phép ứng viên thay thế nội dung một CV đã có bằng phiên bản mới, hoặc đổi tên CV (use case con được include từ UC-CAN-02).
* **Pre-condition:** Ứng viên đã có ít nhất một CV trong danh sách.
* **Post-condition:** CV được chọn được cập nhật với nội dung/tên mới.
* **Luồng sự kiện:**
  * **Luồng chính:**
    1. Ứng viên chọn CV cần chỉnh sửa từ danh sách.
    2. Hệ thống hiển thị tùy chọn: đổi tên hoặc thay thế file.
    3. Ứng viên thực hiện chỉnh sửa (đổi tên và/hoặc tải file mới lên).
    4. Ứng viên nhấn "Lưu".
    5. Hệ thống cập nhật thông tin CV.
    6. Hệ thống thông báo cập nhật thành công.
  * **Luồng thay thế:**
    * **3a.** File mới không hợp lệ $\rightarrow$ hệ thống báo lỗi, giữ nguyên CV cũ.

---

### UC-CAN-05: Xóa CV
* **Tác nhân:** Ứng viên
* **Mô tả:** Cho phép ứng viên xóa một CV không còn sử dụng khỏi danh sách CV của mình (use case con được include từ UC-CAN-02).
* **Pre-condition:** Ứng viên đã có ít nhất một CV trong danh sách.
* **Post-condition:** CV được chọn bị xóa khỏi hệ thống.
* **Luồng sự kiện:**
  * **Luồng chính:**
    1. Ứng viên chọn CV cần xóa và nhấn "Xóa".
    2. Hệ thống hiển thị hộp thoại xác nhận.
    3. Ứng viên xác nhận xóa.
    4. Hệ thống xóa CV khỏi danh sách và cơ sở dữ liệu.
    5. Hệ thống thông báo xóa thành công.
  * **Luồng thay thế:**
    * **1a.** CV đang là CV mặc định và là CV duy nhất $\rightarrow$ hệ thống cảnh báo và yêu cầu tải CV khác trước khi xóa (tùy quy tắc nghiệp vụ).
    * **3a.** Ứng viên hủy thao tác $\rightarrow$ hệ thống giữ nguyên danh sách CV.

---

### UC-CAN-06: Đặt CV mặc định
* **Tác nhân:** Ứng viên
* **Mô tả:** Cho phép ứng viên chọn một CV làm mặc định để sử dụng nhanh khi ứng tuyển (use case con được include từ UC-CAN-02).
* **Pre-condition:** Ứng viên đã có ít nhất một CV trong danh sách.
* **Post-condition:** CV được chọn trở thành CV mặc định; CV mặc định trước đó (nếu có) bị gỡ trạng thái.
* **Luồng sự kiện:**
  * **Luồng chính:**
    1. Ứng viên chọn "Đặt làm mặc định" trên một CV trong danh sách.
    2. Hệ thống gỡ trạng thái mặc định của CV hiện tại (nếu có).
    3. Hệ thống gán trạng thái mặc định cho CV được chọn.
    4. Hệ thống thông báo cập nhật thành công.
  * **Luồng thay thế:** Không có.

---

## NHÓM 2: TÌM KIẾM & KHÁM PHÁ VIỆC LÀM

### UC-CAN-07: Tìm kiếm việc làm
* **Tác nhân:** Ứng viên (đã đăng nhập hoặc khách)
* **Mô tả:** Cho phép người dùng tìm kiếm các tin tuyển dụng theo từ khóa, vị trí, ngành nghề.
* **Pre-condition:** Người dùng đang ở trang tìm kiếm việc làm.
* **Post-condition:** Hệ thống hiển thị danh sách việc làm phù hợp với từ khóa tìm kiếm.
* **Luồng sự kiện:**
  * **Luồng chính:**
    1. Người dùng nhập từ khóa (vị trí, ngành nghề, tên công việc) vào ô tìm kiếm.
    2. Người dùng nhấn "Tìm kiếm".
    3. Hệ thống truy vấn và trả về danh sách việc làm phù hợp.
    4. Hệ thống hiển thị kết quả dạng danh sách.
    5. (Tùy chọn) Người dùng `<<extend>>` sang **UC-CAN-08: Lọc kết quả tìm kiếm** để thu hẹp kết quả.
  * **Luồng thay thế:**
    * **3a.** Không có kết quả phù hợp $\rightarrow$ hệ thống hiển thị thông báo "Không tìm thấy việc làm" kèm gợi ý liên quan.

---

### UC-CAN-08: Lọc kết quả tìm kiếm
* **Tác nhân:** Ứng viên (đã đăng nhập hoặc khách)
* **Mô tả:** Mở rộng của việc tìm kiếm, cho phép người dùng thu hẹp danh sách kết quả theo mức lương, địa điểm, hình thức làm việc (remote/hybrid), kinh nghiệm (use case mở rộng `<<extend>>` từ UC-CAN-07).
* **Pre-condition:** Người dùng đã thực hiện tìm kiếm và đang xem danh sách kết quả.
* **Post-condition:** Danh sách kết quả được cập nhật theo các tiêu chí lọc đã chọn.
* **Luồng sự kiện:**
  * **Luồng chính:**
    1. Người dùng mở bảng bộ lọc.
    2. Người dùng chọn các tiêu chí: mức lương, địa điểm, hình thức làm việc, kinh nghiệm.
    3. Người dùng nhấn "Áp dụng".
    4. Hệ thống lọc lại danh sách việc làm theo tiêu chí đã chọn.
    5. Hệ thống hiển thị danh sách kết quả đã lọc.
  * **Luồng thay thế:**
    * **4a.** Không có việc làm phù hợp với bộ lọc $\rightarrow$ hệ thống thông báo và gợi ý nới lỏng tiêu chí.

---

### UC-CAN-09: Xem chi tiết công việc
* **Tác nhân:** Ứng viên (đã đăng nhập hoặc khách)
* **Mô tả:** Cho phép người dùng xem đầy đủ thông tin của một tin tuyển dụng: mô tả công việc, yêu cầu, quyền lợi, mức lương.
* **Pre-condition:** Người dùng đang xem danh sách việc làm hoặc có liên kết trực tiếp đến tin tuyển dụng.
* **Post-condition:** Thông tin chi tiết công việc được hiển thị đầy đủ cho người dùng.
* **Luồng sự kiện:**
  * **Luồng chính:**
    1. Người dùng chọn một tin tuyển dụng từ danh sách.
    2. Hệ thống truy vấn thông tin chi tiết công việc.
    3. Hệ thống hiển thị mô tả công việc, yêu cầu, quyền lợi, mức lương, và các nút hành động (Ứng tuyển, Lưu).
  * **Luồng thay thế:**
    * **2a.** Tin tuyển dụng đã hết hạn/bị gỡ $\rightarrow$ hệ thống thông báo tin không còn hiệu lực.

---

### UC-CAN-10: Xem thông tin công ty
* **Tác nhân:** Ứng viên (đã đăng nhập hoặc khách)
* **Mô tả:** Cho phép người dùng xem thông tin giới thiệu, quy mô và danh sách các việc làm khác đang mở của một công ty.
* **Pre-condition:** Người dùng đang xem chi tiết công việc hoặc trang danh sách công ty.
* **Post-condition:** Trang thông tin công ty được hiển thị cùng danh sách việc làm liên quan.
* **Luồng sự kiện:**
  * **Luồng chính:**
    1. Người dùng chọn tên/logo công ty.
    2. Hệ thống truy vấn thông tin công ty (giới thiệu, quy mô, lĩnh vực).
    3. Hệ thống truy vấn danh sách việc làm khác đang mở của công ty.
    4. Hệ thống hiển thị trang thông tin công ty kèm danh sách việc làm.
  * **Luồng thay thế:**
    * **3a.** Công ty không còn tin tuyển dụng nào khác $\rightarrow$ hệ thống hiển thị danh sách trống.

---

## NHÓM 3: TƯƠNG TÁC & ỨNG TUYỂN

### UC-CAN-11: Ứng tuyển công việc
* **Tác nhân:** Ứng viên (đã đăng nhập)
* **Mô tả:** Cho phép ứng viên gửi hồ sơ ứng tuyển cho một công việc, bằng cách ứng tuyển nhanh hoặc chọn CV cụ thể để đính kèm.
* **Pre-condition:** Ứng viên đã đăng nhập, đang xem chi tiết một công việc còn hiệu lực.
* **Post-condition:** Hồ sơ ứng tuyển được ghi nhận trong hệ thống với trạng thái "Đã nộp"; nhà tuyển dụng nhận được hồ sơ.
* **Luồng sự kiện:**
  * **Luồng chính:**
    1. Ứng viên nhấn "Ứng tuyển ngay" tại trang chi tiết công việc.
    2. Hệ thống bắt buộc thực hiện `<<include>>` **UC-CAN-12: Chọn / Đính kèm CV** để xác định CV sử dụng.
    3. Ứng viên xác nhận thông tin ứng tuyển (thư giới thiệu — tùy chọn).
    4. Ứng viên nhấn "Gửi hồ sơ".
    5. Hệ thống ghi nhận hồ sơ ứng tuyển với trạng thái "Đã nộp".
    6. Hệ thống gửi thông báo xác nhận cho ứng viên và thông báo cho nhà tuyển dụng.
  * **Luồng thay thế:**
    * **2a.** Ứng viên chưa có CV nào $\rightarrow$ hệ thống chuyển hướng đến **UC-CAN-03: Tải lên CV mới** trước khi tiếp tục.
    * **5a.** Ứng viên đã ứng tuyển công việc này trước đó $\rightarrow$ hệ thống thông báo trùng lặp và ngăn gửi lại.

---

### UC-CAN-12: Chọn / Đính kèm CV
* **Tác nhân:** Ứng viên
* **Mô tả:** Cho phép ứng viên chọn một CV có sẵn (mặc định hoặc CV khác) trong danh sách để đính kèm vào hồ sơ ứng tuyển (use case bắt buộc được include từ UC-CAN-11).
* **Pre-condition:** Ứng viên đang trong quy trình ứng tuyển và có ít nhất một CV.
* **Post-condition:** Một CV cụ thể được xác định để đính kèm cho hồ sơ ứng tuyển.
* **Luồng sự kiện:**
  * **Luồng chính:**
    1. Hệ thống hiển thị danh sách CV của ứng viên, mặc định chọn sẵn CV mặc định (nếu có).
    2. Ứng viên xác nhận CV mặc định hoặc chọn một CV khác trong danh sách.
    3. Hệ thống ghi nhận CV được chọn cho hồ sơ ứng tuyển hiện tại.
  * **Luồng thay thế:**
    * **1a.** Ứng viên chưa có CV nào $\rightarrow$ hệ thống yêu cầu tải CV mới lên (chuyển sang **UC-CAN-03**) trước khi tiếp tục.

---

### UC-CAN-13: Xem danh sách việc đã ứng tuyển
* **Tác nhân:** Ứng viên (đã đăng nhập)
* **Mô tả:** Cho phép ứng viên xem toàn bộ danh sách các công việc đã ứng tuyển, kèm theo dõi trạng thái xử lý hồ sơ.
* **Pre-condition:** Ứng viên đã đăng nhập và đã ứng tuyển ít nhất một công việc.
* **Post-condition:** Danh sách việc làm đã ứng tuyển cùng trạng thái hồ sơ được hiển thị.
* **Luồng sự kiện:**
  * **Luồng chính:**
    1. Ứng viên truy cập mục "Việc làm đã ứng tuyển".
    2. Hệ thống truy vấn danh sách hồ sơ ứng tuyển của ứng viên.
    3. Hệ thống bắt buộc thực hiện `<<include>>` **UC-CAN-14: Theo dõi trạng thái hồ sơ** để hiển thị trạng thái cho từng hồ sơ.
    4. Hệ thống hiển thị danh sách kèm trạng thái tương ứng.
  * **Luồng thay thế:**
    * **2a.** Ứng viên chưa ứng tuyển công việc nào $\rightarrow$ hệ thống hiển thị danh sách trống kèm gợi ý tìm việc.

---

### UC-CAN-14: Theo dõi trạng thái hồ sơ
* **Tác nhân:** Ứng viên
* **Mô tả:** Cho phép ứng viên xem trạng thái xử lý hiện tại của từng hồ sơ ứng tuyển: Đã nộp, Nhà tuyển dụng đã xem, Mời phỏng vấn, Trúng tuyển, Từ chối (use case con được include từ UC-CAN-13).
* **Pre-condition:** Ứng viên đang xem danh sách việc làm đã ứng tuyển.
* **Post-condition:** Trạng thái hồ sơ hiện tại của từng công việc được hiển thị chính xác.
* **Luồng sự kiện:**
  * **Luồng chính:**
    1. Hệ thống truy vấn trạng thái mới nhất của từng hồ sơ ứng tuyển từ cơ sở dữ liệu.
    2. Hệ thống hiển thị nhãn trạng thái tương ứng (Đã nộp / Đã xem / Mời phỏng vấn / Trúng tuyển / Từ chối) bên cạnh mỗi công việc.
    3. Ứng viên có thể chọn một hồ sơ để xem chi tiết lịch sử thay đổi trạng thái.
  * **Luồng thay thế:**
    * **1a.** Trạng thái vừa được nhà tuyển dụng cập nhật $\rightarrow$ hệ thống đồng thời kích hoạt `<<include>>` **UC-CAN-18: Xem thông báo hệ thống** để báo cho ứng viên.

---

### UC-CAN-15: Lưu / Bỏ lưu công việc
* **Tác nhân:** Ứng viên (đã đăng nhập)
* **Mô tả:** Cho phép ứng viên đánh dấu (bookmark) một công việc quan tâm để xem lại sau, hoặc bỏ đánh dấu một công việc đã lưu.
* **Pre-condition:** Ứng viên đã đăng nhập và đang xem một công việc (danh sách hoặc chi tiết).
* **Post-condition:** Trạng thái lưu của công việc được cập nhật (đã lưu/chưa lưu).
* **Luồng sự kiện:**
  * **Luồng chính:**
    1. Ứng viên nhấn biểu tượng "Lưu" (bookmark) trên một công việc.
    2. Hệ thống kiểm tra trạng thái lưu hiện tại của công việc đó.
    3. Hệ thống thêm công việc vào danh sách đã lưu của ứng viên.
    4. Hệ thống cập nhật giao diện biểu tượng thành "Đã lưu".
  * **Luồng thay thế:**
    * **3a.** Công việc đã được lưu trước đó $\rightarrow$ hệ thống gỡ công việc khỏi danh sách đã lưu (bỏ lưu) và cập nhật biểu tượng về trạng thái ban đầu.

---

### UC-CAN-16: Xem danh sách việc đã lưu
* **Tác nhân:** Ứng viên (đã đăng nhập)
* **Mô tả:** Cho phép ứng viên xem lại toàn bộ danh sách các công việc đã đánh dấu/bookmark trước đó (use case mở rộng `<<extend>>` liên quan đến UC-CAN-15).
* **Pre-condition:** Ứng viên đã đăng nhập và đã có ít nhất một công việc được lưu.
* **Post-condition:** Danh sách công việc đã lưu được hiển thị.
* **Luồng sự kiện:**
  * **Luồng chính:**
    1. Ứng viên truy cập mục "Việc làm đã lưu".
    2. Hệ thống truy vấn danh sách công việc đã được ứng viên đánh dấu lưu.
    3. Hệ thống hiển thị danh sách kèm trạng thái tin (còn hiệu lực/hết hạn).
    4. (Tùy chọn) Ứng viên `<<extend>>` thực hiện **UC-CAN-15: Lưu / Bỏ lưu công việc** ngay tại danh sách để bỏ lưu một mục.
  * **Luồng thay thế:**
    * **2a.** Ứng viên chưa lưu công việc nào $\rightarrow$ hệ thống hiển thị danh sách trống kèm gợi ý tìm việc.

---

## NHÓM 4: ĐÁNH GIÁ & TIỆN ÍCH

### UC-CAN-17: Đánh giá công ty
* **Tác nhân:** Ứng viên (đã đăng nhập)
* **Mô tả:** Cho phép ứng viên viết bài đánh giá và chấm điểm trải nghiệm phỏng vấn/làm việc tại một công ty.
* **Pre-condition:** Ứng viên đã đăng nhập và đang ở trang thông tin công ty.
* **Post-condition:** Đánh giá của ứng viên được lưu vào hệ thống và hiển thị (sau kiểm duyệt nếu có).
* **Luồng sự kiện:**
  * **Luồng chính:**
    1. Ứng viên chọn "Viết đánh giá" tại trang công ty.
    2. Hệ thống hiển thị form đánh giá: số sao/điểm, nội dung nhận xét, giai đoạn (phỏng vấn/làm việc).
    3. Ứng viên nhập nội dung đánh giá và nhấn "Gửi".
    4. Hệ thống kiểm tra tính hợp lệ (độ dài, nội dung không vi phạm).
    5. Hệ thống lưu đánh giá và cập nhật điểm trung bình của công ty.
    6. Hệ thống thông báo gửi đánh giá thành công.
  * **Luồng thay thế:**
    * **4a.** Nội dung vi phạm chính sách $\rightarrow$ hệ thống từ chối và yêu cầu chỉnh sửa.
    * **5a.** Hệ thống yêu cầu kiểm duyệt trước khi công khai $\rightarrow$ đánh giá ở trạng thái "chờ duyệt".

---

### UC-CAN-18: Xem thông báo hệ thống
* **Tác nhân:** Ứng viên (đã đăng nhập)
* **Mô tả:** Cho phép ứng viên nhận và xem các thông báo về lịch phỏng vấn, thay đổi trạng thái hồ sơ, hoặc việc làm gợi ý phù hợp.
* **Pre-condition:** Ứng viên đã đăng nhập; hệ thống đã phát sinh ít nhất một thông báo.
* **Post-condition:** Thông báo được hiển thị cho ứng viên và đánh dấu đã đọc (nếu ứng viên mở xem).
* **Luồng sự kiện:**
  * **Luồng chính:**
    1. Hệ thống phát sinh thông báo (từ thay đổi trạng thái hồ sơ, lịch phỏng vấn, gợi ý việc làm...).
    2. Hệ thống hiển thị số lượng thông báo chưa đọc trên biểu tượng chuông.
    3. Ứng viên nhấn vào biểu tượng thông báo.
    4. Hệ thống hiển thị danh sách thông báo theo thời gian gần nhất.
    5. Ứng viên chọn một thông báo để xem chi tiết/điều hướng đến nội dung liên quan.
    6. Hệ thống đánh dấu thông báo đã đọc.
  * **Luồng thay thế:**
    * **1a.** Không có thông báo mới $\rightarrow$ hệ thống hiển thị danh sách trống.

---

## NHÓM 5: PHÂN TÍCH & QUẢN LÝ CÁC TRƯỜNG HỢP BIÊN (EDGE CASES)

### 5.1 Quản lý Hồ sơ & CV (`UC-CAN-01` - `UC-CAN-06`)

| Mã Edge Case | Tên trường hợp biên | Mô tả chi tiết & Cách xử lý hệ thống |
|---|---|---|
| **EC-CAN-CV-01** | **Giả mạo định dạng file CV** | Ứng viên đổi tên file thực thi (`.exe`, `.bat`, `.sh`) thành `.pdf` hoặc `.docx` rồi tải lên.<br>$\rightarrow$ **Xử lý:** Hệ thống không chỉ kiểm tra phần mở rộng file mà phải kiểm tra MIME Type thực tế và Magic Bytes (Signature) của file (vd: `%PDF-` cho PDF). Nếu sai, từ chối và báo lỗi `INVALID_CV_FILE_FORMAT` (HTTP 400). |
| **EC-CAN-CV-02** | **Gián đoạn kết nối S3 / MinIO** | Dịch vụ Object Storage gặp sự cố hoặc timeout khi đang upload file CV.<br>$\rightarrow$ **Xử lý:** Giao dịch lưu metadata database được rollback hoàn toàn (`@Transactional`), xóa file tạm trên server, hệ thống trả về thông báo lỗi thân thiện `503 Service Unavailable` và gợi ý thử lại. |
| **EC-CAN-CV-03** | **Vượt quá số lượng CV cho phép** | Ứng viên tải lên vượt quá số lượng CV tối đa cho phép (ví dụ: tối đa 5 CV/ứng viên).<br>$\rightarrow$ **Xử lý:** Hệ thống kiểm tra số lượng CV hiện tại trước khi nhận file. Nếu đã đạt giới hạn, chặn thao tác và yêu cầu ứng viên xóa bớt CV cũ trước khi tải CV mới. |
| **EC-CAN-CV-04** | **Xóa CV đã dùng để ứng tuyển** | Ứng viên xóa một CV đã được dùng để gửi hồ sơ cho các công việc trước đó.<br>$\rightarrow$ **Xử lý:** File CV đã nộp được chụp bản sao đóng băng (Immutable Snapshot) trong cơ sở dữ liệu / Storage của hồ sơ ứng tuyển đó. Thao tác xóa CV trong danh sách quản lý cá nhân chỉ xóa bản ghi tham chiếu người dùng, không làm hỏng dữ liệu hiển thị của Nhà tuyển dụng. |
| **EC-CAN-CV-05** | **Gửi request upload trùng lặp (Double Click)** | Ứng viên nhấn nút "Tải lên" nhiều lần liên tiếp do mạng chậm.<br>$\rightarrow$ **Xử lý:** Client disable nút bấm ngay khi submit; backend sử dụng token idempotency / rate limiter để loại bỏ các request trùng lặp trong thời gian ngắn. |

---

### 5.2 Tìm kiếm & Khám phá việc làm (`UC-CAN-07` - `UC-CAN-10`)

| Mã Edge Case | Tên trường hợp biên | Mô tả chi tiết & Cách xử lý hệ thống |
|---|---|---|
| **EC-CAN-JOB-01** | **Xung đột thời gian hết hạn công việc** | Tin tuyển dụng hết hạn hoặc bị nhà tuyển dụng đóng ngay trong khoảng thời gian giữa lúc ứng viên xem danh sách và nhấn xem chi tiết.<br>$\rightarrow$ **Xử lý:** Khi ứng viên chọn xem chi tiết, hệ thống kiểm tra trạng thái tin. Nếu tin đã đóng/hết hạn, hiển thị banner cảnh báo: *"Tin tuyển dụng này đã ngừng nhận hồ sơ"* và gợi ý các công việc tương tự. |
| **EC-CAN-JOB-02** | **Ký tự đặc biệt & SQL/RSQL Injection** | Ứng viên nhập các chuỗi ký tự tấn công (như `' OR '1'='1`, `<script>`) vào ô tìm kiếm từ khóa.<br>$\rightarrow$ **Xử lý:** Sanitize toàn bộ input, sử dụng Prepared Statements / Parameterized Queries cho SQL và Escape Special Characters cho Elasticsearch/Full-text Search index. |
| **EC-CAN-JOB-03** | **Phân trang với offset quá lớn** | Client truyền tham số phân trang bất thường như `page=999999`.<br>$\rightarrow$ **Xử lý:** Giới hạn tham số `page` và `size` tối đa (vd: max `pageSize=100`, max `totalPages=500`). Nếu vượt quá, tự động trả về trang cuối cùng hợp lệ hoặc lỗi `400 Bad Request`. |
| **EC-CAN-JOB-04** | **Bộ lọc giá trị vô lý** | Ứng viên chọn mức lương tối thiểu cao hơn mức lương tối đa (`salary_min = 5000` > `salary_max = 2000`).<br>$\rightarrow$ **Xử lý:** Frontend tự động điều chỉnh khoảng slider hợp lệ; backend tự chuẩn hóaswap 2 giá trị hoặc phản hồi lỗi `400 Invalid Filter Criteria`. |

---

### 5.3 Tương tác & Ứng tuyển (`UC-CAN-11` - `UC-CAN-16`)

| Mã Edge Case | Tên trường hợp biên | Mô tả chi tiết & Cách xử lý hệ thống |
|---|---|---|
| **EC-CAN-APP-01** | **Ứng tuyển đồng thời từ nhiều thiết bị (Race Condition)** | Ứng viên mở 2 tab/thiết bị và nhấn "Ứng tuyển" cho cùng 1 công việc tại cùng một thời điểm.<br>$\rightarrow$ **Xử lý:** Ràng buộc duy nhất `UNIQUE(candidate_id, job_id)` ở tầng Database catch ngoại lệ duplicate key, trả về `409 Conflict` kèm thông báo *"Bạn đã ứng tuyển công việc này rồi"*. |
| **EC-CAN-APP-02** | **CV bị xóa ngay trước khi nhấn Gửi hồ sơ** | Ứng viên mở form ứng tuyển, xóa CV ở tab khác, rồi quay lại nhấn "Gửi hồ sơ".<br>$\rightarrow$ **Xử lý:** Backend kiểm tra sự tồn tại và quyền sở hữu đối với `cv_id` ngay trong transaction nộp đơn. Nếu không tìm thấy, trả về lỗi `404 CV_NOT_FOUND` và yêu cầu chọn CV khác. |
| **EC-CAN-APP-03** | **Lỗi Broker Kafka khi gửi sự kiện sau khi lưu DB** | Hồ sơ nộp đã được lưu thành công vào cơ sở dữ liệu PostgreSQL nhưng tiến trình gửi message sang Kafka `guardianwork.application.submitted` bị lỗi (Kafka down/network drop).<br>$\rightarrow$ **Xử lý:** Áp dụng mô hình Transactional Outbox Pattern. Message được ghi vào bảng `outbox_events` trong cùng DB transaction, một Worker background riêng sẽ quét và retry đảm bảo tính nhất quán cuối cùng (Eventual Consistency). |
| **EC-CAN-APP-04** | **Tài khoản bị khóa trong lúc nộp hồ sơ** | Tài khoản ứng viên bị Admin khóa/đình chỉ (`status = 'LOCKED'`) trong khi ứng viên đang soạn thư giới thiệu.<br>$\rightarrow$ **Xử lý:** Spring Security Interceptor kiểm tra trạng thái tài khoản active trên JWT authentication filter ở mọi protected request, từ chối giao dịch với lỗi `403 Forbidden`. |
| **EC-CAN-APP-05** | **Xem việc đã lưu nhưng việc bị nhà tuyển dụng xóa hoàn toàn** | Công việc được lưu trong danh sách bookmark nhưng sau đó bị sếp xóa hẳn (Hard Delete) khỏi database.<br>$\rightarrow$ **Xử lý:** Danh sách việc đã lưu sử dụng `LEFT JOIN`. Nếu bài đăng không tồn tại, hiển thị item dạng Disabled với nhãn *"Công việc không còn tồn tại"* kèm nút *"Bỏ lưu"*. |

---

### 5.4 Đánh giá & Tiện ích (`UC-CAN-17` - `UC-CAN-18`)

| Mã Edge Case | Tên trường hợp biên | Mô tả chi tiết & Cách xử lý hệ thống |
|---|---|---|
| **EC-CAN-REV-01** | **Đánh giá trùng lặp liên tục cho một công ty** | Ứng viên viết nhiều bài đánh giá tiêu cực / spam cho cùng 1 công ty.<br>$\rightarrow$ **Xử lý:** Giới hạn mỗi ứng viên chỉ được gửi tối đa 1 bài đánh giá cho 1 công ty trong vòng 6 tháng. Bài mới gửi sẽ đè bài cũ hoặc yêu cầu chỉnh sửa bài cũ. |
| **EC-CAN-NOTIF-01** | **Thông báo nhận sai thứ tự do trễ mạng** | Trạng thái hồ sơ chuyển đổi nhanh (`SUBMITTED` $\rightarrow$ `REVIEWED` $\rightarrow$ `INVITED_FOR_INTERVIEW`), thông báo gửi về bị sai thứ tự thời gian trên giao diện.<br>$\rightarrow$ **Xử lý:** Thông báo đính kèm timestamp chính xác của sự kiện và `sequence_number`. Frontend sắp xếp danh sách thông báo theo thứ tự thời gian phát sinh thay vì thời gian nhận client. |
