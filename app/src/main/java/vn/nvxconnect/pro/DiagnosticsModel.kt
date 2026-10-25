package vn.nvxconnect.pro

import vn.nvxconnect.bluetooth.LogEntry
import vn.nvxconnect.bluetooth.LogKind

/** Một dòng nhật ký lỗi cho Diagnostics Center: thời gian, nguyên nhân (từ nhật ký thật) và gợi ý khắc phục (từ bảng tra, không bịa mã lỗi). */
data class DiagIssue(val timeMs: Long, val state: String?, val cause: String, val hint: String)

object DiagnosticsModel {
    /**
     * Gợi ý theo từ khóa trong thông điệp lỗi THẬT. Không có từ khóa khớp thì nói thẳng là chưa có gợi ý riêng.
     * Đây không phải mã lỗi ECU và không kết luận lỗi xe; chỉ là lỗi của quá trình kết nối điện thoại ↔ thiết bị.
     */
    fun hintFor(message: String): String {
        val m = message.lowercase()
        return when {
            "quyền" in m || "permission" in m -> "Cấp quyền Bluetooth/Vị trí cho ứng dụng trong Cài đặt hệ thống."
            "bluetooth" in m && ("tắt" in m || "off" in m) -> "Bật Bluetooth của điện thoại rồi thử lại."
            "xác thực" in m || "ghép" in m || "bond" in m || "auth" in m -> "Thiết bị có thể yêu cầu ghép đôi/xác thực. Dùng nút ghép đôi do hệ thống Android cung cấp."
            "quá" in m && ("thời gian" in m || "hạn" in m) || "timeout" in m -> "Đưa điện thoại lại gần thiết bị, đảm bảo không có ứng dụng khác đang giữ kết nối."
            "ngắt" in m || "mất kết nối" in m || "disconnect" in m -> "Kiểm tra khoảng cách và nguồn của thiết bị; thử kết nối lại."
            "đọc thất bại" in m -> "Characteristic có thể không cho phép đọc; chỉ nghe (notify) nếu thuộc tính cho phép."
            "quét" in m -> "Bật Vị trí (Android ≤ 11) và cấp quyền quét; thử quét lại gần thiết bị."
            else -> "Chưa có gợi ý riêng cho lỗi này. Xuất gói hỗ trợ kỹ thuật và gửi kèm mô tả."
        }
    }

    fun issues(log: List<LogEntry>, limit: Int = 100): List<DiagIssue> =
        log.filter { it.kind == LogKind.ERROR }.sortedByDescending { it.timeMs }.take(limit).map { DiagIssue(it.timeMs, it.state, it.message, hintFor(it.message)) }

    /** Tuổi của gói gần nhất → nhãn trạng thái thu thập. null = chưa nhận gói nào. */
    fun collectionState(recording: Boolean, lastPayloadAgeMs: Long?, staleAfterMs: Long = 5_000): String = when {
        !recording -> "Đã dừng ghi: gói đến lúc này không được lưu"
        lastPayloadAgeMs == null -> "Đang ghi nhưng chưa nhận gói nào"
        lastPayloadAgeMs > staleAfterMs -> "Dữ liệu cũ ${lastPayloadAgeMs / 1000} giây — có thể đã mất kết nối"
        else -> "Đang nhận gói (cách đây ${lastPayloadAgeMs / 1000} giây)"
    }
}
