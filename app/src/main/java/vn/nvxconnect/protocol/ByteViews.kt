package vn.nvxconnect.protocol

/**
 * Công cụ XEM byte theo nhiều cách (HEX, thập phân, nhị phân, little/big-endian, bit field) cho người khảo sát.
 *
 * Đây chỉ là PHÉP BIẾN ĐỔI SỐ HỌC, không phải giải mã giao thức: kết quả không mang ý nghĩa/đơn vị nào.
 * Một cách diễn giải chỉ đáng thử khi có cơ sở kiểm chứng (ví dụ biến thiên theo tốc độ GPS, xem [SpeedCrossCheck]);
 * không có hàm nào ở đây tạo ra nhãn như "RPM" hay "nhiệt độ".
 */
object ByteViews {
    enum class Endian(val title: String) { LITTLE("little-endian"), BIG("big-endian") }

    /** Cách diễn giải một trường [width] byte tại [offset]. unsigned/signed luôn tính trên Long (width ≤ 4 nên không tràn). */
    data class Interpretation(val offset: Int, val width: Int, val endian: Endian, val hex: String, val unsigned: Long, val signed: Long) {
        val label: String get() = if (width == 1) "u8/i8" else "${width * 8}-bit ${endian.title}"
    }

    const val MAX_WIDTH = 4

    /** Chấp nhận "AA BB", "aa-bb", "0xAA,0xBB", "AABB" (chẵn ký tự). Trả null nếu có ký tự không phải hex hoặc số ký tự lẻ. */
    fun parseHex(text: String): ByteArray? {
        val cleaned = text.replace(Regex("0[xX]"), " ").replace(Regex("[\\s,;:-]+"), "")
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        if (!cleaned.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(cleaned.length / 2) { cleaned.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun hex(b: ByteArray, sep: String = " "): String = b.joinToString(sep) { "%02X".format(it) }

    fun decimal(b: ByteArray, sep: String = " "): String = b.joinToString(sep) { (it.toInt() and 0xFF).toString() }

    fun binary(b: ByteArray, sep: String = " "): String = b.joinToString(sep) {
        Integer.toBinaryString(it.toInt() and 0xFF).padStart(8, '0')
    }

    /** Chỉ số bit đang bật (0 = bit thấp nhất) của một byte. */
    fun setBits(value: Int): List<Int> = (0..7).filter { (value shr it) and 1 == 1 }

    /** Số nguyên không dấu [width] byte (1..[MAX_WIDTH]) từ [offset]; null nếu ngoài biên hoặc width không hợp lệ. */
    fun readUnsigned(b: ByteArray, offset: Int, width: Int, endian: Endian): Long? {
        if (width !in 1..MAX_WIDTH || offset < 0 || offset + width > b.size) return null
        var v = 0L
        for (k in 0 until width) {
            val byte = b[if (endian == Endian.BIG) offset + k else offset + width - 1 - k].toLong() and 0xFF
            v = (v shl 8) or byte
        }
        return v
    }

    /** Số nguyên có dấu (bù hai) cùng điều kiện như [readUnsigned]. */
    fun readSigned(b: ByteArray, offset: Int, width: Int, endian: Endian): Long? {
        val u = readUnsigned(b, offset, width, endian) ?: return null
        val bits = width * 8
        return if (u and (1L shl (bits - 1)) != 0L) u - (1L shl bits) else u
    }

    /**
     * Mọi cách diễn giải khả dĩ của vùng bắt đầu ở [offset]: 1 byte, và 2/4 byte cho cả hai endian khi đủ dữ liệu.
     * Trường 1 byte chỉ xuất hiện một lần (endian không có nghĩa).
     */
    fun interpretations(b: ByteArray, offset: Int): List<Interpretation> {
        val out = ArrayList<Interpretation>()
        for (width in intArrayOf(1, 2, 4)) {
            val endians = if (width == 1) listOf(Endian.LITTLE) else Endian.values().toList()
            for (e in endians) {
                val u = readUnsigned(b, offset, width, e) ?: continue
                val s = readSigned(b, offset, width, e) ?: continue
                out += Interpretation(offset, width, e, hex(b.copyOfRange(offset, offset + width)), u, s)
            }
        }
        return out
    }
}
