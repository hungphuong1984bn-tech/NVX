package vn.nvxconnect.tftintegration

import vn.nvxconnect.navigation.TurnInstruction
import vn.nvxconnect.protocol.ConnState
import vn.nvxconnect.protocol.NavAdapterRegistry
import vn.nvxconnect.protocol.TftBridgeStatus
import vn.nvxconnect.protocol.TftDisplayPipeline
import vn.nvxconnect.protocol.TftNavigationBridge
import vn.nvxconnect.protocol.TftSendResult
import vn.nvxconnect.protocol.VehicleLinkStatus

/** 6 mức TÁCH BIẠCH. Đạt mức sau không suy ra đạt mức trước và ngược lại. */
enum class TftLevel(val title: String) {
    BLUETOOTH_CONNECTED("Bluetooth đã kết nối (GATT sẵn sàng)"),
    VEHICLE_PROTOCOL_VERIFIED("Đã xác minh giao thức xe"),
    DATA_READ("Đã đọc được dữ liệu xe"),
    TFT_INTEGRATED("Đã tích hợp được TFT"),
    NAV_RUNNING_ON_PHONE("Chỉ dẫn đường đang chạy trên điện thoại"),
    NAV_CONFIRMED_ON_TFT("Chỉ dẫn đường đã được xác nhận hiển thị trên TFT")
}

data class TftLevelStatus(val level: TftLevel, val reached: Boolean, val detail: String)

data class TftIntegrationInputs(
    val conn: ConnState,
    val link: VehicleLinkStatus,
    val verifiedValueSeen: Boolean,
    val bridge: TftNavigationBridge,
    val navRunningOnPhone: Boolean,
    val lastOutcome: TftDisplayPipeline.Outcome? = null
)

data class TftIntegrationReport(val levels: List<TftLevelStatus>, val headline: String, val limitation: String?) {
    fun reached(l: TftLevel) = levels.first { it.level == l }.reached
}

/**
 * Tổng hợp trạng thái tích hợp TFT từ bằng chứng thật. KHÔNG gửi gì tới xe; chỉ đọc trạng thái.
 *  - TFT_INTEGRATED chỉ khi cầu nối là VERIFIED_READY (adapter có tài liệu/giấy phép + đã thử trên xe) VÀ liên kết xe đã xác minh.
 *  - NAV_CONFIRMED_ON_TFT chỉ khi cầu nối báo xác nhận hiển thị tích cực; kết quả "Đã gửi" không đủ.
 */
object TftIntegrationManager {
    fun evaluate(i: TftIntegrationInputs): TftIntegrationReport {
        val ready = i.conn is ConnState.Ready
        val bridgeReady = i.bridge.status == TftBridgeStatus.VERIFIED_READY
        val integrated = bridgeReady && i.link.protocolVerified
        val sent = i.lastOutcome is TftDisplayPipeline.Outcome.Sent
        val confirmed = integrated && sent && i.bridge.displayConfirmed()
        val levels = listOf(
            TftLevelStatus(TftLevel.BLUETOOTH_CONNECTED, ready,
                if (ready) "GATT READY — chưa nói gì về việc đó có phải xe hay không" else "Chưa có phiên GATT sẵn sàng (${i.conn.code})"),
            TftLevelStatus(TftLevel.VEHICLE_PROTOCOL_VERIFIED, i.link.protocolVerified,
                if (i.link.protocolVerified) i.link.headline else VehicleLinkStatus.NOT_VERIFIED),
            TftLevelStatus(TftLevel.DATA_READ, i.verifiedValueSeen && i.link.protocolVerified,
                if (i.verifiedValueSeen && i.link.protocolVerified) "Đã giải mã giá trị xe bằng adapter đã xác minh" else "Chưa nhận giá trị xe đã xác minh"),
            TftLevelStatus(TftLevel.TFT_INTEGRATED, integrated,
                when {
                    i.bridge.status == TftBridgeStatus.UNSUPPORTED -> "KHÔNG HỖ TRỢ: " + i.bridge.limitation
                    i.bridge.status == TftBridgeStatus.NOT_VERIFIED -> "Chưa xác minh: " + i.bridge.limitation
                    !i.link.protocolVerified -> "Có adapter TFT nhưng liên kết xe chưa xác minh giao thức"
                    else -> "Có adapter TFT đã xác minh và liên kết xe đã xác minh"
                }),
            TftLevelStatus(TftLevel.NAV_RUNNING_ON_PHONE, i.navRunningOnPhone,
                if (i.navRunningOnPhone) "Dẫn đường đang chạy trên điện thoại (không liên quan tới TFT)" else "Chưa chạy dẫn đường trên điện thoại"),
            TftLevelStatus(TftLevel.NAV_CONFIRMED_ON_TFT, confirmed,
                when {
                    confirmed -> "Cầu nối TFT xác nhận tích cực đang hiển thị"
                    sent -> "Đã gửi nhưng CHƯA có xác nhận hiển thị từ TFT"
                    else -> "Không có xác nhận hiển thị trên TFT"
                })
        )
        val headline = when {
            confirmed -> "Chỉ dẫn đường đã được xác nhận hiển thị trên TFT"
            integrated -> "TFT đã tích hợp; chưa có xác nhận hiển thị"
            i.navRunningOnPhone -> "Dẫn đường đang chạy trên điện thoại · TFT chưa hỗ trợ"
            i.bridge.status == TftBridgeStatus.UNSUPPORTED -> "TFT chưa được hỗ trợ"
            else -> "TFT chưa xác minh"
        }
        return TftIntegrationReport(levels, headline, if (bridgeReady) null else i.bridge.limitation)
    }
}

sealed interface NavDeliveryResult {
    /** Không có giao thức/API hợp lệ: không gửi gì. Dẫn đường trên điện thoại vẫn hoạt động. */
    data class NotSupported(val reason: String) : NavDeliveryResult
    data class NotReady(val reason: String) : NavDeliveryResult
    /** Đã gửi. [confirmedOnTft] chỉ true khi cầu nối xác nhận tích cực; "đã gửi" tự nó không phải bằng chứng hiển thị. */
    data class Sent(val confirmedOnTft: Boolean) : NavDeliveryResult
    data class Rejected(val reason: String) : NavDeliveryResult
}

/**
 * Cổng DUY NHẤT đưa chỉ dẫn rẽ tới TFT. Không có adapter đã đăng ký hợp lệ (tài liệu/giấy phép + thử trên xe) thì không gọi bridge,
 * không thử "gửi dò", không vượt xác thực. Kết quả luôn nói thật việc gì đã/không xảy ra.
 */
class NavigationIntegrationAdapter(private val registry: NavAdapterRegistry) {
    fun status(): TftBridgeStatus = registry.resolve().status
    fun limitation(): String = registry.resolve().limitation

    fun deliver(instruction: TurnInstruction, linkVerified: Boolean): NavDeliveryResult {
        val b = registry.resolve()
        return when {
            b.status == TftBridgeStatus.UNSUPPORTED -> NavDeliveryResult.NotSupported(b.limitation)
            b.status != TftBridgeStatus.VERIFIED_READY -> NavDeliveryResult.NotReady("Cầu nối TFT chưa xác minh: " + b.limitation)
            !linkVerified -> NavDeliveryResult.NotReady("Chưa kết nối xe hoặc chưa xác minh giao thức.")
            else -> when (val r = b.sendManeuver(instruction)) {
                is TftSendResult.Sent -> NavDeliveryResult.Sent(confirmedOnTft = b.displayConfirmed())
                is TftSendResult.Rejected -> NavDeliveryResult.Rejected(r.reason)
            }
        }
    }
}
