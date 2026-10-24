import vn.nvxconnect.helpcenter.HelpContent
import vn.nvxconnect.helpcenter.HelpSafety
import java.io.File

/**
 * Sinh HELP_CONTENT_VI.md từ CHÍNH HelpContent.kt để tài liệu và nội dung trong app không bao giờ lệch nhau.
 * Chạy: tools/generate-help-md.sh
 */
fun main(args: Array<String>) {
    val out = StringBuilder()
    out.appendLine("# Nội dung Trung tâm hướng dẫn (tiếng Việt)")
    out.appendLine()
    out.appendLine("> Tệp này được SINH TỰ ĐỘNG từ `app/src/main/java/vn/nvxconnect/helpcenter/HelpContent.kt` (`tools/generate-help-md.sh`). Không sửa tay; sửa nguồn rồi sinh lại.")
    out.appendLine()
    out.appendLine("**${HelpSafety.STATIONARY_NOTICE}**")
    out.appendLine()
    out.appendLine("## Mục lục")
    HelpContent.guides.forEach { out.appendLine("${it.order}. [${it.title}](#bai-${it.order}) — ${it.category.title}") }
    out.appendLine()
    for (g in HelpContent.guides) {
        out.appendLine("<a id=\"bai-${g.order}\"></a>")
        out.appendLine("## ${g.order}. ${g.title}")
        out.appendLine()
        out.appendLine("*Nhóm: ${g.category.title} · id: `${g.id}` · nút hành động: ${g.action?.label ?: "không có"} · " +
            (if (g.manualComplete) "có thể đánh dấu hoàn thành (bài có kiểm tra tự động chỉ được đánh dấu khi bước kiểm tra đạt)" else "bài tham khảo, không có trạng thái hoàn thành") +
            (if (g.requiresStationary) " · hiển thị cảnh báo an toàn" else "") + "*")
        out.appendLine()
        out.appendLine("**Mục đích.** ${g.purpose}")
        out.appendLine()
        out.appendLine("**Điều kiện trước khi thực hiện**")
        g.prerequisites.forEach { out.appendLine("- $it") }
        out.appendLine()
        out.appendLine("**Các bước**")
        g.steps.forEachIndexed { i, s -> out.appendLine("${i + 1}. $s") }
        out.appendLine()
        out.appendLine("**Kết quả mong đợi.** ${g.expected}")
        out.appendLine()
        out.appendLine("**Lỗi thường gặp**")
        g.commonErrors.forEach { out.appendLine("- $it") }
        out.appendLine()
        out.appendLine("**Cách khắc phục**")
        g.fixes.forEach { out.appendLine("- $it") }
        out.appendLine()
    }
    val target = args.firstOrNull()
    if (target != null) File(target).writeText(out.toString()) else print(out)
}
