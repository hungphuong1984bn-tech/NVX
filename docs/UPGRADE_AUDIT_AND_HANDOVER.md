# Nâng cấp sau 0.13.0-live — kiểm toán, thay đổi, trạng thái bằng chứng

## 1. Kiểm toán trước nâng cấp
Mốc cơ sở tái lập: type-check sạch, 485 test đạt (khớp HANDOVER 0.13).
Dự án đã có: thu thập bền vững (DurableFrameLog), Protocol Lab, bộ xác minh trường với tiêu chí khóa trước, nhập tham chiếu độc lập, AdapterRegistry rỗng (trả RAW/UNKNOWN), giao diện Telemetry/Verification, che địa chỉ khi xuất.

Khoảng trống so với yêu cầu:
| Khoảng trống | Xử lý |
|---|---|
| Không có lớp ghép khung (khung chia nhỏ / nhiều khung / byte rác) | `protocol/FrameAssembler.kt` |
| Chỉ 4 mức liên kết | `protocol/LinkStage.kt` + dòng "Mức liên kết" trong LinkMonitorModel/Panel |
| Vòng đời trường thiếu CANDIDATE, INVALID | `telemetry/FieldLifecycle.kt` (lớp nhìn, không gán tay) |
| Bộ giải mã chưa khai báo đủ nguồn/độ dài/tổng kiểm/giới hạn | `DecoderDeclaration` |
| Adapter chưa có hồ sơ tương thích | `protocol/AdapterCompatibility.kt`, `AdapterRegistry(requireProfile)` |
| Sự kiện kết nối chỉ ở RAM | `telemetry/DurableEventLog.kt` + hook trong GattSessionController |
| SessionLogBuffer loại bản ghi không đếm | thêm `droppedCount()/isTruncated()` |

## 2. File sửa/thêm
Thêm: FrameAssembler.kt, LinkStage.kt, FieldLifecycle.kt, AdapterCompatibility.kt, DurableEventLog.kt; test: FrameAssemblerTest, LinkStageAndAdapterProfileTest, DurableEventLogTest, ConnectionEventsAndLifecycleTest, LinkMonitorStageTest.
Sửa: Protocol.kt (registry), GattSessionController.kt (eventSink tùy chọn), BleVehicleConnection.kt, NvxApp.kt (registry nghiêm ngặt, eventLog), SessionLog.kt, LinkMonitorModel.kt, LinkMonitorPanel.kt, BleViewModel.kt.

## 3. Build/test thực tế
`tools/verify-jvm.sh` (kotlinc 2.0.21 + android.jar API 34 + JUnit 4.13.2): type-check lớp Android sạch; **545 test, 0 lỗi**.
Kiểm đột biến (6 lần, trên bản sao): cả 6 đột biến (mảnh quá hạn, adapter không cần trường VERIFIED, bỏ yêu cầu subscription, INVALID không bao giờ gán, xoay vòng không đếm mất, sự kiện first-frame lặp) đều bị test bắt.
KHÔNG được chạy: Gradle, Compose, Room, Android SDK thật, điện thoại, xe thật.

## 4. Chạy khảo sát / xuất log
Dùng quy trình có sẵn (README, docs/SAMPLE_LOG_GUIDE.md). Nhật ký sự kiện nằm ở `filesDir/events/ev-*.nvxev`; `NvxApp.eventLog.exportJsonLines(now)` xuất JSONL có dòng meta (số sự kiện, bộ đếm mất). Chưa có nút xuất trên giao diện.

## 5. Trạng thái trường
Không trường nào VERIFIED. RPM, điện áp: giả thuyết từ log trước; nhiệt độ, tốc độ, điện áp sạc, trạng thái động cơ, DTC: UNAVAILABLE. Không có bằng chứng mới trong đợt này.

## 6. Chưa giải quyết
- FrameAssembler đã nối vào CapturePipeline ở chế độ quan sát song song (`framingStats()`, không đổi payload thô); chưa hiển thị trên UI. Vòng đời trường đã hiển thị ở tab Xác minh (`BleViewModel.lifecycleOf` → `VerificationTab.FieldCard`; phần ViewModel đã type-check, phần Compose CHƯA biên dịch được ở đây). DecoderDeclaration và thống kê khung chưa có màn hình.
- Nhật ký sự kiện đã flush khi VehicleConnectionService dừng (type-check đạt); đã có nút "Xuất sự kiện" ở màn hình khảo sát (ViewModel type-check đạt; nút Compose chưa biên dịch).
- Chưa biên dịch Compose/Gradle; chưa kiểm thử Android và xe thật (giai đoạn 4–7).
- Cần: log thật theo trạng thái khóa điện, tham chiếu độc lập (≥2 phiên, nhiều mức vòng tua), MTU thấp để kiểm tra khung chia mảnh.
