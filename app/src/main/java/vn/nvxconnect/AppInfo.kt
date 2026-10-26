package vn.nvxconnect

object AppInfo {
    /** Giữ khớp với versionName trong app/build.gradle.kts. */
    const val VERSION = "0.13.0-live"
    /** Định danh gửi tới máy chủ OSM/Nominatim/OSRM theo chính sách sử dụng của họ. */
    const val USER_AGENT = "NVXConnect/$VERSION (Android; ung dung ca nhan, khong thuong mai)"
}
