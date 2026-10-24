package vn.nvxconnect.protocol

import vn.nvxconnect.navigation.TurnInstruction

enum class TftBridgeStatus { UNSUPPORTED, NOT_VERIFIED, VERIFIED_READY }

sealed interface TftSendResult {
    data class Rejected(val reason: String) : TftSendResult
    data object Sent : TftSendResult
}

/**
 * Cầu nối chỉ dẫn từng chặng lên màn hình TFT của xe.
 *
 * TRẠNG THÁI HIỆN TẠI: UNSUPPORTED. Chưa có giao thức được công bố hoặc phương thức tích hợp được Yamaha cho phép,
 * và chưa có phép thử lặp lại được trên NVX 155 V3 SP. Vì vậy:
 *  - KHÔNG gửi bất kỳ dữ liệu nào tới CCU/TFT (kể cả thử nghiệm).
 *  - KHÔNG tìm cách vượt qua ghép đôi/xác thực của Y-Connect.
 *  - Giao diện phải hiển thị rõ "không hỗ trợ", không mô phỏng việc hiển thị trên xe.
 * Xem docs/TFT_NAVIGATION.md để biết điều kiện cần có trước khi viết triển khai thật.
 */
interface TftNavigationBridge {
    val status: TftBridgeStatus
    /** Lý do/giới hạn bằng tiếng Việt để hiển thị. */
    val limitation: String
    fun sendManeuver(instruction: TurnInstruction): TftSendResult
    /** Gửi một khung hiển thị tổng hợp (chỉ đường + dữ liệu xe đã xác minh). Mặc định từ chối: chỉ adapter đã xác minh mới ghi đè. */
    fun sendFrame(frame: TftFrame): TftSendResult = TftSendResult.Rejected(limitation)
    /**
     * true CHỈ khi giao thức TFT đã xác minh trả về xác nhận tích cực rằng màn hình thực sự đang hiển thị nội dung vừa gửi.
     * "Đã gửi" KHÔNG đồng nghĩa "đã hiển thị". Mặc định false: không có xác nhận thì không được tuyên bố hiển thị.
     */
    fun displayConfirmed(): Boolean = false
}

object UnsupportedTftBridge : TftNavigationBridge {
    override val status = TftBridgeStatus.UNSUPPORTED
    override val limitation =
        "Chưa hỗ trợ hiển thị chỉ dẫn lên TFT của xe: chưa có giao thức hoặc phương thức tích hợp được Yamaha công bố/cho phép, " +
            "và chưa được xác minh trên NVX 155 V3 SP. Dẫn đường hiện chỉ chạy trên điện thoại."
    override fun sendManeuver(instruction: TurnInstruction): TftSendResult = TftSendResult.Rejected(limitation)
}
