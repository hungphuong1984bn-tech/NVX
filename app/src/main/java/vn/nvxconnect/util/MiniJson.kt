package vn.nvxconnect.util

/**
 * Bộ đọc/ghi JSON nhỏ, thuần Kotlin (không phụ thuộc org.json của Android) để test được trên JVM thường.
 * Đọc ra: Map<String, Any?>, List<Any?>, String, Double/Long, Boolean, null.
 */
object MiniJson {
    class JsonException(msg: String) : Exception(msg)
    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw JsonException("Dữ liệu thừa sau JSON ở vị trí ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++ }
        fun fail(m: String): Nothing = throw JsonException("$m (vị trí $i)")
        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("JSON lồng quá sâu")
            if (i >= s.length) fail("JSON bị cắt cụt")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("Ký tự không hợp lệ '$c'")
            }
        }
        fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) fail("Từ khóa không hợp lệ"); i += w.length; return v }
        fun obj(depth: Int): Map<String, Any?> {
            i++; val m = LinkedHashMap<String, Any?>(); ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws(); if (i >= s.length || s[i] != '"') fail("Cần khóa chuỗi")
                val k = str(); ws()
                if (i >= s.length || s[i] != ':') fail("Cần dấu ':'"); i++
                ws(); m[k] = value(depth + 1); ws()
                if (i >= s.length) fail("JSON bị cắt cụt")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                fail("Cần ',' hoặc '}'")
            }
        }
        fun arr(depth: Int): List<Any?> {
            i++; val l = ArrayList<Any?>(); ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws(); l += value(depth + 1); ws()
                if (i >= s.length) fail("JSON bị cắt cụt")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return l }
                fail("Cần ',' hoặc ']'")
            }
        }
        fun str(): String {
            i++; val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Chuỗi chưa đóng")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("Escape bị cắt cụt")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("Escape \\u bị cắt cụt")
                                val h = s.substring(i, i + 4).toIntOrNull(16) ?: fail("Escape \\u không hợp lệ")
                                sb.append(h.toChar()); i += 4
                            }
                            else -> fail("Escape không hợp lệ '\\$e'")
                        }
                    }
                    c < ' ' -> fail("Ký tự điều khiển chưa escape trong chuỗi")
                    else -> sb.append(c)
                }
            }
        }
        fun num(): Any {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            var isD = false
            if (i < s.length && s[i] == '.') { isD = true; i++; while (i < s.length && s[i] in '0'..'9') i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isD = true; i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val t = s.substring(st, i)
            return if (isD) t.toDoubleOrNull() ?: fail("Số không hợp lệ")
            else t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail("Số không hợp lệ")
        }
    }

    // ---- truy cập an toàn ----
    @Suppress("UNCHECKED_CAST") fun Any?.obj(): Map<String, Any?>? = this as? Map<String, Any?>
    @Suppress("UNCHECKED_CAST") fun Any?.list(): List<Any?>? = this as? List<Any?>
    fun Any?.dbl(): Double? = (this as? Number)?.toDouble() ?: (this as? String)?.toDoubleOrNull()
    fun Any?.int(): Int? = (this as? Number)?.toInt()
    fun Any?.text(): String? = this as? String

    // ---- ghi ----
    /** Escape đúng chuẩn JSON, kể cả ký tự điều khiển. Trả về chuỗi KÈM dấu nháy. */
    fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
    fun quoteOrNull(s: String?): String = if (s == null) "null" else quote(s)
}
