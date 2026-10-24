# NVX Connect 0.6 (khảo sát BLE + danh mục chức năng + bản đồ/dẫn đường trên điện thoại)

Ứng dụng Android (Kotlin, Compose, Material 3) cho chủ Yamaha NVX 155. Giữ nguyên nguyên tắc: **không có dữ liệu xe giả**.
Hiện CHƯA có giao thức xe nào được xác minh, nên RPM/nhiệt độ/điện áp/mã lỗi hiển thị "Chưa có dữ liệu".

Có gì: khảo sát BLE thụ động (quét, GATT explorer, nghe notify, xuất log đã che), bản đồ OpenStreetMap + tìm địa điểm + dẫn đường
từng chặng bằng giọng nói trên điện thoại, ghi/xem lại hành trình (GPS), nhiên liệu, bảo dưỡng, hồ sơ xe do bạn nhập.
Không có: hiển thị lên TFT xe (KHÔNG HỖ TRỢ, xem `docs/TFT_NAVIGATION.md`), ghi/gửi lệnh vào xe, vượt xác thực Y-Connect.

Mới ở 0.6: danh mục chức năng xe/app, bảng đối chiếu, hồ sơ giao thức tham chiếu (Phân tích giao thức → Danh mục chức năng).

**Đọc `docs/HANDOVER.md` trước**: nó ghi rõ cái gì đã build/test, cái gì chưa, cái gì chưa được giao thức hỗ trợ.

## Build
Mở bằng Android Studio (Koala+ / AGP 8.5), JDK 17, chạy trên điện thoại thật (Android 8.0+).
`./gradlew testDebugUnitTest` · `./gradlew assembleDebug` (chi tiết: `docs/BUILD.md`).

## Build trên GitHub
1. Tạo repo trống, đẩy toàn bộ thư mục này lên nhánh `main`.
2. Tab **Actions** → **Android CI** (tự chạy khi push, hoặc *Run workflow*).
3. Tải artifact **nvx-connect-debug-apk** ở cuối trang kết quả; cài APK (bật cài từ nguồn không xác định).
Workflow dùng Gradle 8.9 cài sẵn, không cần gradle-wrapper.
