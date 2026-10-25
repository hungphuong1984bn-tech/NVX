package vn.nvxconnect.pro

/** Lưu/nạp phiên Telemetry Center dạng CSV: series,time_ms,value. Nạp lại chỉ để PHÁT LẠI (nhãn REPLAY), không phải dữ liệu trực tiếp. */
object SeriesCsv {
    const val HEADER = "series,time_ms,value"
    data class Result(val series: Map<String, List<Sample>>, val warnings: List<String>)

    fun encode(series: Map<String, List<Sample>>): String = buildString {
        appendLine(HEADER)
        for ((k, list) in series) for (s in list) if (s.value.isFinite()) appendLine("${k.replace(',', '_')},${s.timeMs},${s.value}")
    }

    fun decode(text: String, maxLines: Int = 500_000): Result {
        val out = LinkedHashMap<String, MutableList<Sample>>(); val warn = ArrayList<String>()
        var n = 0
        for (line in text.lineSequence()) {
            val l = line.trim(); if (l.isEmpty() || l.equals(HEADER, true)) continue
            if (++n > maxLines) { warn += "Quá $maxLines dòng: phần còn lại bị bỏ"; break }
            val p = l.split(',')
            val t = p.getOrNull(1)?.toLongOrNull(); val v = p.getOrNull(2)?.toDoubleOrNull()
            if (p.size != 3 || p[0].isBlank() || t == null || v == null || !v.isFinite()) { if (warn.size < 20) warn += "Bỏ dòng $n không hợp lệ"; continue }
            out.getOrPut(p[0]) { ArrayList() } += Sample(t, v)
        }
        return Result(out.mapValues { e -> e.value.sortedBy { it.timeMs } }, warn)
    }
}
