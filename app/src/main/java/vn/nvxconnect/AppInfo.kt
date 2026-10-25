package vn.nvxconnect

object AppInfo {
    /** Giữ khớp với versionName trong app/build.gradle.kts. */
    const val VERSION = "0.10.0-telemetry-verify"
    /** Định danh gửi tới máy chủ OSM/Nominatim/OSRM theo chính sách sử dụng của họ. */
    const val USER_AGENT = "NVXConnect/$VERSION (Android; ung dung ca nhan, khong thuong mai)"
}
