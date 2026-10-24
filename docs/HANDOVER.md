# BÀN GIAO NVX Connect 0.6.0-capabilities (2026-10-23)

Đầu vào: `nvx-connect-v5_3-live-data-ready.zip` (đã có tự khảo sát, GATT, phân tích giao thức, bản đồ). Đã đọc mã và sửa trực tiếp.
Đề bài nhắc `SurveyScreen.kt`/`AppViewModel.kt` là nơi khảo sát: thực tế khảo sát nằm ở `ConnectScreen`/`AutoConnectScreen` + `BleViewModel`; `SurveyScreen.kt` không tồn tại.

## 1. Kết luận trung thực
| | Trạng thái |
|---|---|
| Gradle `assembleDebug`/`testDebugUnitTest` | **CHƯA chạy** (không truy cập được Maven/Google). Xem `BUILD_LOG.md` |
| Logic + lớp Android không-Compose | Type-check sạch với android.jar API 34; **207 test JVM đạt** (baseline 182) |
| Compose (kể cả màn hình mới), osmdroid, Room/KSP, Manifest | **Chưa được trình biên dịch kiểm** — có thể còn lỗi biên dịch nhỏ |
| Điện thoại thật / NVX 155 V3 SP thật | **CHƯA thử** |
| Giao thức Yamaha, dữ liệu xe, TFT | **CHƯA tìm ra / CHƯA hỗ trợ**. Không có adapter. Không gửi gì lên xe |

Việc đầu tiên nên làm: build trên GitHub Actions/Android Studio và gửi log nếu có lỗi.

## 2. Thư viện (chi tiết `LIBRARY_EVALUATION.md`)
Đã đánh giá Nordic Android-BLE-Library (BSD-3, 2.11.0) và Kotlin-BLE-Library (1.0.x beta; v2 alpha). **Không tích hợp**: lớp BLE hiện tại có test và không có đường ghi; không build được phụ thuộc mới ở đây; chưa có lỗi thật trên máy để biện minh. Kế hoạch thay thế sau `GattPort` nếu gặp lỗi stack thật.

## 3. Hồ sơ giao thức tìm được (chi tiết `PROTOCOL_RESEARCH.md`, `capability/ProtocolProfiles.kt`)
Pillion/NaviLite (PolyForm NC, CCU Garmin, **RFCOMM** chứ không phải BLE, MT/R9/XSR/XMAX), YammiC (GPL-3.0, BLE, CAN), tài liệu Y-Connect của Yamaha. **Không nguồn nào nêu NVX → tương thích = chưa biết.** Không sao chép mã (license), không phát lại gói.

## 4. Service/characteristic thực sự quan sát được
Chưa có trong lần làm này (không có thiết bị). Chỉ có ghi chú cũ từ log người dùng gửi trước đó: thiết bị "YCC" với khung kiểu UART của TI và UUID riêng (CHANGELOG 0.4.1) — chưa gán nhãn, chưa có gói notify.

## 5. Chức năng xe (11 nhóm)
Tất cả **UNKNOWN**, trừ "Dữ liệu điều hướng" và "Điều khiển TFT" = **UNSUPPORTED** (cầu nối TFT báo không hỗ trợ). Không có AVAILABLE/OBSERVED/VERIFIED. HYPOTHESIS (tốc độ) chỉ xuất hiện nếu chạy khảo sát có GPS và tìm được trường khớp.

## 6. Chức năng app
Danh mục 27 mục trong `AppCapabilities.kt`. Khoảng trống phát hiện: **không có cảnh báo mất kết nối** cho người lái; chưa hiển thị quãng đường/khóa điện/bảo dưỡng do xe báo; chưa gửi TFT; log chỉ trong bộ nhớ phiên. Chưa mục nào được thử trên máy thật.

## 7. Giới hạn
- Y-Connect giữ kết nối độc quyền → app báo `CONNECTION_BUSY`, không chiếm.
- Có thể cần passkey/xác thực do Yamaha cấp; app không đoán PIN, không vượt xác thực.
- Kênh TFT có thể là Bluetooth Classic (nếu cùng nền tảng Garmin) và sẽ không hiện ở khảo sát BLE — chưa biết.
- Đổi hành vi có chủ đích: BONDED ≠ AUTHENTICATED; nhãn mới ENCRYPTED_OR_AUTHENTICATED; `UNKNOWN` giữ tên, tương ứng "UNSUPPORTED" của đặc tả.

## 8. File đã sửa/thêm
Mới: `capability/{VehicleCapabilities,AppCapabilities,CapabilityMatcher,ProtocolProfiles}.kt`, `protocol/ByteViews.kt`, `ui/CapabilityScreen.kt`, test `CapabilityTest`, `ByteViewsTest`, `docs/LIBRARY_EVALUATION.md`.
Sửa: `protocol/Protocol.kt`, `auto/{AutoState,ProtocolReport}.kt`, `ui/{BleViewModel,MainActivity,ProtocolAnalysisScreen,AutoConnectScreen,Cockpit}.kt`, `AppInfo.kt`, `app/build.gradle.kts`, `tools/verify-jvm.sh`, test `AuthAndStateTest`, `ProtocolReportTest`, tài liệu.

## 9. Chạy khảo sát thực tế
1. Cài bản build; cấp quyền Bluetooth/Vị trí. 2. Kết nối → Tự tìm xe: quét lúc xe TẮT rồi BẬT, xem xếp hạng (không mức nào = "đã xác nhận Yamaha"). 3. Chỉ khi bạn đồng ý: "Kết nối thử" → GATT Explorer; ghi Y-Connect có đang chạy không.
4. Nghe notify ở characteristic có NOTIFY; đánh dấu hành động; chạy có GPS để đối chiếu tốc độ. 5. Phân tích giao thức → Danh mục chức năng → xuất JSON/CSV/Báo cáo. 6. Điền `TEST_CHECKLIST.md`. Không thử lệnh điều khiển.
