# BUILD_LOG — vì sao chưa chạy được assembleDebug (2026-10-22)

Môi trường soạn mã (sandbox) không truy cập được kho cần cho Gradle/Android. Kết quả kiểm tra thật:
```
https://dl.google.com/android/repository/repository2-3.xml -> 000
https://services.gradle.org/distributions/ -> 000
https://repo.maven.apache.org/maven2/ -> 000
https://maven.google.com/ -> 000
```
Không có Android SDK (ANDROID_HOME trống) và không có Gradle. Vì vậy KHÔNG chạy được `testDebugUnitTest` hay `assembleDebug`.

## Thay vào đó đã chạy (tools/verify-jvm.sh)
- kotlinc 2.0.21 biên dịch logic thuần + các lớp Android (bluetooth, protocol, navigation, location, data, NvxApp, ViewModel) với android.jar API 35
  và stub tối thiểu cho Room/DataStore/Lifecycle/Fused Location (tools/stubs). Không có lỗi biên dịch.
- JUnit 4.13.2 trên JVM thường: 103 test, 0 lỗi (chạy lặp 3 lần để kiểm tra độ ổn định của test bất đồng bộ).

## Chưa được trình biên dịch kiểm tra
- Toàn bộ thư mục `ui/` ngoài ViewModel (Jetpack Compose, Material 3): không có thư viện Compose để biên dịch.
- `OsmMap.kt` (osmdroid), `TripRecorder.kt` (chỉ type-check với stub tự viết, không phải thư viện thật), `Settings.kt` (DataStore, stub), `Db.kt` (Room/KSP).
- Gộp Manifest, tài nguyên, FileProvider.

---
# Lần chạy 2026-10-23 (bản 0.6.0-capabilities)
- Gradle/Maven/Google vẫn KHÔNG truy cập được; `assembleDebug`/`testDebugUnitTest` **chưa chạy**.
- Dựng được toolchain thay thế: kotlinc 2.0.21 (GitHub releases), android.jar API 34 (Sable/android-platforms), JUnit 4.13.2 + Hamcrest (apt). Lưu ý: dùng API 34 thay vì 35 như script mô tả.
- `tools/verify-jvm.sh`: type-check Android OK; **207 test đạt** (baseline trước khi sửa: 182 đạt). Chạy lặp ổn định.
- Chưa được trình biên dịch kiểm: toàn bộ Compose (kể cả `CapabilityScreen.kt`), osmdroid, Room/KSP, DataStore thật, gộp Manifest.

---
# Lần chạy 2026-10-24 (0.9.0-help-center)
- Gradle/Maven/Google vẫn không truy cập được (HTTP 000). Không có APK.
- Toolchain thay thế: kotlinc 2.0.21 + android.jar API 34 (GitHub), JUnit 4.13.2 + Hamcrest 2.2 (apt). Lưu ý: `hamcrest-core.jar` của apt là gói chuyển tiếp rỗng; dùng `hamcrest-2.2.jar`.
- `tools/verify-jvm.sh`: type-check OK; **296 test đạt** (baseline 233).
- Chưa kiểm bằng trình biên dịch: `HelpCenterScreen.kt` và chỉnh sửa Compose ở Connect/AutoConnect/Vehicle/Tft/Settings/Analysis/MainActivity.
