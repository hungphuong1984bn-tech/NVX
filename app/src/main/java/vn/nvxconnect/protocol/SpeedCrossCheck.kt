package vn.nvxconnect.protocol

import kotlin.math.abs
import kotlin.math.sqrt

/** Mẫu tốc độ GPS của điện thoại (m/s) tại một thời điểm: "sự thật nền" ĐỘC LẬP duy nhất mà app có. */
data class SpeedSample(val timeMs: Long, val speedMps: Double)

/**
 * Đối chiếu tự động: trường nào trong gói notify biến thiên TUYẾN TÍNH theo tốc độ GPS của điện thoại.
 * Đây là phép kiểm chứng thống kê thật (không dùng nhãn tay), nhưng KẾT QUẢ CHỈ LÀ HYPOTHESIS:
 *  - không gán đơn vị/tên trường (chỉ "giá trị thô ≈ a × km/h + b"),
 *  - không tạo adapter, không đổi LinkLevel, không bao giờ thành VERIFIED.
 * Chặn dương tính giả: cần đủ mẫu, tốc độ GPS phải đổi đủ rộng, loại trường tăng theo thời gian (bộ đếm/ODO),
 * và tương quan phải đạt ở CẢ HAI nửa dữ liệu (kiểm chứng chéo).
 */
object SpeedCrossCheck {
    enum class Encoding(val title: String) { U8("1 byte"), U16_LE("2 byte little-endian"), U16_BE("2 byte big-endian") }

    data class Match(val charUuid: String, val index: Int, val encoding: Encoding, val r: Double,
                     val slopePerKmh: Double, val intercept: Double, val pairs: Int, val maxKmh: Double,
                     /** Độ trễ GPS↔BLE tốt nhất (ms, dương = GPS đến sau) và sai số còn lại của hồi quy, quy ra km/h. */
                     val lagMs: Long = 0, val rmseKmh: Double = 0.0)

    const val MIN_PAIRS = 40
    const val MIN_SPEED_SPAN_MPS = 5.0   // ~18 km/h
    const val MIN_R = 0.95
    private const val MAX_GAP_MS = 2000L
    /** GPS điện thoại thường trễ so với gói BLE; thử các độ trễ này và giữ độ trễ cho tương quan cao nhất. */
    private val LAGS_MS = longArrayOf(0, -1000, 1000, -500, 500, -1500, 1500, -2000, 2000)

    fun find(frames: List<RawFrame>, gps: List<SpeedSample>): List<Match> {
        if (frames.size < MIN_PAIRS || gps.size < 5) return emptyList()
        val g = gps.sortedBy { it.timeMs }
        val out = ArrayList<Match>()
        for ((uuid, fs0) in frames.groupBy { it.charUuid }) {
            val fs = fs0.sortedBy { it.timeMs }
            // Quét MỌI độ dài gói đủ mẫu (0x6B 59 byte và 0x6C 143 byte đều có thể chứa tốc độ), không chỉ độ dài phổ biến nhất.
            for ((len, same) in fs.groupBy { it.bytes.size }) {
                if (same.size < MIN_PAIRS) continue
                for (lag in LAGS_MS) scan(uuid, len, same, g, lag, out)
            }
        }
        return out.sortedByDescending { it.r }
    }

    private fun scan(uuid: String, len: Int, same: List<RawFrame>, g: List<SpeedSample>, lag: Long, out: MutableList<Match>) {
        val paired = same.mapNotNull { f -> interpolate(g, f.timeMs + lag)?.let { f to it } }
        if (paired.size < MIN_PAIRS) return
        val speeds = paired.map { it.second }
        if (speeds.max() - speeds.min() < MIN_SPEED_SPAN_MPS) return
        val times = paired.map { it.first.timeMs.toDouble() }
        for (enc in Encoding.values()) {
            val width = if (enc == Encoding.U8) 1 else 2
            for (i in 0..len - width) {
                val xs = paired.map { value(it.first.bytes, i, enc) }
                if (xs.distinct().size < 4) continue
                if (abs(pearson(xs, times)) > 0.9) continue // tăng theo thời gian: bộ đếm/ODO, không phải tốc độ
                val r = pearson(xs, speeds)
                if (r < MIN_R) continue
                val half = xs.size / 2
                if (pearson(xs.take(half), speeds.take(half)) < 0.9 || pearson(xs.drop(half), speeds.drop(half)) < 0.9) continue
                val kmh = speeds.map { it * 3.6 }
                val (a, b) = fit(kmh, xs)
                // sai số quy ra km/h: (raw − (a·kmh+b)) / a
                val rmse = if (a == 0.0) 0.0 else sqrt(kmh.indices.sumOf { k -> val e = (xs[k] - (a * kmh[k] + b)) / a; e * e } / kmh.size)
                val m = Match(uuid, i, enc, r, a, b, paired.size, speeds.max() * 3.6, lag, rmse)
                val prev = out.firstOrNull { it.charUuid == uuid && it.index == i && it.encoding == enc }
                if (prev == null) out += m else if (m.r > prev.r) { out.remove(prev); out += m }
            }
        }
    }

    /** Nội suy tuyến tính giữa hai mẫu GPS kề nhau (chính xác hơn lấy mẫu gần nhất); null nếu khoảng trống > 2 s. */
    private fun interpolate(g: List<SpeedSample>, t: Long): Double? {
        var lo = 0; var hi = g.size - 1
        if (t < g[lo].timeMs || t > g[hi].timeMs) return g.minByOrNull { abs(it.timeMs - t) }?.takeIf { abs(it.timeMs - t) <= MAX_GAP_MS }?.speedMps
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (g[mid].timeMs <= t) lo = mid else hi = mid }
        val a = g[lo]; val b = g[hi]
        if (b.timeMs - a.timeMs > MAX_GAP_MS * 2 || (t - a.timeMs > MAX_GAP_MS && b.timeMs - t > MAX_GAP_MS)) return null
        if (b.timeMs == a.timeMs) return a.speedMps
        return a.speedMps + (b.speedMps - a.speedMps) * (t - a.timeMs).toDouble() / (b.timeMs - a.timeMs)
    }

    private fun value(b: ByteArray, i: Int, e: Encoding): Double {
        val x = b[i].toInt() and 0xFF
        return when (e) {
            Encoding.U8 -> x.toDouble()
            Encoding.U16_LE -> (x or ((b[i + 1].toInt() and 0xFF) shl 8)).toDouble()
            Encoding.U16_BE -> ((x shl 8) or (b[i + 1].toInt() and 0xFF)).toDouble()
        }
    }

    internal fun pearson(x: List<Double>, y: List<Double>): Double {
        val n = x.size; if (n < 3 || n != y.size) return 0.0
        val mx = x.average(); val my = y.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (k in 0 until n) { val dx = x[k] - mx; val dy = y[k] - my; sxy += dx * dy; sxx += dx * dx; syy += dy * dy }
        return if (sxx == 0.0 || syy == 0.0) 0.0 else sxy / sqrt(sxx * syy)
    }

    /** Hồi quy y = a·x + b với x = km/h, y = giá trị thô. */
    private fun fit(kmh: List<Double>, raw: List<Double>): Pair<Double, Double> {
        val mx = kmh.average(); val my = raw.average()
        var sxy = 0.0; var sxx = 0.0
        for (k in kmh.indices) { sxy += (kmh[k] - mx) * (raw[k] - my); sxx += (kmh[k] - mx) * (kmh[k] - mx) }
        val a = if (sxx == 0.0) 0.0 else sxy / sxx
        return a to (my - a * mx)
    }
}
