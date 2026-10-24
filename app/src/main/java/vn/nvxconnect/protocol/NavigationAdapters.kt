package vn.nvxconnect.protocol

import vn.nvxconnect.navigation.TurnInstruction

/**
 * Bằng chứng bắt buộc để đăng ký một adapter truyền chỉ đường lên TFT: giao thức có tài liệu hoặc được Yamaha cho phép,
 * VÀ đã thử lặp lại trên xe thật. Thiếu một trong hai thì bị từ chối.
 */
data class NavProtocolEvidence(
    val id: String,
    /** Tài liệu/giấy phép tích hợp (đường dẫn, mã tài liệu hoặc văn bản cho phép của Yamaha). */
    val documentationRef: String?,
    val testedOnVehicle: String?,
    val testResult: String?,
    val verified: Boolean
)

/** Triển khai thật của cầu nối TFT (chưa có cái nào). Phải mang bằng chứng và khớp thiết bị đã kết nối. */
interface NavigationProtocolAdapter : TftNavigationBridge {
    val evidence: NavProtocolEvidence
}

/**
 * Điểm cắm cho giao thức dẫn đường lên TFT. Mặc định RỖNG: resolve() trả UnsupportedTftBridge.
 * Tách khỏi lõi BLE và giao diện: bản đồ trên điện thoại hoạt động độc lập, adapter chỉ nhận TurnInstruction.
 */
class NavAdapterRegistry {
    private var adapter: NavigationProtocolAdapter? = null

    fun register(a: NavigationProtocolAdapter) {
        val e = a.evidence
        require(e.verified && !e.documentationRef.isNullOrBlank() && !e.testedOnVehicle.isNullOrBlank() && !e.testResult.isNullOrBlank()) {
            "Từ chối adapter TFT '${e.id}': thiếu tài liệu/giấy phép hoặc chưa thử trên xe thật"
        }
        require(a.status == TftBridgeStatus.VERIFIED_READY) { "Từ chối adapter TFT '${e.id}': trạng thái không phải VERIFIED_READY" }
        adapter = a
    }

    fun resolve(): TftNavigationBridge = adapter ?: UnsupportedTftBridge
    val count get() = if (adapter == null) 0 else 1
}
