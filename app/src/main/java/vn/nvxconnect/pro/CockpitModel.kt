package vn.nvxconnect.pro

import vn.nvxconnect.protocol.VehicleParam
import vn.nvxconnect.protocol.VehicleTelemetry

/** Số liệu có thể hiện trên Cockpit. [param] = null: nguồn là GPS điện thoại, không phải xe. */
enum class CockpitMetric(val key: String, val title: String, val unit: String, val param: VehicleParam?, val maxHint: Double) {
    GPS_SPEED("gps_speed", "Tốc độ GPS", "km/h", null, 120.0),
    RPM("rpm", "Vòng tua", "rpm", VehicleParam.RPM, 10_000.0),
    FUEL("fuel", "Xăng", "", VehicleParam.FUEL_LEVEL, 100.0),
    COOLANT("coolant", "Nhiệt độ động cơ", "°C", VehicleParam.COOLANT_TEMP_C, 120.0),
    VOLTAGE("voltage", "Điện áp", "V", VehicleParam.BATTERY_V, 16.0);
    companion object { fun byKey(k: String) = values().firstOrNull { it.key == k } }
}

/**
 * Trạng thái hiển thị của một ô số liệu. KHÔNG có trạng thái "giữ số cũ như số trực tiếp":
 *  - LIVE: có giá trị mới và hợp lệ → được hiện số.
 *  - STALE: từng có nhưng đã cũ → KHÔNG hiện số, chỉ báo tuổi dữ liệu.
 *  - WAITING: liên kết đã xác minh nhưng chưa nhận giá trị.
 *  - NO_SOURCE: chưa có nguồn xác minh (chưa kết nối / giao thức chưa xác minh / số đo của GPS không có fix).
 */
enum class GaugeState { LIVE, STALE, WAITING, NO_SOURCE }

data class Gauge(val metric: CockpitMetric, val state: GaugeState, val value: Double? = null, val unit: String = metric.unit, val ageMs: Long? = null, val note: String) {
    val showsValue get() = state == GaugeState.LIVE && value != null
    /** Tỉ lệ 0..1 cho vòng đo; chỉ có khi hiện số. */
    val fraction: Float? get() = if (showsValue && metric.maxHint > 0) (value!! / metric.maxHint).coerceIn(0.0, 1.0).toFloat() else null
}

object CockpitModel {
    const val GPS_FRESH_MS = 3_000L
    const val GPS_MAX_ACCURACY_M = 50f

    /** Tốc độ GPS: chỉ khi fix còn mới và độ chính xác đạt. Không bao giờ gán nhãn là tốc độ ECU. */
    fun gpsSpeed(speedMps: Float?, accuracyM: Float?, fixTimeMs: Long?, nowMs: Long): Gauge {
        val m = CockpitMetric.GPS_SPEED
        if (speedMps == null || fixTimeMs == null) return Gauge(m, GaugeState.NO_SOURCE, note = "Chưa có vị trí GPS")
        val age = nowMs - fixTimeMs
        if (age < 0 || age > GPS_FRESH_MS) return Gauge(m, GaugeState.STALE, ageMs = age.coerceAtLeast(0), note = "GPS cũ ${age / 1000} giây — không hiện số")
        if (!speedMps.isFinite() || (accuracyM ?: Float.MAX_VALUE) > GPS_MAX_ACCURACY_M) return Gauge(m, GaugeState.WAITING, ageMs = age, note = "Độ chính xác GPS chưa đủ")
        return Gauge(m, GaugeState.LIVE, value = speedMps * 3.6, ageMs = age, note = "GPS điện thoại (không phải tốc độ ECU)")
    }

    /** Số liệu xe: chỉ từ [VehicleTelemetry] (đã qua kiểm tra xác minh) khi liên kết còn xác minh và giá trị còn mới. */
    fun vehicle(m: CockpitMetric, t: VehicleTelemetry, linkVerified: Boolean, nowMs: Long): Gauge {
        val p = m.param ?: return Gauge(m, GaugeState.NO_SOURCE, note = "Không có nguồn từ xe")
        if (!linkVerified) return Gauge(m, GaugeState.NO_SOURCE, note = "Chưa có giao thức xe được xác minh")
        val raw = t.values[p] ?: return Gauge(m, GaugeState.WAITING, note = "Đang chờ giá trị từ xe")
        val fresh = t.fresh(p, nowMs)
        val age = (nowMs - raw.timeMs).coerceAtLeast(0)
        return if (fresh != null) Gauge(m, GaugeState.LIVE, value = fresh.value, unit = fresh.unit.ifBlank { m.unit }, ageMs = age, note = "Xe (đã xác minh)")
        else Gauge(m, GaugeState.STALE, ageMs = age, note = "Dữ liệu cũ ${age / 1000} giây — không hiện số")
    }

    /** Toàn bộ ô theo thứ tự cố định, bỏ ô người dùng đã ẩn. GPS_SPEED luôn hiện (không thể ẩn). */
    fun gauges(hidden: Set<String>, t: VehicleTelemetry, linkVerified: Boolean, speedMps: Float?, accuracyM: Float?, fixTimeMs: Long?, nowMs: Long): List<Gauge> =
        CockpitMetric.values().filter { it == CockpitMetric.GPS_SPEED || it.key !in hidden }.map {
            if (it == CockpitMetric.GPS_SPEED) gpsSpeed(speedMps, accuracyM, fixTimeMs, nowMs) else vehicle(it, t, linkVerified, nowMs)
        }

    /** Chất lượng dữ liệu tổng hợp để hiện ở thanh trạng thái: dựa trên các ô xe đang hiển thị. */
    fun quality(g: List<Gauge>): String {
        val v = g.filter { it.metric.param != null }
        return when {
            v.isEmpty() -> "Không có ô số liệu xe nào đang bật"
            v.any { it.state == GaugeState.LIVE } && v.any { it.state == GaugeState.STALE } -> "Một phần dữ liệu xe đã cũ"
            v.any { it.state == GaugeState.LIVE } -> "Dữ liệu xe trực tiếp"
            v.any { it.state == GaugeState.STALE } -> "Dữ liệu xe đã cũ"
            v.any { it.state == GaugeState.WAITING } -> "Đang chờ dữ liệu xe"
            else -> "Chưa có nguồn dữ liệu xe đã xác minh"
        }
    }

    fun encodeHidden(h: Set<String>): String = h.filter { CockpitMetric.byKey(it) != null && it != CockpitMetric.GPS_SPEED.key }.sorted().joinToString(",")
    fun decodeHidden(s: String?): Set<String> = (s ?: "").split(',').map { it.trim() }.filter { CockpitMetric.byKey(it) != null && it != CockpitMetric.GPS_SPEED.key }.toSet()
}
