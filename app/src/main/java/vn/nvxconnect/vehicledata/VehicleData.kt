package vn.nvxconnect.vehicledata

import vn.nvxconnect.domain.DataSource
import vn.nvxconnect.protocol.DecodedItem
import vn.nvxconnect.protocol.TelemetryHub
import vn.nvxconnect.protocol.VehicleParam
import java.util.Locale

/**
 * Trạng thái của MỘT giá trị xe:
 *  - VERIFIED: từ adapter đã xác minh, liên kết còn xác minh, còn mới (≤ [TelemetryHub] FRESH, 5 giây), nằm trong khoảng hợp lệ.
 *  - STALE: từng nhận hợp lệ nhưng đã cũ (hoặc liên kết không còn xác minh). Không được hiển thị như số hiện tại.
 *  - UNVERIFIED: có nhận được nhưng bị loại (ngoài khoảng, nguồn không đạt, không hữu hạn). Không đi vào giao diện/TFT.
 *  - UNAVAILABLE: chưa nhận gì.
 *  - UNSUPPORTED: adapter đã xác minh khai báo KHÔNG cung cấp tham số này.
 */
enum class DataStatus { VERIFIED, UNVERIFIED, STALE, UNAVAILABLE, UNSUPPORTED }

/**
 * Khoảng kiểm tra hợp lệ. LƯU Ý TRUNG THỰC: đây là giới hạn hợp lý về vật lý/điện để loại giá trị rác (ví dụ RPM âm),
 * KHÔNG phải thông số công bố của Yamaha cho NVX 155 V3 SP. Chưa có tài liệu hãng nêu giới hạn đo của từng cảm biến.
 * Khi có tài liệu/dữ liệu xe đã xác minh, thu hẹp khoảng ở đây và ghi nguồn vào [basis].
 */
data class ValidRange(val min: Double, val max: Double, val basis: String) {
    operator fun contains(v: Double) = v in min..max
}

object VehicleValueRules {
    const val SANITY = "Giới hạn hợp lý để loại giá trị rác; không phải thông số Yamaha"

    fun range(param: VehicleParam, unit: String): ValidRange? = when (param) {
        VehicleParam.SPEED_KMH -> ValidRange(0.0, 250.0, SANITY)
        VehicleParam.RPM -> ValidRange(0.0, 12_000.0, SANITY)
        VehicleParam.COOLANT_TEMP_C -> ValidRange(-40.0, 150.0, SANITY)
        VehicleParam.BATTERY_V -> ValidRange(0.0, 20.0, "Hệ thống điện 12 V; $SANITY")
        // Nhiên liệu: khoảng phụ thuộc đơn vị adapter khai báo. Đơn vị lạ → không có khoảng nên không thể VERIFIED (xem VehicleDataRepository).
        VehicleParam.FUEL_LEVEL -> when (unit.trim().lowercase(Locale.ROOT)) {
            "%" -> ValidRange(0.0, 100.0, "Phần trăm; $SANITY")
            "l", "lít", "lit" -> ValidRange(0.0, 30.0, "Lít, cùng giới hạn với VehicleSpecs.tankOrDefault; $SANITY")
            else -> null
        }
        VehicleParam.ODOMETER_KM -> ValidRange(0.0, 1_000_000.0, SANITY)
        VehicleParam.TRIP_KM -> ValidRange(0.0, 99_999.0, SANITY)
        VehicleParam.IGNITION_ON -> ValidRange(0.0, 1.0, "Cờ 0/1")
        // Chưa có tài liệu về mã hóa DTC / trạng thái bảo dưỡng: không có khoảng, chỉ yêu cầu số hữu hạn không âm.
        VehicleParam.DTC, VehicleParam.MAINT_STATE -> ValidRange(0.0, Double.MAX_VALUE, "Chưa có tài liệu mã hóa; chỉ yêu cầu số không âm")
    }
}

/** Một giá trị kèm đầy đủ ngữ cảnh để giao diện/báo cáo hiển thị minh bạch. */
data class TrackedValue(
    val param: VehicleParam,
    val status: DataStatus,
    val value: Double?,
    val unit: String?,
    val source: DataSource,
    val updatedAtMs: Long?,
    val ageMs: Long?,
    val evidenceId: String?,
    val note: String,
    val range: ValidRange?
) {
    /** Chỉ VERIFIED mới được phép hiển thị như số liệu xe hiện tại. */
    val displayable get() = status == DataStatus.VERIFIED && value != null
}

/** Một dòng nhật ký nguồn: mỗi giá trị đã giải mã đều có thể truy vết (nhận hay bị loại, vì sao). */
data class SourceLogEntry(
    val timeMs: Long, val param: VehicleParam, val value: Double, val unit: String,
    val evidenceId: String, val accepted: Boolean, val reason: String
)

/**
 * Kho dữ liệu xe: lớp xác thực đứng TRƯỚC [TelemetryHub].
 * Chỉ giá trị qua được mọi kiểm tra (liên kết đã xác minh, nguồn LIVE_VERIFIED, hữu hạn, trong khoảng) mới tới hub — và từ hub tới giao diện/TFT.
 * Không bao giờ suy giá trị từ GPS, không sinh số mẫu.
 */
class VehicleDataRepository(
    private val hub: TelemetryHub,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxLog: Int = 500
) {
    private val lock = Any()
    private val log = ArrayDeque<SourceLogEntry>()
    private val lastRejected = HashMap<VehicleParam, SourceLogEntry>()
    private var unknownFrames = 0L
    private var rejectedCount = 0L
    private var acceptedCount = 0L

    private fun record(e: SourceLogEntry) {
        log.addLast(e); while (log.size > maxLog) log.removeFirst()
        if (e.accepted) acceptedCount++ else { rejectedCount++; lastRejected[e.param] = e }
    }

    /** Trả số giá trị được nhận vào hub. */
    fun ingest(items: List<DecodedItem>, linkVerified: Boolean): Int {
        val now = clock()
        val accepted = ArrayList<DecodedItem>()
        synchronized(lock) {
            for (it in items) when (it) {
                is DecodedItem.Unknown -> unknownFrames++
                is DecodedItem.Value -> {
                    val reason = rejectReason(it, linkVerified)
                    record(SourceLogEntry(now, it.param, it.value, it.unit, it.evidenceId, reason == null, reason ?: "Đạt mọi kiểm tra"))
                    if (reason == null) { accepted += it; lastRejected.remove(it.param) }
                }
            }
        }
        // hub kiểm tra lại verified/nguồn lần nữa (phòng thủ nhiều lớp)
        return hub.ingest(accepted, linkVerified)
    }

    private fun rejectReason(v: DecodedItem.Value, linkVerified: Boolean): String? {
        if (!linkVerified) return "Liên kết chưa xác minh giao thức"
        if (v.source != DataSource.LIVE_VERIFIED) return "Nguồn ${v.source.label} không phải LIVE_VERIFIED"
        if (!v.value.isFinite()) return "Giá trị không hữu hạn"
        val r = VehicleValueRules.range(v.param, v.unit)
            ?: return "Đơn vị \"${v.unit}\" chưa được hỗ trợ để kiểm tra khoảng cho ${v.param}"
        if (v.value !in r) return "Ngoài khoảng hợp lệ [${r.min}; ${r.max}] (${r.basis})"
        return null
    }

    /**
     * Ảnh chụp trạng thái mọi tham số.
     * @param supported tập tham số adapter đã xác minh khai báo (null = adapter không khai báo → không suy ra UNSUPPORTED).
     */
    fun snapshot(linkVerified: Boolean, supported: Set<VehicleParam>?, nowMs: Long = clock()): List<TrackedValue> {
        val tele = hub.state.value
        return VehicleParam.entries.map { p ->
            val live = tele.values[p]
            val fresh = tele.fresh(p, nowMs)
            val rej = synchronized(lock) { lastRejected[p] }
            when {
                supported != null && p !in supported ->
                    TrackedValue(p, DataStatus.UNSUPPORTED, null, null, DataSource.UNAVAILABLE, null, null, null,
                        "Hồ sơ giao thức đã xác minh khai báo không cung cấp tham số này", null)
                fresh != null && linkVerified ->
                    TrackedValue(p, DataStatus.VERIFIED, fresh.value, fresh.unit, DataSource.LIVE_VERIFIED, fresh.timeMs, nowMs - fresh.timeMs,
                        fresh.evidenceId, "Từ adapter đã xác minh, còn mới, trong khoảng hợp lệ", VehicleValueRules.range(p, fresh.unit))
                live != null ->
                    TrackedValue(p, DataStatus.STALE, live.value, live.unit, DataSource.LIVE_VERIFIED, live.timeMs, nowMs - live.timeMs, live.evidenceId,
                        if (linkVerified) "Đã cũ hơn ${TelemetryHub_FRESH_S} giây: không dùng như số hiện tại" else "Liên kết không còn xác minh: không dùng như số hiện tại",
                        VehicleValueRules.range(p, live.unit))
                rej != null ->
                    TrackedValue(p, DataStatus.UNVERIFIED, null, rej.unit, DataSource.UNAVAILABLE, rej.timeMs, nowMs - rej.timeMs, rej.evidenceId,
                        "Đã nhận nhưng bị loại: ${rej.reason}", VehicleValueRules.range(p, rej.unit))
                else ->
                    TrackedValue(p, DataStatus.UNAVAILABLE, null, null, DataSource.UNAVAILABLE, null, null, null, "Chưa nhận giá trị nào từ xe", null)
            }
        }
    }

    fun sourceLog(): List<SourceLogEntry> = synchronized(lock) { log.toList() }
    fun stats(): Triple<Long, Long, Long> = synchronized(lock) { Triple(acceptedCount, rejectedCount, unknownFrames) }

    /** CSV nhật ký nguồn (giá trị số, không chứa dữ liệu nhận dạng). */
    fun sourceLogCsv(): String = buildString {
        append("time_ms,param,value,unit,evidence_id,accepted,reason\n")
        for (e in sourceLog()) append("${e.timeMs},${e.param},${e.value},${csv(e.unit)},${csv(e.evidenceId)},${e.accepted},${csv(e.reason)}\n")
    }

    private fun csv(s: String) = vn.nvxconnect.bluetooth.LogExporter.csvCell(s)

    fun clear() { synchronized(lock) { log.clear(); lastRejected.clear(); unknownFrames = 0; rejectedCount = 0; acceptedCount = 0 }; hub.reset() }

    private companion object { val TelemetryHub_FRESH_S = vn.nvxconnect.protocol.VehicleTelemetry.FRESH_MS / 1000 }
}
