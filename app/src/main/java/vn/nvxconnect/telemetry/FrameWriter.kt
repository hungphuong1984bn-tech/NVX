package vn.nvxconnect.telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Tiêu thụ hàng đợi tiếp nhận và ghi theo LÔ xuống [DurableFrameLog] (tách khỏi callback BLE và khỏi giao diện).
 * Khi ghi lỗi: giữ gói trong bộ đệm thử lại CÓ GIỚI HẠN, thử lại với backoff; nếu bộ đệm đầy thì bỏ gói CŨ NHẤT và ĐẾM (STORAGE_ERROR).
 * Hồi phục tự động khi ghi lại được.
 */
class FrameWriter(
    private val ingress: FrameIngress,
    private val log: DurableFrameLog,
    private val scope: CoroutineScope,
    private val maxBatch: Int = 256,
    private val batchWindowMs: Long = 50,
    private val retryBufferMax: Int = 4096,
    private val retryBaseMs: Long = 200,
    private val retryCapMs: Long = 5_000
) {
    private val _health = MutableStateFlow(WriterHealth.IDLE)
    val health: StateFlow<WriterHealth> = _health
    @Volatile var lastError: String? = null; private set
    val persisted = AtomicLong(0)
    val errors = AtomicLong(0)
    @Volatile private var retry = ArrayList<CapturedFrame>()
    val retryBuffered: Int get() = retry.size
    /** true khi đã lấy gói ra khỏi hàng đợi nhưng chưa ghi xong (dùng để biết khi nào đã "xả" hết). */
    @Volatile var busy = false; private set
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            var failures = 0
            while (true) {
                val batch = ArrayList<CapturedFrame>()
                if (retry.isEmpty()) {
                    val first = ingress.receiver.receiveCatching().getOrNull() ?: break
                    busy = true
                    batch += first; ingress.onTaken()
                    delay(batchWindowMs)
                } else delay(minOf(retryCapMs, retryBaseMs shl minOf(failures, 5)))
                drainInto(batch)
                failures = if (flush(batch)) 0 else failures + 1
                busy = false
                if (failures > 0 && ingress.isClosed) { dropRetryBuffer(); break }
            }
            withContext(NonCancellable) { flush(ArrayList()); _health.value = WriterHealth.STOPPED }
        }
    }

    private fun drainInto(batch: MutableList<CapturedFrame>) {
        while (batch.size < maxBatch) {
            val r: ChannelResult<CapturedFrame> = ingress.receiver.tryReceive()
            val f = r.getOrNull() ?: break
            batch += f; ingress.onTaken()
        }
    }

    /** true nếu đã ghi xong mọi thứ đang chờ. */
    private fun flush(newFrames: List<CapturedFrame>): Boolean {
        val pending = ArrayList<CapturedFrame>(retry.size + newFrames.size).also { it.addAll(retry); it.addAll(newFrames) }
        if (pending.isEmpty()) return true
        return try {
            log.append(pending)
            persisted.addAndGet(pending.size.toLong()); retry = ArrayList(); lastError = null; _health.value = WriterHealth.OK
            true
        } catch (e: IOException) {
            errors.incrementAndGet(); lastError = e.message ?: e.javaClass.simpleName; _health.value = WriterHealth.DEGRADED
            val reason = if (e is StorageLowException) DropReason.STORAGE_LOW else DropReason.STORAGE_ERROR
            if (pending.size > retryBufferMax) {
                val cut = pending.size - retryBufferMax
                val gone = pending.subList(0, cut)
                ingress.recordDrop(reason, cut.toLong(), gone.first().seq, gone.last().seq, lastError)
                retry = ArrayList(pending.subList(cut, pending.size))
            } else retry = pending
            false
        }
    }

    /** Đang dừng mà vẫn không ghi được: không giữ mãi, đếm số gói mất. */
    private fun dropRetryBuffer() {
        if (retry.isNotEmpty()) ingress.recordDrop(DropReason.STORAGE_ERROR, retry.size.toLong(), retry.first().seq, retry.last().seq, lastError)
        retry = ArrayList()
    }

    /** Đóng hàng đợi, ghi nốt phần còn lại (dùng khi dừng dịch vụ/trước khi xuất). Có thời hạn. */
    suspend fun stopAndJoin(timeoutMs: Long = 5_000) {
        ingress.close()
        val j = job ?: return
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { j.join() }
        job = null
    }
}
