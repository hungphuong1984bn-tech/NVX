# FEATURE_STATUS (cập nhật 2026-10-23, bản 0.6.0-capabilities)

> Nguồn sự thật chi tiết từng chức năng app là `capability/AppCapabilities.kt` (xem trong app: Phân tích giao thức → Danh mục chức năng → App). Bảng dưới là bản rút gọn lịch sử; số test hiện tại là 207.

Cột "Test JVM" = có unit test chạy được trên JVM (đã chạy, 103 test đạt). "Build APK" = đã chạy `assembleDebug`: **chưa mục nào** (môi trường
soạn mã không có Android SDK/Maven). "Thiết bị" = đã thử trên điện thoại/xe thật: **chưa mục nào**.

| Chức năng | Viết mã | Test JVM | Build APK | Thiết bị | Ghi chú |
|---|---|---|---|---|---|
| Quét BLE (tên, RSSI, connectable, UUID, mã hãng, thiết bị không tên) | có | một phần (bảng/lọc) | chưa | chưa | Scanner dùng API Android, chưa chạy thật |
| Gợi ý loại địa chỉ (công khai / có thể riêng tư) | có | có | chưa | chưa | Chỉ là gợi ý từ 2 bit cao; Android không cho biết chắc |
| Tìm kiếm + lọc (đã lưu, kết nối được, có tên, ẩn cũ) | có | có | chưa | chưa | |
| Thiết bị đã lưu + nhận lại theo tên khi địa chỉ xoay | có | có | chưa | chưa | |
| Máy trạng thái GATT: CONNECTING→CONNECTED→DISCOVERING_SERVICES→READY, timeout, kết nối lại có giới hạn, BT tắt, thiếu quyền, auth | có | có (fake port) | chưa | chưa | Hành vi với stack BLE thật chưa kiểm chứng |
| Một phiên GATT / thiết bị, đóng BluetoothGatt đúng | có | một phần | chưa | chưa | |
| GATT explorer: service/characteristic/descriptor + thuộc tính | có | không | chưa | chưa | |
| Đăng ký notify/indicate + xác nhận qua ghi CCCD | có | có (fake port) | chưa | chưa | |
| Đọc thông tin chuẩn (tên, hãng, firmware) theo danh sách cho phép | có | có | chưa | chưa | Không đọc serial/ID |
| Log HEX có che, xuất JSON/CSV | có | có | chưa | chưa | Chia sẻ qua FileProvider |
| Trạng thái "CONNECTED — RAW DATA ONLY" / "VEHICLE PROTOCOL NOT VERIFIED" | có | có | chưa | chưa | |
| Adapter giao thức Yamaha | **không có** | – | – | – | Chưa có bằng chứng giao thức |
| Dữ liệu xe (RPM, nhiệt độ, điện áp, DTC) | **không có** | – | – | – | Hiển thị "Chưa có dữ liệu" |
| Hiển thị chỉ dẫn lên TFT xe | **không hỗ trợ** | có (từ chối) | – | – | `docs/TFT_NAVIGATION.md` |
| Bản đồ OSM + vị trí + hướng | có | không | chưa | chưa | osmdroid |
| Tìm địa điểm (Nominatim) / nhập tọa độ | có | có (parser, client giả) | chưa | chưa | 1 yêu cầu/giây, có cache |
| Định tuyến (OSRM), khoảng cách, thời gian ước tính, các bước | có | có | chưa | chưa | Hồ sơ ô tô, máy chủ demo |
| Theo dõi tuyến, nhắc rẽ, lệch tuyến, tính lại, đến nơi | có | có | chưa | chưa | |
| Giọng nói tiếng Việt (TTS) | có | không | chưa | chưa | Cần giọng vi-VN trên máy |
| Bắt đầu / tạm dừng / tiếp tục / kết thúc hành trình | có | có | chưa | chưa | |
| Lưu hành trình theo đợt, khôi phục khi tiến trình bị tắt, xem lại | có | một phần | chưa | chưa | Không đổi schema Room |
| Dịch vụ foreground (location) | có | không | chưa | chưa | Android 14: cần quyền trước |
| Xử lý offline / lỗi tile / giới hạn API | có | có (client) | chưa | chưa | Tile lỗi chỉ báo qua trạng thái mạng |
| Hồ sơ xe do người dùng nhập | có | có | chưa | chưa | |
| Nhiên liệu (L/100km đổ đầy→đổ đầy) | có | có | chưa | chưa | |
| Bảo dưỡng (còn hạn / sắp / quá hạn / hoàn thành) | có | có | chưa | chưa | Chu kỳ gợi ý KHÔNG phải của Yamaha |
| Cài đặt, xuất/xóa dữ liệu, DEMO riêng | có | một phần | chưa | chưa | |

## Chưa làm (có chủ đích hoặc ngoài phạm vi)
Nhắc bảo dưỡng bằng thông báo hệ thống, tải bản đồ offline theo vùng, xoay bản đồ theo hướng đi, dẫn đường theo làn, ghi BLE khi app ở nền
(dịch vụ nền chỉ giữ GPS), chọn điểm đến bằng chạm giữ trên bản đồ, xuất GPX.

## Bổ sung 0.6.0
| Chức năng | Viết mã | Test JVM | Build APK | Thiết bị | Ghi chú |
|---|---|---|---|---|---|
| Vehicle Capability Registry + đánh giá theo bằng chứng | có | có | chưa | chưa | Mặc định UNKNOWN |
| App Capability Registry | có | có (test bảo vệ: id, tham chiếu test thật, không ai khai ON_DEVICE) | chưa | chưa | |
| Capability Matching Engine + xuất text/JSON/CSV | có | có | chưa | chưa | |
| Hồ sơ giao thức tham chiếu (nguồn/license/mẫu xe) | có | có | chưa | chưa | Không hồ sơ nào VERIFIED cho NVX |
| Màn hình Danh mục chức năng (Compose) | có | không | chưa | chưa | Chưa qua trình biên dịch Compose |
| ByteViews (endian/binary/bit) | có | có | chưa | chưa | Chỉ là phép biến đổi, không giải mã |
| Trạng thái BONDED / CONNECTION_BUSY / AUTHENTICATED đúng nghĩa | có | có | chưa | chưa | |
| Nhãn ENCRYPTED_OR_AUTHENTICATED | có | có | chưa | chưa | |
| Cảnh báo mất kết nối cho người lái | **không có** | – | – | – | Khoảng trống được danh mục ghi nhận |
| Dữ liệu xe chuyên sâu (logger, xuất CSV/JSON/TXT/HTML, phục hồi) | có | có (23 test) | chưa | chưa | Chưa xác minh trên xe thật; UI Compose chưa biên dịch; tham số xe "Chưa hỗ trợ" tới khi có adapter có bằng chứng |
