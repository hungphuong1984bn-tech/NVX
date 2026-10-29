# Phát hành APK và quản lý phiên bản

## Nguồn phiên bản duy nhất
`version.properties` (gốc dự án): `VERSION_NAME=MAJOR.MINOR.PATCH`, `VERSION_CODE=số nguyên`.
`app/build.gradle.kts` đọc từ đây (qua `gradle/release.gradle.kts`) và sinh `BuildConfig`; app hiển thị phiên bản từ metadata này
(`AppInfo.VERSION`, `AppInfo.DISPLAY`) ở Tổng quan và Cài đặt, ghi vào metadata của gói ZIP xuất. **Không sửa số phiên bản trong mã.**
`applicationId` giữ `vn.nvxconnect`.

## Lệnh
```
gradle printVersion      # in phiên bản hiện tại và bản kế tiếp, không đổi gì
gradle releaseApk        # PHÁT HÀNH (hoặc ./gradlew releaseApk khi đã có wrapper)
```
`releaseApk` chạy theo thứ tự: kiểm tra đích chưa tồn tại → `testDebugUnitTest` → `assembleRelease` với phiên bản kế tiếp (patch+1, code+1)
→ kiểm tra APK (phiên bản trong `output-metadata.json` khớp, mở được, có manifest/dex, đã ký) → chép sang
`release-apk/NVX-Connect-v<phiên bản>.apk` (không ghi đè, kiểm SHA-256) → **chỉ lúc này** mới ghi `version.properties`.
- Build debug, test, `assembleRelease`, đồng bộ Android Studio: **không bao giờ** đổi phiên bản.
- Thất bại ở bất kỳ bước nào: phiên bản giữ nguyên, không có APK mới, không để lại `.part`.
- Mỗi lần `releaseApk` thành công tăng đúng một lần. Phải gọi đầy đủ tên `releaseApk` (hoặc `:app:releaseApk`), không dùng viết tắt.
- Tệp đích đã có → dừng, không ghi đè; hãy di chuyển/đổi tên rồi chạy lại.
- Kiểm thử logic này không cần Android SDK: `bash tools/test-release.sh` (cần gradle ≥ 8).

## Chữ ký (để cài đè bản cũ)
Android chỉ cho cập nhật đè khi **cùng applicationId, versionCode lớn hơn, cùng khóa ký**. Hãy dùng MỘT khóa cố định cho mọi bản:
```
keytool -genkeypair -v -keystore nvx-release.jks -alias nvx -keyalg RSA -keysize 2048 -validity 10000
```
Tạo `keystore.properties` ở gốc dự án (đã nằm trong `.gitignore`, **không commit**; sao lưu khóa an toàn, mất khóa = không cài đè được):
```
storeFile=nvx-release.jks
storePassword=...
keyAlias=nvx
keyPassword=...
```
Hoặc biến môi trường `NVX_KEYSTORE_FILE`, `NVX_KEYSTORE_PASSWORD`, `NVX_KEY_ALIAS`, `NVX_KEY_PASSWORD` (dùng cho CI).
Chưa cấu hình thì `releaseApk` ký bằng khóa debug của máy build và in cảnh báo; khi đó chỉ cài đè được lên bản từ chính khóa debug đó.
Nếu bản đang cài được ký bằng khóa khác, phải dùng đúng khóa đó hoặc gỡ cài đặt (mất dữ liệu cục bộ) một lần.
