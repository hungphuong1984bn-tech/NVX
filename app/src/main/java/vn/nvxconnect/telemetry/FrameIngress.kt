package vn.nvxconnect.telemetry

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Cửa tiếp nhận gói từ callback Bluetooth: hàng đợi CÓ GIỚI HẠN, không chặn, không I/O.
 * Backpressure: khi đầy, gói bị bỏ NHƯNG được đếm, ghi thời điểm + nguyên nhân + khoảng số thứ tự (gộp theo đợt). Không có đường bỏ im lặng.
 */
class FrameIngress(val capacity: Int = 4096, private val clock: () -> Long = System::currentTimeMillis, private val maxDropEvents: Int = 200) {
    private val channel = Channel<CapturedFrame>(capacity)
    private val seq = AtomicLong(0)
    private val depth = AtomicInteger(0)
    val received = AtomicLong(0); val accepted = AtomicLong(0); val bytesReceived = AtomicLong(0)
    private val droppedIngressCount = AtomicLong(0)
    private val droppedStorageCount = AtomicLong(0)
    private val drops = ArrayDeque<DropEvent>()

    val receiver: ReceiveChannel<CapturedFrame> get() = channel
    val queueDepth: Int get() = depth.get()
    val droppedIngress: Long get() = droppedIngressCount.get()
    val droppedStorage: Long get() = droppedStorageCount.get()
    fun nextSeq(): Long = seq.incrementAndGet()

    /** Gọi trên luồng callback. Trả true nếu đã vào hàng đợi. */
    fun offer(f: CapturedFrame): Boolean {
        received.incrementAndGet(); bytesReceived.addAndGet(f.bytes.size.toLong())
        val ok = channel.trySend(f).isSuccess
        if (ok) { depth.incrementAndGet(); accepted.incrementAndGet() }
        else recordDrop(if (channel.isClosedForSend) DropReason.STOPPED else DropReason.INGRESS_FULL, 1, f.seq, f.seq, null)
        return ok
    }

    /** Bộ ghi gọi sau khi lấy một gói ra khỏi hàng đợi. */
    fun onTaken(n: Int = 1) { depth.addAndGet(-n) }

    /** Ghi nhận gói bị bỏ ở các tầng sau (ví dụ bộ đệm thử lại của bộ ghi đầy). */
    fun recordDrop(reason: DropReason, count: Long, firstSeq: Long, lastSeq: Long, detail: String?) {
        if (count <= 0) return
        if (reason == DropReason.STORAGE_ERROR || reason == DropReason.STORAGE_LOW) droppedStorageCount.addAndGet(count) else droppedIngressCount.addAndGet(count)
        val now = clock()
        synchronized(drops) {
            val last = drops.lastOrNull()
            if (last != null && last.reason == reason && now - last.lastMs <= MERGE_WINDOW_MS) {
                drops.removeLast()
                drops.addLast(last.copy(lastMs = now, count = last.count + count, lastSeq = maxOf(last.lastSeq, lastSeq), detail = detail ?: last.detail))
            } else {
                drops.addLast(DropEvent(now, now, reason, count, firstSeq, lastSeq, detail))
                while (drops.size > maxDropEvents) drops.removeFirst()
            }
        }
    }

    fun dropEvents(): List<DropEvent> = synchronized(drops) { drops.toList() }
    fun close() { channel.close() }
    val isClosed: Boolean get() = channel.isClosedForSend

    private companion object { const val MERGE_WINDOW_MS = 1_000L }
}
