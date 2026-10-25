package vn.nvxconnect.pro

import vn.nvxconnect.domain.GeoPoint
import java.util.Calendar
import java.util.Locale

/** Tóm tắt một chuyến đi (tách khỏi thực thể Room để logic thuần và kiểm thử được). */
data class RideSummary(val id: Long, val startMs: Long, val endMs: Long, val distanceM: Double, val maxSpeedKmh: Double, val isDemo: Boolean) {
    val durationMs get() = (endMs - startMs).coerceAtLeast(0)
    val km get() = distanceM / 1000.0
}

enum class RideRange(val title: String) { ALL("Tất cả"), TODAY("Hôm nay"), WEEK("7 ngày"), MONTH("30 ngày") }
enum class RideSort(val title: String) { NEWEST("Mới nhất"), LONGEST("Xa nhất"), FASTEST("Nhanh nhất") }

object RideTools {
    private const val DAY = 86_400_000L
    fun startOfDay(ms: Long, cal: Calendar = Calendar.getInstance()): Long = cal.apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    fun fromMs(r: RideRange, nowMs: Long): Long = when (r) {
        RideRange.ALL -> Long.MIN_VALUE; RideRange.TODAY -> startOfDay(nowMs)
        RideRange.WEEK -> startOfDay(nowMs) - 6 * DAY; RideRange.MONTH -> startOfDay(nowMs) - 29 * DAY
    }

    /** Tìm theo chuỗi ngày dd/MM (ví dụ "12/10") hoặc số km tối thiểu ("≥5" → gõ ">5"). Rỗng = không lọc. */
    fun filter(all: List<RideSummary>, query: String, range: RideRange, sort: RideSort, nowMs: Long, showDemo: Boolean): List<RideSummary> {
        val from = fromMs(range, nowMs); val q = query.trim()
        val minKm = if (q.startsWith(">")) q.drop(1).trim().replace(',', '.').toDoubleOrNull() else null
        val f = all.filter { it.isDemo == showDemo && it.startMs >= from && (q.isEmpty() || (if (q.startsWith(">")) (minKm != null && it.km >= minKm) else dateText(it.startMs).contains(q))) }
        return when (sort) {
            RideSort.NEWEST -> f.sortedByDescending { it.startMs }
            RideSort.LONGEST -> f.sortedByDescending { it.distanceM }
            RideSort.FASTEST -> f.sortedByDescending { it.maxSpeedKmh }
        }
    }
    fun dateText(ms: Long): String = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("vi")).format(java.util.Date(ms))

    /**
     * Thời gian dừng = tổng khoảng giữa hai điểm GPS liên tiếp khi cả hai gần như đứng yên (< [stillMps]) và khoảng cách thời gian
     * không vượt [maxGapMs] (khoảng đứt GPS lớn hơn KHÔNG tính là dừng — không biết xe có dừng hay không).
     */
    fun stopTimeMs(points: List<GeoPoint>, stillMps: Float = 0.8f, maxGapMs: Long = 30_000L): Long {
        var total = 0L
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]; val dt = b.timeMs - a.timeMs
            if (dt in 1..maxGapMs && a.speedMps < stillMps && b.speedMps < stillMps) total += dt
        }
        return total
    }

    // ---------- chi phí và năng lượng (ước tính từ số do NGƯỜI DÙNG nhập, không phải số đo của xe)
    data class Cost(val liters: Double, val vnd: Long, val source: String, val confidence: String)

    /** Ước tính nhiên liệu/chi phí: km × mức tiêu thụ (l/100 km) × giá. Thiếu cấu hình → null (không bịa số). */
    fun cost(km: Double, consumptionL100: Double, pricePerLiterVnd: Int): Cost? {
        if (!(km >= 0) || consumptionL100 <= 0.0 || pricePerLiterVnd <= 0 || !km.isFinite()) return null
        val l = km * consumptionL100 / 100.0
        return Cost(l, Math.round(l * pricePerLiterVnd),
            "Quãng đường GPS × mức tiêu thụ và giá do bạn nhập (không phải số đo của xe)",
            "THẤP — tiêu thụ thực tế thay đổi theo tải, tốc độ, kẹt xe")
    }
    fun vnd(v: Long): String = String.format(Locale("vi"), "%,d", v).replace(',', '.') + " đ"
}
