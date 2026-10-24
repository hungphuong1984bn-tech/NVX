package vn.nvxconnect.protocol

import vn.nvxconnect.bluetooth.GattServiceInfo
import vn.nvxconnect.domain.DataSource

enum class VehicleParam {
    RPM, COOLANT_TEMP_C, BATTERY_V, DTC, SPEED_KMH, FUEL_LEVEL, ODOMETER_KM,
    /** Quãng đường hành trình do XE tính (khác quãng đường GPS của điện thoại). */
    TRIP_KM,
    /** Trạng thái khóa điện/động cơ do xe báo. */
    IGNITION_ON,
    /** Trạng thái bảo dưỡng do xe báo (khác danh sách bảo dưỡng người dùng tự nhập trong app). */
    MAINT_STATE
}

/** Mức độ liên kết với xe mà giao diện được phép hiển thị. */
enum class LinkLevel { NOT_CONNECTED, CONNECTING, RAW_DATA_ONLY, VERIFIED, ERROR }

data class VehicleLinkStatus(
    val level: LinkLevel,
    /** Dòng trạng thái chính, hiển thị trên chip. */
    val headline: String,
    /** Dòng phụ giải thích giới hạn. */
    val detail: String?,
    /** true chỉ khi có adapter đã xác minh khớp thiết bị. Chỉ khi đó mới được hiển thị dữ liệu LIVE_VERIFIED. */
    val protocolVerified: Boolean
) {
    companion object {
        const val RAW_ONLY = "CONNECTED — RAW DATA ONLY"
        const val NOT_VERIFIED = "VEHICLE PROTOCOL NOT VERIFIED"

        /**
         * Quy tắc: "CONNECTED" chỉ xuất hiện khi trạng thái là READY (GATT đã nối và khám phá xong dịch vụ).
         * Dù READY, nếu chưa có adapter đã xác minh thì vẫn ghi RAW DATA ONLY + VEHICLE PROTOCOL NOT VERIFIED:
         * kết nối GATT tới một thiết bị không chứng minh đó là bộ điều khiển của xe.
         */
        fun resolve(state: ConnState, registry: AdapterRegistry): VehicleLinkStatus = when (state) {
            is ConnState.Disconnected -> VehicleLinkStatus(LinkLevel.NOT_CONNECTED, "CHƯA KẾT NỐI", null, false)
            is ConnState.Connecting, is ConnState.Connected, is ConnState.DiscoveringServices ->
                VehicleLinkStatus(LinkLevel.CONNECTING, "ĐANG KẾT NỐI…", state.code, false)
            is ConnState.Reconnecting ->
                VehicleLinkStatus(LinkLevel.CONNECTING, "ĐANG KẾT NỐI LẠI ${state.attempt}/${state.maxAttempts}", null, false)
            is ConnState.Error -> VehicleLinkStatus(LinkLevel.ERROR, "LỖI KẾT NỐI", state.reason, false)
            is ConnState.Ready -> {
                val a = registry.find(state.services)
                if (a != null) VehicleLinkStatus(LinkLevel.VERIFIED, "CONNECTED — ${a.evidence.id}", null, true)
                else VehicleLinkStatus(LinkLevel.RAW_DATA_ONLY, RAW_ONLY, NOT_VERIFIED, false)
            }
        }
    }
}

class RawFrame(val timeMs: Long, val charUuid: String, val bytes: ByteArray)

sealed interface DecodedItem {
    /** Không giải mã được: hiển thị RAW/UNKNOWN, tuyệt đối không gán nhãn RPM/nhiệt độ/mã lỗi. */
    data class Unknown(val frame: RawFrame) : DecodedItem
    data class Value(val param: VehicleParam, val value: Double, val unit: String, val timeMs: Long,
                     val evidenceId: String, val source: DataSource = DataSource.LIVE_VERIFIED) : DecodedItem
}

/** Bằng chứng bắt buộc để đăng ký adapter: UUID, mẫu gốc đã ẩn, xe đã thử, kết quả kiểm thử. */
data class ProtocolEvidence(
    val id: String, val protocolVersion: String, val serviceUuids: List<String>,
    val packetFormat: String, val unitsAndByteOrder: String, val checksum: String?,
    val sampleHexRedacted: List<String>, val testedOnVehicle: String?, val testResult: String?, val verified: Boolean
)

interface VehicleProtocolAdapter {
    val evidence: ProtocolEvidence
    /**
     * Các tham số mà hồ sơ giao thức ĐÃ XÁC MINH này khai báo là có cung cấp. null = adapter không khai báo gì
     * (không suy ra được "không hỗ trợ"). Khác null: tham số nằm ngoài tập này được coi là UNSUPPORTED trong danh mục chức năng.
     */
    val supportedParams: Set<VehicleParam>? get() = null
    fun matches(services: List<GattServiceInfo>): Boolean
    fun decode(frame: RawFrame): List<DecodedItem>
}

/** Mặc định: không hiểu gì, mọi gói đều là Unknown. */
object RawCaptureAdapter : VehicleProtocolAdapter {
    override val evidence = ProtocolEvidence("raw", "none", emptyList(), "unknown", "unknown", null, emptyList(), null, null, false)
    override fun matches(services: List<GattServiceInfo>) = true
    override fun decode(frame: RawFrame) = listOf<DecodedItem>(DecodedItem.Unknown(frame))
}

/** Rỗng theo mặc định vì chưa có bằng chứng giao thức nào. */
class AdapterRegistry {
    private val adapters = mutableListOf<VehicleProtocolAdapter>()

    fun register(a: VehicleProtocolAdapter) {
        val e = a.evidence
        require(e.verified && !e.testedOnVehicle.isNullOrBlank() && !e.testResult.isNullOrBlank() && e.serviceUuids.isNotEmpty() && e.sampleHexRedacted.isNotEmpty()) {
            "Từ chối adapter '${e.id}': thiếu bằng chứng đã xác minh trên xe thật"
        }
        adapters += a
    }
    /** Adapter ĐÃ XÁC MINH khớp với các service của thiết bị, hoặc null nếu chưa có (RawCaptureAdapter không tính). */
    fun find(services: List<GattServiceInfo>): VehicleProtocolAdapter? = adapters.firstOrNull { it.matches(services) }
    fun decode(services: List<GattServiceInfo>, frame: RawFrame): List<DecodedItem> =
        (find(services) ?: RawCaptureAdapter).decode(frame)
    val count get() = adapters.size
    /** Hồ sơ bằng chứng của các adapter đã đăng ký (chỉ đọc), dùng cho màn hình hồ sơ giao thức. */
    fun evidences(): List<ProtocolEvidence> = adapters.map { it.evidence }
}

object FrameRedactor {
    /** Hex của gói; chuỗi ASCII in được dài >= 6 byte (có thể là VIN/ID) bị thay bằng 2A. */
    fun hexRedacted(b: ByteArray): String {
        val out = b.copyOf(); var i = 0
        while (i < b.size) {
            var j = i
            while (j < b.size && b[j] in 0x20..0x7E) j++
            if (j - i >= 6) for (k in i until j) out[k] = 0x2A
            i = maxOf(j, i + 1)
        }
        return out.joinToString(" ") { "%02X".format(it) }
    }
}
