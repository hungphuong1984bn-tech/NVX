package vn.nvxconnect.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Giá trị ỨNG VIÊN (giả thuyết) suy ra từ thống kê gói thật. KHÔNG phải dữ liệu đã xác minh:
 *  - không đi qua [TelemetryHub], không vào TFT, không mang nguồn LIVE_VERIFIED;
 *  - giao diện phải hiển thị kèm nhãn [YccCandidate.label] và [YccCandidate.basis];
 *  - muốn thành VERIFIED: đối chiếu với đồng hồ xe/đồng hồ vạn năng theo docs/YCC_PROTOCOL_FINDINGS.md rồi đăng ký adapter thủ công.
 */
data class YccCandidate(
    val id: String, val param: VehicleParam, val value: Double, val unit: String, val timeMs: Long,
    val raw: String, val confidence: Confidence, val basis: String,
    /** Giá trị thô chưa quy đổi (đầu vào hiệu chỉnh). */
    val rawValue: Double = value,
    /** Có hệ số hiệu chỉnh từ điểm tham chiếu thật hay không, và sai số RMSE của nó (đơn vị của [unit]). */
    val calibrated: Boolean = false, val errorBand: Double? = null, val calPoints: Int = 0
) {
    val label get() = if (calibrated) "GIẢ THUYẾT đã hiệu chỉnh ($calPoints điểm, ±${"%.2f".format(errorBand ?: 0.0)} $unit) — chưa xác minh độc lập"
                      else "GIẢ THUYẾT — chưa xác minh"
}

enum class Confidence { LOW, MEDIUM }

object YccCandidates {
    const val RPM_ID = "ycc.rpm.u16be@5"
    const val BATT_ID = "ycc.batt.u8@11x0.07"

    private val DEFAULT_SCALE = mapOf(RPM_ID to 1.0, BATT_ID to 0.07)

    /** Quy đổi giá trị thô: dùng hệ số hiệu chỉnh CHỈ khi đạt chất lượng GOOD, ngược lại dùng hệ số giả thuyết. */
    fun convert(id: String, raw: Double, cal: Map<String, CalFit>): Triple<Double, Boolean, CalFit?> {
        val c = cal[id]?.takeIf { Calibration.quality(it, CalSpecs.ALL.getValue(id)) == CalQuality.GOOD }
        return Triple(if (c != null) c.apply(raw) else raw * (DEFAULT_SCALE[id] ?: 1.0), c != null, c)
    }

    /** Dựng lại ứng viên với giá trị thô đã lọc (trung vị), giữ nguyên mọi thuộc tính khác. */
    fun withRaw(c: YccCandidate, raw: Double, cal: Map<String, CalFit>): YccCandidate {
        val r = convert(c.id, raw, cal)
        return c.copy(value = r.first, rawValue = raw, calibrated = r.second, errorBand = r.third?.rmse, calPoints = r.third?.n ?: 0)
    }

    fun decode(f: YccFrame, timeMs: Long, cal: Map<String, CalFit> = emptyMap()): List<YccCandidate> {
        if (f.type != YccFrames.T_STATUS) return emptyList()
        val rawRpm = f.u16be(5)
        val rawBatt = f.u8(11)
        val rp = convert(RPM_ID, rawRpm.toDouble(), cal); val bt = convert(BATT_ID, rawBatt.toDouble(), cal)
        return listOf(
            YccCandidate(RPM_ID, VehicleParam.RPM, rp.first, "rpm", timeMs, "%04X".format(rawRpm), Confidence.MEDIUM,
                "Trường 16-bit big-endian ở byte 5–6: =0 ở 99,9% gói khi chưa nổ máy; 676–3043 khi nổ; ~1.5–1.7k lúc cầm chừng; " +
                    "tương quan 0,97 với một trường độc lập trong khung 0x6C. Chưa đối chiếu với đồng hồ vòng tua.",
                rawValue = rawRpm.toDouble(), calibrated = rp.second, errorBand = rp.third?.rmse, calPoints = rp.third?.n ?: 0),
            YccCandidate(BATT_ID, VehicleParam.BATTERY_V, bt.first, "V", timeMs, "%02X".format(rawBatt), Confidence.LOW,
                "Byte 11: giảm chậm khi tắt máy, tụt khi đề (0x9C), tăng ~0xC3 khi nổ máy (điển hình điện áp sạc). Hệ số 0,07 là ƯỚC LƯỢNG; cần đo bằng vạn năng.",
                rawValue = rawBatt.toDouble(), calibrated = bt.second, errorBand = bt.third?.rmse, calPoints = bt.third?.n ?: 0)
        )
    }
}

/** Kho giá trị ứng viên (tách hẳn khỏi [TelemetryHub]). Cũ hơn [FRESH_MS] coi như không có. */
class YccCandidateHub {
    private val _state = MutableStateFlow<Map<String, YccCandidate>>(emptyMap())
    val state: StateFlow<Map<String, YccCandidate>> = _state.asStateFlow()
    fun ingest(items: List<YccCandidate>) { if (items.isNotEmpty()) _state.update { it + items.associateBy { c -> c.id } } }
    fun reset() { _state.value = emptyMap() }
    companion object {
        const val FRESH_MS = 3000L
        fun fresh(m: Map<String, YccCandidate>, id: String, nowMs: Long): YccCandidate? = m[id]?.takeIf { nowMs - it.timeMs in 0..FRESH_MS }
    }
}
