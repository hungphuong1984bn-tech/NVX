package vn.nvxconnect.telemetry

import vn.nvxconnect.util.MiniJson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Kho bằng chứng giao thức: giả thuyết giải mã, tiêu chí đã khóa, dữ liệu tham chiếu đã nhập, và phán quyết từng trường.
 * Không có hàm nào đặt trạng thái trực tiếp: trạng thái chỉ đến từ [TelemetryValidationEngine.evaluate].
 */
class ProtocolEvidenceRepository {
    private val hypotheses = LinkedHashMap<String, DecoderHypothesis>().apply { BuiltInHypotheses.ALL.forEach { put(it.fieldId, it) } }
    private val criteria = LinkedHashMap<String, LockedCriteria>().apply { FieldCatalog.ALL.forEach { f -> CriteriaRegistry.defaultFor(f.id)?.let { put(f.id, it) } } }
    private var reference: ReferenceDataset? = null
    @Volatile private var lastVerdicts: List<FieldVerdict> = emptyList()

    companion object {
        /** Lý do mặc định cho trường chưa có đường truy cập hợp lệ (không đoán lệnh vào ECU). */
        val INACCESSIBLE = mapOf(FieldIds.DTC to "Chưa có phương thức chẩn đoán được hỗ trợ và có tài liệu cho xe này; app không đoán lệnh vào ECU.")
    }

    @Synchronized fun hypothesisFor(fieldId: String): DecoderHypothesis? = hypotheses[fieldId]
    @Synchronized fun criteriaFor(fieldId: String): LockedCriteria? = criteria[fieldId]
    @Synchronized fun referenceSampleCount(): Int = reference?.samples?.size ?: 0
    @Synchronized fun referenceWarnings(): List<String> = reference?.warnings.orEmpty()
    @Synchronized fun verdicts(): List<FieldVerdict> = lastVerdicts

    /** Thêm giả thuyết giải mã mới (chỉ là giả thuyết; không đổi trạng thái nào). */
    @Synchronized fun registerHypothesis(h: DecoderHypothesis) { hypotheses[h.fieldId] = h }

    /** Nhập CSV tham chiếu và gộp vào bộ hiện có. [ReferenceDataset.importedAtMs] = lần nhập MỚI NHẤT. */
    @Synchronized fun importReference(csv: String, nowMs: Long): ReferenceDataset {
        val d = ReferenceCsv.parse(csv, nowMs)
        val prev = reference
        reference = ReferenceDataset((prev?.samples.orEmpty() + d.samples).distinct(), if (prev == null || d.samples.isNotEmpty()) nowMs else prev.importedAtMs, prev?.warnings.orEmpty() + d.warnings)
        return d
    }

    /**
     * Khóa tiêu chí do người dùng đặt cho một trường. TỪ CHỐI nếu trường đó đã có dữ liệu tham chiếu: tiêu chí phải có trước dữ liệu.
     * Trả null và không đổi gì khi bị từ chối.
     */
    @Synchronized fun lockCriteria(c: VerificationCriteria, nowMs: Long): LockedCriteria? {
        if (reference?.samples?.any { it.fieldId == c.fieldId } == true) return null
        return CriteriaRegistry.lock(c, nowMs).also { criteria[c.fieldId] = it }
    }

    @Synchronized fun clearReference() { reference = null }

    /** Đánh giá mọi trường trong danh mục trên các payload đã có. [nowMs] dùng xét độ cũ. */
    @Synchronized fun evaluateAll(records: List<PayloadRecord>, nowMs: Long): List<FieldVerdict> {
        val out = FieldCatalog.ALL.map { f ->
            val h = hypotheses[f.id]
            val (samples, proofs) = if (h != null) TelemetryValidationEngine.samplesFor(h, records) else emptyList<DecodedSample>() to null
            TelemetryValidationEngine.evaluate(f, h, criteria[f.id], samples, proofs, reference, nowMs, inaccessibleReason = INACCESSIBLE[f.id])
        }
        lastVerdicts = out
        return out
    }

    /** UUID của các characteristic mang trường đã VERIFIED (dùng để cho phép trạng thái ENGINE_LINK_PROVEN ở bộ phân tích). */
    @Synchronized fun verifiedCharUuids(): Set<String> = lastVerdicts.filter { it.baseStatus == VerificationStatus.VERIFIED }.mapNotNull { it.hypothesis?.charUuid }.toSet()
}

object TelemetryEvidenceReport {
    private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    private fun f(d: Double?, n: Int = 3) = if (d == null || !d.isFinite()) "-" else "%.${n}f".format(Locale.US, d)

    fun toMarkdown(v: List<FieldVerdict>): String = buildString {
        appendLine("# Bảng bằng chứng theo từng trường telemetry")
        appendLine()
        appendLine("Trạng thái chỉ do bộ xác minh cấp theo tiêu chí đã khóa. Parser chạy được hoặc tổng kiểm hợp lệ KHÔNG đủ để VERIFIED.")
        appendLine()
        for (x in v) {
            appendLine("## ${x.field.title} (`${x.field.id}`, ${x.field.unit})")
            appendLine("- Trạng thái: **${x.status.name}** — ${x.status.title}" + if (x.status != x.baseStatus) " (trước khi quá hạn: ${x.baseStatus.name})" else "")
            appendLine("- Cấp bằng chứng: ${x.grade.title}")
            appendLine("- Lý do: ${x.reason}")
            x.hypothesis?.let { appendLine("- Giả thuyết `${it.id}`: ${it.formula}; UUID `${it.charUuid ?: "-"}`; khung 0x%02X dài ${it.expectedLength}B; tổng kiểm ${it.checksum ?: "không khai báo"}".format(it.frameType)); appendLine("  - Cơ sở: ${it.basis}") }
            x.lastSample?.let { appendLine("- Mẫu gần nhất: raw=${f(it.raw, 0)} → ${f(it.value)} ${x.field.unit} lúc ${iso(it.timeMs)} (phiên ${it.sessionId}); payload `${it.hex}`") }
            appendLine("- Số mẫu giải mã: ${x.sampleCount}")
            x.metrics?.let { m ->
                appendLine("- Đối chiếu: ${m.pairs}/${m.references} cặp (phủ ${f(m.coverage * 100, 0)}%), ${m.perSessionMae.size} phiên có ≥3 cặp, phủ ${f(m.refSpan)} ${x.field.unit}, ${m.levels} mức, Pearson r=${f(m.pearson, 4)}")
                appendLine("- Sai số: TB tuyệt đối ${f(m.mae)}, lớn nhất ${f(m.maxAbs)}, lệch trung bình ${f(m.bias)}, độ lệch chuẩn ${f(m.errStd)}, tương đối TB ${f(m.meanRelPct, 2)}%, độ trễ ước lượng ${m.lagMs} ms")
                appendLine("- Nguồn tham chiếu: ${m.referenceSources.joinToString("; ")}")
            }
            if (x.unmet.isNotEmpty()) { appendLine("- Tiêu chí chưa đạt:"); x.unmet.forEach { appendLine("  - $it") } }
            if (x.missingEvidence.isNotEmpty()) { appendLine("- Còn thiếu bằng chứng:"); x.missingEvidence.forEach { appendLine("  - $it") } }
            appendLine("- Vân tay tiêu chí: ${x.criteriaFingerprint ?: "(không có tiêu chí)"}")
            appendLine()
        }
    }

    const val CSV_HEADER = "field,unit,status,base_status,evidence_grade,hypothesis,uuid,formula,last_raw,last_value,last_time_utc,samples,pairs,sessions,pearson,mae,max_abs,bias,lag_ms,reference_sources,criteria_fingerprint,reason"

    fun toCsv(v: List<FieldVerdict>): String = buildString {
        appendLine(CSV_HEADER)
        for (x in v) {
            val m = x.metrics; val h = x.hypothesis
            appendLine(listOf(x.field.id, x.field.unit, x.status.name, x.baseStatus.name, x.grade.name, h?.id ?: "", h?.charUuid ?: "", h?.formula ?: "",
                x.lastSample?.let { f(it.raw, 0) } ?: "", x.lastSample?.let { f(it.value) } ?: "", x.lastSample?.let { iso(it.timeMs) } ?: "", x.sampleCount.toString(),
                m?.pairs?.toString() ?: "", m?.perSessionMae?.size?.toString() ?: "", m?.let { f(it.pearson, 4) } ?: "", m?.let { f(it.mae) } ?: "", m?.let { f(it.maxAbs) } ?: "",
                m?.let { f(it.bias) } ?: "", m?.lagMs?.toString() ?: "", m?.referenceSources?.joinToString("; ") ?: "", x.criteriaFingerprint ?: "", x.reason)
                .joinToString(",") { vn.nvxconnect.bluetooth.LogExporter.csvCell(it) })
        }
    }

    fun toJson(v: List<FieldVerdict>): String = buildString {
        fun q(s: String?) = MiniJson.quoteOrNull(s)
        append("{\"format\":\"nvx-connect-field-evidence/1\",\"fields\":[")
        append(v.joinToString(",") { x ->
            val m = x.metrics; val h = x.hypothesis
            "{\"field\":${q(x.field.id)},\"unit\":${q(x.field.unit)},\"status\":${q(x.status.name)},\"base_status\":${q(x.baseStatus.name)},\"evidence_grade\":${q(x.grade.name)}," +
                "\"hypothesis\":${q(h?.id)},\"uuid\":${q(h?.charUuid)},\"formula\":${q(h?.formula)},\"last_raw_hex\":${q(x.lastSample?.hex)},\"last_value\":${x.lastSample?.value ?: "null"}," +
                "\"last_time_ms\":${x.lastSample?.timeMs ?: "null"},\"samples\":${x.sampleCount},\"pairs\":${m?.pairs ?: "null"},\"pearson\":${m?.pearson ?: "null"},\"mae\":${m?.mae ?: "null"}," +
                "\"max_abs_error\":${m?.maxAbs ?: "null"},\"lag_ms\":${m?.lagMs ?: "null"},\"reference_sources\":[${m?.referenceSources.orEmpty().joinToString(",") { MiniJson.quote(it) }}]," +
                "\"unmet\":[${x.unmet.joinToString(",") { MiniJson.quote(it) }}],\"missing\":[${x.missingEvidence.joinToString(",") { MiniJson.quote(it) }}]," +
                "\"criteria_fingerprint\":${q(x.criteriaFingerprint)},\"reason\":${q(x.reason)}}"
        })
        append("]}")
    }
}
