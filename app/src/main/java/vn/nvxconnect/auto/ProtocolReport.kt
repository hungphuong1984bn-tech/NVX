package vn.nvxconnect.auto

import vn.nvxconnect.bluetooth.GattServiceInfo
import vn.nvxconnect.protocol.AdapterRegistry
import vn.nvxconnect.protocol.CorrelationEngine
import vn.nvxconnect.protocol.FrameAnalyzer
import vn.nvxconnect.protocol.RawFrame
import kotlin.math.ln

/**
 * Năm nhãn bằng chứng (UNKNOWN là "chưa biết", không phải mức thấp nhất của bằng chứng; tương ứng nhãn UNSUPPORTED
 * "chưa có bằng chứng đủ dùng" trong đặc tả — giữ tên UNKNOWN để tương thích mã/test/tệp xuất cũ):
 *  - OBSERVED: số liệu đo được trực tiếp từ log (độ dài, byte cố định, chu kỳ...). Đúng với log, không nói về ý nghĩa.
 *  - HYPOTHESIS: suy đoán từ mẫu (checksum, bộ đếm, trùng hợp với hành động). Cần kiểm chứng độc lập.
 *  - VERIFIED: CHỈ khi AdapterRegistry có adapter đã đăng ký kèm hồ sơ bằng chứng (xe thật, kết quả thử, mẫu gói).
 *    Phân tích tự động trong app KHÔNG BAO GIỜ tạo ra VERIFIED.
 *  - UNKNOWN: chưa biết hoặc chưa đủ dữ liệu; kèm điều kiện cần để biết.
 *  - ENCRYPTED_OR_AUTHENTICATED: có DẤU HIỆU dữ liệu được bảo vệ hoặc phụ thuộc xác thực (thiết bị đòi ghép đôi/mã hóa, hoặc entropy rất cao)
 *    mà phương pháp hiện tại không giải mã được. Chỉ là dấu hiệu: entropy cao cũng có thể là dữ liệu nén. Không bao giờ dẫn tới VERIFIED.
 */
enum class EvidenceLevel { OBSERVED, HYPOTHESIS, VERIFIED, UNKNOWN, ENCRYPTED_OR_AUTHENTICATED }

data class Finding(val level: EvidenceLevel, val title: String, val detail: String? = null)

data class ReportInput(
    val frames: List<RawFrame>,
    val tags: List<CorrelationEngine.Tag>,
    val services: List<GattServiceInfo>,
    val registry: AdapterRegistry,
    /** Thiết bị đã từ chối vì cần ghép đôi/xác thực (GATT status 5/15) trong phiên này. */
    val authRequired: Boolean,
    /** Gói theo từng phiên thử nghiệm do người dùng đánh dấu (nhãn → gói). */
    val segments: Map<String, List<RawFrame>> = emptyMap(),
    /** Tốc độ GPS điện thoại cùng thời gian với gói (sự thật nền độc lập). Rỗng = không đối chiếu được. */
    val gpsSpeeds: List<vn.nvxconnect.protocol.SpeedSample> = emptyList()
)

object ProtocolReport {
    private const val NEED_DOCS = "Cần tài liệu giao thức của Yamaha hoặc một cơ chế tích hợp được Yamaha cho phép. " +
        "App không đoán PIN/khóa, không brute-force, không phát lại gói, không vượt xác thực."

    fun build(i: ReportInput): List<Finding> {
        val out = ArrayList<Finding>()

        val adapter = i.registry.find(i.services)
        if (adapter != null) {
            val e = adapter.evidence
            out += Finding(EvidenceLevel.VERIFIED, "Giao thức đã xác minh: ${e.id} (${e.protocolVersion})",
                "Hồ sơ bằng chứng: xe ${e.testedOnVehicle}, kết quả ${e.testResult}. Đăng ký thủ công qua AdapterRegistry, không phải do app tự kết luận.")
        } else out += Finding(EvidenceLevel.UNKNOWN, "Giao thức xe: chưa có adapter đã xác minh",
            "Mọi gói đều RAW/UNKNOWN. Để có VERIFIED cần: bằng chứng lặp lại, kiểm thử độc lập trên xe thật, và tài liệu/hành vi giao thức đủ rõ " +
                "(docs/PROTOCOL_EVIDENCE_TEMPLATE.md), rồi đăng ký adapter thủ công.")

        if (i.authRequired) out += Finding(EvidenceLevel.ENCRYPTED_OR_AUTHENTICATED, "Thiết bị yêu cầu xác thực trước khi cung cấp dữ liệu", NEED_DOCS)

        if (i.frames.isEmpty()) {
            out += Finding(EvidenceLevel.UNKNOWN, "Chưa có gói NOTIFY/INDICATE hay dữ liệu đọc hợp lệ",
                "Kết nối GATT, rồi bấm \"Nghe\" ở characteristic có NOTIFY/INDICATE trong Cây GATT. Không có dữ liệu thì không có gì để phân tích.")
            return out
        }

        val byChar = i.frames.groupBy { it.charUuid }
        for ((uuid, fs0) in byChar) {
            val fs = fs0.sortedBy { it.timeMs }
            val rep = FrameAnalyzer.analyze(fs).first()
            val short = uuid.take(8)
            out += Finding(EvidenceLevel.OBSERVED, "$short: ${fs.size} gói, độ dài ${rep.lengthCounts}, chu kỳ trung vị ${rep.medianIntervalMs ?: "?"} ms",
                "Mẫu (?? = đổi, ** = chuỗi ASCII dài đã che): ${FrameAnalyzer.template(rep)}")

            val top = fs.groupingBy { HexKey.of(it.bytes) }.eachCount().maxByOrNull { it.value }
            if (top != null && top.value >= 3 && fs.size >= 5)
                out += Finding(EvidenceLevel.OBSERVED, "$short: một gói giống hệt lặp ${top.value}/${fs.size} lần", "Có thể là gói nhịp/heartbeat hoặc trạng thái không đổi; chưa nói được ý nghĩa.")

            val constPrefix = rep.positions.takeWhile { it.constant != null }
            if (constPrefix.isNotEmpty() && rep.frames >= 3)
                out += Finding(EvidenceLevel.OBSERVED, "$short: ${constPrefix.size} byte đầu luôn cố định",
                    "Có thể là tiêu đề/magic của khung; mới chỉ là quan sát trên ${rep.frames} gói.")

            val same = fs.filter { it.bytes.size == rep.dominantLength }
            val n = rep.dominantLength
            if (same.size >= 5 && n >= 2) {
                val lenField = listOf(0, -1, -2).firstOrNull { d -> same.all { (it.bytes[0].toInt() and 0xFF) == n + d } }
                if (lenField != null) out += Finding(EvidenceLevel.HYPOTHESIS, "$short: byte 0 có thể là độ dài gói", "Byte 0 = ${n + lenField} trên ${same.size}/${same.size} gói cùng độ dài $n.")
            }
            rep.checksumHypotheses.forEach { out += Finding(EvidenceLevel.HYPOTHESIS, "$short: $it", "Phải kiểm chứng độc lập trên gói mới, không dùng lại chính các gói này.") }

            val counters = counterPositions(same, n)
            counters.forEach { out += Finding(EvidenceLevel.HYPOTHESIS, "$short: byte $it có thể là bộ đếm gói", "Tăng thêm 1 (mod 256) ở hầu hết cặp gói liên tiếp.") }

            val varying = rep.positions.filter { it.constant == null }.map { it.index }
            val correlated = CorrelationEngine.correlate(fs, i.tags).filter { it.charUuid == uuid }
            correlated.groupBy { it.label }.forEach { (label, list) ->
                out += Finding(EvidenceLevel.HYPOTHESIS, "$short: byte ${list.take(4).joinToString { it.byteIndex.toString() }} đổi trùng hành động \"$label\"",
                    "Trùng hợp không phải bằng chứng: cần lặp ở nhiều mức độ thao tác và đối chiếu đồng hồ xe.")
            }
            val explained = (counters + correlated.map { it.byteIndex }).toSet()
            val unexplained = varying.filterNot { it in explained }
            if (unexplained.isNotEmpty())
                out += Finding(EvidenceLevel.UNKNOWN, "$short: ${unexplained.size} vị trí thay đổi chưa giải thích được", "Vị trí: ${unexplained.take(12).joinToString()}. Chưa gán ý nghĩa/đơn vị nào.")
        }

        sequencePattern(i.frames)?.let { out += it }

        val speedMatches = vn.nvxconnect.protocol.SpeedCrossCheck.find(i.frames, i.gpsSpeeds)
        speedMatches.take(3).forEach { m ->
            out += Finding(EvidenceLevel.HYPOTHESIS,
                "${m.charUuid.take(8)}: byte ${m.index} (${m.encoding.title}) biến thiên theo tốc độ GPS (r=%.3f, ${m.pairs} cặp mẫu, tối đa %.0f km/h)".format(m.r, m.maxKmh),
                "Giá trị thô ≈ %.2f × km/h + %.1f. Qua kiểm chứng chéo hai nửa dữ liệu, nhưng CHƯA phải xác minh: chưa biết đơn vị, chưa kiểm trên nhiều lần chạy/xe. Không dùng để hiển thị tốc độ xe.".format(m.slopePerKmh, m.intercept))
        }
        if (speedMatches.isEmpty() && i.frames.size >= vn.nvxconnect.protocol.SpeedCrossCheck.MIN_PAIRS)
            out += Finding(EvidenceLevel.UNKNOWN, "Chưa tìm thấy trường nào khớp tốc độ GPS",
                "Cần bật GPS và chạy xe với tốc độ thay đổi (ít nhất ~18 km/h chênh lệch) trong lúc đang Nghe, và ≥ ${vn.nvxconnect.protocol.SpeedCrossCheck.MIN_PAIRS} gói.")

        val pooled = i.frames.sumOf { it.bytes.size }
        if (pooled >= 1024) {
            val h = entropyBits(i.frames)
            if (h >= 7.5) out += Finding(EvidenceLevel.ENCRYPTED_OR_AUTHENTICATED, "Dấu hiệu dữ liệu được bảo vệ: entropy rất cao (%.2f bit/byte)".format(h),
                "Có thể đã mã hóa, hoặc chỉ là dữ liệu nén; cũng có thể phụ thuộc xác thực/phiên. Phân tích byte vô nghĩa nếu đúng là mã hóa. $NEED_DOCS")
        }

        if (i.segments.size >= 2) out += compareSegments(i.segments)
        return out
    }

    /** Vị trí byte tăng +1 (mod 256) ở >= 90% cặp gói liên tiếp, cần >= 6 gói. */
    internal fun counterPositions(same: List<RawFrame>, n: Int): List<Int> {
        if (same.size < 6) return emptyList()
        val s = same.sortedBy { it.timeMs }
        return (0 until n).filter { p ->
            val pairs = s.zipWithNext()
            pairs.count { (a, b) -> ((b.bytes[p].toInt() and 0xFF) - (a.bytes[p].toInt() and 0xFF)) and 0xFF == 1 } >= pairs.size * 0.9
        }
    }

    internal fun entropyBits(frames: List<RawFrame>): Double {
        val counts = IntArray(256); var total = 0
        frames.forEach { f -> f.bytes.forEach { counts[it.toInt() and 0xFF]++; total++ } }
        if (total == 0) return 0.0
        return counts.filter { it > 0 }.sumOf { c -> val p = c.toDouble() / total; -p * ln(p) / ln(2.0) }
    }

    /** Chuỗi kênh lặp lại (A→B ≥ 3 lần). App không gửi lệnh nên đây là thứ tự notify tự phát, KHÔNG phải cặp yêu cầu/phản hồi. */
    private fun sequencePattern(frames: List<RawFrame>): Finding? {
        val s = frames.sortedBy { it.timeMs }
        if (s.map { it.charUuid }.distinct().size < 2) return null
        val best = s.zipWithNext().filter { (a, b) -> a.charUuid != b.charUuid }
            .groupingBy { it.first.charUuid.take(8) + " → " + it.second.charUuid.take(8) }.eachCount().maxByOrNull { it.value } ?: return null
        if (best.value < 3) return null
        return Finding(EvidenceLevel.OBSERVED, "Chuỗi kênh lặp lại: ${best.key} (${best.value} lần)",
            "Chỉ là thứ tự nhận được. App không gửi lệnh dò, nên không biết đây có phải cặp yêu cầu/phản hồi hay không.")
    }

    /** So sánh các phiên người dùng đánh dấu: vị trí byte có tập giá trị tách rời giữa hai phiên. */
    internal fun compareSegments(segments: Map<String, List<RawFrame>>): List<Finding> {
        val out = ArrayList<Finding>()
        val names = segments.keys.toList()
        val uuids = segments.values.flatten().map { it.charUuid }.distinct()
        for (u in uuids) {
            val perSeg = names.associateWith { segments.getValue(it).filter { f -> f.charUuid == u } }.filterValues { it.size >= 3 }
            if (perSeg.size < 2) continue
            val dom = perSeg.values.flatten().groupingBy { it.bytes.size }.eachCount().maxByOrNull { it.value }!!.key
            val vals = perSeg.mapValues { (_, fs) -> fs.filter { it.bytes.size == dom } }.filterValues { it.size >= 3 }
            if (vals.size < 2) continue
            val keys = vals.keys.toList()
            val diffs = ArrayList<String>()
            for (p in 0 until dom) {
                val sets = keys.map { k -> vals.getValue(k).map { it.bytes[p].toInt() and 0xFF }.toSet() }
                for (a in keys.indices) for (b in a + 1 until keys.size)
                    if (sets[a].isNotEmpty() && sets[b].isNotEmpty() && sets[a].intersect(sets[b]).isEmpty())
                        diffs += "byte $p: \"${keys[a]}\" ${sets[a].take(4).map { "%02X".format(it) }} ≠ \"${keys[b]}\" ${sets[b].take(4).map { "%02X".format(it) }}"
            }
            if (diffs.isNotEmpty()) out += Finding(EvidenceLevel.OBSERVED, "${u.take(8)}: ${diffs.size} khác biệt giữa các phiên đã đánh dấu",
                diffs.take(6).joinToString("\n") + "\nChỉ nói là khác nhau, không nói ý nghĩa; cần lặp lại ở phiên độc lập.")
        }
        return out
    }

    fun render(findings: List<Finding>): String = buildString {
        appendLine("BÁO CÁO GIAO THỨC (OBSERVED / HYPOTHESIS / VERIFIED / UNKNOWN / ENCRYPTED_OR_AUTHENTICATED)")
        findings.forEach { f ->
            appendLine("[${f.level.name}] ${f.title}")
            f.detail?.let { d -> d.lines().forEach { appendLine("    $it") } }
        }
    }

    private object HexKey { fun of(b: ByteArray) = b.joinToString("") { "%02x".format(it) } }
}
