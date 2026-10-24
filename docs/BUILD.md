# Hướng dẫn build

## Yêu cầu
JDK 17, Android SDK 35 (platform + build-tools), Android Studio Koala+ hoặc Gradle 8.9. Cần Internet lần đầu để tải
thư viện (Google Maven, Maven Central).

## Lệnh
```
gradle :app:testDebugUnitTest     # unit test (hoặc ./gradlew nếu đã tạo wrapper bằng `gradle wrapper`)
gradle :app:assembleDebug         # APK: app/build/outputs/apk/debug/app-debug.apk
```
Cài lên điện thoại: bật "Gỡ lỗi USB" rồi `adb install -r app-debug.apk`, hoặc chép tệp APK vào máy.

## Kiểm tra nhanh không cần Android SDK
`tools/verify-jvm.sh` biên dịch phần logic và các lớp Android (type-check với android.jar) rồi chạy unit test bằng JUnit.
Nó KHÔNG biên dịch Compose UI, Room (KSP) hay osmdroid, nên chỉ bổ sung chứ không thay `assembleDebug`.

## Lỗi thường gặp
- `Unresolved reference` ở thư mục `ui/`: mã Compose chưa từng được biên dịch (xem HANDOVER). Gửi nguyên log để sửa.
- Room KSP báo lỗi schema: bản này KHÔNG đổi schema (vẫn version 1), chỉ thêm truy vấn.
- Android 14+: dịch vụ ghi hành trình cần quyền vị trí đã cấp trước khi bấm Bắt đầu.
