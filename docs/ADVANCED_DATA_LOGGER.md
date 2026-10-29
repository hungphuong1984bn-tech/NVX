# Dữ liệu xe chuyên sâu (Advanced Vehicle Data Logger) — hướng dẫn kỹ thuật viên

Trạng thái: **Chưa xác minh trên xe thật.** Gradle/Compose chưa được chạy trong môi trường phát triển (không có Android SDK); logic thuần đã có 23 test JVM (tổng 568 test JVM đều qua).

## 1. Mục đích và nguyên tắc
- **Chỉ đọc.** Màn hình và logger không gửi lệnh nào tới xe/ECU, không ghi vào `6e400002`, không đổi xác thực/mã hóa BLE. Không đoán UUID/PID/ý nghĩa byte.
- **Không tải dữ liệu lên máy chủ.** Xuất = tạo tệp trong cache rồi mở khung chia sẻ Android; người dùng chọn nơi nhận.
- **Không bịa dữ liệu.** Không nội suy, không điền khoảng trống. Mỗi số liệu có nhãn:
  - **Thực**: giá trị đọc được từ nguồn (xe, BLE của điện thoại, GPS).
  - **Suy tính**: tính từ số thực (vd quãng đường GPS cộng dồn, tốc độ nhận gói).
  - **Chưa hỗ trợ**: giao thức hiện tại không có trường này (dòng điện, SOC, trạng thái bộ điều khiển luôn là "Chưa hỗ trợ" cho đến khi có adapter được xác minh).
  - **Không hợp lệ**: có nhưng quá hạn/ngoài khoảng; hiển thị mờ, KHÔNG ghi như mẫu.
  - **Chưa có dữ liệu**.
- Thanh "Chưa xác minh trên xe thật" luôn hiện (màn hình và mọi báo cáo) trừ khi có adapter với bằng chứng xe thật (đã kiểm chứng, có ghi xe đã thử và kết quả).

## 2. Cách dùng
1. Tab **Dữ liệu xe**. Kết nối xe ở tab Kết nối xe nếu cần. Lịch sử, xem phiên và xuất ZIP nằm ở tab **Nhật ký kỹ thuật**.
2. Tab **Trực tiếp**: xem trạng thái BLE (RSSI, MTU, số gói, tốc độ nhận, thời gian từ gói gần nhất, gói mất), các tham số theo nhóm, tuổi dữ liệu và giờ cập nhật.
3. **Bắt đầu ghi log** (đặt nhãn + chu kỳ 0,5/1/2/5 giây) → **Tạm dừng / Tiếp tục / Kết thúc**. Ghi chú sự cố nhập ngay khi đang ghi (tự bỏ ký tự điều khiển, tối đa 1000 ký tự).
4. Tab **Lịch sử & xuất**: xuất CSV / JSON / TXT / HTML, tùy chọn ẩn nhận dạng, kèm ghi chú, thời gian tương đối. Xóa phiên chỉ khi bạn xác nhận.

## 3. Nội dung được ghi
Mỗi bản ghi có giờ hệ thống + giờ đơn điệu: mẫu thực (chỉ khi có mẫu mới từ xe), sự kiện BLE (đổi trạng thái, mất liên kết, nối lại, thất bại), lỗi, ghi chú, **dấu thiếu dữ liệu** (khi một tham số chuyển sang không dùng được) và các mốc phiên (START/PAUSE/RESUME/STOP/RECOVERED/CONTINUE/LIMIT). Khi tạm dừng, mẫu/sự kiện không ghi nhưng được đếm; ghi chú vẫn lưu.

## 4. Lưu trữ, giới hạn, phục hồi
- Tệp `diag-<id>.nvxdiag` (JSON Lines, mỗi dòng có CRC32, ghi nối đuôi, fsync định kỳ) nằm trong bộ nhớ riêng của app.
- **Không tự xóa log.** Giới hạn mỗi phiên 16 MB, tổng 128 MB, bộ nhớ trống tối thiểu 32 MB; vượt thì bỏ **mẫu thường** (có đếm, ghi một dấu LIMIT), vẫn dành 64 KB dự phòng cho mốc phiên/ghi chú/lỗi. Trạng thái: Giới hạn / Sắp hết bộ nhớ / Lỗi ghi.
- **Đóng đột ngột** (kill app, hết pin): lần mở sau, đuôi dòng ghi dở bị cắt, thêm bản ghi RECOVERED, phiên hiện "Bị gián đoạn", bản ghi còn nguyên vẹn được giữ. Có thể **Tiếp tục phiên**; khoảng không ghi không được lấp.
- Nút "xóa toàn bộ dữ liệu" hiện có của app **không** xóa log chẩn đoán; xóa từng phiên ở tab Lịch sử.

## 5. Báo cáo/xuất
- **CSV**: chống chèn công thức (ô bắt đầu bằng = + - @ được thoát). **JSON**: `nvx-connect-diag-log/1`. **TXT/HTML**: thống kê phiên, lỗi, mất kết nối, khoảng trống dữ liệu, ghi chú; HTML có CSP, thoát ký tự, biểu đồ SVG chỉ khi ≥5 điểm và ngắt đoạn ở khoảng trống (không nối qua chỗ thiếu).
- Mặc định **ẩn nhận dạng**: MAC, VIN, email, khóa thiết bị.

## 6. Pin / bộ nhớ / vòng đời
Vòng lấy mẫu chỉ chạy khi đang GHI; chu kỳ tối thiểu 250 ms. Không thêm foreground service mới: ghi log dùng kết nối nền hiện có (`VehicleConnectionService`); nếu app bị hệ thống dừng, tính năng phục hồi ở mục 4 áp dụng. Không thêm quyền Android.

## 7. Giới hạn đã biết
- Chưa build bằng Gradle, màn hình Compose chưa được biên dịch/chạy thật.
- Chưa thử trên xe thật; tham số xe hiện chủ yếu "Chưa hỗ trợ" tới khi có adapter có bằng chứng.
- Chưa có log hành vi trên máy thật cho trường hợp bộ nhớ đầy.

## 8. Kiểm thử
`AdvancedDiagLoggerTest` (23 test): mã hóa/CRC, vòng đời + thời gian hoạt động, mẫu mới, throttle, dấu thiếu, tạm dừng, phân loại, khoảng cách GPS, mất/nối lại kết nối, phục hồi (đuôi dòng hỏng, tiếp tục, idempotent), giới hạn/không xóa/thiếu bộ nhớ, xóa + chống path traversal, CSV/JSON/HTML/TXT, nhảy đồng hồ, xuất ra tệp, làm sạch ghi chú.
