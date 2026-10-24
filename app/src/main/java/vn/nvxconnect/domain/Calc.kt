package vn.nvxconnect.domain

import vn.nvxconnect.data.FuelEntry
import vn.nvxconnect.data.MaintenanceItem
import vn.nvxconnect.data.Trip
import java.util.Calendar
import kotlin.math.*

/** Một điểm GPS của điện thoại (DataSource.GPS_ESTIMATE). bearingDeg là NaN nếu thiết bị không báo hướng. */
data class GeoPoint(val lat: Double, val lon: Double, val speedMps: Float, val accuracyM: Float, val timeMs: Long,
                    val bearingDeg: Float = Float.NaN)
data class TripStatsResult(val distanceM: Double, val durationMs: Long, val avgKmh: Double, val maxKmh: Double)

object TripStats {
    /** Khoảng trống thời gian lớn hơn mức này giữa hai điểm liên tiếp được coi là đứt đoạn (hầm, mất GPS, tạm dừng). */
    const val SEGMENT_GAP_MS = 30_000L
    /** Tốc độ GPS lớn hơn mức này (m/s, ~216 km/h) bị coi là nhiễu, không tính vào tốc độ tối đa. */
    const val MAX_PLAUSIBLE_SPEED_MPS = 60f
    /** Bước nhỏ hơn mức này khi gần như đứng yên bị coi là GPS trôi, không cộng vào quãng đường. */
    private const val JITTER_M = 3.0
    private const val STILL_MPS = 0.8f

    fun haversineM(a: GeoPoint, b: GeoPoint): Double = haversineM(a.lat, a.lon, b.lat, b.lon)

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }

    /** Lọc điểm kém chính xác rồi tách thành các đoạn liên tục. */
    fun splitSegments(points: List<GeoPoint>, maxAccuracyM: Float = 50f, gapMs: Long = SEGMENT_GAP_MS): List<List<GeoPoint>> {
        val out = ArrayList<MutableList<GeoPoint>>()
        var cur: MutableList<GeoPoint>? = null
        for (p in points) {
            if (p.accuracyM > maxAccuracyM) continue
            val last = cur?.lastOrNull()
            if (cur == null || last == null || p.timeMs - last.timeMs > gapMs) { cur = ArrayList(); out += cur }
            cur.add(p)
        }
        return out
    }

    fun compute(points: List<GeoPoint>, maxAccuracyM: Float = 50f): TripStatsResult =
        computeSegments(splitSegments(points, maxAccuracyM))

    /** Mỗi đoạn được tính riêng: khoảng đứt không bị nối thẳng, thời gian tạm dừng không tính vào thời lượng. */
    fun computeSegments(segments: List<List<GeoPoint>>): TripStatsResult {
        var dist = 0.0; var dur = 0L; var maxMps = 0f
        for (seg in segments) {
            if (seg.size < 2) continue
            var anchor = seg[0]
            for (i in 1 until seg.size) {
                val p = seg[i]
                val d = haversineM(anchor, p)
                if (d < JITTER_M && p.speedMps < STILL_MPS) continue
                dist += d; anchor = p
            }
            dur += seg.last().timeMs - seg.first().timeMs
            seg.forEach { if (it.speedMps <= MAX_PLAUSIBLE_SPEED_MPS && it.speedMps > maxMps) maxMps = it.speedMps }
        }
        if (dist == 0.0 && dur == 0L) return TripStatsResult(0.0, 0, 0.0, 0.0)
        val avg = if (dur > 0) dist / 1000.0 / (dur / 3_600_000.0) else 0.0
        return TripStatsResult(dist, dur, avg, maxMps * 3.6)
    }

    /** Hướng (độ, 0=Bắc) từ a đến b. */
    fun bearingDeg(a: GeoPoint, b: GeoPoint): Double = bearingDeg(a.lat, a.lon, b.lat, b.lon)
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val f1 = Math.toRadians(lat1); val f2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(f2); val x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}

/** CALCULATED từ số liệu người dùng nhập, phương pháp đổ đầy bình-đến-đầy bình. */
data class FuelStats(val distanceKm: Int, val liters: Double, val lPer100Km: Double, val vndPerKm: Double)

object FuelCalc {
    fun compute(entries: List<FuelEntry>): FuelStats? {
        val s = entries.sortedBy { it.odometerKm }
        val fulls = s.withIndex().filter { it.value.fullTank }.map { it.index }
        if (fulls.size < 2) return null
        val first = fulls.first(); val last = fulls.last()
        val dist = s[last].odometerKm - s[first].odometerKm
        if (dist <= 0) return null
        val span = s.subList(first + 1, last + 1)
        val liters = span.sumOf { it.liters }; val cost = span.sumOf { it.totalVnd }
        return FuelStats(dist, liters, liters / dist * 100, cost.toDouble() / dist)
    }
}

/** Kiểm tra dữ liệu đổ xăng người dùng nhập. Trả về thông báo lỗi tiếng Việt hoặc null. */
object FuelValidator {
    const val MAX_LITERS = 30.0
    const val MAX_PRICE = 200_000
    fun validate(odoKm: Int?, liters: Double?, pricePerLiter: Int?, totalVnd: Long?, existing: List<FuelEntry>): String? {
        if (odoKm == null || odoKm < 0) return "ODO không hợp lệ"
        if (liters == null || liters <= 0.0) return "Số lít phải lớn hơn 0"
        if (liters > MAX_LITERS) return "Số lít quá lớn (tối đa $MAX_LITERS), kiểm tra lại"
        if (pricePerLiter == null || pricePerLiter <= 0) return "Giá/lít phải lớn hơn 0"
        if (pricePerLiter > MAX_PRICE) return "Giá/lít không hợp lý"
        if (totalVnd != null && totalVnd <= 0) return "Tổng tiền phải lớn hơn 0"
        if (existing.any { it.odometerKm == odoKm }) return "Đã có lần đổ xăng ở ODO $odoKm km"
        return null
    }
}

/** OK = còn hạn; COMPLETED = hạng mục làm một lần (không có chu kỳ) đã ghi hoàn thành. */
enum class MaintStatus { OK, SOON, OVERDUE, COMPLETED, UNRECORDED }
data class DueInfo(val status: MaintStatus, val remainingKm: Int?, val remainingDays: Long?)

object Maintenance {
    fun due(i: MaintenanceItem, odoKm: Int, nowMs: Long, soonKm: Int = 500, soonDays: Int = 14): DueInfo {
        if (i.lastDoneMs == 0L) return DueInfo(MaintStatus.UNRECORDED, null, null)
        if (i.intervalKm == null && i.intervalDays == null) return DueInfo(MaintStatus.COMPLETED, null, null)
        val km = i.intervalKm?.let { it - (odoKm - i.lastDoneOdoKm) }
        val days = i.intervalDays?.let { it - (nowMs - i.lastDoneMs) / 86_400_000L }
        val overdue = (km != null && km <= 0) || (days != null && days <= 0)
        val soon = (km != null && km <= soonKm) || (days != null && days <= soonDays)
        return DueInfo(if (overdue) MaintStatus.OVERDUE else if (soon) MaintStatus.SOON else MaintStatus.OK, km, days)
    }
}

/** Tổng hợp chuyến đi (GPS điện thoại) theo ngày/tuần/tháng. */
object TripSummary {
    data class Range(val km: Double, val maxKmh: Double, val avgKmh: Double, val driveMs: Long)
    private const val DAY = 86_400_000L
    fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    fun startOfMonth(ms: Long): Long = Calendar.getInstance().apply { timeInMillis = startOfDay(ms); set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
    fun range(trips: List<Trip>, fromMs: Long): Range {
        val t = trips.filter { it.startMs >= fromMs }
        val km = t.sumOf { it.distanceM } / 1000.0
        val ms = t.sumOf { it.endMs - it.startMs }
        return Range(km, t.maxOfOrNull { it.maxSpeedKmh } ?: 0.0, if (ms > 0) km / (ms / 3_600_000.0) else 0.0, ms)
    }
    fun dailyKm(trips: List<Trip>, nowMs: Long): List<Pair<String, Double>> {
        val labels = arrayOf("CN", "T2", "T3", "T4", "T5", "T6", "T7")
        val today = startOfDay(nowMs)
        return (6 downTo 0).map { d ->
            val from = today - d * DAY
            val dow = Calendar.getInstance().apply { timeInMillis = from }.get(Calendar.DAY_OF_WEEK)
            labels[dow - 1] to trips.filter { it.startMs in from until from + DAY }.sumOf { it.distanceM } / 1000.0
        }
    }
}

object SpeedAlert {
    /** Quá ngưỡng người dùng đặt (limitKmh <= 0 là tắt). Bỏ qua điểm GPS kém chính xác. Tốc độ GPS, không phải tốc độ đồng hồ xe. */
    fun isOver(speedMps: Float, accuracyM: Float, limitKmh: Int, maxAccuracyM: Float = 50f) =
        limitKmh > 0 && accuracyM <= maxAccuracyM && speedMps * 3.6f > limitKmh
}
