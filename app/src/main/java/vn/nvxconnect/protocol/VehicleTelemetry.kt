package vn.nvxconnect.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import vn.nvxconnect.domain.DataSource
import java.util.Locale

/** Một giá trị ĐÃ GIẢI MÃ từ xe bằng adapter đã xác minh. Không bao giờ do app tự đặt. */
data class LiveValue(val value: Double, val unit: String, val timeMs: Long, val evidenceId: String)

data class VehicleTelemetry(val values: Map<VehicleParam, LiveValue> = emptyMap()) {
    /** Giá trị còn mới (mặc định ≤ 5 giây); cũ hơn coi như CHƯA CÓ để không hiển thị số đã lỗi thời. */
    fun fresh(p: VehicleParam, nowMs: Long, maxAgeMs: Long = FRESH_MS): LiveValue? = values[p]?.takeIf { nowMs - it.timeMs in 0..maxAgeMs }
    companion object { const val FRESH_MS = 5000L }
}

fun formatLive(v: LiveValue): String {
    val n = if (v.value % 1.0 == 0.0) String.format(Locale.US, "%.0f", v.value) else String.format(Locale.US, "%.1f", v.value)
    return if (v.unit.isBlank()) n else "$n ${v.unit}"
}

/**
 * Điểm duy nhất nhận dữ liệu xe thật cho giao diện và TFT. Nhận vào CHỈ khi: liên kết đã xác minh giao thức, mục là Value,
 * nguồn là LIVE_VERIFIED và số hữu hạn. Mọi thứ khác bị bỏ. Ngắt kết nối/mất xác minh thì xóa sạch.
 */
class TelemetryHub {
    private val _state = MutableStateFlow(VehicleTelemetry())
    val state: StateFlow<VehicleTelemetry> = _state.asStateFlow()
    @Volatile var linkVerified: Boolean = false

    /** Trả về số giá trị được nhận. */
    fun ingest(items: List<DecodedItem>, verified: Boolean): Int {
        if (!verified) return 0
        val ok = items.filterIsInstance<DecodedItem.Value>().filter { it.source == DataSource.LIVE_VERIFIED && it.value.isFinite() }
        if (ok.isEmpty()) return 0
        _state.update { cur -> VehicleTelemetry(cur.values + ok.associate { it.param to LiveValue(it.value, it.unit, it.timeMs, it.evidenceId) }) }
        return ok.size
    }

    fun reset() { _state.value = VehicleTelemetry() }
}

// ------------------------------------------------------------------ khung hiển thị lên TFT

data class TftPrefs(val mapOnly: Boolean = false, val speed: Boolean = true, val route: Boolean = true,
                    val trip: Boolean = true, val vehicle: Boolean = true, val clock: Boolean = true)

data class TftField(val key: String, val label: String, val text: String, val source: DataSource)

data class TftFrame(val fields: List<TftField>) {
    val isEmpty get() = fields.isEmpty()
    fun field(key: String) = fields.firstOrNull { it.key == key }
}

/**
 * Dựng khung từ sở thích + dữ liệu. Tốc độ và xăng xe CHỈ lấy từ xe (LIVE_VERIFIED, còn mới); không có thì bỏ trường đó,
 * tuyệt đối không điền GPS/ước tính thay vào. Chỉ đường và thời gian đến là dữ liệu điện thoại, gắn nhãn đúng nguồn.
 */
object TftComposer {
    fun compose(p: TftPrefs, t: VehicleTelemetry, maneuver: String?, remaining: String?, clockText: String?, nowMs: Long): TftFrame {
        val f = ArrayList<TftField>()
        if ((p.route || p.mapOnly) && maneuver != null) f += TftField("route", "Chỉ đường", maneuver, DataSource.GPS_ESTIMATE)
        if (!p.mapOnly) {
            if (p.trip && remaining != null) f += TftField("trip", "Hành trình", remaining, DataSource.CALCULATED)
            if (p.speed) t.fresh(VehicleParam.SPEED_KMH, nowMs)?.let { f += TftField("speed", "Tốc độ", formatLive(it), DataSource.LIVE_VERIFIED) }
            if (p.vehicle) t.fresh(VehicleParam.FUEL_LEVEL, nowMs)?.let { f += TftField("fuel", "Xăng xe", formatLive(it), DataSource.LIVE_VERIFIED) }
            if (p.clock && clockText != null) f += TftField("clock", "Thời gian", clockText, DataSource.CALCULATED)
        }
        return TftFrame(f)
    }
}

/**
 * Cổng gửi khung lên TFT. Chỉ gửi khi: có adapter TFT VERIFIED_READY, liên kết xe đã xác minh giao thức, và khung không rỗng.
 * Hiện KHÔNG có adapter nên luôn là Idle và không gửi gì.
 */
class TftDisplayPipeline(private val bridge: () -> TftNavigationBridge) {
    sealed interface Outcome {
        data class Idle(val reason: String) : Outcome
        data object Sent : Outcome
        data class Rejected(val reason: String) : Outcome
    }
    private val _last = MutableStateFlow<Outcome>(Outcome.Idle("Chưa gửi"))
    val last: StateFlow<Outcome> = _last.asStateFlow()

    fun push(frame: TftFrame, linkVerified: Boolean): Outcome {
        val b = bridge()
        val o: Outcome = when {
            b.status != TftBridgeStatus.VERIFIED_READY -> Outcome.Idle(b.limitation)
            !linkVerified -> Outcome.Idle("Chưa kết nối xe hoặc chưa xác minh giao thức.")
            frame.isEmpty -> Outcome.Idle("Không có dữ liệu để hiển thị.")
            else -> when (val r = b.sendFrame(frame)) { is TftSendResult.Sent -> Outcome.Sent; is TftSendResult.Rejected -> Outcome.Rejected(r.reason) }
        }
        _last.value = o
        return o
    }
}
