package vn.nvxconnect

/**
 * Thông tin phiên bản lấy từ METADATA build (version.properties → Gradle → BuildConfig), không gõ tay ở đây.
 * Muốn đổi phiên bản: chạy tác vụ `releaseApk` (xem docs/RELEASE.md), đừng sửa số trong mã nguồn.
 */
object AppInfo {
    /** versionName, ví dụ "0.13.1". */
    val VERSION: String get() = BuildConfig.VERSION_NAME
    /** versionCode (số nguyên tăng dần; Android dùng để cho phép cập nhật đè). */
    val VERSION_CODE: Int get() = BuildConfig.VERSION_CODE
    val APPLICATION_ID: String get() = BuildConfig.APPLICATION_ID
    /** Dạng hiển thị trên giao diện: "0.13.1 (bản dựng 16)". */
    val DISPLAY: String get() = "$VERSION (bản dựng $VERSION_CODE)"
    /** Định danh gửi tới máy chủ OSM/Nominatim/OSRM theo chính sách sử dụng của họ. */
    val USER_AGENT: String get() = "NVXConnect/$VERSION (Android; ung dung ca nhan, khong thuong mai)"
}
