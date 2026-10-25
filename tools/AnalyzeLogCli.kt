import vn.nvxconnect.telemetry.*
import java.io.File

/**
 * Phân tích log NVX Connect ngoại tuyến (không cần Android).
 *   analyze-log.sh log1.json [log2.csv ...] [--ref tham_chieu.csv] [--json]
 * In báo cáo cấu trúc (Markdown, hoặc JSON với --json) rồi bảng bằng chứng từng trường. Không gán nghĩa vật lý cho byte nào.
 */
fun main(args: Array<String>) {
    val files = ArrayList<String>(); var ref: String? = null; var json = false
    var i = 0
    while (i < args.size) { when (args[i]) { "--ref" -> ref = args.getOrNull(++i); "--json" -> json = true; else -> files += args[i] }; i++ }
    if (files.isEmpty()) { System.err.println("Cách dùng: analyze-log.sh log.json|log.csv [...] [--ref tham_chieu.csv] [--json]"); System.exit(2) }
    val logs = files.map { f -> TelemetryLogImport.parse(File(f).readText()).also { System.err.println("$f: ${it.records.size} payload, ${it.warnings.size} cảnh báo") } }
    val synthetic = (files + listOfNotNull(ref)).any { it.contains("SYNTHETIC", true) } || logs.any { l -> l.records.any { it.deviceId.contains("synth", true) } }
    if (synthetic) println("> ⚠ DỮ LIỆU TỔNG HỢP (SYNTHETIC): kết quả chỉ kiểm tra công cụ, KHÔNG phải bằng chứng về giao thức hay xe thật.\n")
    val repo = ProtocolEvidenceRepository()
    ref?.let { r -> val d = repo.importReference(File(r).readText(), System.currentTimeMillis()); System.err.println("$r: ${d.samples.size} mẫu tham chiếu, ${d.warnings.size} cảnh báo"); d.warnings.take(10).forEach { System.err.println("  - $it") } }
    val records = logs.flatMapIndexed { li, l -> l.records.map { if (logs.size > 1) it.copy(sessionId = "L${li + 1}:${it.sessionId}") else it } }
    // Log ngoại tuyến: xét độ cũ tại thời điểm gói cuối (không phải hôm nay).
    val verdicts = repo.evaluateAll(records, records.maxOfOrNull { it.timeMs } ?: 0L)
    val report = OfflineLogAnalyzer.analyze(logs, repo.verifiedCharUuids())
    if (json) { println(OfflineLogAnalyzer.toJson(report)); println(TelemetryEvidenceReport.toJson(verdicts)) }
    else { println(OfflineLogAnalyzer.toMarkdown(report)); println(); println(TelemetryEvidenceReport.toMarkdown(verdicts)) }
}
