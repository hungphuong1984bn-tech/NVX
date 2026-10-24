package vn.nvxconnect.protocol

/**
 * Thống kê THỤ ĐỘNG trên gói notify đã bắt: độ dài, byte cố định/thay đổi, chu kỳ, giả thuyết checksum.
 * Đây KHÔNG phải giải mã: không gán nhãn RPM/nhiệt độ/mã lỗi. Dùng để chuẩn bị hồ sơ bằng chứng
 * (docs/PROTOCOL_EVIDENCE_TEMPLATE.md), đối chiếu với đồng hồ xe rồi mới được viết adapter.
 */
object FrameAnalyzer {
    data class PositionStat(val index: Int, val distinct: Int, val constant: Int?)
    data class CharReport(
        val charUuid: String, val frames: Int, val lengthCounts: Map<Int, Int>, val dominantLength: Int,
        val positions: List<PositionStat>, val medianIntervalMs: Long?, val checksumHypotheses: List<String>
    )

    fun analyze(frames: List<RawFrame>): List<CharReport> =
        frames.groupBy { it.charUuid }.map { (uuid, fs) -> analyzeOne(uuid, fs.sortedBy { it.timeMs }) }

    private fun analyzeOne(uuid: String, fs: List<RawFrame>): CharReport {
        val lengths = fs.groupingBy { it.bytes.size }.eachCount()
        val dom = lengths.maxByOrNull { it.value }!!.key
        val same = fs.filter { it.bytes.size == dom }
        val positions = (0 until dom).map { i ->
            val vals = same.map { it.bytes[i].toInt() and 0xFF }.toSet()
            PositionStat(i, vals.size, if (vals.size == 1) vals.first() else null)
        }
        val diffs = fs.zipWithNext { a, b -> b.timeMs - a.timeMs }.filter { it >= 0 }.sorted()
        val median = if (diffs.isEmpty()) null else diffs[diffs.size / 2]
        return CharReport(uuid, fs.size, lengths.toSortedMap(), dom, positions, median, checksums(same, dom))
    }

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun checksums(fs: List<RawFrame>, n: Int): List<String> {
        if (fs.size < 5 || n < 3) return emptyList()
        if (fs.map { it.bytes[n - 1] }.toSet().size < 2) return emptyList() // byte cuối không đổi: không kết luận được
        fun sum(b: ByteArray, from: Int) = (from until n - 1).sumOf { u(b, it) } and 0xFF
        fun xor(b: ByteArray, from: Int) = (from until n - 1).fold(0) { a, i -> a xor u(b, i) }
        val tests: List<Pair<String, (ByteArray) -> Int>> = listOf(
            "SUM8(0..n-2)" to { b: ByteArray -> sum(b, 0) },
            "SUM8(1..n-2)" to { b: ByteArray -> sum(b, 1) },
            "XOR8(0..n-2)" to { b: ByteArray -> xor(b, 0) },
            "XOR8(1..n-2)" to { b: ByteArray -> xor(b, 1) },
            "-SUM8(0..n-2)" to { b: ByteArray -> (-sum(b, 0)) and 0xFF }
        )
        return tests.filter { (_, f) -> fs.all { f(it.bytes) == u(it.bytes, n - 1) } }
            .map { "${it.first} khớp ${fs.size}/${fs.size} gói (giả thuyết, chưa xác minh)" }
    }

    /** Mẫu byte: "??" = thay đổi; chuỗi ASCII cố định dài >= 6 bị che thành "**" (có thể là ID/VIN). */
    fun template(r: CharReport): String {
        val n = r.positions.size
        val toks = ArrayList<String>(n)
        var i = 0
        while (i < n) {
            val c = r.positions[i].constant
            if (c == null) { toks += "??"; i++; continue }
            var j = i
            while (j < n && r.positions[j].constant?.let { it in 0x20..0x7E } == true) j++
            if (j - i >= 6) { repeat(j - i) { toks += "**" }; i = j } else { toks += "%02X".format(c); i++ }
        }
        return toks.joinToString(" ")
    }

    fun render(reports: List<CharReport>): String = buildString {
        appendLine("PHÂN TÍCH THỤ ĐỘNG (thống kê, KHÔNG phải giải mã; không gán nhãn RPM/nhiệt độ/mã lỗi)")
        if (reports.isEmpty()) appendLine("Chưa có gói nào.")
        reports.forEach { r ->
            appendLine("- ${r.charUuid}: ${r.frames} gói, độ dài ${r.lengthCounts}, chu kỳ trung vị ${r.medianIntervalMs ?: "?"} ms")
            appendLine("  mẫu: ${template(r)}")
            val varying = r.positions.filter { it.constant == null }
            if (varying.isNotEmpty()) appendLine("  vị trí thay đổi (số giá trị khác nhau): " + varying.joinToString { "${it.index}(${it.distinct})" })
            r.checksumHypotheses.forEach { appendLine("  checksum? $it") }
        }
    }
}
