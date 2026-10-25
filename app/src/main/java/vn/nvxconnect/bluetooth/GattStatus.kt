package vn.nvxconnect.bluetooth

/** Dịch mã trạng thái GATT của Android sang tiếng Việt và cho biết có nên thử lại hay không. */
object GattStatus {
    const val AUTH_REQUIRED = 5
    const val ENCRYPTION_REQUIRED = 15
    /** GATT_CONN_TERMINATE_PEER_USER: phía xe chủ động cắt liên kết. */
    const val REMOTE_TERMINATED = 19

    fun describe(status: Int): String = when (status) {
        0 -> "Thành công"
        2 -> "Characteristic không cho đọc"
        3 -> "Characteristic không cho ghi"
        5 -> "Xe yêu cầu xác thực/ghép nối (5)"
        7 -> "Offset không hợp lệ"
        8 -> "Hết thời gian kết nối (8)"
        13 -> "Độ dài thuộc tính không hợp lệ"
        15 -> "Xe yêu cầu mã hóa/ghép nối (15)"
        19 -> "Xe chủ động ngắt kết nối (19)"
        22 -> "Điện thoại ngắt kết nối (22)"
        34 -> "Mất phản hồi liên kết LMP (34)"
        62 -> "Không thiết lập được kết nối (62)"
        129 -> "Lỗi nội bộ ngăn xếp Bluetooth (129)"
        133 -> "Lỗi GATT chung (133) - thường do nhiễu, quá xa hoặc xe đang bận với thiết bị khác"
        257 -> "Thất bại (257)"
        else -> "Mã trạng thái $status"
    }

    /** Xác thực/mã hóa: KHÔNG thử lại và KHÔNG tìm cách vượt qua. Các lỗi còn lại thường là tạm thời. */
    fun retriable(status: Int): Boolean = status != AUTH_REQUIRED && status != ENCRYPTION_REQUIRED

    fun needsPairing(status: Int): Boolean = !retriable(status)

    /** Mã thường gặp khi thiết bị ngoài phạm vi, tắt nguồn hoặc không phản hồi. */
    fun looksLikeOutOfRange(status: Int): Boolean = status == 8 || status == 34 || status == 62 || status == 133
}
