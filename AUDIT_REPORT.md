# AUDIT_REPORT — NVX Connect 0.8.0 → 0.9.0 (2026-10-24)

Phạm vi: đọc toàn bộ mã nguồn trong `NVX-main-0_8_0-ycc-calibration.zip` (182 tệp, ~10,8 nghìn dòng Kotlin) trước khi sửa.
Baseline đo thật: type-check Android sạch, **233 test JVM đạt**.

## 1. Bức tranh hiện trạng
| Hạng mục đề bài | Hiện trạng thực tế trước khi sửa |
|---|---|
| BleScanner, GATT session, GattOpQueue | Có, có test với `GattPort` giả: máy trạng thái, timeout 15 s, retry có giới hạn, watchdog thao tác, dừng khi cần xác thực. **Không có lỗi biên dịch/rò rỉ nào phát hiện được** ở phần này; đóng `BluetoothGatt` đúng theo test. |
| Tên trong đề bài khác tên thật | Không có `ProtocolAnalysisScreen`-riêng cho "Bluetooth Diagnostics": chức năng nằm ở `ConnectScreen` + `AutoConnectScreen` + `ProtocolAnalysisScreen`; không có `SurveyScreen.kt`. Giữ cấu trúc, không tạo dự án mới. |
| VehicleTelemetry / VehicleProtocolAdapter / VehicleCapabilityRegistry | Đã có (`protocol/`, `capability/`). Thiếu: trạng thái VERIFIED/UNVERIFIED/STALE/UNAVAILABLE/UNSUPPORTED theo từng giá trị, kiểm tra khoảng hợp lệ, nhật ký nguồn. |
| VehicleDataRepository | **Chưa có** → đã thêm. |
| TFT/Y-Connect | `TftNavigationBridge` luôn UNSUPPORTED, `NavAdapterRegistry` đòi bằng chứng. Thiếu: phân tách 6 mức, và phân biệt "đã gửi" với "đã hiển thị". |
| Trung tâm hướng dẫn | **Chưa có** → đã thêm. |
| GPS/hành trình/bảo dưỡng | Đã có (TripEngine, Room, bảo dưỡng theo km/ngày do người dùng nhập, nhãn nguồn). Không sửa. |
| Dữ liệu mô phỏng | DEMO chỉ khi người dùng bật, gắn nhãn, tách khỏi số thật. Không dùng để báo "đã kết nối". |

## 2. Phát hiện
| # | Mức | Phát hiện | Xử lý |
|---|---|---|---|
| A1 | Trung bình | `BleViewModel` đặt `valueSeen = true` ngay khi `decoded` có một `DecodedItem.Value`, **trước** khi kiểm tra liên kết đã xác minh/nguồn LIVE_VERIFIED. Cờ này quyết định `AutoState.DATA_AVAILABLE` ("CÓ DỮ LIỆU XE"). Hiện vô hại vì chưa có adapter, nhưng sẽ báo "có dữ liệu" sai khi có adapter mà giá trị bị hub loại. | Sửa: chỉ đặt khi `VehicleDataRepository.ingest(...) > 0`. |
| A2 | Thấp | Màn hình TFT hiển thị chip "Đã gửi khung gần nhất" tông xanh khi `Outcome.Sent` — dễ hiểu là đã hiển thị. | Đổi thành "Đã gửi (chưa chắc đã hiển thị)" tông cảnh báo; thêm `displayConfirmed()` (mặc định false) và 6 mức tích hợp. |
| A3 | Thấp | "Xóa toàn bộ dữ liệu" không phủ dữ liệu mới (tiến độ hướng dẫn, nhật ký nguồn). | Đã nối `helpProgress.reset()` và `vehicleData.clear()`. |
| A4 | Thông tin | Không có điểm vào hướng dẫn nào; lỗi hiển thị nhưng không dẫn tới cách khắc phục. | Thêm HelpCenter + nút theo ngữ cảnh. |
| A5 | Thông tin | Toàn bộ Compose, osmdroid, Room/KSP, DataStore thật, gộp Manifest **chưa từng được trình biên dịch kiểm** (không có Maven/Gradle trong môi trường). | Không giải quyết được ở đây; xem mục build. |

## 3. Không tái cấu trúc hàng loạt
Đề bài gợi ý chia module `connection/diagnostics/vehicledata/navigation/tftintegration/helpcenter/maintenance`. Di chuyển ~100 tệp đang có test mà không có Gradle để kiểm lại là rủi ro vỡ build không phát hiện được, nên: **thêm gói mới** `helpcenter`, `vehicledata`, `tftintegration`, `diagnostics`; `bluetooth/`+`protocol/` giữ vai trò "connection", `location/`+`navigation/` giữ vai trò navigation, bảo dưỡng giữ trong `data/`+`ui/VehicleScreen`. Không có gói `maintenance` riêng (chưa có logic mới để tách).
