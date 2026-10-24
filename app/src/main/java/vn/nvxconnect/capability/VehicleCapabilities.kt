package vn.nvxconnect.capability

import vn.nvxconnect.protocol.SpeedCrossCheck
import vn.nvxconnect.protocol.TftBridgeStatus
import vn.nvxconnect.protocol.VehicleParam
import vn.nvxconnect.protocol.VehicleProtocolAdapter
import vn.nvxconnect.protocol.VehicleTelemetry

/**
 * Trạng thái của MỘT chức năng XE (không phải chức năng của app):
 *  - AVAILABLE: adapter giao thức ĐÃ XÁC MINH khai báo và giá trị xe còn mới (≤ 5 giây) đang được nhận.
 *  - OBSERVED: từng nhận được giá trị xe qua adapter đã xác minh nhưng hiện đã cũ (dữ liệu đã quan sát, không còn tươi).
 *  - HYPOTHESIS: có giả thuyết từ phân tích tự động; CHƯA xác minh, không được dùng để hiển thị số liệu xe.
 *  - UNSUPPORTED: có bằng chứng tích cực là xe/giao thức hiện không hỗ trợ (ví dụ adapter đã xác minh khai báo không có tham số này,
 *    hoặc cầu nối TFT báo không hỗ trợ). Khác UNKNOWN: ở đây có căn cứ để nói "không".
 *  - UNKNOWN: chưa có bằng chứng nào. Đây là giá trị MẶC ĐỊNH của mọi chức năng.
 */
enum class CapabilityStatus { AVAILABLE, OBSERVED, HYPOTHESIS, UNSUPPORTED, UNKNOWN }

enum class CapabilitySource(val label: String) { VEHICLE("Xe"), PHONE("Điện thoại/GPS"), DERIVED("Suy ra/tính toán") }

enum class AcquisitionMethod { READ, NOTIFY, API, COMPUTED }

/** Một dòng trong danh mục chức năng XE. Mọi trường "đã biết" chỉ được điền khi có bằng chứng (xem [VehicleCapabilityRegistry.evaluate]). */
data class VehicleCapability(
    val id: String,
    val name: String,
    val source: CapabilitySource,
    /** Service UUID của hồ sơ giao thức ĐÃ XÁC MINH; rỗng khi chưa có. Không bao giờ lấy từ giả thuyết. */
    val serviceRefs: List<String>,
    val dataType: String?,
    val unit: String?,
    val method: AcquisitionMethod?,
    val status: CapabilityStatus,
    val evidence: String,
    val checkedAtMs: Long?,
    val profileVersion: String,
    /** Tuổi (ms) của giá trị gần nhất; null nếu chưa từng nhận giá trị. */
    val freshnessMs: Long?,
    val lastError: String?
) {
    /** Chỉ AVAILABLE/OBSERVED mới có nghĩa "xe đã được xác minh cung cấp dữ liệu này" (cả hai chỉ đến từ adapter đã xác minh). */
    val verified: Boolean get() = status == CapabilityStatus.AVAILABLE || status == CapabilityStatus.OBSERVED
}

/** Mọi đầu vào của bộ đánh giá là THỰC TẾ quan sát được trong phiên; không có giá trị mặc định "lạc quan". */
data class VehicleEvidenceInputs(
    /** Adapter đã xác minh khớp thiết bị đang nối, hoặc null. */
    val adapter: VehicleProtocolAdapter? = null,
    val telemetry: VehicleTelemetry = VehicleTelemetry(),
    val nowMs: Long,
    /** Trường byte khớp tốc độ GPS (nếu phân tích tìm thấy). Chỉ cho phép HYPOTHESIS, không bao giờ VERIFIED. */
    val speedMatch: SpeedCrossCheck.Match? = null,
    /** Đã khám phá GATT: số characteristic (null = chưa khám phá). Chỉ dùng để viết bằng chứng cho đúng. */
    val gattCharacteristics: Int? = null,
    val tftStatus: TftBridgeStatus = TftBridgeStatus.UNSUPPORTED,
    val tftLimitation: String = "",
    val lastError: String? = null
)

object VehicleCapabilityRegistry {
    /** Tăng khi danh mục hoặc quy tắc đánh giá đổi. Ghi vào mọi dòng để báo cáo cũ không bị hiểu nhầm. */
    const val PROFILE_VERSION = "vehicle-caps-1"

    /** Mô tả một chức năng trong danh mục (chưa có trạng thái). [param] = tham số adapter có thể giải mã, null nếu không phải số liệu. */
    data class Def(val id: String, val name: String, val source: CapabilitySource, val param: VehicleParam?, val kind: Kind = Kind.PARAM)
    enum class Kind { PARAM, TFT_NAV, TFT_CONTROL }

    /** 11 nhóm chức năng cần khảo sát. Danh mục KHÔNG khẳng định xe có chúng: chỉ là danh sách câu hỏi cần trả lời bằng bằng chứng. */
    val defs: List<Def> = listOf(
        Def("veh.speed", "Tốc độ", CapabilitySource.VEHICLE, VehicleParam.SPEED_KMH),
        Def("veh.rpm", "Vòng tua máy", CapabilitySource.VEHICLE, VehicleParam.RPM),
        Def("veh.fuel_level", "Mức nhiên liệu", CapabilitySource.VEHICLE, VehicleParam.FUEL_LEVEL),
        Def("veh.odometer", "Tổng quãng đường (ODO)", CapabilitySource.VEHICLE, VehicleParam.ODOMETER_KM),
        Def("veh.trip_distance", "Quãng đường hành trình", CapabilitySource.VEHICLE, VehicleParam.TRIP_KM),
        Def("veh.battery_voltage", "Điện áp ắc quy", CapabilitySource.VEHICLE, VehicleParam.BATTERY_V),
        Def("veh.ignition_state", "Trạng thái khóa điện/động cơ", CapabilitySource.VEHICLE, VehicleParam.IGNITION_ON),
        Def("veh.dtc", "Cảnh báo / mã lỗi", CapabilitySource.VEHICLE, VehicleParam.DTC),
        Def("veh.maintenance", "Trạng thái bảo dưỡng", CapabilitySource.VEHICLE, VehicleParam.MAINT_STATE),
        Def("veh.navigation_data", "Dữ liệu điều hướng (gửi lên xe)", CapabilitySource.PHONE, null, Kind.TFT_NAV),
        Def("veh.tft_control", "Trạng thái và khả năng điều khiển TFT", CapabilitySource.VEHICLE, null, Kind.TFT_CONTROL)
    )

    fun idOf(param: VehicleParam): String? = defs.firstOrNull { it.param == param }?.id

    /** Đánh giá toàn bộ danh mục từ bằng chứng hiện có. Thuần hàm: cùng đầu vào cho cùng kết quả. */
    fun evaluate(i: VehicleEvidenceInputs): List<VehicleCapability> = defs.map { d ->
        when (d.kind) {
            Kind.PARAM -> evaluateParam(d, i)
            Kind.TFT_NAV -> evaluateTftNav(d, i)
            Kind.TFT_CONTROL -> evaluateTftControl(d, i)
        }
    }

    private fun base(d: Def, i: VehicleEvidenceInputs, status: CapabilityStatus, evidence: String) = VehicleCapability(
        d.id, d.name, d.source, emptyList(), null, null, null, status, evidence, i.nowMs, PROFILE_VERSION, null, i.lastError)

    private fun evaluateParam(d: Def, i: VehicleEvidenceInputs): VehicleCapability {
        val param = d.param!!
        val a = i.adapter
        if (a != null) {
            val e = a.evidence
            val services = e.serviceUuids
            val live = i.telemetry.values[param]
            val declared = a.supportedParams
            if (live != null) {
                val age = i.nowMs - live.timeMs
                val fresh = i.telemetry.fresh(param, i.nowMs) != null
                return base(d, i, if (fresh) CapabilityStatus.AVAILABLE else CapabilityStatus.OBSERVED,
                    "Adapter đã xác minh ${e.id} (${e.protocolVersion}) giải mã được giá trị; " +
                        (if (fresh) "giá trị còn mới." else "giá trị gần nhất đã cũ, chưa có giá trị mới.")).copy(
                    serviceRefs = services, dataType = "number", unit = live.unit.ifBlank { null }, method = AcquisitionMethod.NOTIFY,
                    freshnessMs = age.coerceAtLeast(0))
            }
            if (declared != null && param !in declared)
                return base(d, i, CapabilityStatus.UNSUPPORTED,
                    "Adapter đã xác minh ${e.id} (${e.protocolVersion}) khai báo danh sách tham số hỗ trợ và không có chức năng này.").copy(serviceRefs = services)
            return base(d, i, CapabilityStatus.UNKNOWN,
                "Adapter đã xác minh ${e.id} đang khớp nhưng chưa nhận giá trị nào cho chức năng này trong phiên." +
                    if (declared == null) " Adapter không khai báo danh sách tham số hỗ trợ." else " Adapter khai báo có hỗ trợ.").copy(serviceRefs = services)
        }
        if (param == VehicleParam.SPEED_KMH && i.speedMatch != null) {
            val m = i.speedMatch
            return base(d, i, CapabilityStatus.HYPOTHESIS,
                "Giả thuyết: byte ${m.index} (${m.encoding.title}) trên ${m.charUuid.take(8)} biến thiên theo tốc độ GPS (r=%.3f, ${m.pairs} cặp mẫu). ".format(m.r) +
                    "CHƯA xác minh, chưa biết đơn vị; không dùng để hiển thị tốc độ xe.")
        }
        val gatt = i.gattCharacteristics?.let { " Đã khám phá GATT ($it characteristic) nhưng chưa gán được characteristic nào cho chức năng này." }
            ?: " Chưa khám phá GATT của xe."
        return base(d, i, CapabilityStatus.UNKNOWN, "Chưa có adapter giao thức đã xác minh.$gatt")
    }

    private fun evaluateTftNav(d: Def, i: VehicleEvidenceInputs): VehicleCapability = when (i.tftStatus) {
        TftBridgeStatus.UNSUPPORTED -> base(d, i, CapabilityStatus.UNSUPPORTED, i.tftLimitation.ifBlank { "Cầu nối TFT báo UNSUPPORTED." })
        TftBridgeStatus.NOT_VERIFIED -> base(d, i, CapabilityStatus.HYPOTHESIS, "Có cầu nối TFT nhưng chưa xác minh trên xe thật.")
        TftBridgeStatus.VERIFIED_READY -> base(d, i, CapabilityStatus.AVAILABLE, "Cầu nối TFT đã đăng ký kèm tài liệu và đã thử trên xe thật.").copy(method = AcquisitionMethod.API)
    }

    /**
     * Cầu nối dẫn đường đã xác minh KHÔNG chứng minh khả năng điều khiển TFT nói chung (chỉ chứng minh gửi khung chỉ đường),
     * nên chỉ có thể là UNSUPPORTED (cầu nối không hỗ trợ) hoặc UNKNOWN.
     */
    private fun evaluateTftControl(d: Def, i: VehicleEvidenceInputs): VehicleCapability =
        if (i.tftStatus == TftBridgeStatus.UNSUPPORTED) base(d, i, CapabilityStatus.UNSUPPORTED, i.tftLimitation.ifBlank { "Cầu nối TFT báo UNSUPPORTED." })
        else base(d, i, CapabilityStatus.UNKNOWN, "Cầu nối TFT chỉ nói về gửi chỉ đường; chưa có bằng chứng về điều khiển/đọc trạng thái TFT.")
}
