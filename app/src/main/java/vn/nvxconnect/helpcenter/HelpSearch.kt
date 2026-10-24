package vn.nvxconnect.helpcenter

import java.text.Normalizer

/** Tìm kiếm bài hướng dẫn, không phân biệt hoa thường và dấu tiếng Việt ("ket noi" tìm được "Kết nối"). */
object HelpSearch {
    private val combining = Regex("\\p{InCombiningDiacriticalMarks}+")

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(combining, "").replace('đ', 'd').trim()

    private fun tokens(q: String) = normalize(q).split(Regex("[\\s,.;:]+")).filter { it.isNotEmpty() }

    /** Mọi từ trong truy vấn phải xuất hiện (AND). Xếp hạng: tiêu đề > từ khóa > mục đích > nội dung các bước. Truy vấn rỗng = toàn bộ theo thứ tự. */
    fun search(query: String, guides: List<HelpGuide> = HelpContent.guides): List<HelpGuide> {
        val t = tokens(query)
        if (t.isEmpty()) return guides.sortedBy { it.order }
        return guides.mapNotNull { g ->
            val title = normalize(g.title)
            val kw = normalize(g.keywords.joinToString(" ") + " " + g.category.title)
            val purpose = normalize(g.purpose)
            val body = normalize((g.prerequisites + g.steps + listOf(g.expected) + g.commonErrors + g.fixes).joinToString(" "))
            var score = 0
            for (w in t) {
                val s = when {
                    title.contains(w) -> 8
                    kw.contains(w) -> 5
                    purpose.contains(w) -> 3
                    body.contains(w) -> 1
                    else -> return@mapNotNull null
                }
                score += s
            }
            g to score
        }.sortedWith(compareByDescending<Pair<HelpGuide, Int>> { it.second }.thenBy { it.first.order }).map { it.first }
    }
}
