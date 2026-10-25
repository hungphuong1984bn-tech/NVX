# IMPLEMENTATION_REPORT — NVX Connect 0.10.0-telemetry-verify (2026-10-25)

## 1. Kết luận trung thực
| Hạng mục | Kết quả |
|---|---|
| Log cũ (`nvx-ble-log-20261024-071131_1.json`, 3 log 10-23) | **Không có trong zip** → không phân tích được; chỉ dùng số liệu tổng hợp trong tài liệu cũ |
| Dữ liệu tham chiếu độc lập | **Không có** |
| Trường VERIFIED / CORRELATED | **0 / 0**. RPM, điện áp ắc quy: DECODED_HYPOTHESIS; nhiệt độ, tốc độ, điện áp sạc, trạng thái: UNAVAILABLE; DTC: UNAVAILABLE (không có đường truy cập hợp lệ) |
| Build Gradle `assembleDebug`/`testDebugUnitTest` | **CHƯA chạy** (không có Android SDK/Gradle/Maven trong môi trường) |
| `tools/verify-jvm.sh` (kotlinc 2.0.21 + android.jar API 34 + JUnit 4.13.2) | Type-check sạch; **351 test đạt** (baseline 296 + 55 mới) |
| Compose (kể cả `VerificationTab.kt`, sửa `ProtocolAnalysisScreen.kt`), Room, osmdroid, Manifest | **Chưa được trình biên dịch kiểm** |
| Thiết bị thật / xe thật | **Chưa thử** |

## 2. Đã làm
- **Collector**: `telemetry/RawFrameRecorder.kt` — mỗi payload có thời gian, phiên, thiết bị ẩn danh, nguồn (ADVERTISEMENT/READ/NOTIFICATION/INDICATION), service/characteristic, RSSI, hex, cờ (EMPTY/OVERSIZE/UNIFORM/DUPLICATE); sự kiện kết nối có cấu trúc (CONNECT/DISCOVERY/MTU/SUBSCRIBE_*/TIMEOUT/RECONNECTING/ERROR…); bộ đếm theo luồng, thời điểm gói gần nhất; bộ lọc theo thiết bị/UUID/nguồn/phiên/cờ; giới hạn dung lượng giữ 200 bản ghi đầu + vòng đệm 20.000, **báo số bị cắt**; đăng ký nghe thành công không tính là có dữ liệu.
- **Xuất/nhập**: `TelemetryLogIO.kt` — JSON `nvx-connect-telemetry-log/3` (gồm thiết bị, cây GATT, sự kiện, payload, thống kê, giới hạn, ghi chú "0x6B/0x6C đang nghiên cứu") + CSV; nhập `/2` cũ và `/3`, lỗi đi vào cảnh báo (không ném); ASCII dài ≥6 byte bị che khi xuất.
- **Phân tích ngoại tuyến**: `OfflineLogAnalyzer.kt` — thống kê theo thiết bị/service/characteristic/nguồn, độ dài, chu kỳ, khoảng trống, rỗng/bất thường, tổng kiểm (5 sơ đồ), ứng viên bộ đếm + ước lượng thiếu gói, byte đổi/không đổi, so sánh nhiều phiên/nhiều log, bảng thiết bị–characteristic, bậc thiết bị (phát hiện → kết nối → truyền dữ liệu → xác định chức năng → chứng minh liên quan động cơ). Không gán nghĩa vật lý (test kiểm).
- **Xác minh**: `TelemetryVerification.kt` — 6 trạng thái, 5 cấp bằng chứng, tiêu chí đặt trước + khóa + vân tay, nhập tham chiếu CSV (loại dòng thiếu nguồn/đơn vị/cấp), ghép theo thời gian + ước lượng độ trễ, Pearson, sai số tuyệt đối/tương đối, độ lệch chuẩn, tái lập theo phiên, độ phủ, STALE. `FieldVerdict` không thể dựng VERIFIED khi còn tiêu chí chưa đạt (kiểm trong `init`).
- **Kho bằng chứng/báo cáo**: `ProtocolEvidenceRepository.kt` (+ `TelemetryEvidenceReport` MD/CSV/JSON); từ chối khóa tiêu chí sau khi có tham chiếu.
- **ViewModel/UI**: `BleViewModel` ghi payload, sự kiện, quảng bá; nút bắt đầu/dừng ghi, xuất JSON/CSV, nhập log, nhập tham chiếu, bảng trường (`VerificationTab.kt`, tab thứ 4 của Phân tích giao thức). "Chưa có dữ liệu"/"Chưa xác minh" thay cho số 0.
- **Công cụ**: `tools/analyze-log.sh` (+ `AnalyzeLogCli.kt`), `tools/verify-jvm.sh` cập nhật; mẫu ở `docs/samples/`.
- **Tài liệu**: `BLE_PROTOCOL_AUDIT.md`, `TELEMETRY_VERIFICATION_PLAN.md`, `TELEMETRY_FIELD_EVIDENCE.md`, `SAMPLE_LOG_GUIDE.md`, tệp này.

## 3. File sửa/thêm
Mới: `telemetry/{RawFrameRecorder,TelemetryLogIO,OfflineLogAnalyzer,TelemetryVerification,ProtocolEvidenceRepository}.kt`, `ui/VerificationTab.kt`, `tools/{AnalyzeLogCli.kt,analyze-log.sh}`, test `{TelemetryTestSupport,TelemetryRecorderTest,TelemetryLogIoTest,OfflineLogAnalyzerTest,TelemetryVerificationTest,EvidenceRepositoryTest}.kt`, `docs/samples/*`, 4 tài liệu gốc ở trên.
Sửa: `ui/BleViewModel.kt`, `ui/ProtocolAnalysisScreen.kt`, `AppInfo.kt`, `app/build.gradle.kts` (version/versionCode), `tools/verify-jvm.sh`, `docs/{PROTOCOL_STATUS,CHANGELOG}.md`.
Giữ nguyên: parser YCC, hiệu chỉnh cũ, `LogExporter` v2, mọi chức năng điều hướng/chuyến đi.

## 4. Test mới (55)
Bộ ghi (cờ, dừng ghi, vòng đệm, nguồn quảng bá, bộ lọc), xuất/nhập (JSON/CSV vòng khứ hồi, log `/2`, rác/cụt, che ASCII), phân tích (7 khung thật, bộ đếm, tổng kiểm sai, khoảng trống, so sánh phiên, đăng ký-không-dữ-liệu, không gán nghĩa), xác minh (không tham chiếu, VERIFIED/CORRELATED/độc lập vs đồng hồ xe, một phiên, phủ hẹp, lệch, độ trễ, khóa tiêu chí muộn, sửa tiêu chí, khung sai tổng kiểm/độ dài, STALE, sai đơn vị, byte order/có dấu, nhập CSV, tiêu chí ghim). Khung tổng hợp được đánh dấu `synth*`; khung thật chỉ dùng cho cấu trúc.

## 5. Vấn đề còn tồn tại
1. Cần log thật mới + tham chiếu độc lập (SAMPLE_LOG_GUIDE) — nếu không, không có trường nào vượt giả thuyết.
2. Compose và Gradle chưa được kiểm; có thể còn lỗi biên dịch nhỏ ở `VerificationTab.kt` (dùng `OpenDocument` của activity-compose 1.9.2).
3. Muối ẩn danh vẫn ngẫu nhiên mỗi lần mở app → so sánh nhiều file theo UUID, không theo thiết bị.
4. Engine chỉ kiểm giả thuyết, không tự ước lượng hệ số; DTC/trạng thái chưa có kiểm danh mục mã.
5. Chưa nối điểm hiệu chỉnh cũ (`CalSession`, tham chiếu đồng hồ xe) sang tập tham chiếu mới.
6. Ngưỡng nhiệt độ/tốc độ/trạng thái/điện áp sạc là lựa chọn của dự án, chưa hiệu chuẩn.
7. Lỗi `status 19` sau ~29 s chưa rõ nguyên nhân.

---
# Lịch sử: 0.9.0-help-center

## Đã làm (có test JVM đạt)
- **vehicledata/VehicleDataRepository**: lớp xác thực đứng trước `TelemetryHub`. Mỗi giá trị có giá trị, đơn vị, nguồn, thời điểm, tuổi, `evidenceId`, trạng thái `VERIFIED/UNVERIFIED/STALE/UNAVAILABLE/UNSUPPORTED`. Loại giá trị: liên kết chưa xác minh, nguồn khác LIVE_VERIFIED, NaN/∞, ngoài khoảng, đơn vị lạ (nhiên liệu). Nhật ký nguồn (vòng 500 dòng) + xuất CSV chống chèn công thức. GPS không bao giờ thành tốc độ xe (test).
  - **Khoảng hợp lệ là giới hạn hợp lý, KHÔNG phải thông số Yamaha** (chưa có tài liệu hãng); ghi rõ trong mã và UI.
- **helpcenter/**: 14 bài tiếng Việt (đủ mục đích, điều kiện, các bước, kết quả, lỗi thường gặp, khắc phục, nút hành động chỉ tới màn hình có thật, trạng thái hoàn thành); tìm kiếm không dấu; lưu tiến độ cục bộ + đặt lại; ánh xạ lỗi → bài (`when` đầy đủ, lỗi biên dịch nếu thiếu); quy trình kiểm tra 8 bước từ trạng thái thật, **bước sau bị chặn khi bước trước chưa đạt**, bài có kiểm tra chỉ đánh dấu được khi bước đạt.
- **tftintegration/**: `TftIntegrationManager` (6 mức tách bạch), `NavigationIntegrationAdapter` (không gọi bridge nếu chưa VERIFIED_READY + liên kết xác minh; "đã gửi" ≠ "đã xác nhận hiển thị"). Hiện kết quả thực tế: **TFT không hỗ trợ**; không gửi gì tới xe.
- **diagnostics/DiagnosticsReport**: JSON/CSV (checklist, trạng thái giá trị, mức TFT, dòng thời gian lỗi/trạng thái), che MAC, không có gói thô/GPS.
- **UI (Compose)**: `HelpCenterScreen`; nút "Hướng dẫn" ở Kết nối, Tự tìm xe, Chẩn đoán, Xe (dữ liệu), TFT, Cài đặt; "Tìm cách khắc phục" khi có lỗi thật; bảng "Dữ liệu từ xe" với trạng thái; bảng "Mức tích hợp TFT".
- Sửa A1–A3 (xem AUDIT_REPORT).

## Đã có từ trước, không đổi
Quét/GATT explorer/xuất log (đã che), kiểm tra quyền theo phiên bản Android (`BlePermissions`/`BlePreflight`), timeout/retry, hành trình GPS, nhiên liệu, bảo dưỡng.

## KHÔNG làm / chưa làm (có chủ đích)
- Không thêm UUID, byte, hệ số, giao thức xác thực hay lệnh xe nào. Không có trường dữ liệu xe nào được "triển khai" thêm vì **chưa có nguồn đã xác minh** — các trường RPM/nhiệt độ/điện áp/nhiên liệu/ODO/DTC có trong bộ quy tắc, nhưng chỉ hiện số khi có adapter đã xác minh.
- Không có tích hợp Y-Connect/TFT thật; không có tài liệu/SDK được phép.
- Chưa có thông báo hệ thống nhắc bảo dưỡng, chưa tách gói `maintenance`.

## Kết quả build & test
| | Kết quả |
|---|---|
| `./gradlew assembleDebug` / `testDebugUnitTest` | **KHÔNG chạy được**: `services.gradle.org`, Maven Central, `maven.google.com`, `dl.google.com` đều trả 000 (kiểm tra lại 2026-10-24). **Không có APK.** Workflow `.github/workflows/android.yml` có sẵn để build APK trên GitHub Actions. |
| `tools/verify-jvm.sh` (kotlinc 2.0.21, android.jar API 34, JUnit 4.13.2) | type-check lớp Android không-Compose **sạch**; **296 test, 0 lỗi** (baseline 233, thêm 63). |
| Compose/Room/osmdroid/Manifest | **Chưa được trình biên dịch kiểm** — gồm `HelpCenterScreen.kt` và các chỉnh sửa UI. Có thể còn lỗi biên dịch nhỏ; việc đầu tiên cần làm là build trên GitHub Actions/Android Studio. |
| Thiết bị/xe thật | Chưa thử. |
