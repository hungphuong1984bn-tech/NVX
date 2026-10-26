package vn.nvxconnect.telemetry

/**
 * Một gói thu thập được, đủ thông tin để phân tích lại sau này.
 * [seq] được cấp NGAY KHI gói đến (trước khi vào hàng đợi): nếu gói bị bỏ thì trong dữ liệu đã lưu sẽ lộ ra KHOẢNG TRỐNG số thứ tự.
 * [bytes] là dữ liệu thô nguyên vẹn, không bao giờ bị sửa trong quá trình giải mã.
 * Không chứa địa chỉ MAC/tên thiết bị.
 */
class CapturedFrame(
    val seq: Long, val sessionId: String, val epoch: Int,
    val wallMs: Long, val monoNs: Long,
    val charUuid: String, val serviceUuid: String?, val source: PayloadSource,
    val rssi: Int?, val connState: String, val bytes: ByteArray
) {
    val hex: String get() = CaptureCodec.hexCompact(bytes)
    override fun toString() = "CapturedFrame(#$seq $sessionId e$epoch $charUuid ${bytes.size}B)"
}

enum class DropReason(val title: String) {
    INGRESS_FULL("Hàng đợi tiếp nhận đầy (bộ ghi không theo kịp)"),
    STORAGE_ERROR("Lỗi ghi bộ nhớ, bộ đệm thử lại đã đầy"),
    STORAGE_LOW("Bộ nhớ trống quá thấp"),
    STOPPED("Bộ thu thập đã dừng")
}

/** Một đợt gói bị bỏ do ỨNG DỤNG (không phải do xe không gửi). Các đợt liên tiếp cùng nguyên nhân được gộp. */
data class DropEvent(val firstMs: Long, val lastMs: Long, val reason: DropReason, val count: Long, val firstSeq: Long, val lastSeq: Long, val detail: String? = null)

enum class WriterHealth { IDLE, OK, DEGRADED, STOPPED }

data class CaptureStats(
    val sessionId: String = "",
    val received: Long = 0, val bytes: Long = 0, val persisted: Long = 0,
    val droppedIngress: Long = 0, val droppedStorage: Long = 0,
    val queueDepth: Int = 0, val queueCapacity: Int = 0, val retryBuffered: Int = 0,
    val writer: WriterHealth = WriterHealth.IDLE, val writerError: String? = null, val writerErrors: Long = 0,
    val lastFrameWallMs: Long? = null, val ratePerSec: Double = 0.0, val maxGapMs: Long = 0,
    val wallClockJumps: Long = 0, val emptyFrames: Long = 0, val oversizeFrames: Long = 0, val duplicatePayloads: Long = 0,
    val storageBytes: Long = 0, val evictedSegments: Long = 0, val recentDrops: List<DropEvent> = emptyList()
) {
    /** Gói đến từ xe nhưng ứng dụng không lưu được. */
    val lostByApp: Long get() = droppedIngress + droppedStorage
}
