package vn.nvxconnect.helpcenter

import vn.nvxconnect.bluetooth.BleBlocker
import vn.nvxconnect.helpcenter.HelpContent.Id
import vn.nvxconnect.protocol.ConnState
import vn.nvxconnect.protocol.LinkLevel
import vn.nvxconnect.protocol.TftBridgeStatus
import vn.nvxconnect.protocol.VehicleLinkStatus

enum class CheckStep(val title: String, val guideId: String) {
    BLUETOOTH_AVAILABLE("Bluetooth khả dụng", Id.PREPARE_PHONE),
    PERMISSIONS_GRANTED("Đã cấp quyền", Id.PERMISSIONS),
    DEVICE_FOUND("Tìm thấy thiết bị", Id.SCAN_SELECT),
    CONNECTED("Kết nối thành công", Id.CONNECT_STATES),
    SERVICES_DISCOVERED("Khám phá dịch vụ thành công", Id.GATT_SERVICES),
    PROTOCOL_VERIFIED("Đã xác minh giao thức xe", Id.VEHICLE_DATA),
    VALID_DATA_RECEIVED("Đã nhận dữ liệu hợp lệ", Id.VEHICLE_DATA),
    TFT_SUPPORT("TFT: được hỗ trợ hay chưa xác minh", Id.TFT_NAV)
}

/** PASSED chỉ khi có bằng chứng. UNSUPPORTED = có căn cứ nói "chưa hỗ trợ" (khác FAILED = lỗi, và PENDING = chưa kiểm tra được). */
enum class CheckStatus { PASSED, FAILED, PENDING, UNSUPPORTED }

data class StepResult(val step: CheckStep, val status: CheckStatus, val detail: String) {
    val guideId get() = step.guideId
}

/** Mọi đầu vào là trạng thái quan sát được. null = chưa biết (PENDING), không bao giờ mặc định "đạt". */
data class ChecklistInputs(
    val blockers: List<BleBlocker>? = null,
    val deviceFound: Boolean = false,
    val conn: ConnState = ConnState.Disconnected,
    val link: VehicleLinkStatus = VehicleLinkStatus(LinkLevel.NOT_CONNECTED, "CHƯA KẾT NỐI", null, false),
    val verifiedValueSeen: Boolean = false,
    val tftStatus: TftBridgeStatus = TftBridgeStatus.UNSUPPORTED
)

object ConnectionChecklist {
    /** Bài hướng dẫn nào bị chặn bởi bước kiểm tra nào (xem [HelpProgressTracker.markComplete]). */
    val stepForGuide: Map<String, CheckStep> = mapOf(
        Id.PREPARE_PHONE to CheckStep.BLUETOOTH_AVAILABLE,
        Id.PERMISSIONS to CheckStep.PERMISSIONS_GRANTED,
        Id.SCAN_SELECT to CheckStep.DEVICE_FOUND,
        Id.CONNECT_STATES to CheckStep.CONNECTED,
        Id.GATT_SERVICES to CheckStep.SERVICES_DISCOVERED
    )

    private val bluetoothBlockers = setOf(BleBlocker.NO_BLE_HARDWARE, BleBlocker.BLUETOOTH_OFF, BleBlocker.LOCATION_SERVICES_OFF)

    fun evaluate(i: ChecklistInputs): List<StepResult> {
        val out = ArrayList<StepResult>()
        // Chuỗi phụ thuộc: sau bước đầu tiên chưa đạt, các bước kế tiếp là PENDING dù đầu vào có vẻ "tốt".
        var blocked: CheckStep? = null
        fun add(step: CheckStep, status: CheckStatus, detail: String) {
            val b = blocked
            if (b != null && step != CheckStep.TFT_SUPPORT) { out += StepResult(step, CheckStatus.PENDING, "Chưa kiểm tra: cần đạt \"${b.title}\" trước."); return }
            out += StepResult(step, status, detail)
            if (status != CheckStatus.PASSED && step != CheckStep.TFT_SUPPORT) blocked = step
        }

        val bl = i.blockers
        add(CheckStep.BLUETOOTH_AVAILABLE,
            when { bl == null -> CheckStatus.PENDING; bl.any { it in bluetoothBlockers } -> CheckStatus.FAILED; else -> CheckStatus.PASSED },
            when { bl == null -> "Chưa kiểm tra"; bl.isEmpty() -> "Bluetooth bật, điện thoại hỗ trợ BLE"
                else -> bl.filter { it in bluetoothBlockers }.joinToString("; ") { it.message }.ifEmpty { "Bluetooth bật, điện thoại hỗ trợ BLE" } })
        add(CheckStep.PERMISSIONS_GRANTED,
            when { bl == null -> CheckStatus.PENDING; BleBlocker.PERMISSION_MISSING in bl -> CheckStatus.FAILED; else -> CheckStatus.PASSED },
            when { bl == null -> "Chưa kiểm tra"; BleBlocker.PERMISSION_MISSING in bl -> BleBlocker.PERMISSION_MISSING.message; else -> "Đã đủ quyền để quét và kết nối" })

        val connectedNow = i.conn is ConnState.Ready || i.conn is ConnState.Connected || i.conn is ConnState.DiscoveringServices
        add(CheckStep.DEVICE_FOUND,
            if (i.deviceFound || connectedNow) CheckStatus.PASSED else CheckStatus.PENDING,
            if (i.deviceFound || connectedNow) "Đã thấy hoặc đã chọn một thiết bị (chưa khẳng định đó là xe Yamaha)" else "Chưa thấy thiết bị nào")
        val c = i.conn
        add(CheckStep.CONNECTED,
            when (c) { is ConnState.Ready, is ConnState.Connected, is ConnState.DiscoveringServices -> CheckStatus.PASSED
                is ConnState.Error -> CheckStatus.FAILED; else -> CheckStatus.PENDING },
            when (c) { is ConnState.Ready, is ConnState.Connected, is ConnState.DiscoveringServices -> "Liên kết GATT đã thiết lập"
                is ConnState.Error -> "${c.kind.title}: ${c.reason}"
                is ConnState.Reconnecting -> "Đang kết nối lại ${c.attempt}/${c.maxAttempts}"
                is ConnState.Connecting -> "Đang kết nối…"; ConnState.Disconnected -> "Chưa kết nối" })
        add(CheckStep.SERVICES_DISCOVERED,
            if (c is ConnState.Ready) CheckStatus.PASSED else CheckStatus.PENDING,
            if (c is ConnState.Ready) "Đã khám phá ${c.services.size} dịch vụ" else "Chưa khám phá xong dịch vụ")
        add(CheckStep.PROTOCOL_VERIFIED,
            if (i.link.protocolVerified) CheckStatus.PASSED else if (c is ConnState.Ready) CheckStatus.FAILED else CheckStatus.PENDING,
            if (i.link.protocolVerified) i.link.headline else if (c is ConnState.Ready) "${VehicleLinkStatus.NOT_VERIFIED}: chưa có adapter đã xác minh trên xe thật" else "Chưa kiểm tra")
        add(CheckStep.VALID_DATA_RECEIVED,
            if (i.verifiedValueSeen) CheckStatus.PASSED else if (i.link.protocolVerified) CheckStatus.PENDING else CheckStatus.PENDING,
            if (i.verifiedValueSeen) "Đã nhận giá trị xe đã xác minh" else "Chưa nhận giá trị xe nào")
        // TFT độc lập với chuỗi kết nối: trạng thái hỗ trợ là thuộc tính của app, không phụ thuộc vào kết nối hiện tại.
        add(CheckStep.TFT_SUPPORT,
            when (i.tftStatus) { TftBridgeStatus.VERIFIED_READY -> CheckStatus.PASSED; TftBridgeStatus.UNSUPPORTED -> CheckStatus.UNSUPPORTED; TftBridgeStatus.NOT_VERIFIED -> CheckStatus.PENDING },
            when (i.tftStatus) { TftBridgeStatus.VERIFIED_READY -> "Có adapter TFT đã xác minh"
                TftBridgeStatus.UNSUPPORTED -> "KHÔNG HỖ TRỢ: chưa có giao thức/API hợp lệ cho NVX 155 V3 SP"
                TftBridgeStatus.NOT_VERIFIED -> "Chưa xác minh" })
        return out
    }

    /** Bước đầu tiên chưa đạt (không tính TFT, vốn là thuộc tính hỗ trợ), kèm bài khắc phục. null nếu 7 bước đầu đều đạt. */
    fun firstProblem(results: List<StepResult>): StepResult? =
        results.firstOrNull { it.step != CheckStep.TFT_SUPPORT && it.status != CheckStatus.PASSED }

    fun passedCount(results: List<StepResult>) = results.count { it.status == CheckStatus.PASSED }
}
