package vn.nvxconnect.pro

import vn.nvxconnect.telemetry.PayloadFlag
import vn.nvxconnect.telemetry.PayloadRecord
import vn.nvxconnect.telemetry.PayloadSource

enum class PacketSort(val title: String) { NEWEST("Mới nhất"), OLDEST("Cũ nhất"), LONGEST("Dài nhất"), SHORTEST("Ngắn nhất") }

/** Bộ lọc cho Live Packet Monitor / Session Logs. Trường rỗng/null = không lọc. */
data class PacketQuery(
    val text: String = "",
    val uuid: String = "",
    val minLen: Int? = null,
    val maxLen: Int? = null,
    val source: PayloadSource? = null,
    val flag: PayloadFlag? = null,
    val sessionId: String? = null,
    val sort: PacketSort = PacketSort.NEWEST
) {
    val active get() = text.isNotBlank() || uuid.isNotBlank() || minLen != null || maxLen != null || source != null || flag != null || sessionId != null
}

object PacketFilter {
    /** So khớp HEX không phân biệt khoảng trắng/hoa thường: "6b 0a" khớp "6B0A". */
    private fun norm(s: String) = s.filter { !it.isWhitespace() && it != ':' && it != ',' }.lowercase()

    fun matches(r: PayloadRecord, q: PacketQuery): Boolean {
        val len = r.length
        if (q.minLen != null && len < q.minLen) return false
        if (q.maxLen != null && len > q.maxLen) return false
        if (q.source != null && r.source != q.source) return false
        if (q.flag != null && q.flag !in r.flags) return false
        if (q.sessionId != null && r.sessionId != q.sessionId) return false
        if (q.uuid.isNotBlank()) {
            val u = q.uuid.trim().lowercase()
            if (!(r.charUuid?.contains(u) == true || r.serviceUuid?.contains(u) == true)) return false
        }
        if (q.text.isNotBlank()) {
            val t = q.text.trim()
            val inHex = norm(r.hex).contains(norm(t))
            val inMeta = r.charUuid?.contains(t, ignoreCase = true) == true || r.source.name.contains(t, ignoreCase = true) || r.sessionId.equals(t, ignoreCase = true)
            if (!inHex && !inMeta) return false
        }
        return true
    }

    fun apply(all: List<PayloadRecord>, q: PacketQuery, limit: Int = Int.MAX_VALUE): List<PayloadRecord> {
        val f = all.filter { matches(it, q) }
        val sorted = when (q.sort) {
            PacketSort.NEWEST -> f.sortedByDescending { it.seq }
            PacketSort.OLDEST -> f.sortedBy { it.seq }
            PacketSort.LONGEST -> f.sortedWith(compareByDescending<PayloadRecord> { it.length }.thenByDescending { it.seq })
            PacketSort.SHORTEST -> f.sortedWith(compareBy<PayloadRecord> { it.length }.thenByDescending { it.seq })
        }
        return if (limit < sorted.size) sorted.take(limit) else sorted
    }

    /** Các độ dài gói đang có (để làm chip lọc), tăng dần, kèm số lượng. */
    fun lengthHistogram(all: List<PayloadRecord>): List<Pair<Int, Int>> =
        all.groupingBy { it.length }.eachCount().toList().sortedBy { it.first }
}
