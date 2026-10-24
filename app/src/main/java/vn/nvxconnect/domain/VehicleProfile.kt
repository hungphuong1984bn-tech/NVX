package vn.nvxconnect.domain

import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Hồ sơ xe do NGƯỜI DÙNG nhập (DataSource.USER_INPUT). App không đọc các giá trị này từ xe. */
data class VehicleProfile(
    val model: String = "Yamaha NVX 155",
    val variant: String = "V3 SP 2025",
    val engineCc: Int = 155,
    /** yyyy-MM-dd hoặc rỗng. */
    val purchaseDate: String = ""
)

object ProfileValidator {
    /** Trả về thông báo lỗi tiếng Việt, hoặc null nếu hợp lệ. */
    fun validate(p: VehicleProfile, today: LocalDate = LocalDate.now()): String? {
        if (p.model.isBlank()) return "Tên xe không được để trống"
        if (p.model.length > 60 || p.variant.length > 60) return "Tên/phiên bản quá dài (tối đa 60 ký tự)"
        if (p.engineCc !in 20..2000) return "Dung tích động cơ phải từ 20 đến 2000 cc"
        if (p.purchaseDate.isNotBlank()) {
            val d = parseDate(p.purchaseDate) ?: return "Ngày mua phải theo dạng yyyy-MM-dd, ví dụ 2025-06-30"
            if (d.isAfter(today)) return "Ngày mua không thể ở tương lai"
            if (d.year < 1990) return "Ngày mua không hợp lý"
        }
        return null
    }

    fun parseDate(s: String): LocalDate? = try { LocalDate.parse(s.trim()) } catch (_: DateTimeParseException) { null }
}
