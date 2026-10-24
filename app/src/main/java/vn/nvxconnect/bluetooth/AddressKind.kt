package vn.nvxconnect.bluetooth

/**
 * Gợi ý về loại địa chỉ BLE, suy từ 2 bit cao của octet đầu. API công khai của Android KHÔNG cho biết loại địa chỉ
 * chắc chắn, nên đây chỉ là gợi ý:
 *  - 10 chỉ có thể là địa chỉ công khai (địa chỉ ngẫu nhiên không bao giờ có 2 bit này).
 *  - 01, 00, 11 có thể là địa chỉ riêng tư/ngẫu nhiên, nhưng cũng có thể là địa chỉ công khai thuộc OUI tương ứng.
 * Hệ quả thiết kế: không dùng địa chỉ làm định danh cố định của xe; so khớp thiết bị đã lưu theo địa chỉ HOẶC tên.
 */
enum class AddressKind(val label: String, val mayChange: Boolean) {
    PUBLIC("Công khai (OUI)", false),
    MAYBE_RESOLVABLE_PRIVATE("Có thể riêng tư xoay vòng", true),
    MAYBE_STATIC_RANDOM("Có thể ngẫu nhiên tĩnh", true),
    MAYBE_NON_RESOLVABLE("Có thể ngẫu nhiên/công khai", true),
    INVALID("Địa chỉ không hợp lệ", false);

    companion object {
        private val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
        fun isValid(addr: String) = MAC.matches(addr)
        fun classify(addr: String): AddressKind {
            if (!isValid(addr)) return INVALID
            return when ((addr.substring(0, 2).toInt(16) shr 6) and 0b11) {
                0b10 -> PUBLIC
                0b01 -> MAYBE_RESOLVABLE_PRIVATE
                0b11 -> MAYBE_STATIC_RANDOM
                else -> MAYBE_NON_RESOLVABLE
            }
        }
    }
}
