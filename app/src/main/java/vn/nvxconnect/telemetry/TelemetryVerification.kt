package vn.nvxconnect.telemetry

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.sqrt

/** Trạng thái xác minh của MỘT trường telemetry. VERIFIED chỉ do [TelemetryValidationEngine] cấp, không có đường gán tay. */
enum class VerificationStatus(val title: String) {
    RAW_UNVERIFIED("Chỉ có dữ liệu thô"), DECODED_HYPOTHESIS("Giả thuyết giải mã"), CORRELATED("Đã đối chiếu, chưa đủ tiêu chí"),
    VERIFIED("Đã xác minh"), UNAVAILABLE("Chưa tìm thấy nguồn dữ liệu"), STALE("Dữ liệu đã quá hạn")
}

/** Cấp bằng chứng, khớp bảng bắt buộc của TELEMETRY_FIELD_EVIDENCE.md. */
enum class EvidenceGrade(val title: String) {
    OFFICIAL_SOURCE("Đã xác minh bằng nguồn chính thức"), INDEPENDENT_MEASUREMENT("Đã đối chiếu bằng phép đo độc lập"),
    VEHICLE_CLUSTER("Đối chiếu với đồng hồ xe (không độc lập hoàn toàn: cùng nguồn ECU, làm tròn)"),
    SAMPLE_INFERENCE("Chỉ là suy luận từ mẫu dữ liệu"), NO_DATA("Chưa có dữ liệu"), INACCESSIBLE("Không thể truy cập bằng phương thức hiện tại")
}

object FieldIds {
    const val RPM = "rpm"; const val ENGINE_TEMP = "engine_temp"; const val SPEED = "vehicle_speed"; const val BATTERY_V = "battery_voltage"
    const val CHARGING_V = "charging_voltage"; const val ENGINE_STATUS = "engine_status"; const val DTC = "dtc"
}

data class FieldSpec(val id: String, val title: String, val unit: String)

object FieldCatalog {
    val ALL = listOf(
        FieldSpec(FieldIds.RPM, "Vòng tua động cơ (RPM)", "rpm"), FieldSpec(FieldIds.ENGINE_TEMP, "Nhiệt độ động cơ", "°C"),
        FieldSpec(FieldIds.SPEED, "Tốc độ xe", "km/h"), FieldSpec(FieldIds.BATTERY_V, "Điện áp ắc quy", "V"),
        FieldSpec(FieldIds.CHARGING_V, "Điện áp sạc", "V"), FieldSpec(FieldIds.ENGINE_STATUS, "Trạng thái động cơ", "state"),
        FieldSpec(FieldIds.DTC, "Mã lỗi chẩn đoán (DTC)", "mã")
    )
    fun spec(id: String) = ALL.firstOrNull { it.id == id }
}

enum class ByteOrderKind { BIG, LITTLE }

/**
 * Giả thuyết giải mã: vị trí byte + công thức. Chỉ là GIẢ THUYẾT; có hồ sơ ở đây không có nghĩa đã đúng.
 * giá trị = scale × raw + offsetValue.
 */
data class DecoderHypothesis(
    val id: String, val fieldId: String, val charUuid: String?, val frameType: Int, val expectedLength: Int,
    val byteOffset: Int, val byteCount: Int, val order: ByteOrderKind, val signed: Boolean,
    val scale: Double, val offsetValue: Double, val unit: String, val checksum: String?, val basis: String
) {
    val formula get() = "giá trị = $scale × raw($byteCount byte ${order.name.lowercase()}-endian ${if (signed) "có dấu" else "không dấu"} @ byte $byteOffset) + $offsetValue [$unit]"

    fun raw(b: ByteArray): Double? {
        if (b.size != expectedLength || b.isEmpty() || (b[0].toInt() and 0xFF) != frameType || byteOffset + byteCount > b.size) return null
        var v = 0L
        val range = if (order == ByteOrderKind.BIG) byteOffset until byteOffset + byteCount else (byteOffset + byteCount - 1) downTo byteOffset
        for (i in range) v = (v shl 8) or (b[i].toLong() and 0xFF)
        if (signed) { val bits = byteCount * 8; if (bits < 64 && (v shr (bits - 1)) and 1L == 1L) v -= (1L shl bits) }
        return v.toDouble()
    }
    fun value(b: ByteArray): Double? = raw(b)?.let { scale * it + offsetValue }
}

object BuiltInHypotheses {
    private const val NUS_NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    private const val CK = "SUM8(toàn gói) ≡ 0"
    /** Từ docs/YCC_PROTOCOL_FINDINGS.md (5.988 gói, 2026-10-23). KHÔNG phải công thức chính thức. */
    val RPM = DecoderHypothesis("ycc.rpm.u16be@5", FieldIds.RPM, NUS_NOTIFY, 0x6B, 59, 5, 2, ByteOrderKind.BIG, false, 1.0, 0.0, "rpm", CK,
        "Giả thuyết từ mẫu: =0 khi chưa nổ, 676–3043 khi nổ, tương quan 0,97 với một trường trong khung 0x6C. Chưa đối chiếu với tham chiếu độc lập.")
    val BATTERY = DecoderHypothesis("ycc.batt.u8@11x0.07", FieldIds.BATTERY_V, NUS_NOTIFY, 0x6B, 59, 11, 1, ByteOrderKind.BIG, false, 0.07, 0.0, "V", CK,
        "Giả thuyết từ mẫu: byte 11 giảm khi tắt máy, tụt khi đề, cao khi nổ. Hệ số 0,07 là ước lượng.")
    val ALL = listOf(RPM, BATTERY)
}

/** Tiêu chí đạt. Đặt TRƯỚC khi chạy kiểm thử; đổi tiêu chí sau khi có dữ liệu tham chiếu làm kết quả tối đa là CORRELATED. */
data class VerificationCriteria(
    val fieldId: String, val unit: String,
    val minPairs: Int, val minSessions: Int, val minRefSpan: Double, val minLevels: Int, val levelSeparation: Double,
    val minPearson: Double, val maxMeanAbsError: Double, val maxAbsError: Double, val maxErrStd: Double,
    val minCoverage: Double, val maxLagMs: Long, val pairWindowMs: Long, val staleMs: Long,
    val minChecksumPass: Double = 0.999, val checksumRequired: Boolean = true,
    val allowedGrades: Set<EvidenceGrade> = setOf(EvidenceGrade.OFFICIAL_SOURCE, EvidenceGrade.INDEPENDENT_MEASUREMENT),
    val correlatedMinPairs: Int = 3, val correlatedMinPearson: Double = 0.8
) {
    internal fun canonical(): String = listOf(fieldId, unit, minPairs, minSessions, minRefSpan, minLevels, levelSeparation, minPearson, maxMeanAbsError, maxAbsError,
        maxErrStd, minCoverage, maxLagMs, pairWindowMs, staleMs, minChecksumPass, checksumRequired, allowedGrades.map { it.name }.sorted(), correlatedMinPairs, correlatedMinPearson).joinToString("|")
}

enum class CriteriaOrigin { BUILT_IN, USER }

/** Tiêu chí đã KHÓA (có dấu thời gian + vân tay). Chỉ [CriteriaRegistry] tạo được; engine kiểm tra vân tay để phát hiện sửa sau khi khóa. */
class LockedCriteria internal constructor(val criteria: VerificationCriteria, val lockedAtMs: Long, val origin: CriteriaOrigin, val fingerprint: String) {
    fun intact() = fingerprint == CriteriaRegistry.fingerprintOf(criteria)
}

object CriteriaRegistry {
    fun fingerprintOf(c: VerificationCriteria): String =
        MessageDigest.getInstance("SHA-256").digest(c.canonical().toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** Khóa tiêu chí do người dùng đặt tại thời điểm [nowMs]. Phải làm TRƯỚC khi nhập dữ liệu tham chiếu. */
    fun lock(c: VerificationCriteria, nowMs: Long): LockedCriteria = LockedCriteria(c, nowMs, CriteriaOrigin.USER, fingerprintOf(c))

    private fun builtIn(c: VerificationCriteria) = LockedCriteria(c, 0L, CriteriaOrigin.BUILT_IN, fingerprintOf(c))

    /** Mặc định đặt trong mã nguồn (commit trước mọi dữ liệu thật); null = không có phương thức xác minh số học trong app (DTC). */
    fun defaultFor(fieldId: String): LockedCriteria? = when (fieldId) {
        FieldIds.RPM -> builtIn(VerificationCriteria(fieldId, "rpm", minPairs = 30, minSessions = 2, minRefSpan = 1500.0, minLevels = 4, levelSeparation = 300.0,
            minPearson = 0.997, maxMeanAbsError = 50.0, maxAbsError = 100.0, maxErrStd = 60.0, minCoverage = 0.8, maxLagMs = 1000, pairWindowMs = 500, staleMs = 3000))
        FieldIds.ENGINE_TEMP -> builtIn(VerificationCriteria(fieldId, "°C", 20, 2, 20.0, 3, 5.0, 0.98, 2.0, 4.0, 2.5, 0.8, 5000, 2000, 10_000))
        FieldIds.SPEED -> builtIn(VerificationCriteria(fieldId, "km/h", 40, 2, 18.0, 4, 4.0, 0.98, 2.0, 5.0, 3.0, 0.8, 3000, 1500, 3000))
        FieldIds.BATTERY_V -> builtIn(VerificationCriteria(fieldId, "V", 20, 2, 1.0, 3, 0.3, 0.99, 0.10, 0.20, 0.12, 0.8, 5000, 2000, 10_000))
        FieldIds.CHARGING_V -> builtIn(VerificationCriteria(fieldId, "V", 20, 2, 0.8, 3, 0.2, 0.99, 0.10, 0.20, 0.12, 0.8, 5000, 2000, 10_000))
        FieldIds.ENGINE_STATUS -> builtIn(VerificationCriteria(fieldId, "state", 30, 2, 1.0, 2, 0.5, 0.95, 0.05, 1.0, 0.25, 0.8, 2000, 1000, 5000))
        else -> null
    }
}

enum class ReferenceGradeLabel(val grade: EvidenceGrade) { OFFICIAL(EvidenceGrade.OFFICIAL_SOURCE), INDEPENDENT(EvidenceGrade.INDEPENDENT_MEASUREMENT), CLUSTER(EvidenceGrade.VEHICLE_CLUSTER), INFERENCE(EvidenceGrade.SAMPLE_INFERENCE) }

data class ReferenceSample(val timeMs: Long, val fieldId: String, val value: Double, val unit: String, val source: String, val grade: EvidenceGrade, val method: String, val sessionId: String?)

/** Bộ dữ liệu tham chiếu đã nhập. [importedAtMs] dùng để kiểm tra tiêu chí được khóa TRƯỚC khi có dữ liệu. */
data class ReferenceDataset(val samples: List<ReferenceSample>, val importedAtMs: Long, val warnings: List<String>)

/**
 * Nhập CSV tham chiếu: `time_ms|time_utc,field,value,unit,source,grade,method[,session]`.
 * Dòng thiếu nguồn/đơn vị/cấp bằng chứng bị BỎ kèm cảnh báo; cấp lạ không bao giờ mặc định thành cấp cao.
 */
object ReferenceCsv {
    private fun parseTime(s: String): Long? = s.toLongOrNull() ?: listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'").firstNotNullOfOrNull { f ->
        runCatching { SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }.parse(s)?.time }.getOrNull() }

    fun parse(text: String, nowMs: Long): ReferenceDataset {
        val lines = text.removePrefix("\uFEFF").lines().filter { it.isNotBlank() && !it.trim().startsWith("#") }
        if (lines.isEmpty()) return ReferenceDataset(emptyList(), nowMs, listOf("Tệp tham chiếu rỗng"))
        val h = TelemetryLogImport.splitCsv(lines.first()).map { it.trim().lowercase() }
        val warns = ArrayList<String>(); val out = ArrayList<ReferenceSample>()
        fun idx(vararg n: String) = n.firstNotNullOfOrNull { k -> h.indexOf(k).takeIf { it >= 0 } } ?: -1
        val iT = idx("time_ms", "time_utc"); val iF = idx("field"); val iV = idx("value"); val iU = idx("unit"); val iS = idx("source"); val iG = idx("grade"); val iM = idx("method"); val iSes = idx("session")
        if (iT < 0 || iF < 0 || iV < 0 || iU < 0 || iS < 0 || iG < 0) return ReferenceDataset(emptyList(), nowMs, listOf("Thiếu cột bắt buộc (time_ms|time_utc, field, value, unit, source, grade)"))
        for ((n, line) in lines.drop(1).withIndex()) {
            val c = TelemetryLogImport.splitCsv(line).map { it.trim() }
            val ln = n + 2
            val t = c.getOrNull(iT)?.let(::parseTime); val v = c.getOrNull(iV)?.replace(',', '.')?.toDoubleOrNull()
            val f = c.getOrNull(iF).orEmpty(); val u = c.getOrNull(iU).orEmpty(); val s = c.getOrNull(iS).orEmpty()
            val g = c.getOrNull(iG)?.uppercase()?.let { x -> runCatching { ReferenceGradeLabel.valueOf(x) }.getOrNull() }
            when {
                t == null -> warns += "Dòng $ln: thời gian không hợp lệ"
                v == null || !v.isFinite() -> warns += "Dòng $ln: giá trị không phải số hữu hạn"
                f.isEmpty() || FieldCatalog.spec(f) == null -> warns += "Dòng $ln: trường '$f' không có trong danh mục"
                u.isEmpty() -> warns += "Dòng $ln: thiếu đơn vị"
                s.isEmpty() -> warns += "Dòng $ln: thiếu nguồn tham chiếu"
                g == null -> warns += "Dòng $ln: cấp bằng chứng '${c.getOrNull(iG)}' không hợp lệ (official|independent|cluster|inference)"
                else -> out += ReferenceSample(t, f, v, u, s, g.grade, if (iM >= 0) c.getOrNull(iM).orEmpty() else "", if (iSes >= 0) c.getOrNull(iSes)?.ifEmpty { null } else null)
            }
        }
        return ReferenceDataset(out, nowMs, warns)
    }
}

data class DecodedSample(val timeMs: Long, val value: Double, val raw: Double, val sessionId: String, val hex: String)

/** Điều kiện nền của khung: độ dài, tổng kiểm. Chỉ là điều KIỆN CẦN, không bao giờ đủ để VERIFIED. */
data class FrameProofs(val framesOfType: Int, val lengthOk: Int, val checksumTested: Int, val checksumPass: Int)

data class Metrics(
    val pairs: Int, val references: Int, val coverage: Double, val sessionsWithPairs: Int, val refSpan: Double, val levels: Int,
    val pearson: Double, val mae: Double, val maxAbs: Double, val meanRelPct: Double?, val bias: Double, val errStd: Double, val lagMs: Long,
    val perSessionMae: Map<String, Double>, val grades: Set<EvidenceGrade>, val referenceSources: Set<String>, val outliers: Int
)

data class FieldVerdict(
    val field: FieldSpec, val status: VerificationStatus, val baseStatus: VerificationStatus, val grade: EvidenceGrade,
    val hypothesis: DecoderHypothesis?, val sampleCount: Int, val lastSample: DecodedSample?, val metrics: Metrics?,
    val unmet: List<String>, val missingEvidence: List<String>, val criteriaFingerprint: String?, val reason: String
) {
    init { require(status != VerificationStatus.VERIFIED || (unmet.isEmpty() && metrics != null && hypothesis != null)) { "VERIFIED cần metrics, giả thuyết và không còn tiêu chí chưa đạt" } }
}

object TelemetryValidationEngine {
    private fun pearson(x: List<Double>, y: List<Double>): Double {
        if (x.size < 3) return 0.0
        val mx = x.average(); val my = y.average()
        val sxx = x.sumOf { (it - mx) * (it - mx) }; val syy = y.sumOf { (it - my) * (it - my) }
        if (sxx == 0.0 || syy == 0.0) return 0.0
        return x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / sqrt(sxx * syy)
    }

    private data class Pair3(val value: Double, val ref: Double, val session: String, val grade: EvidenceGrade, val source: String)

    private fun pair(samples: List<DecodedSample>, refs: List<ReferenceSample>, lag: Long, window: Long): List<Pair3> {
        if (samples.isEmpty()) return emptyList()
        val times = samples.map { it.timeMs }
        return refs.mapNotNull { r ->
            val target = r.timeMs + lag
            var lo = 0; var hi = times.size
            while (lo < hi) { val m = (lo + hi) / 2; if (times[m] < target) lo = m + 1 else hi = m }
            val cand = listOfNotNull(samples.getOrNull(lo - 1), samples.getOrNull(lo)).minByOrNull { abs(it.timeMs - target) } ?: return@mapNotNull null
            if (abs(cand.timeMs - target) > window) null else Pair3(cand.value, r.value, cand.sessionId, r.grade, r.source)
        }
    }

    private fun levels(vals: List<Double>, sep: Double): Int {
        if (vals.isEmpty()) return 0
        val s = vals.sorted(); var n = 1
        for (i in 1 until s.size) if (s[i] - s[i - 1] > sep) n++
        return n
    }

    private fun metricsOf(samples: List<DecodedSample>, refs: List<ReferenceSample>, c: VerificationCriteria): Metrics? {
        if (samples.isEmpty() || refs.isEmpty()) return null
        val sorted = samples.sortedBy { it.timeMs }
        val step = maxOf(50L, c.maxLagMs / 10)
        var bestLag = 0L; var bestMae = Double.MAX_VALUE; var bestPairs: List<Pair3> = emptyList()
        var lag = -c.maxLagMs
        while (lag <= c.maxLagMs) {
            val p = pair(sorted, refs, lag, c.pairWindowMs)
            if (p.size >= minOf(3, refs.size)) {
                val mae = p.sumOf { abs(it.value - it.ref) } / p.size
                if (mae < bestMae || (mae == bestMae && abs(lag) < abs(bestLag))) { bestMae = mae; bestLag = lag; bestPairs = p }
            }
            lag += step
        }
        if (bestPairs.isEmpty()) return Metrics(0, refs.size, 0.0, 0, 0.0, 0, 0.0, 0.0, 0.0, null, 0.0, 0.0, 0, emptyMap(), emptySet(), refs.map { it.source }.toSet(), 0)
        val err = bestPairs.map { it.value - it.ref }
        val rel = bestPairs.filter { abs(it.ref) > 1e-9 }.map { abs(it.value - it.ref) / abs(it.ref) * 100.0 }
        val bias = err.average(); val std = sqrt(err.sumOf { (it - bias) * (it - bias) } / err.size)
        val perSes = bestPairs.groupBy { it.session }.filterValues { it.size >= 3 }.mapValues { (_, l) -> l.sumOf { abs(it.value - it.ref) } / l.size }
        val refVals = bestPairs.map { it.ref }
        return Metrics(bestPairs.size, refs.size, bestPairs.size.toDouble() / refs.size, bestPairs.map { it.session }.toSet().size, refVals.max() - refVals.min(),
            levels(refVals, c.levelSeparation), pearson(bestPairs.map { it.value }, refVals), bestMae, err.maxOf { abs(it) }, rel.takeIf { it.isNotEmpty() }?.average(), bias, std, bestLag,
            perSes, bestPairs.map { it.grade }.toSet(), bestPairs.map { it.source }.toSet(), err.count { abs(it) > c.maxAbsError })
    }

    /** Trích mẫu giải mã từ payload đã ghi theo một giả thuyết (chỉ khung đúng loại/độ dài/UUID). */
    fun samplesFor(h: DecoderHypothesis, records: List<PayloadRecord>): Pair<List<DecodedSample>, FrameProofs> {
        val ck = OfflineLogAnalyzer.checksumByName(h.checksum)
        var ofType = 0; var lenOk = 0; var tested = 0; var pass = 0
        val out = ArrayList<DecodedSample>()
        for (r in records) {
            if (r.source == PayloadSource.ADVERTISEMENT) continue
            if (h.charUuid != null && !h.charUuid.equals(r.charUuid, true)) continue
            val b = r.bytes?.takeIf { it.isNotEmpty() } ?: continue
            if ((b[0].toInt() and 0xFF) != h.frameType) continue
            ofType++
            if (b.size != h.expectedLength) continue
            lenOk++
            if (ck != null) { tested++; if (ck(b)) pass++ else continue }
            val raw = h.raw(b) ?: continue
            out += DecodedSample(r.timeMs, h.scale * raw + h.offsetValue, raw, r.sessionId, r.hex)
        }
        return out to FrameProofs(ofType, lenOk, tested, pass)
    }

    /**
     * Đánh giá một trường. [nowMs] = thời điểm xét độ cũ (log ngoại tuyến: dùng thời điểm gói cuối).
     * Mọi trường chỉ lên VERIFIED khi toàn bộ tiêu chí đã khóa đạt; parser chạy được hoặc tổng kiểm hợp lệ KHÔNG đủ.
     */
    fun evaluate(
        field: FieldSpec, hypothesis: DecoderHypothesis?, criteria: LockedCriteria?, samples: List<DecodedSample>, proofs: FrameProofs?,
        refs: ReferenceDataset?, nowMs: Long, rawSeen: Boolean = false, inaccessibleReason: String? = null
    ): FieldVerdict {
        fun verdict(st: VerificationStatus, base: VerificationStatus, grade: EvidenceGrade, m: Metrics?, unmet: List<String>, missing: List<String>, reason: String) =
            FieldVerdict(field, st, base, grade, hypothesis, samples.size, samples.maxByOrNull { it.timeMs }, m, unmet, missing, criteria?.fingerprint, reason)

        if (criteria == null) {
            return if (hypothesis == null) verdict(VerificationStatus.UNAVAILABLE, VerificationStatus.UNAVAILABLE, EvidenceGrade.INACCESSIBLE, null, emptyList(),
                listOf("Phương thức xác minh được hỗ trợ cho trường này"), inaccessibleReason ?: "Không có tiêu chí/phương thức xác minh trong ứng dụng cho trường này.")
            else verdict(VerificationStatus.DECODED_HYPOTHESIS, VerificationStatus.DECODED_HYPOTHESIS, EvidenceGrade.SAMPLE_INFERENCE, null, emptyList(), listOf("Tiêu chí xác minh"), "Có giả thuyết nhưng không có tiêu chí xác minh.")
        }
        if (hypothesis == null) {
            return if (rawSeen) verdict(VerificationStatus.RAW_UNVERIFIED, VerificationStatus.RAW_UNVERIFIED, EvidenceGrade.NO_DATA, null, emptyList(), listOf("Giả thuyết giải mã", "Dữ liệu tham chiếu độc lập"), "Có dữ liệu thô nhưng chưa có giả thuyết giải mã cho trường này.")
            else verdict(VerificationStatus.UNAVAILABLE, VerificationStatus.UNAVAILABLE, if (inaccessibleReason != null) EvidenceGrade.INACCESSIBLE else EvidenceGrade.NO_DATA, null, emptyList(),
                listOf("Nguồn dữ liệu truy cập được", "Giả thuyết giải mã"), inaccessibleReason ?: "Chưa tìm thấy nguồn dữ liệu cho trường này trong dữ liệu đã có.")
        }
        val c = criteria.criteria
        val missing = ArrayList<String>(); val unmet = ArrayList<String>()
        if (!criteria.intact()) unmet += "Tiêu chí đã bị sửa sau khi khóa (vân tay không khớp)"
        val fieldRefs = refs?.samples.orEmpty().filter { it.fieldId == field.id }
        val unitMismatch = fieldRefs.count { it.unit != c.unit }
        val usable = fieldRefs.filter { it.unit == c.unit }
        val info = ArrayList<String>()
        if (unitMismatch > 0) info += "$unitMismatch mẫu tham chiếu khác đơn vị '${c.unit}' đã bị loại (không quy đổi ngầm)"
        if (samples.isEmpty()) missing += "Mẫu giải mã từ payload thật (không có gói đúng loại/độ dài/UUID)"
        val m = metricsOf(samples, usable, c)
        if (usable.isEmpty()) missing += "Dữ liệu tham chiếu độc lập (${c.unit}) có timestamp, nguồn và cấp bằng chứng"

        var base = VerificationStatus.DECODED_HYPOTHESIS
        var grade = EvidenceGrade.SAMPLE_INFERENCE
        if (m != null && m.pairs >= c.correlatedMinPairs && m.pearson >= c.correlatedMinPearson) {
            base = VerificationStatus.CORRELATED
            grade = when {
                EvidenceGrade.OFFICIAL_SOURCE in m.grades -> EvidenceGrade.OFFICIAL_SOURCE
                EvidenceGrade.INDEPENDENT_MEASUREMENT in m.grades -> EvidenceGrade.INDEPENDENT_MEASUREMENT
                EvidenceGrade.VEHICLE_CLUSTER in m.grades -> EvidenceGrade.VEHICLE_CLUSTER
                else -> EvidenceGrade.SAMPLE_INFERENCE
            }
            if (grade == EvidenceGrade.SAMPLE_INFERENCE) { base = VerificationStatus.DECODED_HYPOTHESIS; unmet += "Tham chiếu chỉ là suy luận từ mẫu, không phải phép đo độc lập" }
        } else if (m != null && m.pairs > 0) unmet += "Tương quan chưa đủ để gọi là CORRELATED (cần ≥${c.correlatedMinPairs} cặp và r ≥ ${c.correlatedMinPearson})"
        else if (m != null) unmet += "Không ghép được mẫu nào với tham chiếu trong cửa sổ ${c.pairWindowMs} ms (±${c.maxLagMs} ms độ trễ)"

        if (m != null && base == VerificationStatus.CORRELATED) {
            if (proofs == null || proofs.lengthOk == 0) unmet += "Chưa kiểm độ dài khung"
            else if (proofs.lengthOk < proofs.framesOfType) unmet += "Chỉ ${proofs.lengthOk}/${proofs.framesOfType} khung đúng độ dài kỳ vọng ${hypothesis.expectedLength}"
            if (c.checksumRequired) {
                if (proofs == null || proofs.checksumTested == 0) unmet += "Chưa có bằng chứng tổng kiểm"
                else if (proofs.checksumPass.toDouble() / proofs.checksumTested < c.minChecksumPass) unmet += "Tổng kiểm đạt ${proofs.checksumPass}/${proofs.checksumTested} < ${c.minChecksumPass}"
            }
            if (!grade.let { it in c.allowedGrades }) unmet += "Tham chiếu tốt nhất là '${grade.title}'; VERIFIED cần: ${c.allowedGrades.joinToString { it.title }}"
            if (m.pairs < c.minPairs) unmet += "Số cặp ${m.pairs} < ${c.minPairs}"
            if (m.sessionsWithPairs < c.minSessions || m.perSessionMae.size < c.minSessions) unmet += "Số phiên có ≥3 cặp ${m.perSessionMae.size} < ${c.minSessions} (cần tái lập nhiều phiên)"
            if (m.refSpan < c.minRefSpan) unmet += "Độ phủ tham chiếu ${"%.2f".format(Locale.US, m.refSpan)} < ${c.minRefSpan} ${c.unit}"
            if (m.levels < c.minLevels) unmet += "Số mức khác nhau ${m.levels} < ${c.minLevels}"
            if (m.pearson < c.minPearson) unmet += "Pearson r ${"%.4f".format(Locale.US, m.pearson)} < ${c.minPearson}"
            if (m.mae > c.maxMeanAbsError) unmet += "Sai số tuyệt đối TB ${"%.3f".format(Locale.US, m.mae)} > ${c.maxMeanAbsError} ${c.unit}"
            if (m.maxAbs > c.maxAbsError) unmet += "Sai số lớn nhất ${"%.3f".format(Locale.US, m.maxAbs)} > ${c.maxAbsError} ${c.unit}"
            if (m.errStd > c.maxErrStd) unmet += "Độ lệch chuẩn sai số ${"%.3f".format(Locale.US, m.errStd)} > ${c.maxErrStd}"
            if (m.coverage < c.minCoverage) unmet += "Độ phủ ghép cặp ${"%.0f".format(Locale.US, m.coverage * 100)}% < ${"%.0f".format(Locale.US, c.minCoverage * 100)}% (thiếu dữ liệu)"
            m.perSessionMae.forEach { (s, e) -> if (e > c.maxMeanAbsError) unmet += "Phiên $s: sai số TB ${"%.3f".format(Locale.US, e)} > ${c.maxMeanAbsError}" }
            if (criteria.origin == CriteriaOrigin.USER && refs != null && criteria.lockedAtMs > refs.importedAtMs) unmet += "Tiêu chí được khóa SAU khi nhập dữ liệu tham chiếu: không loại trừ việc chỉnh tiêu chí theo kết quả"
        }

        val verified = base == VerificationStatus.CORRELATED && unmet.isEmpty()
        val status = if (verified) VerificationStatus.VERIFIED else base
        val last = samples.maxByOrNull { it.timeMs }
        val stale = last != null && nowMs - last.timeMs > c.staleMs
        val shown = if (stale) VerificationStatus.STALE else status
        val reason = when {
            stale -> "Mẫu giải mã gần nhất cũ ${(nowMs - last!!.timeMs) / 1000} s (> ${c.staleMs / 1000} s): không hiển thị như giá trị hiện tại."
            verified -> "Đạt toàn bộ tiêu chí đã khóa (vân tay ${criteria.fingerprint})."
            status == VerificationStatus.CORRELATED -> "Đã đối chiếu nhưng chưa đạt: ${unmet.take(3).joinToString("; ")}"
            else -> "Chỉ là giả thuyết giải mã: ${(missing + unmet).take(3).joinToString("; ").ifEmpty { "chưa có tham chiếu" }}"
        }
        return FieldVerdict(field, shown, status, grade, hypothesis, samples.size, last, m, unmet, missing + info, criteria.fingerprint, reason)
    }
}
