# IMPLEMENTATION_REPORT — NVX Connect 0.9.0-help-center

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
