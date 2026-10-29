package vn.nvxconnect.telemetry

import vn.nvxconnect.protocol.VehicleParam
import vn.nvxconnect.vehicledata.DataStatus
import vn.nvxconnect.vehicledata.TrackedValue
import java.util.Locale

/** Một dòng số liệu trên màn hình Dữ liệu xe chuyên sâu / trong nhật ký. [value] CHỈ có khi [cls] là REAL hoặc DERIVED (số dùng được). */
data class DiagRow(
    val id: String, val title: String, val group: String, val unit: String, val cls: DiagClass,
    val value: Double?, val source: DiagSource?, val updatedWallMs: Long?, val ageMs: Long?, val note: String,
    /** Giá trị cuối cùng đã nhận nhưng KHÔNG còn dùng được (quá hạn/ngoài khoảng): chỉ để hiển thị mờ, không bao giờ ghi như mẫu. */
    val lastUnusableValue: Double? = null
) {
    val usable: Boolean get() = value != null && value.isFinite() && (cls == DiagClass.REAL || cls == DiagClass.DERIVED)
}

/** Trạng thái liên kết do app quan sát được. Không số nào ở đây do app tự đặt. */
data class DiagLink(
    val stateCode: String, val stateText: String, val ready: Boolean, val rssi: Int?, val mtu: Int?,
    val framesTotal: Long, val framesLost: Long, val ratePerSec: Double?, val lastFrameWallMs: Long?, val maxGapMs: Long?,
    val protocolVerified: Boolean, val verifiedOnVehicle: Boolean, val adapterId: String? = null, val hint: String? = null
)

/** Mẫu GPS điện thoại (không phải ECU). */
data class GpsSample(val speedKmh: Double, val timeMs: Long, val accuracyM: Double)

data class DiagSnapshot(val wallMs: Long, val rows: List<DiagRow>, val link: DiagLink) {
    fun row(id: String): DiagRow? = rows.firstOrNull { it.id == id }
}

object DiagIds {
    const val VEH_PREFIX = "veh."
    fun veh(p: VehicleParam) = VEH_PREFIX + p.name.lowercase(Locale.ROOT)
    const val VEH_CURRENT = "veh.current_a"; const val VEH_SOC = "veh.soc_pct"; const val VEH_CONTROLLER = "veh.controller_state"
    const val GPS_SPEED = "gps.speed_kmh"; const val GPS_DISTANCE = "gps.distance_m"
    const val BLE_RSSI = "ble.rssi_dbm"; const val BLE_MTU = "ble.mtu"; const val BLE_FRAMES = "ble.frames_total"; const val BLE_LOST = "ble.frames_lost"
    const val BLE_RATE = "ble.rate_pps"; const val BLE_SILENCE = "ble.silence_ms"
    const val BLE_CONNECT_MS = "ble.connect_ms"; const val BLE_FIRST_FRAME_MS = "ble.first_frame_ms"
}

/**
 * Danh mục số liệu hiển thị. Tham số xe lấy từ [VehicleParam] (những gì giao thức có thể khai báo). Dòng "chưa hỗ trợ" cố định
 * (dòng điện, mức sạc pin, bộ điều khiển) tồn tại để kỹ thuật viên thấy RÕ app KHÔNG đọc được chúng — không đoán UUID/PID/byte.
 */
object DiagCatalog {
    const val G_MOTION = "Chuyển động"; const val G_ELECTRIC = "Điện"; const val G_THERMAL = "Nhiệt độ"; const val G_STATE = "Trạng thái xe"
    const val G_PHONE = "Điện thoại (suy tính)"; const val G_BLE = "Bluetooth / liên kết"
    const val UNSUPPORTED_NOTE = "Chưa có tham số này trong giao thức đã xác minh. App không suy đoán UUID, PID hay ý nghĩa byte."

    data class Def(val param: VehicleParam, val title: String, val unit: String, val group: String)

    val vehicle: List<Def> = listOf(
        Def(VehicleParam.SPEED_KMH, "Tốc độ (xe)", "km/h", G_MOTION),
        Def(VehicleParam.ODOMETER_KM, "Tổng quãng đường ODO (xe)", "km", G_MOTION),
        Def(VehicleParam.TRIP_KM, "Quãng đường chuyến (xe tính)", "km", G_MOTION),
        Def(VehicleParam.RPM, "Vòng tua động cơ", "rpm", G_MOTION),
        Def(VehicleParam.BATTERY_V, "Điện áp ắc quy", "V", G_ELECTRIC),
        Def(VehicleParam.COOLANT_TEMP_C, "Nhiệt độ động cơ/nước làm mát", "°C", G_THERMAL),
        Def(VehicleParam.FUEL_LEVEL, "Mức nhiên liệu", "", G_STATE),
        Def(VehicleParam.IGNITION_ON, "Khóa điện (0/1)", "", G_STATE),
        Def(VehicleParam.DTC, "Mã lỗi chẩn đoán (DTC)", "", G_STATE),
        Def(VehicleParam.MAINT_STATE, "Trạng thái bảo dưỡng do xe báo", "", G_STATE)
    )

    /** Các mục người dùng yêu cầu nhưng giao thức hiện KHÔNG có tham số tương ứng. */
    val alwaysUnsupported: List<Triple<String, String, Pair<String, String>>> = listOf(
        Triple(DiagIds.VEH_CURRENT, "Dòng điện", "A" to G_ELECTRIC),
        Triple(DiagIds.VEH_SOC, "Pin — mức sạc", "%" to G_ELECTRIC),
        Triple(DiagIds.VEH_CONTROLLER, "Trạng thái bộ điều khiển (ECU/controller)", "" to G_STATE)
    )
}

object DiagSnapshotBuilder {
    /** Tuổi tối đa của một mẫu GPS để còn được coi là "hiện tại". */
    const val GPS_FRESH_MS = 5_000L
    const val NO_PROTOCOL_NOTE = "Chưa có giao thức xe nào được xác minh trên xe thật; app không đọc/giải mã tham số này và không đoán."

    /**
     * Dựng ảnh chụp từ dữ liệu đã có. [tracked] = [vn.nvxconnect.vehicledata.VehicleDataRepository.snapshot] (đã qua kiểm tra khoảng hợp lệ).
     * Quy tắc phân loại: VERIFIED→REAL; STALE/UNVERIFIED→INVALID; UNSUPPORTED→UNSUPPORTED; chưa nhận gì→NO_DATA,
     * NHƯNG nếu liên kết chưa có giao thức xác minh thì "chưa nhận gì" được nói thẳng là UNSUPPORTED (không phải "đang chờ").
     */
    fun build(nowMs: Long, tracked: List<TrackedValue>, gps: GpsSample?, gpsDistanceM: Double?, link: DiagLink): DiagSnapshot {
        val rows = ArrayList<DiagRow>()
        for (d in DiagCatalog.vehicle) {
            val t = tracked.firstOrNull { it.param == d.param }
            val unit = t?.unit?.takeIf { it.isNotBlank() } ?: d.unit
            val id = DiagIds.veh(d.param)
            rows += when {
                t == null || t.status == DataStatus.UNAVAILABLE ->
                    if (!link.protocolVerified) DiagRow(id, d.title, d.group, unit, DiagClass.UNSUPPORTED, null, null, null, null, DiagSnapshotBuilder.NO_PROTOCOL_NOTE)
                    else DiagRow(id, d.title, d.group, unit, DiagClass.NO_DATA, null, null, null, null, "Chưa nhận mẫu nào từ xe")
                t.status == DataStatus.UNSUPPORTED -> DiagRow(id, d.title, d.group, unit, DiagClass.UNSUPPORTED, null, null, null, null, t.note)
                t.status == DataStatus.VERIFIED && t.value != null ->
                    DiagRow(id, d.title, d.group, unit, DiagClass.REAL, t.value, DiagSource.VEHICLE, t.updatedAtMs, t.ageMs, t.note)
                else -> DiagRow(id, d.title, d.group, unit, DiagClass.INVALID, null, null, t.updatedAtMs, t.ageMs, t.note, lastUnusableValue = t.value)
            }
        }
        for ((id, title, ug) in DiagCatalog.alwaysUnsupported)
            rows += DiagRow(id, title, ug.second, ug.first, DiagClass.UNSUPPORTED, null, null, null, null, DiagCatalog.UNSUPPORTED_NOTE)

        // Suy tính từ GPS điện thoại: luôn gắn nhãn DERIVED + PHONE_GPS, không bao giờ thay thế tốc độ của xe.
        val gpsFresh = gps != null && nowMs - gps.timeMs in 0..GPS_FRESH_MS && gps.speedKmh.isFinite() && gps.speedKmh >= 0
        rows += if (gpsFresh) DiagRow(DiagIds.GPS_SPEED, "Tốc độ GPS (điện thoại)", DiagCatalog.G_PHONE, "km/h", DiagClass.DERIVED, gps!!.speedKmh, DiagSource.PHONE_GPS, gps.timeMs, nowMs - gps.timeMs,
            "GPS điện thoại, độ chính xác ±${gps.accuracyM.toInt()} m — KHÔNG phải tốc độ ECU")
        else DiagRow(DiagIds.GPS_SPEED, "Tốc độ GPS (điện thoại)", DiagCatalog.G_PHONE, "km/h", DiagClass.NO_DATA, null, null, null, null,
            if (gps == null) "Chưa có tọa độ GPS (GPS chỉ chạy khi đang ghi hành trình)" else "GPS đã cũ, không dùng như số hiện tại")
        rows += if (gpsDistanceM != null && gpsDistanceM.isFinite()) DiagRow(DiagIds.GPS_DISTANCE, "Quãng đường GPS (cộng dồn)", DiagCatalog.G_PHONE, "m", DiagClass.DERIVED, gpsDistanceM, DiagSource.PHONE_GPS, null, null,
            "Chỉ cộng các cặp mẫu GPS liền kề; khoảng GPS mất tín hiệu KHÔNG được nội suy")
        else DiagRow(DiagIds.GPS_DISTANCE, "Quãng đường GPS (cộng dồn)", DiagCatalog.G_PHONE, "m", DiagClass.NO_DATA, null, null, null, null, "Chưa có dữ liệu GPS")

        // Liên kết Bluetooth: đo thật ở điện thoại (REAL) hoặc tính từ mốc thời gian gói thật (DERIVED).
        val silence = if (link.ready && link.lastFrameWallMs != null) (nowMs - link.lastFrameWallMs).takeIf { it >= 0 } else null
        rows += linkRow(DiagIds.BLE_RSSI, "RSSI", "dBm", link.rssi?.toDouble().takeIf { link.stateCode != "DISCONNECTED" }, DiagClass.REAL, DiagSource.PHONE_BLE, "Điện thoại đo từ liên kết đang mở")
        rows += linkRow(DiagIds.BLE_MTU, "MTU đã thỏa thuận", "byte", link.mtu?.toDouble().takeIf { link.ready }, DiagClass.REAL, DiagSource.PHONE_BLE, "Kết quả thỏa thuận GATT")
        rows += linkRow(DiagIds.BLE_FRAMES, "Số gói đã nhận (phiên app)", "gói", link.framesTotal.toDouble().takeIf { link.framesTotal > 0 || link.ready }, DiagClass.DERIVED, DiagSource.APP, "Đếm gói thật nhận được")
        rows += linkRow(DiagIds.BLE_LOST, "Gói app làm mất", "gói", link.framesLost.toDouble().takeIf { link.framesTotal > 0 || link.ready }, DiagClass.DERIVED, DiagSource.APP, "Gói tới nhưng app không lưu được (khác với xe không gửi)")
        rows += linkRow(DiagIds.BLE_RATE, "Tốc độ nhận gói", "gói/giây", link.ratePerSec.takeIf { link.ready && it != null && it.isFinite() }, DiagClass.DERIVED, DiagSource.APP, "Tính từ số gói đếm được")
        rows += linkRow(DiagIds.BLE_SILENCE, "Thời gian từ gói gần nhất (phản hồi)", "ms", silence?.toDouble(), DiagClass.DERIVED, DiagSource.APP, "Càng lớn càng cho thấy xe ngừng phát/đứt liên kết")
        return DiagSnapshot(nowMs, rows, link)
    }

    private fun linkRow(id: String, title: String, unit: String, v: Double?, cls: DiagClass, src: DiagSource, note: String) =
        if (v != null && v.isFinite()) DiagRow(id, title, DiagCatalog.G_BLE, unit, cls, v, src, null, null, note)
        else DiagRow(id, title, DiagCatalog.G_BLE, unit, DiagClass.NO_DATA, null, null, null, null, "Chưa có dữ liệu (chưa kết nối hoặc chưa nhận gói)")
}

/**
 * Cộng dồn quãng đường từ tốc độ GPS. CHỈ cộng giữa hai mẫu liền kề cách nhau ≤ [maxGapMs]; khoảng trống lớn hơn bị BỎ (đếm vào [skippedGaps]),
 * tuyệt đối không nội suy/ước lượng quãng đường lúc mất GPS. Kết quả là dữ liệu SUY TÍNH.
 */
class GpsDistanceAccumulator(private val maxGapMs: Long = 3_000L) {
    var meters: Double = 0.0; private set
    var skippedGaps: Long = 0; private set
    /** Số mẫu GPS hợp lệ đã nhận; cần ≥ 2 mới có quãng đường. */
    var samples: Long = 0; private set
    private var lastT: Long? = null; private var lastV: Double? = null

    @Synchronized fun add(s: GpsSample) {
        if (!s.speedKmh.isFinite() || s.speedKmh < 0) return
        samples++
        val t0 = lastT; val v0 = lastV
        if (t0 != null && v0 != null && s.timeMs > t0) {
            val dt = s.timeMs - t0
            if (dt <= maxGapMs) meters += (v0 + s.speedKmh) / 2.0 / 3.6 * dt / 1000.0 else skippedGaps++
        }
        if (t0 == null || s.timeMs > t0) { lastT = s.timeMs; lastV = s.speedKmh }
    }
    @Synchronized fun reset() { meters = 0.0; skippedGaps = 0; samples = 0; lastT = null; lastV = null }
}
