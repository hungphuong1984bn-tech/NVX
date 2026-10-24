package vn.nvxconnect.diagnostics

import vn.nvxconnect.bluetooth.LogEntry
import vn.nvxconnect.bluetooth.LogExporter
import vn.nvxconnect.bluetooth.LogKind
import vn.nvxconnect.helpcenter.StepResult
import vn.nvxconnect.tftintegration.TftIntegrationReport
import vn.nvxconnect.util.MiniJson
import vn.nvxconnect.vehicledata.TrackedValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Báo cáo chẩn đoán gửi hỗ trợ kỹ thuật: quy trình kiểm tra, trạng thái từng giá trị xe, mức tích hợp TFT, dòng thời gian lỗi/trạng thái.
 * Địa chỉ MAC trong văn bản tự do luôn bị che bằng muối riêng của phiên. Không chứa tọa độ GPS, VIN hay dữ liệu cá nhân của hành trình.
 */
data class DiagnosticsInput(
    val appVersion: String,
    val androidSdk: Int,
    val generatedAtMs: Long,
    val checklist: List<StepResult>,
    val values: List<TrackedValue>,
    val tft: TftIntegrationReport,
    val log: List<LogEntry>,
    val salt: String
)

object DiagnosticsReport {
    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    /** Chỉ lấy sự kiện hữu ích cho chẩn đoán: lỗi và chuyển trạng thái (không lấy gói dữ liệu thô). */
    private fun timeline(i: DiagnosticsInput) = i.log.filter { it.kind == LogKind.ERROR || it.kind == LogKind.STATE }

    fun toJson(i: DiagnosticsInput): String {
        val q = MiniJson::quote
        fun r(s: String) = q(LogExporter.redactText(s, i.salt))
        return buildString {
            append("{\n")
            append("  \"schema\": \"nvx-diagnostics-1\",\n")
            append("  \"appVersion\": ${q(i.appVersion)},\n  \"androidSdk\": ${i.androidSdk},\n")
            append("  \"generatedAt\": ${q(iso(i.generatedAtMs))},\n  \"generatedAtMs\": ${i.generatedAtMs},\n")
            append("  \"checklist\": [\n")
            append(i.checklist.joinToString(",\n") { "    {\"step\":${q(it.step.name)},\"title\":${q(it.step.title)},\"status\":${q(it.status.name)},\"detail\":${r(it.detail)},\"helpGuide\":${q(it.guideId)}}" })
            append("\n  ],\n  \"values\": [\n")
            append(i.values.joinToString(",\n") {
                "    {\"param\":${q(it.param.name)},\"status\":${q(it.status.name)},\"value\":${it.value?.toString() ?: "null"},\"unit\":${MiniJson.quoteOrNull(it.unit)}," +
                    "\"source\":${q(it.source.name)},\"updatedAtMs\":${it.updatedAtMs?.toString() ?: "null"},\"ageMs\":${it.ageMs?.toString() ?: "null"},\"note\":${r(it.note)}}"
            })
            append("\n  ],\n  \"tft\": {\"headline\":${q(i.tft.headline)},\"levels\":[\n")
            append(i.tft.levels.joinToString(",\n") { "    {\"level\":${q(it.level.name)},\"reached\":${it.reached},\"detail\":${r(it.detail)}}" })
            append("\n  ]},\n  \"timeline\": [\n")
            append(timeline(i).joinToString(",\n") { "    {\"timeMs\":${it.timeMs},\"time\":${q(iso(it.timeMs))},\"kind\":${q(it.kind.name)},\"state\":${MiniJson.quoteOrNull(it.state)},\"message\":${r(it.message)}}" })
            append("\n  ]\n}\n")
        }
    }

    /** CSV một bảng: loại dòng (CHECK/VALUE/TFT/EVENT) để mở trong bảng tính; ô được chống chèn công thức. */
    fun toCsv(i: DiagnosticsInput): String = buildString {
        val c = LogExporter::csvCell
        fun red(s: String) = LogExporter.redactText(s, i.salt)
        appendLine("section,time_utc,time_ms,name,status,detail")
        i.checklist.forEach { appendLine(listOf(c("CHECK"), c(iso(i.generatedAtMs)), i.generatedAtMs.toString(), c(it.step.name), c(it.status.name), c(red(it.detail))).joinToString(",")) }
        i.values.forEach { appendLine(listOf(c("VALUE"), c(iso(i.generatedAtMs)), i.generatedAtMs.toString(), c(it.param.name), c(it.status.name), c(red(it.note))).joinToString(",")) }
        i.tft.levels.forEach { appendLine(listOf(c("TFT"), c(iso(i.generatedAtMs)), i.generatedAtMs.toString(), c(it.level.name), c(if (it.reached) "REACHED" else "NOT_REACHED"), c(red(it.detail))).joinToString(",")) }
        timeline(i).forEach { appendLine(listOf(c("EVENT"), c(iso(it.timeMs)), it.timeMs.toString(), c(it.kind.name), c(it.state ?: ""), c(red(it.message))).joinToString(",")) }
    }
}
