package vn.nvxconnect.telemetry

import vn.nvxconnect.bluetooth.AdvertRecord
import vn.nvxconnect.bluetooth.HexUtil

/** Nguồn của MỖI payload. Đăng ký notify thành công KHÔNG phải là một payload: chỉ gói thật mới được ghi ở đây. */
enum class PayloadSource { ADVERTISEMENT, READ, NOTIFICATION, INDICATION }

/** Cờ kiểm tra payload. DUPLICATE chỉ nghĩa là trùng byte-by-byte với gói liền trước cùng luồng, không tự là lỗi. */
enum class PayloadFlag { EMPTY, OVERSIZE, UNIFORM, DUPLICATE }

/** Một payload thật. deviceId đã được ẩn danh lúc ghi; hex là dữ liệu thô (che chuỗi ASCII dài khi xuất). */
data class PayloadRecord(
    val seq: Long, val timeMs: Long, val sessionId: String, val deviceId: String,
    val source: PayloadSource, val serviceUuid: String?, val charUuid: String?, val rssi: Int?,
    val hex: String, val flags: Set<PayloadFlag> = emptySet()
) {
    val bytes: ByteArray? get() = HexUtil.fromHex(hex)
    val length: Int get() = if (hex.isBlank()) 0 else hex.trim().split(Regex("\\s+")).size
}

enum class ConnEventType {
    SESSION_START, SESSION_STOP, SCAN_SEEN, CONNECT_START, CONNECTED, DISCOVERY_DONE, MTU, SUBSCRIBE_PENDING, SUBSCRIBE_OK, SUBSCRIBE_FAILED,
    UNSUBSCRIBED, READ_FAILED, DISCONNECTED, RECONNECTING, TIMEOUT, ERROR, RSSI
}

/** Sự kiện kết nối có cấu trúc (khác nhật ký văn bản): để tái lập phân tích mà không phải đọc chuỗi tiếng Việt. */
data class ConnEvent(val timeMs: Long, val sessionId: String, val deviceId: String, val type: ConnEventType, val uuid: String? = null, val detail: String? = null)

data class StreamKey(val deviceId: String, val charUuid: String?, val source: PayloadSource)

data class StreamStats(
    val count: Long = 0, val bytes: Long = 0, val empty: Long = 0, val duplicates: Long = 0, val abnormal: Long = 0,
    val firstMs: Long = 0, val lastMs: Long = 0
)

data class DeviceInfo(val deviceId: String, val name: String?, val serviceUuids: List<String>, val lastRssi: Int?, val lastSeenMs: Long)

/** Bộ lọc xem/xuất. Trường null = không lọc. */
data class RecordFilter(
    val deviceId: String? = null, val charUuid: String? = null, val source: PayloadSource? = null,
    val sessionId: String? = null, val flag: PayloadFlag? = null
) {
    fun matches(r: PayloadRecord) =
        (deviceId == null || r.deviceId == deviceId) && (charUuid == null || r.charUuid.equals(charUuid, true)) &&
            (source == null || r.source == source) && (sessionId == null || r.sessionId == sessionId) && (flag == null || flag in r.flags)
}

/**
 * Bộ ghi payload thô, an toàn đa luồng, có giới hạn dung lượng.
 *  - Giữ [pinnedHead] bản ghi ĐẦU phiên (khung hello/info chỉ xuất hiện đầu phiên) + vòng đệm các bản ghi mới nhất.
 *  - Không bao giờ cắt âm thầm: [droppedOldest] được báo trong thống kê và trong file xuất.
 *  - Chỉ ghi khi [recording]; gói đến lúc đang dừng bị đếm vào [ignoredWhileStopped].
 *  - Chỉ ghi nhận dữ liệu thu động; không có đường ghi/gửi dữ liệu xuống xe.
 */
class RawFrameRecorder(
    private val maxRecords: Int = 20_000, private val pinnedHead: Int = 200, private val maxEvents: Int = 5_000,
    private val advertMinIntervalMs: Long = 1_000L,
    private val anonymize: (String) -> String = { it },
    private val anonymizeName: (String?) -> String? = { it },
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val head = ArrayList<PayloadRecord>()
    private val ring = ArrayDeque<PayloadRecord>()
    private val events = ArrayDeque<ConnEvent>()
    private val stats = LinkedHashMap<StreamKey, StreamStats>()
    private val lastHex = HashMap<StreamKey, String>()
    private val lastAdvertMs = HashMap<String, Long>()
    private val devices = LinkedHashMap<String, DeviceInfo>()
    private var seq = 0L
    private var sessionSeq = 0
    var sessionId: String = "s0"; private set
    var sessionLabel: String = ""; private set
    var recording: Boolean = false; private set
    var droppedOldest: Long = 0; private set
    var droppedEvents: Long = 0; private set
    var ignoredWhileStopped: Long = 0; private set
    var lastPayloadMs: Long? = null; private set

    /** Bắt đầu một phiên ghi mới (id tăng dần). Dữ liệu cũ được giữ; dùng [clear] để xóa. */
    @Synchronized fun start(label: String = ""): String {
        sessionSeq++; sessionId = "s$sessionSeq"; sessionLabel = label; recording = true
        addEvent(ConnEvent(clock(), sessionId, "-", ConnEventType.SESSION_START, null, label))
        return sessionId
    }
    @Synchronized fun stop() { if (recording) addEvent(ConnEvent(clock(), sessionId, "-", ConnEventType.SESSION_STOP)); recording = false }

    @Synchronized fun clear() {
        head.clear(); ring.clear(); events.clear(); stats.clear(); lastHex.clear(); lastAdvertMs.clear(); devices.clear()
        droppedOldest = 0; droppedEvents = 0; ignoredWhileStopped = 0; lastPayloadMs = null
    }

    private fun addEvent(e: ConnEvent) { events.addLast(e); while (events.size > maxEvents) { events.removeFirst(); droppedEvents++ } }

    /** Ghi một payload của luồng GATT (read/notify/indicate). Trả bản ghi, hoặc null nếu đang dừng ghi. */
    @Synchronized fun record(
        address: String, source: PayloadSource, serviceUuid: String?, charUuid: String?, bytes: ByteArray,
        timeMs: Long = clock(), rssi: Int? = null
    ): PayloadRecord? {
        if (!recording) { ignoredWhileStopped++; return null }
        val dev = anonymize(address); val cu = charUuid?.lowercase()
        val key = StreamKey(dev, cu, source)
        val hex = HexUtil.toHex(bytes)
        val flags = HashSet<PayloadFlag>()
        if (bytes.isEmpty()) flags += PayloadFlag.EMPTY
        if (bytes.size > MAX_ATT_VALUE) flags += PayloadFlag.OVERSIZE
        if (bytes.size >= 4 && bytes.all { it == bytes[0] }) flags += PayloadFlag.UNIFORM
        if (bytes.isNotEmpty() && lastHex[key] == hex) flags += PayloadFlag.DUPLICATE
        lastHex[key] = hex
        val r = PayloadRecord(++seq, timeMs, sessionId, dev, source, serviceUuid?.lowercase(), cu, rssi, hex, flags)
        if (head.size < pinnedHead) head += r else { ring.addLast(r); while (head.size + ring.size > maxRecords) { ring.removeFirst(); droppedOldest++ } }
        val s = stats[key] ?: StreamStats(firstMs = timeMs)
        stats[key] = s.copy(count = s.count + 1, bytes = s.bytes + bytes.size, empty = s.empty + (if (bytes.isEmpty()) 1 else 0),
            duplicates = s.duplicates + (if (PayloadFlag.DUPLICATE in flags) 1 else 0),
            abnormal = s.abnormal + (if (flags.any { it != PayloadFlag.DUPLICATE }) 1 else 0), lastMs = timeMs)
        if (source != PayloadSource.ADVERTISEMENT) lastPayloadMs = timeMs
        return r
    }

    /** Ghi dữ liệu nhà sản xuất trong gói quảng bá (giới hạn tần suất theo thiết bị để không tràn bộ nhớ) và cập nhật bảng thiết bị. */
    @Synchronized fun recordAdvert(a: AdvertRecord) {
        val dev = anonymize(a.address)
        devices[dev] = DeviceInfo(dev, anonymizeName(a.name) ?: devices[dev]?.name, a.advertisedServiceUuids.map { it.lowercase() }, a.rssi, a.seenAtMillis)
        if (!recording) return
        val last = lastAdvertMs[dev]
        if (last != null && a.seenAtMillis - last < advertMinIntervalMs) return
        lastAdvertMs[dev] = a.seenAtMillis
        addEvent(ConnEvent(a.seenAtMillis, sessionId, dev, ConnEventType.SCAN_SEEN, null, "rssi=${a.rssi};connectable=${a.connectable}"))
        for ((company, hex) in a.manufacturerData) {
            val bytes = HexUtil.fromHex(hex) ?: continue
            record(a.address, PayloadSource.ADVERTISEMENT, null, "mfr:%04x".format(company), bytes, a.seenAtMillis, a.rssi)
        }
    }

    @Synchronized fun event(address: String, type: ConnEventType, uuid: String? = null, detail: String? = null, timeMs: Long = clock()) {
        if (!recording) return
        addEvent(ConnEvent(timeMs, sessionId, anonymize(address), type, uuid?.lowercase(), detail))
    }

    @Synchronized fun snapshot(f: RecordFilter = RecordFilter()): List<PayloadRecord> = (head + ring).filter { f.matches(it) }
    @Synchronized fun eventsSnapshot(type: ConnEventType? = null, deviceId: String? = null): List<ConnEvent> =
        events.filter { (type == null || it.type == type) && (deviceId == null || it.deviceId == deviceId) }
    @Synchronized fun streamStats(): Map<StreamKey, StreamStats> = LinkedHashMap(stats)
    @Synchronized fun deviceList(): List<DeviceInfo> = devices.values.toList()
    @Synchronized fun totalPayloads(): Long = stats.values.sumOf { it.count }
    @Synchronized fun payloadCount(): Int = head.size + ring.size
    /** Số gói nhận được (không tính quảng bá) trên một characteristic. 0 = chưa nhận gói nào, bất kể đã đăng ký notify hay chưa. */
    @Synchronized fun receivedOn(charUuid: String): Long = stats.filter { it.key.charUuid.equals(charUuid, true) && it.key.source != PayloadSource.ADVERTISEMENT }.values.sumOf { it.count }

    companion object { const val MAX_ATT_VALUE = 512 }
}
