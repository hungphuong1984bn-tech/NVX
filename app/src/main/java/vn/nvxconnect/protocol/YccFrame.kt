package vn.nvxconnect.protocol

/**
 * Lớp KHUNG (framing) của kênh Nordic-UART trên thiết bị "YCC", suy ra từ 5.988 gói notify thật (3 lần bắt, 2026-10-23).
 * Chỉ mô tả CẤU TRÚC gói: loại, tổng kiểm, bộ đếm, TLV. KHÔNG gán ý nghĩa vật lý ở đây (xem [YccCandidates]).
 *
 * Bằng chứng cấu trúc (docs/YCC_PROTOCOL_FINDINGS.md): tổng kiểm SUM8 khớp 5.988/5.988 gói; bộ đếm tăng 1 ở 99,9% cặp gói liên tiếp.
 * Đây là bằng chứng về ĐỊNH DẠNG, không phải bằng chứng về NGHĨA của các trường.
 */
object YccFrames {
    const val SERVICE_NUS = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val CHAR_NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

    const val T_HELLO = 0x5A   // 8 byte, một lần khi bắt đầu phiên đầu tiên
    const val T_BLOCK = 0x59   // 77 byte, 30 gói dạng khối khi bắt đầu phiên (nội dung chưa rõ)
    const val T_INFO = 0x6A    // 74 byte, bảng DID kiểu chẩn đoán (F190/F194/F197...) — chứa trường định danh, KHÔNG xuất/lưu
    const val T_STATUS = 0x6B  // 59 byte, ~12 gói/giây, bố cục cố định
    const val T_PARAMS = 0x6C  // 143 byte, ~6 gói/giây, danh sách TLV 24 mục

    /** Tổng mọi byte (kể cả byte cuối) mod 256 phải bằng 0. */
    fun checksumOk(b: ByteArray): Boolean = b.size >= 3 && (b.sumOf { it.toInt() and 0xFF } and 0xFF) == 0

    fun parse(b: ByteArray): YccParse {
        if (b.size < 3) return YccParse.Invalid("quá ngắn (${b.size} byte)")
        val type = b[0].toInt() and 0xFF
        if (type !in KNOWN) return YccParse.Invalid("loại khung lạ 0x%02X".format(type))
        if (!checksumOk(b)) return YccParse.Invalid("sai tổng kiểm SUM8")
        val expected = EXPECTED_LEN[type]
        if (expected != null && b.size != expected) return YccParse.Invalid("độ dài ${b.size} ≠ $expected cho loại 0x%02X".format(type))
        return when (type) {
            T_STATUS, T_PARAMS -> {
                val counter = b[b.size - 2].toInt() and 0xFF
                if (type == T_PARAMS) {
                    val tlv = parseTlv(b) ?: return YccParse.Invalid("TLV 0x6C không khớp độ dài")
                    YccParse.Valid(YccFrame(type, b, counter, tlv))
                } else YccParse.Valid(YccFrame(type, b, counter, null))
            }
            else -> YccParse.Valid(YccFrame(type, b, null, null))
        }
    }

    /** 0x6C: [0x6C][số mục][ (nhóm)(id)(len)(dữ liệu*len) ]*[bộ đếm][tổng kiểm]. */
    private fun parseTlv(b: ByteArray): List<YccTlv>? {
        val out = ArrayList<YccTlv>(); var i = 2; val end = b.size - 2
        while (i < end) {
            if (i + 3 > end) return null
            val len = b[i + 2].toInt() and 0xFF
            if (i + 3 + len > end) return null
            out += YccTlv(b[i].toInt() and 0xFF, b[i + 1].toInt() and 0xFF, b.copyOfRange(i + 3, i + 3 + len)); i += 3 + len
        }
        return if (out.size == (b[1].toInt() and 0xFF)) out else null
    }

    private val KNOWN = setOf(T_HELLO, T_BLOCK, T_INFO, T_STATUS, T_PARAMS)
    private val EXPECTED_LEN = mapOf(T_HELLO to 8, T_BLOCK to 77, T_INFO to 74, T_STATUS to 59, T_PARAMS to 143)
}

class YccTlv(val group: Int, val id: Int, val data: ByteArray) {
    val key get() = (group shl 8) or id
    /** Byte 0xFE = "không có dữ liệu" theo quan sát (CHƯA xác minh ý nghĩa). */
    val allFe get() = data.isNotEmpty() && data.all { (it.toInt() and 0xFF) == 0xFE }
}

class YccFrame(val type: Int, val raw: ByteArray, val counter: Int?, val tlv: List<YccTlv>?) {
    fun u8(i: Int) = raw[i].toInt() and 0xFF
    fun u16be(i: Int) = (u8(i) shl 8) or u8(i + 1)
    fun item(group: Int, id: Int) = tlv?.firstOrNull { it.group == group && it.id == id }
}

sealed interface YccParse {
    data class Valid(val frame: YccFrame) : YccParse
    data class Invalid(val reason: String) : YccParse
}

/** Thống kê sức khỏe kênh: đếm gói hợp lệ/hỏng, khoảng trống bộ đếm (mất gói). Không giữ dữ liệu gói. */
class YccLinkMonitor {
    data class Stats(val valid: Long = 0, val invalid: Long = 0, val counterGaps: Long = 0, val lastInvalid: String? = null,
                     val byType: Map<Int, Long> = emptyMap(), val lastFrameMs: Long = 0)
    private var lastCounter = HashMap<Int, Int>()
    var stats = Stats(); private set

    /** Gọi cho mỗi gói notify của [YccFrames.CHAR_NOTIFY]. Trả về khung hợp lệ hoặc null. */
    fun accept(bytes: ByteArray, timeMs: Long): YccFrame? {
        return when (val r = YccFrames.parse(bytes)) {
            is YccParse.Invalid -> { stats = stats.copy(invalid = stats.invalid + 1, lastInvalid = r.reason); null }
            is YccParse.Valid -> {
                val f = r.frame; var gaps = stats.counterGaps
                f.counter?.let { c -> lastCounter[f.type]?.let { p -> if (((c - p) and 0xFF) != 1) gaps++ }; lastCounter[f.type] = c }
                stats = stats.copy(valid = stats.valid + 1, counterGaps = gaps, byType = stats.byType + (f.type to ((stats.byType[f.type] ?: 0) + 1)), lastFrameMs = timeMs)
                f
            }
        }
    }
    /** Gọi khi mất kết nối: bộ đếm sẽ nhảy sau khi nối lại, không tính là mất gói. */
    fun onDisconnect() { lastCounter.clear() }
    fun reset() { lastCounter.clear(); stats = Stats() }
}
