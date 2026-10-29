package vn.nvxconnect.telemetry

import vn.nvxconnect.util.MiniJson
import vn.nvxconnect.util.MiniJson.int
import vn.nvxconnect.util.MiniJson.obj
import vn.nvxconnect.util.MiniJson.text
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

/** Kết quả của một sự kiện: phân biệt rõ thành công / thất bại / CHƯA KIỂM TRA (không được hiểu là thành công). */
enum class EventOutcome { SUCCESS, FAILURE, NOT_TESTED, INFO }

/**
 * Sự kiện kết nối có cấu trúc (không phải payload). [deviceKey] là mã ẩn danh ổn định ([DurableEventLog.deviceKey]), KHÔNG phải địa chỉ MAC.
 * [kind] dùng từ vựng cố định (xem [EventKinds]) để phân tích ngoại tuyến.
 */
data class ConnectionEvent(
    val wallMs: Long, val monoNs: Long, val sessionId: String, val epoch: Int, val deviceKey: String?,
    val kind: String, val outcome: EventOutcome, val gattStatus: Int? = null, val detail: String = "",
    val attrs: Map<String, String> = emptyMap()
)

object EventKinds {
    const val SCAN_RESULT = "scan.result"; const val CONNECT_ATTEMPT = "gatt.connect.attempt"; const val CONNECTED = "gatt.connected"
    const val MTU = "gatt.mtu"; const val BOND = "gatt.bond"; const val DISCOVERY = "gatt.discovery"
    const val SUBSCRIBE = "gatt.subscribe"; const val DATA_FIRST = "data.first-frame"; const val TIMEOUT = "gatt.timeout"
    const val DISCONNECT = "gatt.disconnect"; const val RECONNECT = "gatt.reconnect"; const val ERROR = "gatt.error"
    const val USER_ACTION = "user.action"; const val STAGE = "link.stage"
}

enum class EventLogHealth { OK, LOW_STORAGE, WRITE_ERROR }

data class EventLogStats(
    val accepted: Long, val persisted: Long, val droppedQueueFull: Long, val droppedStorage: Long,
    val segmentsDeleted: Long, val eventsInDeletedSegments: Long, val corruptLinesOnOpen: Long,
    val health: EventLogHealth, val lastError: String?, val totalBytes: Long, val warnings: List<String>
)

/**
 * Nhật ký sự kiện kết nối bền vững, dạng JSON Lines có CRC32, chia đoạn + xoay vòng theo tổng dung lượng.
 * Khác với bộ đệm RAM: không làm mất sự kiện âm thầm — mọi sự kiện bị loại đều có bộ đếm và cảnh báo.
 * Ghi chạy trên luồng riêng (gọi [append] không chặn luồng callback Bluetooth).
 *
 * Định dạng dòng: `E1,<crc32 hex 8>,<json>`; CRC tính trên phần json. Dòng ghi dở (tiến trình bị tắt giữa chừng) bị phát hiện và đếm khi mở lại.
 */
class DurableEventLog(
    private val dir: File,
    private val maxSegmentBytes: Long = 256 * 1024, private val maxTotalBytes: Long = 4L * 1024 * 1024,
    private val minFreeBytes: Long = 8L * 1024 * 1024, private val maxQueue: Int = 2_000,
    private val freeSpace: () -> Long = { dir.usableSpace },
    private val executor: ExecutorService? = null
) {
    private val ownExecutor = executor == null
    private val exec: ExecutorService = executor ?: Executors.newSingleThreadExecutor { r -> Thread(r, "nvx-event-log").apply { isDaemon = true } }
    private val lock = Any()
    private val queue = ArrayDeque<ConnectionEvent>()
    private val accepted = AtomicLong(); private val persisted = AtomicLong(); private val droppedQueue = AtomicLong(); private val droppedStorage = AtomicLong()
    private var segDeleted = 0L; private var eventsDeleted = 0L; private var corruptOnOpen = 0L
    private var health = EventLogHealth.OK; private var lastError: String? = null
    private var segIndex = 0; private var current: File? = null; private var out: FileOutputStream? = null; private var currentBytes = 0L
    private val warnings = ArrayList<String>()
    private var drainScheduled = false

    init { dir.mkdirs(); openExisting() }

    companion object {
        private val ISO = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }
        fun iso(ms: Long): String = ISO.get()!!.format(Date(ms))

        /** Mã thiết bị ẩn danh, ổn định giữa các phiên cùng [salt]; không cho phép khôi phục địa chỉ MAC. */
        fun deviceKey(address: String, salt: String): String =
            "dev-" + MessageDigest.getInstance("SHA-256").digest((salt + address.uppercase()).toByteArray()).joinToString("") { "%02x".format(it) }.take(10)

        private fun crc(s: String) = "%08x".format(CRC32().also { it.update(s.toByteArray(Charsets.UTF_8)) }.value)

        fun encode(e: ConnectionEvent): String {
            val json = buildString {
                append("{\"v\":1,\"utc\":").append(MiniJson.quote(iso(e.wallMs))).append(",\"wallMs\":").append(e.wallMs).append(",\"monoNs\":").append(e.monoNs)
                append(",\"session\":").append(MiniJson.quote(e.sessionId)).append(",\"epoch\":").append(e.epoch)
                append(",\"device\":").append(MiniJson.quoteOrNull(e.deviceKey)).append(",\"kind\":").append(MiniJson.quote(e.kind))
                append(",\"outcome\":").append(MiniJson.quote(e.outcome.name)).append(",\"gattStatus\":").append(e.gattStatus?.toString() ?: "null")
                append(",\"detail\":").append(MiniJson.quote(e.detail)).append(",\"attrs\":{")
                e.attrs.entries.joinTo(this, ",") { MiniJson.quote(it.key) + ":" + MiniJson.quote(it.value) }
                append("}}")
            }
            return "E1,${crc(json)},$json"
        }

        /** null nếu dòng hỏng (sai CRC, JSON hỏng, thiếu trường). */
        fun decode(line: String): ConnectionEvent? {
            if (!line.startsWith("E1,")) return null
            val c = line.indexOf(',', 3); if (c < 0) return null
            val json = line.substring(c + 1)
            if (crc(json) != line.substring(3, c)) return null
            return try {
                val o = MiniJson.parse(json).obj() ?: return null
                val attrs = (o["attrs"].obj() ?: emptyMap()).mapValues { (it.value as? String) ?: it.value.toString() }
                ConnectionEvent(
                    wallMs = (o["wallMs"] as? Number)?.toLong() ?: return null, monoNs = (o["monoNs"] as? Number)?.toLong() ?: return null,
                    sessionId = o["session"].text() ?: return null, epoch = o["epoch"].int() ?: return null, deviceKey = o["device"].text(),
                    kind = o["kind"].text() ?: return null, outcome = EventOutcome.valueOf(o["outcome"].text() ?: return null),
                    gattStatus = o["gattStatus"].int(), detail = o["detail"].text() ?: "", attrs = attrs)
            } catch (ex: Exception) { null }
        }
    }

    /** Không chặn: đưa vào hàng đợi có giới hạn; đầy → đếm [EventLogStats.droppedQueueFull] và ghi cảnh báo. */
    fun append(e: ConnectionEvent) {
        accepted.incrementAndGet()
        val schedule: Boolean
        synchronized(lock) {
            if (queue.size >= maxQueue) { droppedQueue.incrementAndGet(); if (warnings.none { it.startsWith("Hàng đợi") }) warnings += "Hàng đợi sự kiện đầy: có sự kiện bị loại (xem bộ đếm)"; return }
            queue.addLast(e); schedule = !drainScheduled; if (schedule) drainScheduled = true
        }
        if (schedule) try { exec.execute { drain() } } catch (_: Exception) { synchronized(lock) { drainScheduled = false } }
    }

    /** Ghi hết hàng đợi ngay trên luồng gọi (dùng khi tắt dịch vụ/xuất/test). */
    fun flushBlocking() { drain() }

    /**
     * Khóa riêng cho TOÀN BỘ vòng rút-và-ghi: chỉ một luồng được rút/ghi tại một thời điểm → thứ tự dòng = thứ tự [append],
     * và [flushBlocking] là rào chắn thật (khi nó trả về, mọi sự kiện đã nhận trước đó đều đã được ghi hoặc đã được đếm là bị loại).
     */
    private val drainLock = Any()

    private fun drain() = synchronized(drainLock) {
        while (true) {
            val e = synchronized(lock) { queue.removeFirstOrNull() ?: run { drainScheduled = false; return@synchronized null } } ?: return@synchronized
            writeOne(e)
        }
    }

    private fun writeOne(e: ConnectionEvent) = synchronized(lock) {
        try {
            if (freeSpace() < minFreeBytes) {
                health = EventLogHealth.LOW_STORAGE; droppedStorage.incrementAndGet()
                if (warnings.none { it.startsWith("Dung lượng") }) warnings += "Dung lượng trống thấp (< ${minFreeBytes / 1024 / 1024} MB): sự kiện bị loại, đã đếm"
                return@synchronized
            }
            val bytes = (encode(e) + "\n").toByteArray(Charsets.UTF_8)
            if (out == null || currentBytes + bytes.size > maxSegmentBytes) rotate()
            out!!.write(bytes); out!!.fd.sync(); currentBytes += bytes.size; persisted.incrementAndGet()
            if (health != EventLogHealth.OK) { health = EventLogHealth.OK }
            enforceTotal()
        } catch (ex: Exception) {
            health = EventLogHealth.WRITE_ERROR; lastError = ex.message ?: ex.javaClass.simpleName; droppedStorage.incrementAndGet()
            try { out?.close() } catch (_: Exception) {}; out = null
        }
    }

    private fun segFiles(): List<File> = (dir.listFiles { f -> f.name.matches(Regex("ev-\\d{6}\\.nvxev")) } ?: emptyArray()).sortedBy { it.name }

    private fun openExisting() = synchronized(lock) {
        val segs = segFiles()
        segIndex = segs.lastOrNull()?.name?.substring(3, 9)?.toIntOrNull() ?: 0
        for (f in segs) corruptOnOpen += f.readLines().count { it.isNotBlank() && decode(it) == null }
        val last = segs.lastOrNull()
        if (last != null && last.length() < maxSegmentBytes) {
            // Sửa đuôi ghi dở: nếu byte cuối không phải '\n' thì cắt tới '\n' cuối cùng trước khi nối thêm.
            val data = last.readBytes()
            if (data.isNotEmpty() && data.last() != '\n'.code.toByte()) {
                val cut = data.lastIndexOf('\n'.code.toByte()) + 1
                last.writeBytes(data.copyOfRange(0, cut)); warnings += "Đã cắt dòng ghi dở ở cuối ${last.name}"
            }
            current = last; currentBytes = last.length(); out = FileOutputStream(last, true)
        }
    }

    private fun rotate() {
        try { out?.close() } catch (_: Exception) {}
        segIndex++; val f = File(dir, "ev-%06d.nvxev".format(segIndex)); current = f; currentBytes = 0; out = FileOutputStream(f, true)
    }

    private fun enforceTotal() {
        var segs = segFiles()
        while (segs.size > 1 && segs.sumOf { it.length() } > maxTotalBytes) {
            val victim = segs.first()
            val n = try { victim.readLines().count { it.isNotBlank() } } catch (_: Exception) { 0 }
            if (victim == current) break
            if (victim.delete()) { segDeleted++; eventsDeleted += n; if (warnings.none { it.startsWith("Xoay vòng") }) warnings += "Xoay vòng: đã xóa đoạn cũ nhất để giữ dưới ${maxTotalBytes / 1024} KB (xem bộ đếm)" }
            segs = segFiles()
        }
    }

    /** Đọc lại mọi sự kiện còn trên đĩa theo thứ tự ghi, kèm số dòng hỏng. Gọi [flushBlocking] trước nếu cần dữ liệu mới nhất. */
    fun readAll(): Pair<List<ConnectionEvent>, Int> {
        var bad = 0; val ev = ArrayList<ConnectionEvent>()
        synchronized(lock) { for (f in segFiles()) for (l in f.readLines()) { if (l.isBlank()) continue; val d = decode(l); if (d == null) bad++ else ev += d } }
        return ev to bad
    }

    fun stats(): EventLogStats = synchronized(lock) {
        EventLogStats(accepted.get(), persisted.get(), droppedQueue.get(), droppedStorage.get(), segDeleted, eventsDeleted, corruptOnOpen, health, lastError,
            segFiles().sumOf { it.length() }, warnings.toList())
    }

    /** Xuất JSON Lines (đã ẩn danh sẵn: chỉ có deviceKey). Dòng đầu là metadata + bộ đếm để người đọc biết có mất dữ liệu hay không. */
    fun exportJsonLines(nowMs: Long): String {
        flushBlocking()
        val (events, bad) = readAll(); val s = stats()
        return buildString {
            append("{\"meta\":true,\"format\":\"nvx-events-v1\",\"exportedUtc\":").append(MiniJson.quote(iso(nowMs)))
            append(",\"events\":").append(events.size).append(",\"corruptLines\":").append(bad)
            append(",\"droppedQueueFull\":").append(s.droppedQueueFull).append(",\"droppedStorage\":").append(s.droppedStorage)
            append(",\"segmentsDeleted\":").append(s.segmentsDeleted).append(",\"eventsInDeletedSegments\":").append(s.eventsInDeletedSegments).append("}\n")
            for (e in events) append(encode(e).substringAfter(',').substringAfter(',')).append('\n')
        }
    }

    fun clear() = synchronized(lock) {
        try { out?.close() } catch (_: Exception) {}; out = null; current = null; segFiles().forEach { it.delete() }; segIndex = 0; currentBytes = 0
        queue.clear(); warnings.clear(); health = EventLogHealth.OK; lastError = null
    }

    fun close() { flushBlocking(); synchronized(lock) { try { out?.close() } catch (_: Exception) {}; out = null }; if (ownExecutor) exec.shutdown() }
}

/** Đích nhận sự kiện kết nối. Cài đặt phải không chặn. */
fun interface ConnectionEventSink { fun onEvent(e: ConnectionEvent) }

/** Điền mã phiên + mã thiết bị ẩn danh vào sự kiện rồi ghi bền vững. Địa chỉ MAC không bao giờ vào nhật ký. */
class ConnectionEventRecorder(
    private val log: DurableEventLog, private val sessionId: () -> String, private val deviceKeyOf: () -> String?
) : ConnectionEventSink {
    override fun onEvent(e: ConnectionEvent) {
        log.append(e.copy(sessionId = sessionId(), deviceKey = deviceKeyOf()))
    }
}
