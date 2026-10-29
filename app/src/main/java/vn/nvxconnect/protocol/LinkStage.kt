package vn.nvxconnect.protocol

/**
 * Năm mức liên kết phải hiển thị TÁCH BẠCH (theo thứ tự tăng dần bằng chứng). Mỗi mức chỉ đạt khi có bằng chứng của CHÍNH mức đó
 * VÀ mọi mức dưới nó; không mức nào được suy ra mức cao hơn.
 *  CONNECTED: liên kết GATT đã thiết lập (chưa biết gì về dịch vụ).
 *  GATT_READY: đã khám phá xong dịch vụ (ConnState.Ready).
 *  SUBSCRIPTION_SUCCESS: ít nhất một characteristic được thiết bị XÁC NHẬN ghi CCCD (ACTIVE), không phải PENDING/FAILED.
 *  DATA_RECEIVED: đã nhận ít nhất một payload trong phiên GATT hiện tại.
 *  PROTOCOL_VERIFIED: có adapter đã xác minh khớp thiết bị VÀ ít nhất một trường đạt VERIFIED. Nhận được gói thô KHÔNG đủ.
 */
enum class LinkStage(val title: String, val level: Int) {
    DISCONNECTED("Chưa kết nối", 0), CONNECTED("CONNECTED", 1), GATT_READY("GATT READY", 2),
    SUBSCRIPTION_SUCCESS("SUBSCRIPTION SUCCESS", 3), DATA_RECEIVED("DATA RECEIVED", 4), PROTOCOL_VERIFIED("PROTOCOL VERIFIED", 5)
}

data class LinkStageInput(
    val state: ConnState,
    val subscriptions: Map<String, SubscriptionState>,
    /** Số payload đã nhận trong phiên GATT hiện tại (không phải tổng các phiên). */
    val framesThisEpoch: Long,
    val adapterVerified: Boolean,
    val verifiedFieldCount: Int
)

data class LinkStageResult(val stage: LinkStage, val missingForNext: String?)

object LinkStages {
    fun evaluate(i: LinkStageInput): LinkStageResult {
        val connected = i.state is ConnState.Connected || i.state is ConnState.DiscoveringServices || i.state is ConnState.Ready
        if (!connected) return LinkStageResult(LinkStage.DISCONNECTED, "Liên kết GATT chưa thiết lập")
        if (i.state !is ConnState.Ready) return LinkStageResult(LinkStage.CONNECTED, "Chưa khám phá xong dịch vụ GATT")
        if (i.subscriptions.values.none { it == SubscriptionState.ACTIVE })
            return LinkStageResult(LinkStage.GATT_READY, if (i.subscriptions.values.any { it == SubscriptionState.FAILED }) "Đăng ký nghe THẤT BẠI" else "Chưa có đăng ký nghe nào được thiết bị xác nhận")
        if (i.framesThisEpoch <= 0) return LinkStageResult(LinkStage.SUBSCRIPTION_SUCCESS, "Chưa nhận payload nào trong phiên này")
        if (!i.adapterVerified || i.verifiedFieldCount <= 0)
            return LinkStageResult(LinkStage.DATA_RECEIVED, "Thiếu adapter đã xác minh và/hoặc trường nào đạt VERIFIED")
        return LinkStageResult(LinkStage.PROTOCOL_VERIFIED, null)
    }
}
