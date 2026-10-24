# Nâng cấp kết nối Bluetooth (v0.2.0-ble, 2026-10-21)

## Quan sát từ log khảo sát đã gửi (chỉ là dữ kiện, chưa phải kết luận)
- Thiết bị tên bắt đầu "YCC" (RSSI khoảng -47 đến -49 dBm, gần nhất cùng với một thiết bị "T-R", -43 đến -45) quảng bá kết nối được, không quảng bá service UUID nào.
- GATT của thiết bị "YCC": Generic Access, Generic Attribute và service 6e400001-b5a3-f393-e0a9-e50e24dcca9e (khung Nordic UART): một characteristic ghi (6e400002, WRITE + WRITE_NO_RESP) và một characteristic notify (6e400003).
- Điều này chỉ cho biết thiết bị dùng khung serial-qua-BLE phổ biến. KHÔNG cho biết nội dung gói, có cần ghép nối hay không, hay có đúng là bộ CCU của xe hay không. Tên "YCC" chưa được xác minh thuộc xe.
- Thiết bị "T-R" (FEE0), "YXl" (FFB0), "AL_" (180A + FEF5), "VIN" (180A) và "APE" nhiều khả năng là thiết bị xung quanh; chưa có bằng chứng liên quan xe.
- Phần FRAMES trong log đang trống: chưa có gói notify nào được bắt, nên chưa thể phân tích định dạng gói.

## Cách xác định thiết bị của xe (không cần giao thức)
1. Quét khi xe tắt hẳn, xuất log. 2. Bật khóa/khởi động xe, quét lại. 3. Thiết bị chỉ xuất hiện/đổi RSSI theo trạng thái xe là ứng viên. Lặp lại ít nhất 3 lần, ghi vào TEST_LOG.csv.

## Thay đổi
- BleVehicleConnection v2: hàng đợi thao tác GATT tuần tự + watchdog, thỏa thuận MTU, RSSI định kỳ, notify/indicate, ngừng nghe, tự nghe lại sau khi nối lại, bỏ qua callback của đối tượng GATT cũ, dịch mã trạng thái GATT, quan sát (không khởi tạo) trạng thái ghép nối, nhật ký kỹ thuật.
- Xe trả lỗi xác thực/mã hóa (5/15): dừng, báo lỗi, không thử lại, không vượt qua.
- BleScanner: bộ lọc tùy chọn (địa chỉ, service, tiền tố tên), chế độ tiết kiệm, thông báo lỗi quét đã dịch (kể cả giới hạn 5 lần/30 giây).
- DeviceTable: RSSI làm mượt, gộp tên/service giữa các gói quảng bá, đánh dấu mất tín hiệu.
- KnownUuids: nhãn chung cho UUID phổ biến, chỉ để dễ đọc.
- FrameAnalyzer: thống kê thụ động trên gói notify đã bắt (độ dài, byte cố định/đổi, chu kỳ, giả thuyết checksum), che chuỗi ASCII dài. Kết quả tự gắn vào phần xuất log. Không gán nhãn RPM/nhiệt độ/mã lỗi.

## Vẫn KHÔNG làm
Không ghi vào characteristic dữ liệu (kể cả 6e400002), không tự ghép nối/xác thực thay Y-Connect, không gửi lệnh đoán mò vào xe.

## Trạng thái kiểm chứng
- Đã biên dịch và chạy 10 unit test cho phần logic thuần (GattStatus, KnownUuids, DeviceTable, GattOpQueue, FrameAnalyzer) bằng kotlinc 2.0.21 ngoài Android: PASS.
- BleVehicleConnection, BleScanner, ViewModel, SurveyScreen CHƯA build bằng Gradle/Android SDK và CHƯA thử trên điện thoại hay xe. Hãy chạy workflow GitHub Actions để biên dịch thật.

## v3 (0.3.0-nav)
Kiến trúc: `GattSessionController` (thuần Kotlin, test được) ↔ `GattPort` ↔ `AndroidGattPort` (BluetoothGatt). Trạng thái: DISCONNECTED, CONNECTING, CONNECTED,
DISCOVERING_SERVICES, READY, RECONNECTING, ERROR(kind). Thao tác GATT đi qua hàng đợi tuần tự có watchdog. `GattPort` không có hàm ghi dữ liệu.
Lỗi xác thực (status 5/15): dừng, không thử lại. Ngắt: disconnect() rồi close() khi hệ thống báo xong hoặc sau 600 ms.
