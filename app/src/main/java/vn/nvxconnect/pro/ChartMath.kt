package vn.nvxconnect.pro

import kotlin.math.max
import kotlin.math.min

/** Một mẫu thật đã nhận. Không bao giờ do app sinh ra hay nội suy. */
data class Sample(val timeMs: Long, val value: Double)

data class SeriesStats(val count: Int, val min: Double?, val max: Double?, val mean: Double?, val firstMs: Long?, val lastMs: Long?) {
    companion object { val EMPTY = SeriesStats(0, null, null, null, null, null) }
}

/** Một đoạn liên tục của chuỗi: giữa hai đoạn là KHOẢNG TRỐNG (thiếu gói), không được vẽ nối. */
data class Segment(val samples: List<Sample>)

object ChartMath {
    /** Các cửa sổ thời gian người dùng chọn. 0 = toàn bộ dữ liệu đang giữ. */
    val WINDOWS_MS = listOf(30_000L, 60_000L, 5 * 60_000L, 15 * 60_000L, 0L)
    fun windowLabel(ms: Long) = when (ms) { 0L -> "Tất cả"; 30_000L -> "30 giây"; 60_000L -> "1 phút"; else -> "${ms / 60_000} phút" }

    /** Mẫu có timeMs ∈ [fromMs, toMs]. Giữ nguyên thứ tự. */
    fun window(s: List<Sample>, fromMs: Long, toMs: Long): List<Sample> = s.filter { it.timeMs in fromMs..toMs }

    fun stats(s: List<Sample>): SeriesStats {
        val v = s.filter { it.value.isFinite() }
        if (v.isEmpty()) return SeriesStats.EMPTY
        return SeriesStats(v.size, v.minOf { it.value }, v.maxOf { it.value }, v.sumOf { it.value } / v.size, v.minOf { it.timeMs }, v.maxOf { it.timeMs })
    }

    /**
     * Chia thành các đoạn liên tục. Hai mẫu cách nhau > [gapMs] là một khoảng trống (không nối). Mẫu không hữu hạn bị loại
     * khỏi đoạn và cũng cắt đoạn (mẫu lỗi không được che bằng nội suy).
     */
    fun segments(s: List<Sample>, gapMs: Long): List<Segment> {
        val out = ArrayList<Segment>(); var cur = ArrayList<Sample>()
        fun flush() { if (cur.isNotEmpty()) { out += Segment(cur); cur = ArrayList() } }
        var prev: Sample? = null
        for (x in s.sortedBy { it.timeMs }) {
            if (!x.value.isFinite()) { flush(); prev = null; continue }
            if (prev != null && x.timeMs - prev.timeMs > gapMs) flush()
            cur += x; prev = x
        }
        flush(); return out
    }

    /** Số khoảng trống (chỗ đứt giữa các đoạn) và tổng thời gian trống. */
    fun gaps(s: List<Sample>, gapMs: Long): Pair<Int, Long> {
        val seg = segments(s, gapMs); var total = 0L
        for (i in 1 until seg.size) total += seg[i].samples.first().timeMs - seg[i - 1].samples.last().timeMs
        return max(0, seg.size - 1) to total
    }

    /**
     * Giảm số điểm để vẽ nhanh, KHÔNG làm mất đỉnh: mỗi nhóm giữ mẫu min và max theo thời gian. Không tạo giá trị mới.
     * Kết quả luôn là tập con của đầu vào, theo thứ tự thời gian.
     */
    fun downsample(s: List<Sample>, maxPoints: Int): List<Sample> {
        if (maxPoints < 4 || s.size <= maxPoints) return s
        val buckets = maxPoints / 2; val size = s.size.toDouble() / buckets
        val out = ArrayList<Sample>(maxPoints)
        for (b in 0 until buckets) {
            val from = (b * size).toInt(); val to = min(s.size, ((b + 1) * size).toInt().coerceAtLeast(from + 1))
            if (from >= s.size) break
            val part = s.subList(from, to)
            val lo = part.minByOrNull { it.value }!!; val hi = part.maxByOrNull { it.value }!!
            if (lo === hi) out += lo else if (lo.timeMs <= hi.timeMs) { out += lo; out += hi } else { out += hi; out += lo }
        }
        return out
    }

    /** Mẫu gần thời điểm [timeMs] nhất (cho tooltip), chỉ khi cách không quá [toleranceMs]; ngược lại null. */
    fun nearest(s: List<Sample>, timeMs: Long, toleranceMs: Long): Sample? =
        s.minByOrNull { kotlin.math.abs(it.timeMs - timeMs) }?.takeIf { kotlin.math.abs(it.timeMs - timeMs) <= toleranceMs }

    /** Vùng dữ liệu đã chọn [a,b] (thứ tự bất kỳ): thống kê chỉ trên mẫu thật trong vùng. */
    fun selectionStats(s: List<Sample>, aMs: Long, bMs: Long): SeriesStats = stats(window(s, min(aMs, bMs), max(aMs, bMs)))
}

/**
 * Bộ nhớ lịch sử có giới hạn theo từng chuỗi. Chỉ nhận mẫu thật qua [add]. Giới hạn theo số mẫu; mẫu cũ nhất bị bỏ và ĐƯỢC ĐẾM
 * ([dropped]) để giao diện nói thật về việc cắt dữ liệu. Có giới hạn tần suất ghi vào lịch sử ([minIntervalMs]) tách khỏi tần suất log gói.
 */
class SeriesStore(private val maxPerSeries: Int = 3_600, private val minIntervalMs: Long = 250L) {
    private val data = LinkedHashMap<String, ArrayDeque<Sample>>()
    private val droppedBy = HashMap<String, Long>()
    private val skippedBy = HashMap<String, Long>()

    @Synchronized fun add(key: String, timeMs: Long, value: Double): Boolean {
        if (!value.isFinite()) return false
        val q = data.getOrPut(key) { ArrayDeque() }
        val last = q.lastOrNull()
        if (last != null) {
            if (timeMs <= last.timeMs) return false                       // trùng/ngược thời gian: bỏ
            if (timeMs - last.timeMs < minIntervalMs) { skippedBy[key] = (skippedBy[key] ?: 0) + 1; return false }
        }
        q.addLast(Sample(timeMs, value))
        while (q.size > maxPerSeries) { q.removeFirst(); droppedBy[key] = (droppedBy[key] ?: 0) + 1 }
        return true
    }
    @Synchronized fun series(key: String): List<Sample> = data[key]?.toList() ?: emptyList()
    @Synchronized fun keys(): List<String> = data.keys.toList()
    @Synchronized fun dropped(key: String): Long = droppedBy[key] ?: 0
    @Synchronized fun skipped(key: String): Long = skippedBy[key] ?: 0
    @Synchronized fun clear() { data.clear(); droppedBy.clear(); skippedBy.clear() }
    @Synchronized fun clear(key: String) { data.remove(key); droppedBy.remove(key); skippedBy.remove(key) }
}
