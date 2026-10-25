package vn.nvxconnect.pro

/** Kết quả so sánh hai khung HEX theo từng vị trí byte. */
data class HexDiffResult(
    val a: List<Int>, val b: List<Int>,
    /** Vị trí (0-based) có byte khác nhau, hoặc chỉ có ở một bên (do độ dài khác nhau). */
    val diffIndices: List<Int>,
    val lengthDiffers: Boolean
) {
    val identical get() = diffIndices.isEmpty() && !lengthDiffers
    val sameLengthDiffCount get() = diffIndices.count { it < minOf(a.size, b.size) }
}

object HexDiff {
    /** Đọc chuỗi HEX (cách nhau bằng khoảng trắng, hoặc liền nhau, cho phép 0x). Trả null nếu có ký tự không phải HEX. */
    fun parse(text: String): List<Int>? {
        val t = text.trim().replace("0x", "", ignoreCase = true)
        if (t.isEmpty()) return emptyList()
        val tokens: List<String> = if (t.any { it.isWhitespace() || it == ',' || it == ':' || it == '-' }) t.split(Regex("[\\s,:\\-]+")).filter { it.isNotEmpty() }
        else { if (t.length % 2 != 0) return null; t.chunked(2) }
        val out = ArrayList<Int>(tokens.size)
        for (tk in tokens) { if (tk.length !in 1..2) return null; out += tk.toIntOrNull(16) ?: return null }
        return out
    }

    fun diff(a: List<Int>, b: List<Int>): HexDiffResult {
        val n = maxOf(a.size, b.size); val d = ArrayList<Int>()
        for (i in 0 until n) { val x = a.getOrNull(i); val y = b.getOrNull(i); if (x != y) d += i }
        return HexDiffResult(a, b, d, a.size != b.size)
    }

    fun format(bytes: List<Int>) = bytes.joinToString(" ") { "%02X".format(it and 0xFF) }
}
