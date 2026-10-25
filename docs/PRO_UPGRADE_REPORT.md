# NVX Connect Pro 0.11.0-pro — báo cáo nâng cấp UI/UX

## Kết quả kiểm chứng (ghi đúng những gì đã và chưa chạy)

| Bước | Trạng thái |
|---|---|
| Type-check lớp Android phi-Compose (ViewModel, NvxApp, data, bluetooth...) với android.jar API 35 | ĐÃ CHẠY, OK |
| Unit test JVM (`tools/verify-jvm.sh`, kotlinc + JUnit 4) | ĐÃ CHẠY: 436 test, 0 lỗi (351 cũ + 85 mới) |
| Biên dịch các file Jetpack Compose mới/sửa | CHƯA CHẠY: môi trường không tải được Gradle, Compose, Android SDK. Đã rà thủ công chữ ký API với mã sẵn có |
| `./gradlew testDebugUnitTest`, `assembleDebug`, GitHub Actions, tải APK artifact | CHƯA CHẠY, nên CHƯA có APK |
| Thử trên thiết bị/giả lập, BLE thật | CHƯA CHẠY |

Việc cần làm ngay: đẩy lên GitHub, để workflow hiện có chạy. Nếu build lỗi biên dịch Compose, lỗi sẽ nằm ở các file UI liệt kê bên dưới.

## File thêm
- `pro/` (logic thuần, có test): ProNav, ChartMath (+SeriesStore), HexDiff, PacketFilter, CockpitModel, RideTools, LabStatus, ProSettings, DiagnosticsModel, SeriesCsv
- `data/ProPrefs.kt`
- `ui/`: ProComponents, ScreenMode, CockpitScreen, TelemetryCenterScreen, ProtocolLabScreen (thay ProtocolAnalysisScreen), DiagnosticsScreen, ToolsScreen
- test: ProNavTest, ChartMathTest, HexDiffTest, PacketFilterTest, CockpitModelTest, RideToolsTest, ProLogicTest

## File sửa
Design.kt (dark/light/system, token), Common.kt (Page responsive), Components.kt/Cockpit.kt/AutoConnectScreen.kt (bỏ màu cứng), MainActivity.kt (5 tab, Back, lưu trạng thái), HomeScreen.kt, TripsScreen.kt, SettingsScreen.kt, BleViewModel.kt (chỉ thêm hàm đọc + dung lượng log), AppViewModel.kt (deleteTrips, replaySeries), NvxApp.kt (proPrefs, history), AndroidManifest.xml (configChanges cho Activity), themes.xml, AppInfo.kt, build.gradle.kts (version), tools/verify-jvm.sh, CHANGELOG.

Ghi chú: `NvxApp.kt` là lớp Application; điều hướng thực tế nằm ở `MainActivity.kt` nên thay đổi navigation làm ở đó.

## Đã làm
- Design system: dark than chì + cyan mặc định; light riêng; Tối/Sáng/Theo hệ thống; responsive.
- 5 tab; Công cụ chứa Protocol Lab, Chẩn đoán, Cài đặt và mọi màn hình cũ. Back: chi tiết hành trình → cockpit toàn màn hình → trang con → Trang chủ.
- Cockpit: tốc độ GPS lớn (ghi rõ không phải ECU), vòng đo, ẩn/hiện ô, toàn màn hình + giữ sáng + khóa ngang, tương phản cao, khóa chỉnh bố cục khi đang chạy. Số xe chỉ hiện khi giao thức đã xác minh và giá trị còn mới; số cũ không được hiện như số trực tiếp.
- Telemetry Center: chuỗi thật (GPS, xe đã xác minh, giả thuyết YCC có nhãn), cửa sổ thời gian, min/max/TB, thu phóng/cuộn/tooltip/chọn vùng, trục thời gian chung, khoảng trống tô nền (không nội suy), đếm mẫu bị cắt, lưu CSV và phát lại CSV.
- Protocol Lab 9 tab: Scanner, GATT, Live Packets (tìm, lọc UUID/độ dài/nguồn/trạng thái, sắp xếp, tạm dừng), HEX Inspector (so sánh hai khung, tô byte khác), Frame Analyzer, Hypotheses, Evidence, Session Logs, Export. 6 nhãn OBSERVED/HYPOTHESIS/CORRELATED/VERIFIED/UNAVAILABLE/INACCESSIBLE ánh xạ một chiều từ engine, không có nút gán VERIFIED.
- Ride Center: tìm, lọc ngày, sắp xếp, thống kê, thời gian dừng, chi phí/năng lượng từ số người dùng nhập (ghi nguồn, độ tin cậy THẤP), xóa có xác nhận (đơn và hàng loạt).
- Diagnostics Center: quyền/BT/GATT, từng bước, nhật ký lỗi kèm gợi ý, báo cáo thu thập/xác minh, gói hỗ trợ (JSON/CSV, MAC đã che). Không tạo mã lỗi ECU.
- Cài đặt: chủ đề, chu kỳ vẽ, ô cockpit, giá xăng/tiêu thụ, dung lượng log, phiên, thiết bị đã lưu (ưu tiên/xóa có xác nhận), phiên bản decoder.

## Giới hạn còn lại
1. Chưa biên dịch UI Compose, chưa có APK (xem bảng trên).
2. Không có "pin" và "công suất" trên Cockpit: NVX chạy xăng và app không có nguồn xác minh cho các số này (không bịa).
3. Không có tốc độ xe từ ECU: chưa có giao thức xe nào được xác minh.
4. Dung lượng log đổi có hiệu lực sau khi mở lại app.
5. Phát lại ngoại tuyến trong Telemetry Center dùng CSV do chính nó xuất; log payload JSON nạp ở Protocol Lab → Evidence.
6. Nhập dữ liệu toàn bộ ứng dụng (khôi phục từ JSON xuất) chưa làm; hiện chỉ có xuất.
7. Test UI Compose (instrumented) chưa có; test hiện bao phủ logic thuần: điều hướng, stale/disconnected, hiển thị theo trạng thái xác minh, lọc log, CSV xuất/nạp.
8. Đổi chủ đề dùng một cờ trạng thái toàn cục (`NvxColors.dark`); hoạt động qua Compose snapshot nhưng cần xem thực tế trên máy.
