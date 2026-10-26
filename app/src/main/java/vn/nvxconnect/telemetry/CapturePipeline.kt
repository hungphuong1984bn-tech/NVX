package vn.nvxconnect.telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import vn.nvxconnect.protocol.FrameSink
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Đường thu thập gói theo phạm vi TIẾN TRÌNH (không thuộc ViewModel/Activity): nhận từ callback BLE → hàng đợi có giới hạn → ghi lô xuống đĩa.
 * Tách riêng 4 việc: (1) nhận [onFrame] không chặn, (2) phân tích giao thức (ở ViewModel, đọc từ luồng frames), (3) lưu trữ (FrameWriter), (4) cập nhật giao diện (stats ≤ 2 lần/giây).
 */
class CapturePipeline(
    dir: File,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    capacity: Int = 4096,
    segmentMaxBytes: Long = 4L * 1024 * 1024,
    maxTotalBytes: Long = 256L * 1024 * 1024,
    minFreeBytes: Long = 64L * 1024 * 1024,
    freeSpace: () -> Long = { dir.usableSpace },
    private val statsEveryMs: Long = 500,
    syncEachBatch: Boolean = true
) : FrameSink {
    val log = DurableFrameLog(dir, segmentMaxBytes, maxTotalBytes, minFreeBytes, freeSpace, syncEachBatch)
    val ingress = FrameIngress(capacity, clock)
    val writer = FrameWriter(ingress, log, scope)
    private val sessionCounter = AtomicInteger(0)
    @Volatile var sessionId: String = newSessionId(); private set

    private val _stats = MutableStateFlow(CaptureStats(sessionId = sessionId, queueCapacity = capacity))
    val stats: StateFlow<CaptureStats> = _stats

    // Chỉ một luồng callback gọi onFrame nên các trường dưới đây không cần khóa; @Volatile để luồng thống kê đọc được.
    @Volatile private var lastEpoch = -1
    @Volatile private var lastMono = 0L
    @Volatile private var lastWall = 0L
    @Volatile private var lastFrameWall: Long? = null
    @Volatile private var maxGapMs = 0L
    @Volatile private var wallJumps = 0L
    @Volatile private var empty = 0L
    @Volatile private var oversize = 0L
    @Volatile private var dupPayload = 0L
    private val lastPayload = HashMap<String, ByteArray>()
    private var prevReceived = 0L; private var prevAt = 0L; @Volatile private var rate = 0.0

    init {
        writer.start()
        scope.launch { while (true) { delay(statsEveryMs); publish() } }
    }

    private fun newSessionId() = "s" + java.lang.Long.toString(clock(), 36) + java.lang.Integer.toString(sessionCounter.incrementAndGet(), 36)

    override fun onSessionStart() {
        sessionId = newSessionId(); lastEpoch = -1; lastMono = 0; maxGapMs = 0
        synchronized(lastPayload) { lastPayload.clear() }
        publish()
    }

    override fun onFrame(epoch: Int, charUuid: String, serviceUuid: String?, bytes: ByteArray, wallMs: Long, monoNs: Long, rssi: Int?, connState: String, indication: Boolean) {
        if (epoch != lastEpoch) { lastEpoch = epoch; lastMono = 0; synchronized(lastPayload) { lastPayload.clear() } }   // khoảng cách giữa các gói chỉ có nghĩa trong cùng một phiên GATT
        if (lastMono != 0L) { val gap = (monoNs - lastMono) / 1_000_000; if (gap > maxGapMs) maxGapMs = gap }
        if (lastWall != 0L && wallMs < lastWall - CLOCK_JUMP_TOLERANCE_MS) wallJumps++
        lastMono = monoNs; lastWall = wallMs; lastFrameWall = wallMs
        if (bytes.isEmpty()) empty++
        if (bytes.size > RawFrameRecorder.MAX_ATT_VALUE) oversize++
        val key = charUuid.lowercase()
        synchronized(lastPayload) { val prev = lastPayload.put(key, bytes); if (prev != null && bytes.isNotEmpty() && prev.contentEquals(bytes)) dupPayload++ }
        val src = if (indication) PayloadSource.INDICATION else PayloadSource.NOTIFICATION
        ingress.offer(CapturedFrame(ingress.nextSeq(), sessionId, epoch, wallMs, monoNs, key, serviceUuid?.lowercase(), src, rssi, connState, bytes))
    }

    fun publish() {
        val now = System.nanoTime(); val rec = ingress.received.get()
        if (prevAt != 0L && now > prevAt) rate = (rec - prevReceived) * 1e9 / (now - prevAt)
        prevReceived = rec; prevAt = now
        val drops = ingress.dropEvents()
        _stats.value = CaptureStats(
            sessionId = sessionId, received = rec, bytes = ingress.bytesReceived.get(), persisted = writer.persisted.get(),
            droppedIngress = ingress.droppedIngress, droppedStorage = ingress.droppedStorage,
            queueDepth = ingress.queueDepth, queueCapacity = ingress.capacity, retryBuffered = writer.retryBuffered,
            writer = writer.health.value, writerError = writer.lastError, writerErrors = writer.errors.get(),
            lastFrameWallMs = lastFrameWall, ratePerSec = rate, maxGapMs = maxGapMs, wallClockJumps = wallJumps,
            emptyFrames = empty, oversizeFrames = oversize, duplicatePayloads = dupPayload,
            storageBytes = runCatching { log.totalBytes() }.getOrDefault(0), evictedSegments = log.evictedSegments,
            recentDrops = drops.takeLast(5))
    }

    /** Dừng nhận, ghi nốt phần còn lại xuống đĩa (gọi trước khi xuất hoặc khi dịch vụ dừng). */
    suspend fun flushAndStop() { writer.stopAndJoin(); publish() }

    /** Chờ cho tới khi mọi gói đã nhận đều xuống đĩa (hoặc hết [timeoutMs]). false = còn gói chưa ghi được (ví dụ đang lỗi ghi). */
    suspend fun drain(timeoutMs: Long = 3_000): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { while (ingress.queueDepth > 0 || writer.busy || writer.retryBuffered > 0) delay(10); true } ?: false

    /** Người dùng chủ động xóa toàn bộ dữ liệu thu thập đã lưu. Bộ đếm trong phiên vẫn giữ để không che giấu việc đã mất gói. */
    fun clearStored() { log.clear(); publish() }

    private companion object { const val CLOCK_JUMP_TOLERANCE_MS = 2_000L }
}
