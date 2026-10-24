package vn.nvxconnect.domain

/**
 * Nguồn gốc của MỌI giá trị hiển thị. Các nguồn không được trộn lẫn:
 *  - LIVE_VERIFIED: chỉ đến từ xe thật, qua adapter giao thức đã có bằng chứng (hiện CHƯA có adapter nào).
 *  - GPS_ESTIMATE: suy ra từ GPS điện thoại (tốc độ, quãng đường). Không phải tốc độ/ODO của xe.
 *  - USER_INPUT: người dùng tự nhập (ODO, hồ sơ xe, đổ xăng, bảo dưỡng).
 *  - CALCULATED: tính từ số liệu USER_INPUT hoặc GPS_ESTIMATE (ví dụ L/100km).
 *  - DEMO: dữ liệu minh họa, luôn gắn nhãn DEMO, không tham gia số liệu thật.
 *  - UNAVAILABLE: chưa có dữ liệu.
 */
enum class DataSource(val label: String) {
    LIVE_VERIFIED("XE (đã xác minh)"),
    GPS_ESTIMATE("GPS ước tính"),
    USER_INPUT("Bạn nhập"),
    CALCULATED("Tính toán"),
    DEMO("DEMO"),
    UNAVAILABLE("Chưa có dữ liệu")
}

data class Reading<T>(val value: T?, val source: DataSource, val updatedAtMillis: Long?) {
    companion object { fun <T> unavailable() = Reading<T>(null, DataSource.UNAVAILABLE, null) }
}

/** Giá trị đo từ xe. Mặc định là "chưa có dữ liệu"; không bao giờ điền số giả. */
object VehicleReadings {
    const val NO_DATA = "Chưa có dữ liệu"
}
