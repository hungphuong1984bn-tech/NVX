package vn.nvxconnect.protocol

/** Lưu/đọc điểm tham chiếu hiệu chỉnh. Chỉ chứa số đo do người dùng nhập, không có định danh xe. */
interface CalibrationStore {
    fun load(): Map<String, List<CalPoint>>
    fun save(points: Map<String, List<CalPoint>>)
}

object CalCodec {
    /** Mỗi dòng: id=raw:ref:time,raw:ref:time */
    fun encode(m: Map<String, List<CalPoint>>): String =
        m.filterValues { it.isNotEmpty() }.entries.joinToString("\n") { (id, ps) -> id + "=" + ps.joinToString(",") { "${it.raw}:${it.ref}:${it.timeMs}" } }

    fun decode(s: String?): Map<String, List<CalPoint>> {
        if (s.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, List<CalPoint>>()
        for (line in s.lines()) {
            val eq = line.indexOf('='); if (eq <= 0) continue
            val id = line.substring(0, eq)
            val pts = line.substring(eq + 1).split(',').mapNotNull { t ->
                val p = t.split(':'); if (p.size != 3) return@mapNotNull null
                val raw = p[0].toDoubleOrNull(); val ref = p[1].toDoubleOrNull(); val tm = p[2].toLongOrNull()
                if (raw == null || ref == null || tm == null || !raw.isFinite() || !ref.isFinite()) null else CalPoint(raw, ref, tm)
            }
            if (id in CalSpecs.ALL && pts.isNotEmpty()) out[id] = pts   // bỏ id lạ/hỏng, không ném lỗi
        }
        return out
    }
}
