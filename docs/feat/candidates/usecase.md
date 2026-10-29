# Sơ đồ usecase tổng quan & Mô tả usecase (Bản chi tiết)

## BẢNG PHÂN PHỐI ƯU TIÊN

| Use case | Nhóm | Ưu tiên | Ghi chú |
| :--- | :--- | :--- | :--- |
| **UC-CAN-01** Cập nhật hồ sơ cá nhân | Hồ sơ | Cao | |
| **UC-CAN-03** Tải lên hồ sơ xin việc mới | CV | Cao | |
| **UC-CAN-07** Tìm kiếm tin tuyển dụng | Tìm kiếm | Cao | Tác nhân: Khách hoặc Ứng viên. Đã bao gồm việc lọc kết quả (từ khóa, địa điểm, ngành nghề, mức lương, kinh nghiệm, hình thức làm việc) |
| **UC-CAN-09** Xem chi tiết tin tuyển dụng | Tìm kiếm | Cao | Tác nhân: Khách hoặc Ứng viên |
| **UC-CAN-11** Điền thông tin ứng tuyển | Ứng tuyển | Cao | Đã bao gồm tự động điền từ hồ sơ cá nhân và chọn / đính kèm hồ sơ xin việc |
| **UC-CAN-13** Xem danh sách tin tuyển dụng đã ứng tuyển | Ứng tuyển | Cao | Có lọc theo trạng thái và tìm theo tên công việc / công ty |
| **UC-CAN-14** Nhận thông báo về trạng thái ứng tuyển | Ứng tuyển | Cao | |
| **UC-CAN-08** Tìm kiếm thông tin công ty tuyển dụng | Tìm kiếm | Trung bình | Tác nhân: Khách hoặc Ứng viên. Use case mới, thay cho "Lọc kết quả tìm kiếm" (đã gộp vào UC-CAN-07) |
| **UC-CAN-10** Xem chi tiết thông tin công ty tuyển dụng | Tìm kiếm | Trung bình | Tác nhân: Khách hoặc Ứng viên |
| **UC-CAN-15** Lưu lại tin tuyển dụng | Ứng tuyển | Trung bình | Tách từ "Lưu / Bỏ lưu công việc" |
| **UC-CAN-19** Bỏ lưu tin tuyển dụng | Ứng tuyển | Trung bình | Tách từ "Lưu / Bỏ lưu công việc" |
| **UC-CAN-16** Xem danh sách tin tuyển dụng đã lưu | Ứng tuyển | Trung bình | |
| **UC-CAN-18** Xem thông báo hệ thống | Tiện ích | Trung bình | |
| **UC-CAN-23** Hỏi đáp luật Lao động với AI | Tiện ích | Trung bình | Tác nhân: Ứng viên, nhân viên tuyển dụng. Use case mới; ưu tiên là đề xuất vì UC-CAN-22 mở rộng từ use case này |
| **UC-CAN-05** Xóa hồ sơ xin việc | CV | Thấp | |
| **UC-CAN-06** Đặt hồ sơ xin việc làm mặc định | CV | Thấp | |
| **UC-CAN-17** Đánh giá bằng sao | Đánh giá | Thấp | Tách từ "Đánh giá công ty" |
| **UC-CAN-21** Viết nhận xét cá nhân | Đánh giá | Thấp | Tách từ "Đánh giá công ty" |
| **UC-CAN-20** Báo cáo tin tuyển dụng | Tiện ích | Thấp | Use case mới |
| **UC-CAN-22** Xem nguồn tin luật lao động | Tiện ích | Thấp | Use case mới; mở rộng từ UC-CAN-23 |
| **UC-CAN-02** Quản lý hồ sơ xin việc (tổng) | CV | — | Use case tổng hợp của UC-CAN-03, 05, 06 |

**Các thay đổi so với bản trước:**
- Bỏ **UC-CAN-04** "Chỉnh sửa / Cập nhật CV" (không có trong mô tả use case).
- Gộp **UC-CAN-08** "Lọc kết quả tìm kiếm" vào UC-CAN-07; mã UC-CAN-08 được dùng lại cho "Tìm kiếm thông tin công ty tuyển dụng".
- Gộp **UC-CAN-12** "Chọn / Đính kèm CV" vào UC-CAN-11.
- Tách **UC-CAN-15** thành "Lưu lại" (UC-CAN-15) và "Bỏ lưu" (UC-CAN-19).
- Thêm mới UC-CAN-20, 21, 22, 23; ưu tiên của các use case mới là đề xuất, cần xác nhận lại.
- Hồ sơ đã nộp được sao chép thành bản lưu riêng gắn với đơn ứng tuyển (cập nhật UC-CAN-11, UC-CAN-05 và EC-CAN-CV-04).
- Bổ sung edge case EC-CAN-LAW-01 đến 03 cho UC-CAN-22.

---
## Tìm kiếm tin tuyển dụng

- **Tác nhân:** Khách hoặc Ứng viên
- **Mô tả:** Cho phép tác nhân tìm kiếm các tin tuyển dụng trên hệ thống theo từ khóa, ngành nghề, địa điểm, mức lương và các tiêu chí lọc khác.
- **Pre-condition:** Tác nhân truy cập vào hệ thống.
- **Post-condition:** Hệ thống hiển thị danh sách các tin tuyển dụng phù hợp với điều kiện tìm kiếm.
- **Luồng sự kiện:**
  - **Luồng chính:**
    1. Tác nhân nhập từ khóa tìm kiếm và/hoặc lựa chọn các bộ lọc (địa điểm, ngành nghề, mức lương, kinh nghiệm, hình thức làm việc).
    2. Tác nhân nhấn nút "Tìm kiếm".
    3. Hệ thống tiếp nhận và kiểm tra tính hợp lệ của tiêu chí tìm kiếm.
    4. Hệ thống truy vấn cơ sở dữ liệu để lấy các tin tuyển dụng đang hoạt động thỏa mãn điều kiện.
    5. Hệ thống hiển thị danh sách kết quả phù hợp kèm theo thông tin tóm tắt và phân trang.
  - **Luồng thay thế:**
    - *3a.* Không tìm thấy kết quả phù hợp. Hệ thống hiển thị thông báo "Không tìm thấy tin tuyển dụng nào phù hợp với tìm kiếm của bạn" và đưa ra gợi ý điều chỉnh tiêu chí lọc.

## Xem chi tiết tin tuyển dụng

- **Tác nhân:** Khách hoặc ứng viên
- **Mô tả:** Cho phép tác nhân xem đầy đủ nội dung chi tiết của một bài đăng tuyển dụng bao gồm mô tả công việc, yêu cầu, quyền lợi, mức lương, địa điểm và thông tin nhà tuyển dụng.
- **Pre-condition:** Tác nhân đang ở trang danh sách tin tuyển dụng hoặc truy cập qua đường dẫn trực tiếp.
- **Post-condition:** Thông tin chi tiết của tin tuyển dụng được hiển thị đầy đủ trên giao diện.
- **Luồng sự kiện:**
  - **Luồng chính:**
    1. Tác nhân nhấn chọn một tin tuyển dụng từ danh sách hiển thị.
    2. Hệ thống kiểm tra tính khả dụng và trạng thái của tin tuyển dụng.
    3. Hệ thống truy xuất dữ liệu và hiển thị toàn bộ nội dung bài tuyển dụng: Tiêu đề, Mức lương, Địa điểm, Mô tả công việc, Yêu cầu ứng viên, Quyền lợi, Hạn nộp hồ sơ và Thông tin công ty tuyển dụng.
    4. Hệ thống tự động ghi nhận và tăng số lượt xem của tin tuyển dụng.
  - **Luồng thay thế:**
    - *2a.* Tin tuyển dụng đã bị ẩn, xóa hoặc hết hạn. Hệ thống hiển thị thông báo "Tin tuyển dụng này không còn tồn tại hoặc đã hết hạn nộp hồ sơ" kèm gợi ý các công việc tương tự.

## Tìm kiếm thông tin công ty tuyển dụng

- **Tác nhân:** Khách hoặc ứng viên
- **Mô tả:** Cho phép tác nhân tìm kiếm các công ty tuyển dụng trên hệ thống theo tên, ngành nghề hoạt động hoặc địa điểm.
- **Pre-condition:** Tác nhân truy cập vào trang Danh sách công ty trên hệ thống.
- **Post-condition:** Hệ thống hiển thị danh sách các công ty phù hợp với từ khóa hoặc điều kiện lọc.
- **Luồng sự kiện:**
  - **Luồng chính:**
    1. Tác nhân nhập tên công ty vào ô tìm kiếm và/hoặc chọn các bộ lọc (lĩnh vực hoạt động, quy mô, địa điểm).
    2. Tác nhân nhấn nút "Tìm kiếm".
    3. Hệ thống kiểm tra điều kiện tìm kiếm và truy vấn danh sách doanh nghiệp từ cơ sở dữ liệu.
    4. Hệ thống hiển thị danh sách các công ty đáp ứng điều kiện, bao gồm: Logo, Tên công ty, Lĩnh vực, Địa điểm và số lượng tin tuyển dụng đang mở.
  - **Luồng thay thế:**
    - *3a.* Không có công ty nào khớp với điều kiện tìm kiếm. Hệ thống hiển thị thông báo "Không tìm thấy công ty phù hợp" và gợi ý thử lại với từ khóa khác.

## Xem chi tiết thông tin công ty tuyển dụng

- **Tác nhân:** Khách hoặc ứng viên
- **Mô tả:** Cho phép tác nhân xem trang thông tin chi tiết của doanh nghiệp bao gồm giới thiệu, quy mô, địa điểm, văn hóa doanh nghiệp, các tin tuyển dụng đang mở và đánh giá từ cộng đồng.
- **Pre-condition:** Tác nhân chọn một công ty từ danh sách tìm kiếm hoặc nhấp vào tên công ty từ bài đăng tuyển dụng.
- **Post-condition:** Màn hình chi tiết thông tin công ty được hiển thị đầy đủ.
- **Luồng sự kiện:**
  - **Luồng chính:**
    1. Tác nhân nhấn vào tên hoặc logo của công ty muốn xem.
    2. Hệ thống kiểm tra sự tồn tại của hồ sơ doanh nghiệp.
    3. Hệ thống truy xuất dữ liệu và hiển thị đầy đủ trang cá nhân của công ty, bao gồm:
      - Thông tin chung: Tên, Logo, Ảnh bìa, Lĩnh vực, Quy mô, Địa chỉ, Website.
      - Mô tả chi tiết giới thiệu doanh nghiệp và môi trường làm việc.
      - Danh sách các tin tuyển dụng đang hoạt động của công ty.
      - Danh sách bài nhận xét/đánh giá từ người dùng.
  - **Luồng thay thế:**
    - *2a.* Thông tin công ty không tồn tại hoặc đã bị khóa. Hệ thống hiển thị thông báo "Thông tin công ty hiện không khả dụng".

## Cập nhật hồ sơ cá nhân

- **Tác nhân:** Ứng viên
- **Mô tả:** Cho phép ứng viên cập nhật thông tin trong hồ sơ cá nhân bao gồm họ tên, số điện thoại, kỹ năng chuyên môn, kinh nghiệm làm việc và trình độ học vấn.
- **Pre-condition:** Ứng viên đã đăng nhập và đang ở trang Hồ sơ cá nhân.
- **Post-condition:** Thông tin hồ sơ cá nhân được cập nhật và lưu vào hệ thống.
- **Luồng sự kiện:**
  - **Luồng chính:**
    1. Ứng viên chọn "Chỉnh sửa hồ sơ".
    2. Hệ thống hiển thị form với thông tin hiện tại.
    3. Ứng viên chỉnh sửa các trường thông tin cần thiết.
    4. Ứng viên nhấn "Lưu".
    5. Hệ thống kiểm tra tính hợp lệ của dữ liệu mới:
      - *Email*: kiểu chuỗi, định dạng chuẩn theo quy định, tối đa 50 ký tự.
      - *Họ tên*: kiểu chuỗi, không chứa khoảng trắng, tối đa 150 ký tự.
      - *Số điện thoại*: kiểu chuỗi, phải định dạng theo 0xxxxxxxxx, tối đa 10 ký tự.
      - *Kỹ năng chuyên môn*: kiểu danh sách ký tự, tối đa 20 mục, mỗi mục dài 1-30 ký tự.
      - *Kinh nghiệm làm việc*: kiểu đối tượng, tất cả các trường dữ liệu đều không được trống.
      - *Trình độ học vấn*: kiểu đối tượng, tất cả các trường dữ liệu đều không được trống.
    6. Hệ thống chỉ cập nhật dữ liệu được yêu cầu vào hồ sơ; dữ liệu khác giữ nguyên.
    7. Hệ thống thông báo cập nhật thành công.
  - **Luồng thay thế:**
    - *5a.* Dữ liệu nhập không hợp lệ -> Hệ thống báo lỗi, yêu cầu sửa lại.

## Tải lên hồ sơ xin việc mới

- **Tác nhân:** Ứng viên
- **Mô tả:** Cho phép ứng viên tải lên một tệp hồ sơ xin việc mới, được hệ thống lưu trên dịch vụ lưu trữ đám mây (Cloudinary) và dùng để ứng tuyển khi tìm việc.
- **Pre-condition:** Ứng viên đã đăng nhập và đang ở trang Quản lý hồ sơ xin việc.
- **Post-condition:** Tệp hồ sơ xin việc hợp lệ được lưu trữ trên Cloudinary trong phạm vi truy cập của ứng viên; thông tin của tệp được ghi nhận vào hồ sơ tương ứng và hiển thị trong danh sách hồ sơ.
- **Luồng sự kiện:**
  - **Luồng chính:**
    1. Ứng viên chọn "Tải lên hồ sơ mới".
    2. Hệ thống hiển thị cảnh báo yêu cầu ứng viên không đưa các thông tin nhạy cảm, bao gồm: số căn cước công dân, thông tin tài khoản ngân hàng hoặc mật khẩu vào tệp hồ sơ; đồng thời hiển thị ô xác nhận bắt buộc.
    3. Ứng viên xác nhận, chọn tệp từ thiết bị và chọn "Tải lên".
    4. Hệ thống kiểm tra tệp về phần mở rộng, kiểu MIME tương ứng với định dạng PDF, DOC hoặc DOCX. Dung lượng tệp không vượt quá 10 MB.
    5. Hệ thống yêu cầu máy chủ ứng dụng cung cấp đường dẫn có chữ ký xác thực và tiến hành tải tệp lên Cloudinary.
    6. Hệ thống xác nhận thành công từ Cloudinary và lưu thông tin của tệp như tên, kích thước, kiểu tệp, ngày đăng, id độc nhất (publicId) vào cơ sở dữ liệu.
    7. Hệ thống thông báo quá trình đã thành công và cập nhật danh sách hồ sơ của ứng viên.
  - **Luồng thay thế:**
    - *2a.* Ứng viên chưa xác nhận nội dung cảnh báo. Hệ thống không khởi tạo quá trình tải lên và yêu cầu ứng viên thực hiện xác nhận.
    - *4a.* Tệp sai định dạng hoặc vượt dung lượng cho phép. Hệ thống báo lỗi, yêu cầu chọn tệp khác.
    - *6a.* Quá trình tải lên thất bại. Hệ thống không ghi nhận thông tin tệp và thông báo nguyên nhân lỗi.

## Xóa hồ sơ xin việc

- **Tác nhân:** Ứng viên
- **Mô tả:** Cho phép ứng viên xóa một tệp hồ sơ xin việc đã tải lên khỏi danh sách hồ sơ xin việc và hệ thống lưu trữ Cloudinary.
- **Pre-condition:** Ứng viên đã đăng nhập và đang ở trang Quản lý hồ sơ xin việc, danh sách hồ sơ có ít nhất một tệp.
- **Post-condition:** Tệp hồ sơ được xóa khỏi cơ sở dữ liệu và hệ thống lưu trữ Cloudinary, không còn hiển thị trong danh sách hồ sơ của ứng viên. Các đơn ứng tuyển đã nộp không bị ảnh hưởng.
- **Luồng sự kiện:**
  - **Luồng chính:**
    1. Ứng viên chọn "Xóa" tại tệp hồ sơ xin việc muốn xóa trong danh sách.
    2. Hệ thống hiển thị hộp thoại xác nhận việc xóa hồ sơ kèm cảnh báo hành động này không thể hoàn tác và nêu rõ các đơn ứng tuyển đã nộp trước đó vẫn giữ bản hồ sơ đã gửi.
    3. Ứng viên chọn "Xác nhận xóa".
    4. Hệ thống gửi yêu cầu xóa tệp tương ứng với publicId trên Cloudinary.
    5. Hệ thống nhận phản hồi thành công từ Cloudinary và cập nhật cơ sở dữ liệu để xóa bản ghi thông tin hồ sơ.
    6. Hệ thống thông báo xóa hồ sơ thành công và cập nhật lại danh sách hồ sơ hiển thị.
  - **Luồng thay thế:**
    - *3a.* Ứng viên chọn "Hủy bỏ". Hệ thống đóng hộp thoại xác nhận và giữ nguyên trạng thái danh sách hồ sơ.
    - *4a.* Lỗi kết nối hoặc xóa tệp trên Cloudinary thất bại. Hệ thống báo lỗi "Không thể xóa tệp, vui lòng thử lại sau" và giữ nguyên bản ghi trong cơ sở dữ liệu.

## Đặt hồ sơ xin việc làm mặc định

- **Tác nhân:** Ứng viên
- **Mô tả:** Cho phép ứng viên thiết lập một tệp hồ sơ xin việc làm hồ sơ mặc định để tự động đính kèm khi thực hiện ứng tuyển công việc.
- **Pre-condition:** Ứng viên đã đăng nhập và đang ở trang Quản lý hồ sơ xin việc, danh sách hồ sơ có ít nhất một tệp hồ sơ xin việc.
- **Post-condition:** Hồ sơ được chọn được đánh dấu là hồ sơ mặc định trong cơ sở dữ liệu, trạng thái mặc định của hồ sơ cũ (nếu có) bị xóa bỏ.
- **Luồng sự kiện:**
  - **Luồng chính:**
    1. Ứng viên chọn "Đặt làm hồ sơ mặc định" tại một tệp hồ sơ xin việc trong danh sách.
    2. Hệ thống kiểm tra xem hồ sơ này đã ở trạng thái mặc định hay chưa.
    3. Hệ thống cập nhật trạng thái hồ sơ được chọn thành mặc định (`isDefault = true`) và đặt lại trạng thái mặc định của các hồ sơ khác thành `false`.
    4. Hệ thống cập nhật giao diện, hiển thị nhãn "Mặc định" bên cạnh tệp hồ sơ vừa chọn.
    5. Hệ thống thông báo "Đã thiết lập hồ sơ mặc định thành công".
  - **Luồng thay thế:**
    - *2a.* Hồ sơ được chọn vốn đã là hồ sơ mặc định. Hệ thống hiển thị thông báo hồ sơ này hiện tại đã là mặc định và dừng quá trình.

## Điền thông tin ứng tuyển

- **Tác nhân:** Ứng viên
- **Mô tả:** Cho phép ứng viên điền các thông tin cần thiết, tự động trích xuất thông tin từ hồ sơ cá nhân và đính kèm hồ sơ xin việc để nộp đơn ứng tuyển cho một tin tuyển dụng cụ thể.
- **Pre-condition:** Ứng viên đã đăng nhập, đang xem thông tin chi tiết của tin tuyển dụng và tin tuyển dụng đang còn hạn nộp.
- **Post-condition:** Đơn ứng tuyển kèm bản sao tệp hồ sơ đã nộp được ghi nhận vào cơ sở dữ liệu với trạng thái "Đã nộp", thông tin hiển thị danh sách đã ứng tuyển được cập nhật.
- **Luồng sự kiện:**
  - **Luồng chính:**
    1. Ứng viên nhấn nút "Ứng tuyển" tại tin tuyển dụng.
    2. Hệ thống hiển thị mẫu form ứng tuyển bao gồm các trường: Họ tên, Email, Số điện thoại, Thư giới thiệu và khu vực đính kèm hồ sơ.
    3. Ứng viên chọn "Tự động điền từ Hồ sơ cá nhân" (hoặc hệ thống tự động kích hoạt tính năng này theo mặc định).
    4. Hệ thống truy vấn thông tin trong Hồ sơ cá nhân của ứng viên để điền tự động vào các trường tương ứng trên form, đồng thời tự động đính kèm tệp hồ sơ mặc định (nếu có).
    5. Ứng viên kiểm tra, điều chỉnh thông tin nếu cần thiết, viết thư giới thiệu và lựa chọn tệp hồ sơ xin việc mong muốn.
    6. Ứng viên nhấn "Gửi đơn ứng tuyển".
    7. Hệ thống kiểm tra tính hợp lệ của dữ liệu nhập:
      - *Họ tên*: kiểu chuỗi, bắt buộc, tối đa 150 ký tự.
      - *Email*: kiểu chuỗi, định dạng email chuẩn.
      - *Số điện thoại*: kiểu chuỗi, định dạng 0xxxxxxxxx, tối đa 10 ký tự.
      - *Thư giới thiệu*: kiểu chuỗi, tùy chọn, tối đa 1000 ký tự.
      - *Hồ sơ đính kèm*: phải có ít nhất 1 tệp hồ sơ xin việc hợp lệ được chọn.
    8. Hệ thống tạo bản ghi đơn ứng tuyển mới với trạng thái "Đã nộp", sao chép tệp hồ sơ đính kèm thành bản lưu riêng gắn với đơn ứng tuyển (không phụ thuộc vào tệp trong danh sách hồ sơ của ứng viên) và thông báo ứng tuyển thành công.
  - **Luồng thay thế:**
    - *3a.* Ứng viên muốn nhập mới hoàn toàn. Ứng viên chọn "Nhập thủ công", hệ thống xóa thông tin đã điền sẵn để ứng viên tự điền lại từ đầu.
    - *4a.* Hồ sơ cá nhân chưa có thông tin hoặc thông tin chưa đầy đủ. Hệ thống chỉ điền các trường hiện có, đưa ra gợi ý "Cập nhật hồ sơ cá nhân" hoặc yêu cầu ứng viên nhập bổ sung trực tiếp các trường còn thiếu.
    - *7a.* Thông tin điền chưa hợp lệ hoặc thiếu hồ sơ đính kèm. Hệ thống báo lỗi chi tiết tại các trường vi phạm và yêu cầu ứng viên hoàn thiện.
    - *8a.* Ứng viên đã ứng tuyển vào tin tuyển dụng này trước đó. Hệ thống đưa ra thông báo "Bạn đã nộp hồ sơ cho vị trí này" và ngăn việc gửi lại đơn.
    - *8b.* Sao chép tệp hồ sơ đính kèm thất bại. Hệ thống không tạo đơn ứng tuyển, thông báo lỗi và yêu cầu ứng viên gửi lại.

## Xem danh sách tin tuyển dụng đã ứng tuyển

- **Tác nhân:** Ứng viên
- **Mô tả:** Cho phép ứng viên theo dõi và kiểm tra danh sách toàn bộ các tin tuyển dụng mà họ đã nộp hồ sơ ứng tuyển kèm trạng thái xử lý hiện tại.
- **Pre-condition:** Ứng viên đã đăng nhập vào hệ thống.
- **Post-condition:** Danh sách các tin tuyển dụng đã nộp hồ sơ được truy xuất và hiển thị trực quan cho ứng viên.
- **Luồng sự kiện:**
  - **Luồng chính:**
    1. Ứng viên chọn mục "Công việc đã ứng tuyển" từ menu điều hướng cá nhân.
    2. Hệ thống lấy danh sách đơn ứng tuyển của ứng viên từ cơ sở dữ liệu.
    3. Hệ thống hiển thị danh sách gồm: Tên vị trí tuyển dụng, Tên công ty, Ngày ứng tuyển, Hồ sơ đã đính kèm và Trạng thái ứng tuyển (Đã nộp, Đã xem, Từ chối).
    4. Ứng viên có thể chọn lọc đơn ứng tuyển theo trạng thái hoặc tìm kiếm theo tên công việc/công ty.
    5. Hệ thống hiển thị kết quả lọc/tìm kiếm tương ứng.
  - **Luồng thay thế:**
    - *2a.* Ứng viên chưa từng nộp đơn ứng tuyển nào. Hệ thống hiển thị thông điệp "Bạn chưa ứng tuyển công việc nào" kèm gợi ý tìm việc làm.

## Nhận thông báo về trạng thái ứng tuyển

- **Tác nhân:** Ứng viên
- **Mô tả:** Cho phép ứng viên nhận các thông báo cập nhật tự động khi nhân viên tuyển dụng thay đổi trạng thái hồ sơ ứng tuyển của họ.
- **Pre-condition:** Ứng viên đã nộp hồ sơ ứng tuyển và nhân viên tuyển dụng đã cập nhật trạng thái của hồ sơ ứng tuyển đó.
- **Post-condition:** Thông báo mới được lưu vào hệ thống và hiển thị trong danh sách thông báo của ứng viên.
- **Luồng sự kiện:**
  - **Luồng chính:**
    1. Nhân viên tuyển dụng thực hiện cập nhật trạng thái đơn ứng tuyển của ứng viên.
    2. Hệ thống tự động tạo bản ghi thông báo mới cho tài khoản ứng viên kèm nội dung chi tiết về thay đổi trạng thái.
    3. Hệ thống cập nhật biểu tượng thông báo (tăng số lượng thông báo chưa đọc) trên giao diện của ứng viên.
    4. Ứng viên chọn xem danh sách thông báo và nhấn vào thông báo cập nhật trạng thái.
    5. Hệ thống chuyển hướng ứng viên đến trang chi tiết đơn ứng tuyển tương ứng và đánh dấu thông báo là "Đã đọc".
  - **Luồng thay thế:**
    - *4a.* Ứng viên đang ngoại tuyến. Hệ thống ghi nhận thông báo vào cơ sở dữ liệu và gửi thông báo qua email đăng ký của ứng viên.

## Lưu lại tin tuyển dụng

- **Tác nhân:** Ứng viên
- **Mô tả:** Cho phép ứng viên lưu các tin tuyển dụng quan tâm vào danh sách lưu trữ cá nhân để xem lại hoặc ứng tuyển sau.
- **Pre-condition:** Ứng viên đã đăng nhập và đang xem danh sách hoặc chi tiết tin tuyển dụng.
- **Post-condition:** Tin tuyển dụng được ghi nhận vào danh sách công việc đã lưu của ứng viên.
- **Luồng sự kiện:**
  - **Luồng chính:**
    1. Ứng viên chọn biểu tượng "Lưu tin" tại tin tuyển dụng tương ứng.
    2. Hệ thống kiểm tra xem tin tuyển dụng này đã nằm trong danh sách lưu trữ của ứng viên chưa.
    3. Hệ thống thêm bản ghi lưu tin tuyển dụng vào cơ sở dữ liệu cho ứng viên.
    4. Hệ thống thay đổi trạng thái biểu tượng "Lưu tin" thành đã lưu và thông báo "Đã lưu tin tuyển dụng thành công".
  - **Luồng thay thế:**
    - *2a.* Tin tuyển dụng đã được lưu từ trước. Hệ thống giữ nguyên trạng thái hoặc kích hoạt use case *Bỏ lưu tin tuyển dụng* nếu ứng viên nhấp lại vào biểu tượng.

## Xem danh sách tin tuyển dụng đã lưu

- **Tác nhân:** Ứng viên
- **Mô tả:** Cho phép ứng viên xem toàn bộ danh sách các tin tuyển dụng mà họ đã lưu trước đó.
- **Pre-condition:** Ứng viên đã đăng nhập hệ thống.
- **Post-condition:** Danh sách tin tuyển dụng đã lưu hiển thị đầy đủ trên màn hình quản lý.
- **Luồng sự kiện:**
  - **Luồng chính:**
    1. Ứng viên chọn mục "Tin tuyển dụng đã lưu" trên hệ thống.
    2. Hệ thống truy vấn danh sách các tin tuyển dụng đã được ứng viên đánh dấu lưu từ cơ sở dữ liệu.
    3. Hệ thống hiển thị danh sách các tin tuyển dụng bao gồm: Tên công việc, Công ty, Mức lương, Địa điểm làm việc và Hạn nộp.
    4. Ứng viên có thể nhấn vào từng tin tuyển dụng để xem chi tiết hoặc nộp đơn ứng tuyển.
  - **Luồng thay thế:**
    - *2a.* Danh sách lưu trống. Hệ thống hiển thị thông báo "Bạn chưa lưu tin tuyển dụng nào".

## Bỏ lưu tin tuyển dụng

- **Tác nhân:** Ứng viên
- **Mô tả:** Cho phép ứng viên loại bỏ một tin tuyển dụng ra khỏi danh sách đã lưu khi không còn quan tâm.
- **Pre-condition:** Ứng viên đã đăng nhập, tin tuyển dụng đang nằm trong danh sách tin đã lưu.
- **Post-condition:** Tin tuyển dụng bị xóa khỏi danh sách tin tuyển dụng đã lưu của ứng viên.
- **Luồng sự kiện:**
  - **Luồng chính:**
    1. Tại màn hình chi tiết hoặc danh sách tin tuyển dụng đã lưu, ứng viên chọn "Bỏ lưu".
    2. Hệ thống xóa bản ghi lưu tin tuyển dụng tương ứng khỏi cơ sở dữ liệu.
    3. Hệ thống cập nhật lại giao diện và thông báo "Đã gỡ tin tuyển dụng khỏi danh sách đã lưu".
  - **Luồng thay thế:**
    - *2a.* Lỗi kết nối máy chủ. Hệ thống thông báo "Không thể gỡ lưu tin tuyển dụng, vui lòng thử lại sau".

## Báo cáo tin tuyển dụng

- **Tác nhân:** Ứng viên
- **Mô tả:** Cho phép ứng viên gửi báo cáo vi phạm nếu phát hiện tin tuyển dụng có nội dung lừa đảo, giả mạo hoặc vi phạm chính sách hệ thống.
- **Pre-condition:** Ứng viên đã đăng nhập và đang ở trang chi tiết tin tuyển dụng.
- **Post-condition:** Báo cáo vi phạm được lưu trữ trong hệ thống để quản trị viên tiến hành kiểm tra và xử lý.
- **Luồng sự kiện:**
  - **Luồng chính:**
    1. Ứng viên chọn "Báo cáo tin tuyển dụng".
    2. Hệ thống hiển thị form báo cáo vi phạm gồm danh sách các lý do (như: Lừa đảo/Thu phí, Thông tin sai sự thật, Ngôn ngữ không phù hợp, Khác) và ô mô tả chi tiết.
    3. Ứng viên chọn lý do vi phạm và điền thêm thông tin mô tả cụ thể.
    4. Ứng viên chọn "Gửi báo cáo".
    5. Hệ thống kiểm tra tính hợp lệ của dữ liệu báo cáo:
      - *Lý do báo cáo*: bắt buộc chọn ít nhất 1 mục.
      - *Chi tiết báo cáo*: kiểu chuỗi, không vượt quá 500 ký tự.
    6. Hệ thống lưu báo cáo vào cơ sở dữ liệu và gửi thông báo tiếp nhận tới ứng viên.
  - **Luồng thay thế:**
    - *5a.* Ứng viên chưa chọn lý do vi phạm. Hệ thống yêu cầu ứng viên chọn lý do trước khi gửi.

## Viết nhận xét cá nhân

- **Tác nhân:** Ứng viên
- **Mô tả:** Cho phép ứng viên viết các đánh giá, nhận xét trải nghiệm chi tiết về môi trường làm việc, văn hóa, chế độ đãi ngộ của công ty.
- **Pre-condition:** Ứng viên đã đăng nhập và đang ở trang Đánh giá công ty.
- **Post-condition:** Bài nhận xét được lưu trữ và hiển thị trên trang thông tin đánh giá công ty.
- **Luồng sự kiện:**
  - **Luồng chính:**
    1. Ứng viên chọn "Viết nhận xét" trên trang Đánh giá công ty.
    2. Hệ thống hiển thị form nhập thông tin gồm: Tiêu đề nhận xét, Nội dung chia sẻ trải nghiệm, Ưu điểm và Nhược điểm.
    3. Ứng viên hoàn thành các trường nội dung nhận xét.
    4. Ứng viên chọn "Gửi nhận xét".
    5. Hệ thống kiểm tra tính hợp lệ của dữ liệu nhập:
      - *Tiêu đề nhận xét*: kiểu chuỗi, bắt buộc, tối đa 100 ký tự.
      - *Nội dung chi tiết*: kiểu chuỗi, từ 50 đến 2000 ký tự.
      - *Ưu điểm / Nhược điểm*: kiểu chuỗi, tối đa 500 ký tự.
    6. Hệ thống lưu bài nhận xét vào cơ sở dữ liệu và hiển thị thông báo "Gửi nhận xét đánh giá công ty thành công".
  - **Luồng thay thế:**
    - *5a.* Nội dung nhận xét quá ngắn (dưới 50 ký tự) hoặc thiếu tiêu đề. Hệ thống hiển thị cảnh báo yêu cầu chỉnh sửa lại.

## Đánh giá bằng sao

- **Tác nhân:** Ứng viên
- **Mô tả:** Cho phép ứng viên chấm điểm xếp hạng công ty theo số lượng sao (thang điểm 1-5 sao) cho từng tiêu chí cụ thể.
- **Pre-condition:** Ứng viên đã đăng nhập và đang ở trang Đánh giá công ty.
- **Post-condition:** Điểm đánh giá bằng sao được lưu lại và tính hợp nhất vào điểm trung bình tổng thể của công ty.
- **Luồng sự kiện:**
  - **Luồng chính:**
    1. Tại trang Đánh giá công ty, ứng viên lựa chọn số sao (từ 1 đến 5 sao) cho từng tiêu chí (ví dụ: Môi trường, Lương thưởng, Cơ hội thăng tiến, Ban quản lý).
    2. Hệ thống ghi nhận số sao được chọn và cập nhật hiển thị trực quan.
    3. Ứng viên chọn "Lưu đánh giá".
    4. Hệ thống kiểm tra đảm bảo ứng viên đã chấm điểm cho các tiêu chí bắt buộc.
    5. Hệ thống lưu kết quả chấm điểm vào cơ sở dữ liệu và tính toán lại điểm rating tổng thể của công ty.
    6. Hệ thống hiển thị thông báo "Cảm ơn bạn đã chấm điểm đánh giá công ty".
  - **Luồng thay thế:**
    - *4a.* Ứng viên chưa chọn số sao cho tiêu chí bắt buộc. Hệ thống thông báo yêu cầu ứng viên hoàn tất đánh giá bằng sao.

## Hỏi đáp luật Lao động với AI

- **Tác nhân:** Ứng viên, nhân viên tuyển dụng
- **Mô tả:** Cho phép người dùng đặt câu hỏi về pháp luật lao động và nhận câu trả lời từ module AI của hệ thống. Module AI chỉ trả lời dựa trên kho văn bản luật từ trang Pháp luật Chính phủ.
- **Pre-condition:** Tác nhân đã đăng nhập và đang ở trang "Hỏi đáp luật Lao động".
- **Post-condition:** Câu trả lời kèm trích dẫn được hiển thị trong khung hội thoại.
- **Luồng sự kiện:**
  - **Luồng chính:**
    1. Người dùng nhập câu hỏi và nhấn "Gửi". (Hệ thống đã hiển thị sẵn khuyến cáo: nội dung chỉ mang tính tham khảo, không nhập thông tin nhạy cảm.)
    2. Hệ thống kiểm tra câu hỏi hợp lệ (không rỗng, tối đa 1000 ký tự) và chuyển cho module AI.
    3. Module AI xác định câu hỏi thuộc phạm vi luật lao động.
    4. Module AI truy xuất các điều khoản còn hiệu lực liên quan từ kho văn bản luật.
    5. Module AI soạn câu trả lời chỉ dựa trên các điều khoản đã truy xuất, kèm trích dẫn (văn bản, điều, khoản).
    6. Hệ thống hiển thị câu trả lời, trích dẫn và khuyến cáo; người dùng có thể hỏi tiếp.
  - **Luồng thay thế:**
    - *2a.* Câu hỏi rỗng hoặc quá dài. Hệ thống báo lỗi, yêu cầu nhập lại.
    - *3a.* Câu hỏi ngoài phạm vi luật lao động. Module AI từ chối và gợi ý câu hỏi phù hợp.
    - *4a.* Không tìm thấy điều khoản phù hợp. Module AI không tự suy đoán, hệ thống thông báo chưa có căn cứ và gợi ý diễn đạt lại.
    - *5a.* Module AI lỗi hoặc quá thời gian. Hệ thống thông báo lỗi, giữ nguyên câu hỏi để gửi lại.

## Xem nguồn tin luật lao động

- **Tác nhân:** Ứng viên
- **Mô tả:** Cho phép ứng viên mở xem chi tiết nguồn trích dẫn văn bản pháp luật được tích hợp kèm theo câu trả lời của AI trong trò chuyện.
- **Pre-condition:** Ứng viên đang ở giao diện Hỏi đáp luật Lao Động với AI và câu phản hồi của AI có kèm đường dẫn trích dẫn luật.
- **Post-condition:** Thông tin trích dẫn chi tiết về điều luật/văn bản pháp luật tương ứng được hiển thị cho ứng viên.
- **Luồng sự kiện:**
  - **Luồng chính:**
    1. Trong đoạn phản hồi từ AI tại use case *Hỏi đáp luật Lao Động với AI*, ứng viên chọn vào đường dẫn trích dẫn nguồn luật (ví dụ: "Điều 21 - Báo trước khi đơn phương chấm dứt HĐLĐ").
    2. Hệ thống hiển thị khung/modal chứa nội dung chi tiết điều luật trích dẫn từ cơ sở dữ liệu văn bản pháp luật.
    3. Ứng viên đọc nội dung tham chiếu và nhấn "Đóng" khi hoàn thành.
  - **Luồng thay thế:**
    - *2a.* Liên kết nguồn luật bị lỗi hoặc nội dung không tồn tại. Hệ thống thông báo "Không thể tải nội dung trích dẫn nguồn luật vào lúc này".

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
| **EC-CAN-CV-01** | **Giả mạo định dạng file CV** | Ứng viên đổi tên file thực thi (`.exe`, `.bat`, `.sh`) thành `.pdf`, `.doc` hoặc `.docx` rồi tải lên.<br>$\rightarrow$ **Xử lý:** Hệ thống không chỉ kiểm tra phần mở rộng file mà còn kiểm tra kiểu MIME thực tế phải tương ứng với định dạng PDF, DOC hoặc DOCX (dung lượng tối đa 10 MB). Nếu sai, từ chối tải lên, báo lỗi `INVALID_CV_FILE_FORMAT` (HTTP 400) và yêu cầu ứng viên chọn tệp khác (luồng thay thế 4a). |
| **EC-CAN-CV-02** | **Gián đoạn kết nối Cloudinary** | Dịch vụ lưu trữ Cloudinary gặp sự cố hoặc timeout khi đang tải tệp hồ sơ lên.<br>$\rightarrow$ **Xử lý:** Hệ thống không ghi nhận thông tin tệp vào cơ sở dữ liệu, thông báo nguyên nhân lỗi thân thiện và gợi ý ứng viên thử lại (luồng thay thế 6a). |
| **EC-CAN-CV-03** | **Vượt quá số lượng CV cho phép** | Ứng viên tải lên vượt quá số lượng CV tối đa cho phép (ví dụ: tối đa 5 CV/ứng viên).<br>$\rightarrow$ **Xử lý:** Hệ thống kiểm tra số lượng CV hiện tại trước khi nhận file. Nếu đã đạt giới hạn, chặn thao tác và yêu cầu ứng viên xóa bớt CV cũ trước khi tải CV mới. |
| **EC-CAN-CV-04** | **Xóa CV đã dùng để ứng tuyển** | Ứng viên xóa một tệp hồ sơ xin việc đã được dùng để gửi đơn ứng tuyển trước đó.<br>$\rightarrow$ **Xử lý:** Khi nộp đơn, tệp hồ sơ được sao chép thành bản lưu riêng gắn với đơn ứng tuyển (Immutable Snapshot). Hộp thoại xác nhận xóa nêu rõ các đơn đã nộp vẫn giữ bản hồ sơ đã gửi. Thao tác xóa chỉ xóa tệp theo `publicId` trên Cloudinary và bản ghi trong danh sách hồ sơ của ứng viên, không ảnh hưởng đến dữ liệu hiển thị của nhà tuyển dụng. Nếu xóa trên Cloudinary thất bại, hệ thống giữ nguyên bản ghi và báo lỗi "Không thể xóa tệp, vui lòng thử lại sau" (luồng thay thế 4a). |
| **EC-CAN-CV-05** | **Gửi request upload trùng lặp (Double Click)** | Ứng viên nhấn nút "Tải lên" nhiều lần liên tiếp do mạng chậm.<br>$\rightarrow$ **Xử lý:** Client disable nút bấm ngay khi submit; backend sử dụng token idempotency / rate limiter để loại bỏ các request trùng lặp trong thời gian ngắn. |

---

### 5.2 Tìm kiếm & Khám phá việc làm (`UC-CAN-07` - `UC-CAN-10`)

| Mã Edge Case | Tên trường hợp biên | Mô tả chi tiết & Cách xử lý hệ thống |
|---|---|---|
| **EC-CAN-JOB-01** | **Xung đột thời gian hết hạn công việc** | Tin tuyển dụng hết hạn, bị ẩn hoặc bị xóa ngay trong khoảng thời gian giữa lúc ứng viên xem danh sách và nhấn xem chi tiết.<br>$\rightarrow$ **Xử lý:** Khi tác nhân chọn xem chi tiết, hệ thống kiểm tra trạng thái tin. Nếu tin không còn khả dụng, hiển thị thông báo *"Tin tuyển dụng này không còn tồn tại hoặc đã hết hạn nộp hồ sơ"* kèm gợi ý các công việc tương tự (luồng thay thế 2a). |
| **EC-CAN-JOB-02** | **Ký tự đặc biệt & SQL/RSQL Injection** | Tác nhân nhập các chuỗi ký tự tấn công (như `' OR '1'='1`, `<script>`) vào ô tìm kiếm từ khóa.<br>$\rightarrow$ **Xử lý:** Hệ thống kiểm tra tính hợp lệ của tiêu chí tìm kiếm (bước 3), sanitize toàn bộ input, sử dụng Prepared Statements / Parameterized Queries khi truy vấn cơ sở dữ liệu và escape các ký tự đặc biệt. |
| **EC-CAN-JOB-03** | **Phân trang với offset quá lớn** | Client truyền tham số phân trang bất thường như `page=999999`.<br>$\rightarrow$ **Xử lý:** Giới hạn tham số `page` và `size` tối đa (vd: max `pageSize=100`, max `totalPages=500`). Nếu vượt quá, tự động trả về trang cuối cùng hợp lệ hoặc lỗi `400 Bad Request`. |
| **EC-CAN-JOB-04** | **Bộ lọc giá trị vô lý** | Ứng viên chọn mức lương tối thiểu cao hơn mức lương tối đa (`salary_min = 5000` > `salary_max = 2000`).<br>$\rightarrow$ **Xử lý:** Frontend tự động điều chỉnh khoảng slider hợp lệ; backend tự chuẩn hóaswap 2 giá trị hoặc phản hồi lỗi `400 Invalid Filter Criteria`. |

---

### 5.3 Tương tác & Ứng tuyển (`UC-CAN-11` - `UC-CAN-16`, `UC-CAN-19`, `UC-CAN-20`)

| Mã Edge Case | Tên trường hợp biên | Mô tả chi tiết & Cách xử lý hệ thống                                                                                                                                                                                                                                                                                                                                                                                              |
|---|---|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **EC-CAN-APP-01** | **Ứng tuyển đồng thời từ nhiều thiết bị (Race Condition)** | Ứng viên mở 2 tab/thiết bị và nhấn "Gửi đơn ứng tuyển" cho cùng 1 tin tuyển dụng tại cùng một thời điểm.<br>$\rightarrow$ **Xử lý:** Ràng buộc duy nhất `UNIQUE(candidate_id, job_id)` ở tầng Database catch ngoại lệ duplicate key, trả về `409 Conflict` kèm thông báo *"Bạn đã nộp hồ sơ cho vị trí này"* và ngăn việc gửi lại đơn (luồng thay thế 8a). |
| **EC-CAN-APP-02** | **CV bị xóa ngay trước khi nhấn Gửi hồ sơ** | Ứng viên mở form ứng tuyển, xóa tệp hồ sơ ở tab khác, rồi quay lại nhấn "Gửi đơn ứng tuyển".<br>$\rightarrow$ **Xử lý:** Backend kiểm tra sự tồn tại và quyền sở hữu đối với `cv_id` ngay trong transaction nộp đơn. Nếu không còn tệp hợp lệ, trả về lỗi `404 CV_NOT_FOUND`, báo lỗi tại khu vực hồ sơ đính kèm và yêu cầu chọn tệp khác (luồng thay thế 7a). |
| **EC-CAN-APP-03** | **Lỗi Broker Kafka khi gửi sự kiện sau khi lưu DB** | Hồ sơ nộp đã được lưu thành công vào cơ sở dữ liệu PostgreSQL nhưng tiến trình gửi message sang Kafka `guardianwork.application.submitted` bị lỗi (Kafka down/network drop).<br>$\rightarrow$ **Xử lý:** Áp dụng mô hình Transactional Outbox Pattern. Message được ghi vào bảng `outbox_events` trong cùng DB transaction, một Worker background riêng sẽ quét và retry đảm bảo tính nhất quán cuối cùng (Eventual Consistency). |
| **EC-CAN-APP-04** | **Tài khoản bị khóa trong lúc nộp hồ sơ** | Tài khoản ứng viên bị Staff khóa/đình chỉ (`status = 'BANNED'`) trong khi ứng viên đang soạn thư giới thiệu.<br>$\rightarrow$ **Xử lý:** Spring Security Interceptor kiểm tra trạng thái tài khoản active trên JWT authentication filter ở mọi protected request, từ chối giao dịch với lỗi `403 Forbidden`.                                                                                                                      |
| **EC-CAN-APP-05** | **Xem tin đã lưu nhưng tin bị nhà tuyển dụng xóa hoàn toàn** | Tin tuyển dụng nằm trong danh sách đã lưu nhưng sau đó bị xóa hẳn (Hard Delete) khỏi database.<br>$\rightarrow$ **Xử lý:** Danh sách tin đã lưu sử dụng `LEFT JOIN`. Nếu bài đăng không tồn tại, hiển thị item dạng Disabled với nhãn *"Công việc không còn tồn tại"* kèm nút *"Bỏ lưu"* (UC-CAN-19). |

---

### 5.4 Đánh giá & Tiện ích (`UC-CAN-17`, `UC-CAN-18`, `UC-CAN-21`, `UC-CAN-22`)

| Mã Edge Case | Tên trường hợp biên | Mô tả chi tiết & Cách xử lý hệ thống |
|---|---|---|
| **EC-CAN-REV-01** | **Đánh giá trùng lặp liên tục cho một công ty** | Ứng viên viết nhiều bài đánh giá tiêu cực / spam cho cùng 1 công ty.<br>$\rightarrow$ **Xử lý:** Giới hạn mỗi ứng viên chỉ được gửi tối đa 1 bài đánh giá cho 1 công ty trong vòng 6 tháng. Bài mới gửi sẽ đè bài cũ hoặc yêu cầu chỉnh sửa bài cũ. |
| **EC-CAN-NOTIF-01** | **Thông báo nhận sai thứ tự do trễ mạng** | Trạng thái hồ sơ chuyển đổi nhanh (Đã nộp $\rightarrow$ Đã xem $\rightarrow$ Từ chối), thông báo gửi về bị sai thứ tự thời gian trên giao diện.<br>$\rightarrow$ **Xử lý:** Thông báo đính kèm timestamp chính xác của sự kiện và `sequence_number`. Frontend sắp xếp danh sách thông báo theo thứ tự thời gian phát sinh thay vì thời gian nhận client. |
| **EC-CAN-LAW-01** | **Điều luật bị sửa đổi / hết hiệu lực sau khi AI trả lời** | Ứng viên mở lại trích dẫn trong lịch sử hội thoại, nhưng điều luật đã bị thay thế hoặc hết hiệu lực sau thời điểm AI trả lời.<br>$\rightarrow$ **Xử lý:** Khung trích dẫn hiển thị nhãn tình trạng hiệu lực ("Đã hết hiệu lực" / "Đã được thay thế") kèm liên kết sang văn bản thay thế (nếu có). Khi trả lời mới, module AI chỉ truy xuất các điều khoản còn hiệu lực (UC-CAN-23, bước 4). |
| **EC-CAN-LAW-02** | **Trích dẫn không xác minh được trong kho văn bản luật** | Nguồn trích dẫn AI đưa ra không khớp với văn bản, điều hoặc khoản nào trong kho văn bản luật.<br>$\rightarrow$ **Xử lý:** Hệ thống chỉ tạo đường dẫn trích dẫn khi điều luật đã được xác thực trong cơ sở dữ liệu. Nếu không xác minh được, hiển thị văn bản thường kèm cảnh báo "Chưa xác minh được nguồn", tránh dẫn ứng viên đến điều luật sai. |
| **EC-CAN-LAW-03** | **Điều luật quá dài hoặc tải chậm** | Nội dung điều luật trích dẫn rất dài hoặc phản hồi từ cơ sở dữ liệu văn bản pháp luật chậm.<br>$\rightarrow$ **Xử lý:** Khung trích dẫn hiển thị trạng thái đang tải, cho phép cuộn nội dung dài; khi quá thời gian, hiển thị thông báo "Không thể tải nội dung trích dẫn nguồn luật vào lúc này" (luồng thay thế 2a của UC-CAN-22) kèm nút thử lại. |
