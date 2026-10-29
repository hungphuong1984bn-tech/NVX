package vn.nvxconnect.protocol

/**
 * Lớp KHUNG (framing) độc lập theo từng (phiên GATT, characteristic): gom khung bị chia nhỏ qua nhiều notification,
 * tách nhiều khung trong một notification, đồng bộ lại sau byte rác, và đếm mọi thứ bị bỏ.
 *
 * Nguyên tắc: bộ ghép KHÔNG đoán ranh giới khung. Nó chỉ làm theo một [FramingRule] đã KHAI BÁO (có nguồn bằng chứng).
 * Với giao thức chưa rõ dùng [FramingRule.PerNotification]: mỗi notification là một khung thô, không gom, không cắt.
 * Không bao giờ ghi gì lên xe; không giải mã ý nghĩa trường.
 */
sealed interface FramingRule {
    /** Id + nguồn bằng chứng của quy tắc, để báo cáo. */
    val id: String
    val basis: String

    /** Chưa biết khung: mỗi notification giữ nguyên là một khung thô (không ghép/cắt). */
    data object PerNotification : FramingRule {
        override val id = "per-notification"
        override val basis = "Mặc định cho giao thức chưa rõ; không suy đoán ranh giới khung."
    }

    /**
     * Khung dài cố định, loại = byte đầu. [lengths] phải đến từ log thật (ghi vào [basis]).
     * [checksum] (nếu đã xác minh từ log) dùng để đồng bộ lại sau byte rác; null = chỉ dựa độ dài.
     */
    class FixedLengthByType(
        override val id: String, override val basis: String,
        val lengths: Map<Int, Int>, val checksum: ((ByteArray) -> Boolean)? = null
    ) : FramingRule {
        init { require(lengths.isNotEmpty() && lengths.values.all { it >= 2 }) { "lengths không hợp lệ" } }
        val maxLen: Int = lengths.values.max()
    }
}

enum class FrameOrigin(val title: String) {
    WHOLE_NOTIFICATION("Nguyên một notification"),
    JOINED_FRAGMENTS("Ghép từ nhiều mảnh"),
    SPLIT_FROM_MULTI("Tách từ notification chứa nhiều khung"),
    RAW_UNFRAMED("Thô, chưa biết khung")
}

/** [checksumValid] null = quy tắc không có tổng kiểm đã xác minh (không phải "hợp lệ"). */
class AssembledFrame(val bytes: ByteArray, val origin: FrameOrigin, val checksumValid: Boolean?)

data class AssemblerStats(
    val notifications: Long = 0, val framesOut: Long = 0, val joinedFrames: Long = 0, val splitFrames: Long = 0,
    val checksumFailures: Long = 0, val resyncDroppedBytes: Long = 0, val staleFragmentsDropped: Long = 0,
    val bufferOverflowDroppedBytes: Long = 0, val pendingBytes: Int = 0
) {
    /** Số byte bị bỏ vì lý do nào đó (không âm thầm). */
    val totalDroppedBytes: Long get() = resyncDroppedBytes + bufferOverflowDroppedBytes
}

/** Một instance cho một (epoch, characteristic). Gọi [reset] khi đổi phiên GATT: mảnh dở của phiên cũ không được dính sang phiên mới. */
class FrameAssembler(private val rule: FramingRule, private val maxPendingMs: Long = 2_000) {
    private var buf = ByteArray(0)
    private var pendingSinceMs = 0L
    private var s = AssemblerStats()
    val stats: AssemblerStats get() = s.copy(pendingBytes = buf.size)

    fun reset() {
        if (buf.isNotEmpty()) s = s.copy(staleFragmentsDropped = s.staleFragmentsDropped + 1, resyncDroppedBytes = s.resyncDroppedBytes + buf.size)
        buf = ByteArray(0)
    }

    fun feed(bytes: ByteArray, timeMs: Long): List<AssembledFrame> {
        s = s.copy(notifications = s.notifications + 1)
        if (bytes.isEmpty()) return emptyList()
        return when (rule) {
            is FramingRule.PerNotification -> {
                s = s.copy(framesOut = s.framesOut + 1)
                listOf(AssembledFrame(bytes.copyOf(), FrameOrigin.RAW_UNFRAMED, null))
            }
            is FramingRule.FixedLengthByType -> feedFixed(rule, bytes, timeMs)
        }
    }

    private fun feedFixed(r: FramingRule.FixedLengthByType, bytes: ByteArray, timeMs: Long): List<AssembledFrame> {
        if (buf.isNotEmpty() && timeMs - pendingSinceMs > maxPendingMs) {
            s = s.copy(staleFragmentsDropped = s.staleFragmentsDropped + 1, resyncDroppedBytes = s.resyncDroppedBytes + buf.size)
            buf = ByteArray(0)
        }
        val pendingBefore = buf.size
        if (pendingBefore == 0) pendingSinceMs = timeMs
        buf += bytes
        val out = ArrayList<AssembledFrame>()
        var pos = 0
        while (pos < buf.size) {
            val type = buf[pos].toInt() and 0xFF
            val len = r.lengths[type]
            if (len == null) { pos = dropToNextCandidate(r, pos); continue }      // byte rác: đồng bộ lại
            if (buf.size - pos < len) break                                        // chưa đủ: đợi mảnh sau
            val frame = buf.copyOfRange(pos, pos + len)
            val ck = r.checksum?.invoke(frame)
            if (ck == false) {
                // Đã đủ độ dài nhưng tổng kiểm sai: coi vị trí này là sai, bỏ 1 byte và tìm lại (không phát khung hỏng như hợp lệ).
                s = s.copy(checksumFailures = s.checksumFailures + 1, resyncDroppedBytes = s.resyncDroppedBytes + 1); pos++; continue
            }
            // Khung bắt đầu trong phần đã chờ từ notification trước → ghép; bắt đầu đầu notification này và dài đúng bằng nó → nguyên; còn lại → tách.
            val origin = when {
                pos < pendingBefore -> FrameOrigin.JOINED_FRAGMENTS
                pos == 0 && len == bytes.size -> FrameOrigin.WHOLE_NOTIFICATION
                else -> FrameOrigin.SPLIT_FROM_MULTI
            }
            out += AssembledFrame(frame, origin, ck)
            if (origin == FrameOrigin.JOINED_FRAGMENTS) s = s.copy(joinedFrames = s.joinedFrames + 1)
            if (origin == FrameOrigin.SPLIT_FROM_MULTI) s = s.copy(splitFrames = s.splitFrames + 1)
            pos += len
        }
        buf = if (pos >= buf.size) ByteArray(0) else buf.copyOfRange(pos, buf.size)
        // Bộ đệm không được phình vô hạn: tối đa một khung dài nhất. Quá mức → bỏ byte đầu và đếm.
        if (buf.size > r.maxLen) {
            val over = buf.size - r.maxLen
            s = s.copy(bufferOverflowDroppedBytes = s.bufferOverflowDroppedBytes + over); buf = buf.copyOfRange(over, buf.size)
        }
        if (buf.isNotEmpty() && (pendingBefore == 0 || pos > 0)) pendingSinceMs = timeMs   // phần dư bắt đầu từ notification này
        s = s.copy(framesOut = s.framesOut + out.size)
        return out
    }

    /** Bỏ byte không phải loại khung hợp lệ cho tới byte kế tiếp là loại đã khai báo; trả vị trí mới. */
    private fun dropToNextCandidate(r: FramingRule.FixedLengthByType, from: Int): Int {
        var i = from
        while (i < buf.size && (buf[i].toInt() and 0xFF) !in r.lengths) i++
        s = s.copy(resyncDroppedBytes = s.resyncDroppedBytes + (i - from))
        return i
    }
}

/** Quy tắc khung ĐÃ QUAN SÁT cho thiết bị "YCC" (docs/YCC_PROTOCOL_FINDINGS.md, 5.988 gói). Chỉ là quy tắc KHUNG, không nói gì về nghĩa trường. */
object KnownFramingRules {
    val YCC_NUS = FramingRule.FixedLengthByType(
        id = "ycc.nus.fixed-by-type.v1",
        basis = "Quan sát 5.988 gói notify trên 6e400003: độ dài cố định theo byte đầu (0x5A=8, 0x59=77, 0x6A=74, 0x6B=59, 0x6C=143), SUM8 toàn gói = 0 ở 5.988/5.988. Chưa kiểm với MTU nhỏ hơn 247 (khi đó xe có thể chia mảnh).",
        lengths = mapOf(0x5A to 8, 0x59 to 77, 0x6A to 74, 0x6B to 59, 0x6C to 143),
        checksum = { YccFrames.checksumOk(it) }
    )
    /** Chọn quy tắc theo characteristic; không thuộc danh sách → PerNotification. */
    fun forChar(charUuid: String): FramingRule = if (charUuid.equals(YccFrames.CHAR_NOTIFY, true)) YCC_NUS else FramingRule.PerNotification
}
