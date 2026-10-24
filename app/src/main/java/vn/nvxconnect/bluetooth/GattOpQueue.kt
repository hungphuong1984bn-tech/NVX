package vn.nvxconnect.bluetooth

/**
 * Android chỉ cho MỘT thao tác GATT chạy tại một thời điểm (MTU, discover, ghi descriptor, đọc RSSI...).
 * Hàng đợi này xếp tuần tự; callback gọi complete(tên) khi xong. complete chỉ có tác dụng nếu thao tác đang chạy
 * khớp tiền tố, để callback không do mình yêu cầu (ví dụ hệ thống tự đổi MTU) không nhả nhầm hàng đợi.
 */
class GattOpQueue(private val onStartFailed: (String) -> Unit = {}) {
    private class Op(val name: String, val start: () -> Boolean)
    private val pending = ArrayDeque<Op>()
    private var running: Op? = null

    @Synchronized fun enqueue(name: String, start: () -> Boolean) { pending.addLast(Op(name, start)); pump() }

    @Synchronized fun complete(prefix: String? = null) {
        val r = running ?: return
        if (prefix != null && !r.name.startsWith(prefix)) return
        running = null; pump()
    }

    @Synchronized fun clear() { pending.clear(); running = null }
    @Synchronized fun runningName(): String? = running?.name
    /** Có thao tác nào (đang chạy hoặc đang chờ) có tên bắt đầu bằng prefix? Dùng để không xếp trùng (ví dụ đọc RSSI). */
    @Synchronized fun contains(prefix: String): Boolean =
        running?.name?.startsWith(prefix) == true || pending.any { it.name.startsWith(prefix) }
    @Synchronized fun size(): Int = pending.size + (if (running != null) 1 else 0)

    private fun pump() {
        while (running == null) {
            val op = pending.removeFirstOrNull() ?: return
            val ok = runCatching { op.start() }.getOrDefault(false)
            if (ok) running = op else onStartFailed(op.name)
        }
    }
}
