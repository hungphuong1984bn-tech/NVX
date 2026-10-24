package vn.nvxconnect.protocol

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Hiệu chỉnh một kênh ứng viên bằng ĐIỂM THAM CHIẾU THẬT do người dùng đọc từ thiết bị độc lập
 * (đồng hồ vòng tua của xe, đồng hồ vạn năng). Không có điểm tham chiếu thì không có hiệu chỉnh.
 * Kết quả vẫn KHÔNG phải LIVE_VERIFIED: chỉ "đủ điều kiện đề nghị xác minh" sau khi qua kiểm chứng độc lập (điểm lấy SAU khi chốt hệ số).
 */
data class CalPoint(val raw: Double, val ref: Double, val timeMs: Long)

data class CalFit(
    val id: String, val scale: Double, val offset: Double, val n: Int,
    val r2: Double, val rmse: Double, val maxAbsErr: Double, val looRmse: Double?, val refSpan: Double
) {
    fun apply(raw: Double) = scale * raw + offset
}

enum class CalQuality(val title: String) {
    NONE("Chưa hiệu chỉnh"), INSUFFICIENT("Chưa đủ điểm/độ phủ"), POOR("Sai số còn lớn"), GOOD("Đạt")
}

/** Ngưỡng chấp nhận theo từng kênh. */
data class CalSpec(
    val id: String, val unit: String, val minPoints: Int, val minSpan: Double, val maxRmse: Double, val minR2: Double,
    /** Hệ số hợp lý quanh giả thuyết ban đầu; ngoài khoảng này coi là nhập nhầm. */
    val scaleRange: ClosedFloatingPointRange<Double>, val maxSteadyRelSpread: Double, val maxOffset: Double
)

object CalSpecs {
    val RPM = CalSpec(YccCandidates.RPM_ID, "rpm", minPoints = 4, minSpan = 1500.0, maxRmse = 50.0, minR2 = 0.995,
        scaleRange = 0.8..1.25, maxSteadyRelSpread = 0.06, maxOffset = 300.0)
    val BATT = CalSpec(YccCandidates.BATT_ID, "V", minPoints = 4, minSpan = 1.0, maxRmse = 0.10, minR2 = 0.99,
        scaleRange = 0.04..0.12, maxSteadyRelSpread = 0.02, maxOffset = 2.0)
    val ALL = listOf(RPM, BATT).associateBy { it.id }
}

object Calibration {
    /** Hồi quy bình phương tối thiểu ref = scale·raw + offset. Cần ≥ 2 điểm và raw không đồng nhất. */
    fun fit(id: String, pts: List<CalPoint>): CalFit? {
        if (pts.size < 2) return null
        val ab = ols(pts) ?: return null
        val (a, b) = ab
        val res = pts.map { it.ref - (a * it.raw + b) }
        val rmse = sqrt(res.sumOf { it * it } / pts.size)
        val my = pts.map { it.ref }.average()
        val ssTot = pts.sumOf { (it.ref - my) * (it.ref - my) }
        val r2 = if (ssTot == 0.0) 0.0 else 1.0 - res.sumOf { it * it } / ssTot
        // Leave-one-out: sai số dự đoán điểm bị bỏ ra, phản ánh độ ổn định thật hơn RMSE trên chính mẫu.
        val loo = if (pts.size >= 4) sqrt(pts.indices.sumOf { i ->
            val rest = pts.filterIndexed { j, _ -> j != i }
            val (a2, b2) = ols(rest) ?: return@sumOf 1e12
            val e = pts[i].ref - (a2 * pts[i].raw + b2); e * e
        } / pts.size) else null
        return CalFit(id, a, b, pts.size, r2, rmse, res.maxOf { abs(it) }, loo, pts.maxOf { it.ref } - pts.minOf { it.ref })
    }

    private fun ols(pts: List<CalPoint>): Pair<Double, Double>? {
        val mx = pts.map { it.raw }.average(); val my = pts.map { it.ref }.average()
        val sxx = pts.sumOf { (it.raw - mx) * (it.raw - mx) }
        if (sxx == 0.0) return null
        val a = pts.sumOf { (it.raw - mx) * (it.ref - my) } / sxx
        return a to (my - a * mx)
    }

    fun quality(f: CalFit?, s: CalSpec): CalQuality = when {
        f == null -> CalQuality.NONE
        f.n < s.minPoints || f.refSpan < s.minSpan -> CalQuality.INSUFFICIENT
        f.scale !in s.scaleRange || abs(f.offset) > s.maxOffset -> CalQuality.POOR
        f.rmse > s.maxRmse || f.r2 < s.minR2 || (f.looRmse != null && f.looRmse > s.maxRmse * 1.5) -> CalQuality.POOR
        else -> CalQuality.GOOD
    }

    /**
     * Điểm bị nghi nhập sai: điểm có sai số leave-one-out lớn nhất, CHỈ khi vượt 2× ngưỡng RMSE và các điểm còn lại
     * khi bỏ nó đi khớp tốt (≤ ngưỡng). Nếu bỏ điểm đó mà phần còn lại vẫn xấu thì lỗi là do hệ số sai, không phải gõ nhầm → không gắn cờ.
     */
    fun suspects(pts: List<CalPoint>, s: CalSpec): List<Int> {
        if (pts.size < 4) return emptyList()
        var worst = -1; var worstErr = 0.0
        for (i in pts.indices) {
            val rest = pts.filterIndexed { j, _ -> j != i }
            val (a, b) = ols(rest) ?: continue
            val e = abs(pts[i].ref - (a * pts[i].raw + b))
            if (e > worstErr) { worstErr = e; worst = i }
        }
        if (worst < 0 || worstErr <= s.maxRmse * 2) return emptyList()
        val rest = pts.filterIndexed { j, _ -> j != worst }
        val f = fit(s.id, rest) ?: return emptyList()
        return if (f.rmse <= s.maxRmse) listOf(worst) else emptyList()
    }

    /**
     * Giá trị thô ĐẠI DIỆN của cửa sổ gần nhất, hoặc null nếu tín hiệu chưa ổn định (biên độ > ngưỡng tương đối).
     * Lấy trung vị để loại nhiễu, từ chối khi đang tăng/giảm ga: điểm tham chiếu chỉ có ý nghĩa khi giữ ổn định.
     */
    fun steadyRaw(window: List<Double>, maxRelSpread: Double, minSamples: Int = 8): Double? {
        if (window.size < minSamples) return null
        val s = window.sorted(); val med = s[s.size / 2]
        val trimmed = s.subList(s.size / 10, s.size - s.size / 10)   // bỏ 10% mỗi đầu
        val spread = trimmed.last() - trimmed.first()
        val denom = maxOf(abs(med), 1.0)
        return if (spread / denom <= maxRelSpread) med else null
    }
}

/** Kiểm chứng độc lập: điểm lấy SAU khi chốt hệ số, so với dự đoán. Sẵn sàng đề nghị xác minh khi đủ điểm đạt và không điểm nào vượt ngưỡng. */
class CalSession(val spec: CalSpec) {
    private val points = ArrayList<CalPoint>()
    private val validations = ArrayList<Double>()   // sai số tuyệt đối từng điểm kiểm chứng
    var fit: CalFit? = null; private set
    val pointList get() = points.toList()
    val validationErrors get() = validations.toList()

    fun load(f: CalFit?) { fit = f }

    fun addPoint(p: CalPoint): CalFit? {
        points += p; validations.clear()     // đổi hệ số thì kiểm chứng cũ mất hiệu lực
        fit = Calibration.fit(spec.id, points); return fit
    }
    fun removeLast() { if (points.isNotEmpty()) { points.removeAt(points.size - 1); validations.clear(); fit = Calibration.fit(spec.id, points) } }
    fun reset() { points.clear(); validations.clear(); fit = null }

    val quality get() = Calibration.quality(fit, spec)

    /** Ghi một điểm kiểm chứng; trả sai số tuyệt đối, hoặc null nếu chưa có hệ số đạt. */
    fun validate(raw: Double, ref: Double): Double? {
        val f = fit ?: return null
        if (quality != CalQuality.GOOD) return null
        val e = abs(f.apply(raw) - ref); validations += e; return e
    }

    val promotionReady get() = quality == CalQuality.GOOD && validations.size >= MIN_VALIDATIONS && validations.all { it <= spec.maxRmse * 2 }
    companion object { const val MIN_VALIDATIONS = 3 }
}

/**
 * Lọc tín hiệu hiển thị: loại giá trị bất khả thi (ngoài miền, nhảy quá nhanh) rồi lấy trung vị 5 mẫu.
 * Không bịa số: khi bị loại hoặc chưa đủ mẫu trả về null; mất kết nối thì [reset].
 */
class YccSignalFilter(private val min: Double, private val max: Double, private val maxJumpPerSample: Double, private val window: Int = 5) {
    private val buf = ArrayDeque<Double>(); private var last: Double? = null
    var rejected = 0L; private set
    fun push(raw: Double): Double? {
        if (!raw.isFinite() || raw < min || raw > max) { rejected++; return null }
        val l = last
        if (l != null && abs(raw - l) > maxJumpPerSample && buf.size >= 3) { rejected++; return null }
        last = raw; buf.addLast(raw); while (buf.size > window) buf.removeFirst()
        if (buf.size < 3) return null
        return buf.sorted()[buf.size / 2]
    }
    fun reset() { buf.clear(); last = null }
}
