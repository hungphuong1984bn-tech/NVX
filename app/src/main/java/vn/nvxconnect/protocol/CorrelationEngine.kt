package vn.nvxconnect.protocol

/**
 * Hỗ trợ TƯƠNG QUAN cho người khảo sát: bạn gắn nhãn một hành động vừa làm trên xe thật (vặn ga, phanh,
 * bật xi-nhan...), công cụ so các gói NHẬN ĐƯỢC ngay trước và ngay sau mỗi lần gắn nhãn, rồi liệt kê những
 * vị trí byte đổi giá trị nhiều lần trùng với hành động đó.
 *
 * Đây KHÔNG phải giải mã tự động và KHÔNG xác minh giao thức. Trùng hợp không phải bằng chứng: một vị trí
 * byte có thể đổi vì lý do khác (thời gian, nhiệt độ pin, bộ đếm gói...). Không trường nào ở đây được gán
 * đơn vị, tên, hay dùng để hiển thị số liệu xe. Chỉ người khảo sát — bằng cách tự nhìn đồng hồ xe thật và
 * lặp lại ở nhiều mức độ khác nhau — mới có thể kết luận một vị trí là thật, rồi điền
 * docs/PROTOCOL_EVIDENCE_TEMPLATE.md và viết adapter thủ công qua AdapterRegistry.register.
 */
object CorrelationEngine {
    data class Tag(val label: String, val atMs: Long)

    data class Candidate(
        val charUuid: String,
        val label: String,
        val byteIndex: Int,
        val trials: Int,
        val changedTrials: Int,
        val beforeValues: List<Int>,
        val afterValues: List<Int>
    ) {
        /** Tỉ lệ lần đổi trên tổng số lần thử cùng nhãn. KHÔNG phải xác suất đúng, chỉ để xếp hạng gợi ý. */
        val hitRate: Double get() = if (trials == 0) 0.0 else changedTrials.toDouble() / trials
    }

    private const val WINDOW_MS = 1500L
    private const val MIN_TRIALS = 2

    fun correlate(frames: List<RawFrame>, tags: List<Tag>): List<Candidate> {
        if (tags.isEmpty() || frames.isEmpty()) return emptyList()
        val byChar = frames.groupBy { it.charUuid }
        val byLabel = tags.groupBy { it.label }
        val out = ArrayList<Candidate>()
        byChar.forEach { (uuid, fs) ->
            val sorted = fs.sortedBy { it.timeMs }
            val dom = sorted.groupingBy { it.bytes.size }.eachCount().maxByOrNull { it.value }?.key ?: return@forEach
            val same = sorted.filter { it.bytes.size == dom }
            if (same.size < 4 || dom <= 0) return@forEach
            byLabel.forEach { (label, occurrences) ->
                val trialsData = occurrences.mapNotNull { tag ->
                    val before = same.lastOrNull { it.timeMs in (tag.atMs - WINDOW_MS)..tag.atMs }
                    val after = same.firstOrNull { it.timeMs in tag.atMs..(tag.atMs + WINDOW_MS) }
                    if (before != null && after != null && before !== after) before to after else null
                }
                if (trialsData.size < MIN_TRIALS) return@forEach
                for (i in 0 until dom) {
                    val changed = trialsData.count { (b, a) -> (b.bytes[i].toInt() and 0xFF) != (a.bytes[i].toInt() and 0xFF) }
                    val threshold = (trialsData.size * 0.6).coerceAtLeast(MIN_TRIALS.toDouble())
                    if (changed >= threshold) {
                        out += Candidate(uuid, label, i, trialsData.size, changed,
                            trialsData.map { it.first.bytes[i].toInt() and 0xFF },
                            trialsData.map { it.second.bytes[i].toInt() and 0xFF })
                    }
                }
            }
        }
        return out.sortedByDescending { it.hitRate }
    }

    fun render(cands: List<Candidate>, tagCountByLabel: Map<String, Int>): String = buildString {
        appendLine("TƯƠNG QUAN HÀNH ĐỘNG ↔ BYTE THAY ĐỔI — GỢI Ý, CHƯA XÁC MINH")
        if (tagCountByLabel.isEmpty()) {
            appendLine("Chưa gắn nhãn hành động nào. Bấm một nút hành động khi bạn vừa làm việc đó trên xe thật, lặp lại 3-5 lần mỗi loại.")
            return@buildString
        }
        tagCountByLabel.forEach { (label, n) -> if (n < MIN_TRIALS) appendLine("- \"$label\": mới $n lần, cần tối thiểu $MIN_TRIALS lần để xét.") }
        if (cands.isEmpty()) { appendLine("Chưa thấy vị trí byte nào đổi trùng với hành động đã gắn nhãn đủ số lần."); return@buildString }
        cands.groupBy { it.label }.forEach { (label, list) ->
            appendLine("- \"$label\":")
            list.take(5).forEach { c ->
                appendLine("    ${c.charUuid.take(8)} byte ${c.byteIndex}: đổi ${c.changedTrials}/${c.trials} lần " +
                    "(trước ${c.beforeValues} → sau ${c.afterValues})")
            }
        }
        appendLine()
        appendLine("Trùng hợp không phải bằng chứng. Trước khi coi là thật: (1) lặp lại ở nhiều mức độ thao tác khác nhau " +
            "(ga nhẹ/mạnh, phanh nhẹ/gấp...) và xem giá trị có đổi theo mức độ một cách hợp lý; (2) đối chiếu trực tiếp với " +
            "đồng hồ xe hoặc phép đo độc lập; (3) thử lại ở phiên kết nối khác, tốt nhất trên xe/CCU khác cùng đời; " +
            "(4) điền docs/PROTOCOL_EVIDENCE_TEMPLATE.md. App sẽ không tự đặt tên trường hay tự chuyển sang VERIFIED.")
    }
}
