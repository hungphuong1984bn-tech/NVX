package vn.nvxconnect.telemetry

/**
 * Vòng đời một trường theo yêu cầu nâng cấp: UNAVAILABLE → CANDIDATE → DECODED_HYPOTHESIS → CORRELATED → VERIFIED, thêm INVALID.
 * Đây là LỚP NHÌN (view) suy ra từ [FieldVerdict] do [TelemetryValidationEngine] cấp; không có đường gán tay, nên không thể
 * "nâng cấp" trạng thái vì parser chạy được, checksum hợp lệ hay giá trị trông hợp lý.
 *
 *  UNAVAILABLE: chưa tìm thấy nguồn dữ liệu / không có phương thức hợp lệ.
 *  CANDIDATE: có dữ liệu thô hoặc một công thức đã khai báo NHƯNG chưa giải mã được mẫu thật nào (chỉ là manh mối vị trí).
 *  DECODED_HYPOTHESIS: công thức đã giải mã ra mẫu từ payload thật, chưa đối chiếu độc lập.
 *  CORRELATED: đã đối chiếu độc lập (r ≥ ngưỡng, đủ cặp tối thiểu) nhưng chưa đạt toàn bộ tiêu chí đã khóa.
 *  VERIFIED: đạt toàn bộ tiêu chí đã khóa (nhiều mức, nhiều phiên, sai số).
 *  INVALID: giả thuyết bị BÁC BỎ — đã đủ số cặp theo tiêu chí tối thiểu nhưng tương quan vẫn dưới ngưỡng CORRELATED.
 */
enum class FieldLifecycle(val title: String, val rank: Int) {
    UNAVAILABLE("UNAVAILABLE", 0), CANDIDATE("CANDIDATE", 1), DECODED_HYPOTHESIS("DECODED_HYPOTHESIS", 2),
    CORRELATED("CORRELATED", 3), VERIFIED("VERIFIED", 4), INVALID("INVALID", -1)
}

/** [stale]: mẫu gần nhất đã quá hạn (cờ riêng, không làm mất trạng thái bằng chứng). */
data class FieldLifecycleState(val fieldId: String, val lifecycle: FieldLifecycle, val stale: Boolean, val displayAsCurrent: Boolean, val rationale: String)

object FieldLifecycles {
    fun of(v: FieldVerdict, criteria: LockedCriteria? = null): FieldLifecycleState {
        val stale = v.status == VerificationStatus.STALE
        val base = v.baseStatus
        val refuted = base != VerificationStatus.VERIFIED && v.metrics != null && criteria != null &&
            v.metrics.pairs >= criteria.criteria.minPairs && v.metrics.pearson < criteria.criteria.correlatedMinPearson
        val lc = when {
            refuted -> FieldLifecycle.INVALID
            base == VerificationStatus.VERIFIED -> FieldLifecycle.VERIFIED
            base == VerificationStatus.CORRELATED -> FieldLifecycle.CORRELATED
            base == VerificationStatus.DECODED_HYPOTHESIS -> if (v.sampleCount > 0) FieldLifecycle.DECODED_HYPOTHESIS else FieldLifecycle.CANDIDATE
            base == VerificationStatus.RAW_UNVERIFIED -> FieldLifecycle.CANDIDATE
            else -> FieldLifecycle.UNAVAILABLE
        }
        // Chỉ VERIFIED + còn mới mới được hiển thị như "giá trị hiện tại". Mọi mức khác phải kèm nhãn trạng thái.
        val current = lc == FieldLifecycle.VERIFIED && !stale
        val why = when (lc) {
            FieldLifecycle.INVALID -> "Giả thuyết bị bác bỏ: ${v.metrics!!.pairs} cặp, Pearson r=${"%.3f".format(java.util.Locale.US, v.metrics.pearson)} dưới ngưỡng ${criteria!!.criteria.correlatedMinPearson}."
            FieldLifecycle.CANDIDATE -> if (base == VerificationStatus.RAW_UNVERIFIED) "Có dữ liệu thô, chưa có công thức giải mã." else "Có công thức khai báo nhưng chưa giải mã được mẫu thật nào."
            else -> v.reason
        }
        return FieldLifecycleState(v.field.id, lc, stale, current, why)
    }

    fun all(verdicts: List<FieldVerdict>, criteriaOf: (String) -> LockedCriteria?): List<FieldLifecycleState> = verdicts.map { of(it, criteriaOf(it.field.id)) }
}

/**
 * Khai báo ĐẦY ĐỦ một bộ giải mã (mục 5 yêu cầu). Dựng từ [DecoderHypothesis] + bằng chứng đã đo; không tự thêm thông tin chưa có.
 * Một decoder chỉ áp dụng cho ĐÚNG nguồn (characteristic + loại khung + độ dài) đã khai báo — không có parser chung trộn nhiều characteristic.
 */
data class DecoderDeclaration(
    val decoderId: String, val decoderVersion: String, val fieldId: String,
    val sourceCharUuid: String?, val recognition: String, val minLength: Int, val maxLength: Int,
    val byteOffset: Int, val byteWidth: Int, val signed: Boolean, val byteOrder: ByteOrderKind,
    val formula: String, val unit: String, val checksum: String?, val checksumVerified: Boolean,
    val evidenceBasis: String, val framesTried: Int, val framesDecoded: Int, val framesRejected: Int, val limits: String
) {
    val rejectedRatio: Double get() = if (framesTried == 0) 0.0 else framesRejected.toDouble() / framesTried
}

object DecoderDeclarations {
    const val VERSION = "1"
    fun from(h: DecoderHypothesis, proofs: FrameProofs?, decoded: Int): DecoderDeclaration {
        val tried = proofs?.framesOfType ?: 0
        return DecoderDeclaration(
            decoderId = h.id, decoderVersion = VERSION, fieldId = h.fieldId, sourceCharUuid = h.charUuid,
            recognition = "byte[0] == 0x%02X và độ dài == %d%s".format(h.frameType, h.expectedLength, h.charUuid?.let { " và characteristic == $it" } ?: " (mọi characteristic — KHÔNG khuyến nghị)"),
            minLength = h.expectedLength, maxLength = h.expectedLength,
            byteOffset = h.byteOffset, byteWidth = h.byteCount, signed = h.signed, byteOrder = h.order,
            formula = h.formula, unit = h.unit, checksum = h.checksum,
            // Tổng kiểm chỉ được coi là ĐÃ XÁC MINH khi có thuật toán khai báo VÀ có khung đã thử đạt; đạt tổng kiểm chỉ là toàn vẹn khung.
            checksumVerified = h.checksum != null && (proofs?.checksumTested ?: 0) > 0 && proofs!!.checksumPass == proofs.checksumTested,
            evidenceBasis = h.basis, framesTried = tried, framesDecoded = decoded, framesRejected = (tried - decoded).coerceAtLeast(0),
            limits = "Chỉ áp dụng cho khung đúng loại/độ dài/characteristic ở trên. Công thức là GIẢ THUYẾT cho tới khi trường đạt VERIFIED; tổng kiểm hợp lệ không chứng minh ý nghĩa trường."
        )
    }
}
