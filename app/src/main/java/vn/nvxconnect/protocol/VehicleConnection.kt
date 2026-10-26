package vn.nvxconnect.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import vn.nvxconnect.bluetooth.ErrorKind
import vn.nvxconnect.bluetooth.GattServiceInfo
import vn.nvxconnect.bluetooth.LogEntry

/**
 * Trạng thái phiên GATT. Chỉ Ready nghĩa là đã kết nối GATT VÀ khám phá xong dịch vụ.
 * Giao diện không được hiển thị "Connected" cho bất kỳ trạng thái nào khác.
 */
sealed interface ConnState {
    val code: String

    data object Disconnected : ConnState { override val code = "DISCONNECTED" }
    data object Connecting : ConnState { override val code = "CONNECTING" }
    /** Liên kết GATT đã thiết lập, chưa khám phá dịch vụ (đang thỏa thuận MTU). */
    data object Connected : ConnState { override val code = "CONNECTED" }
    data object DiscoveringServices : ConnState { override val code = "DISCOVERING_SERVICES" }
    data class Ready(val services: List<GattServiceInfo>) : ConnState { override val code = "READY" }
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : ConnState { override val code = "RECONNECTING" }
    data class Error(val kind: ErrorKind, val reason: String) : ConnState { override val code = "ERROR" }

    val isActive get() = this is Connecting || this is Connected || this is DiscoveringServices || this is Ready || this is Reconnecting
}

enum class SubscriptionState { PENDING, ACTIVE, FAILED }

enum class LinkEventKind { ATTEMPT_FAILED, LINK_LOST, USER_DISCONNECT, BLUETOOTH_OFF, BLUETOOTH_RESUMED, AUTH_REQUIRED, READY }

/**
 * Một mốc trong lịch sử kết nối. [upMs] = thời gian liên kết đã sống (nếu từng Ready) trước khi kết thúc; [status] = mã GATT thô (nếu có).
 */
data class LinkEvent(val wallMs: Long, val epoch: Int, val kind: LinkEventKind, val status: Int?, val reason: String, val upMs: Long? = null, val attempt: Int = 0, val framesInSession: Long = 0)

data class LinkDiagnostics(val lateEventsDropped: Long = 0, val uiFramesDropped: Long = 0, val epoch: Int = 0)

/**
 * Gợi ý về LÝ DO liên kết đang thế nào, suy ra từ log thật 2026-10-25 (không phải kết luận về giao thức):
 *  - STREAMING: đã nhận ít nhất một gói notify trong phiên GATT hiện tại.
 *  - WAITING_IGNITION: xe tự ngắt (status 19) khi chưa có gói nào ở ~29,3 s, hoặc xe biến mất sau khi từng phát dữ liệu.
 *    Quan sát: tắt khóa điện thì xe ngắt liên kết rảnh ~29,3 s/lần; bật khóa thì giữ liên kết (133 s, 355 s) và phát ~18 gói/s.
 *  - NOT_IN_RANGE: chưa từng thấy xe trong lần kết nối này (hết thời gian chờ), có thể xe tắt/ngoài tầm/Y-Connect đang giữ.
 */
enum class LinkHint { NONE, STREAMING, WAITING_IGNITION, NOT_IN_RANGE }

/**
 * Độc lập giao diện. KHÔNG có hàm ghi dữ liệu: giai đoạn này chỉ nghe thụ động.
 * Thao tác ghi duy nhất của app là ghi descriptor CCCD (bật/tắt notify) trên characteristic người dùng chọn.
 */
interface VehicleConnection {
    val state: StateFlow<ConnState>
    val frames: Flow<RawFrame>
    /** Nhật ký sự kiện kỹ thuật (trạng thái, mã GATT đã dịch, ghép nối, kết quả ghi descriptor). */
    val log: Flow<LogEntry>
    /** RSSI đo được khi đang kết nối (dBm), null nếu chưa có. */
    val rssi: StateFlow<Int?>
    /** MTU đã thỏa thuận (23 nếu chưa đổi). */
    val mtu: StateFlow<Int>
    /** Trạng thái đăng ký nghe theo characteristic: ACTIVE chỉ khi thiết bị đã xác nhận ghi descriptor. */
    val subscriptions: StateFlow<Map<String, SubscriptionState>>
    /** Kết quả đọc các characteristic thông tin chuẩn (tên thiết bị, hãng, firmware...). */
    val deviceInfo: StateFlow<Map<String, String>>
    /** Gợi ý lý do trạng thái liên kết (xem [LinkHint]). Mặc định NONE cho các cài đặt không theo dõi. */
    val hint: StateFlow<LinkHint> get() = MutableStateFlow(LinkHint.NONE)
    /** Thiết bị đang được giữ phiên (null nếu chưa/đã ngắt). Để giao diện gắn lại sau khi Activity/ViewModel được tạo mới. */
    val address: String? get() = null
    /** Lịch sử các lần mất kết nối/nối lại gần nhất (mới nhất ở cuối, tối đa 100). */
    val linkHistory: StateFlow<List<LinkEvent>> get() = MutableStateFlow(emptyList())
    /** Mốc đồng hồ đơn điệu (System.nanoTime) lúc phiên GATT hiện tại đạt Ready; null nếu chưa Ready. */
    val readySinceNs: StateFlow<Long?> get() = MutableStateFlow(null)
    /** Số thông báo đến muộn/ngoài phiên bị loại, số gói giao diện không theo kịp (đã vẫn được ghi ở FrameSink). */
    val diagnostics: StateFlow<LinkDiagnostics> get() = MutableStateFlow(LinkDiagnostics())

    /** Chỉ một phiên GATT hoạt động: gọi lại cho cùng thiết bị thì bỏ qua; khác thiết bị thì đóng phiên cũ trước. */
    fun connect(address: String)
    fun disconnect()
    /** Chỉ đăng ký notify/indicate trên characteristic người dùng chọn và có thuộc tính tương ứng. */
    fun subscribe(charUuid: String): Boolean
    fun unsubscribe(charUuid: String): Boolean
    /** Chỉ đọc được các characteristic thông tin chuẩn trong danh sách cho phép (StandardInfo). */
    fun readStandardInfo(charUuid: String): Boolean
}

/**
 * Lùi thời gian 1s,2s,4s... tối đa [capMs]; null = bỏ cuộc.
 * [persistent] = chế độ XE: không bao giờ bỏ cuộc (chỉ dừng khi người dùng ngắt hoặc xe đòi xác thực), lùi tối đa [persistentCapMs]
 * để bắt kịp lúc bật khóa điện. Khi đó `maxAttempts` được báo là 0 (= không giới hạn).
 */
class ReconnectPolicy(
    val maxAttempts: Int = 5, private val baseMs: Long = 1000, private val capMs: Long = 30_000,
    val persistent: Boolean = false, private val persistentCapMs: Long = 4_000,
    /** 0 = không rung. >0: thời gian chờ thực tế được nhân với (1 ± jitter) để nhiều thiết bị/lần thử không đồng bộ nhau. */
    val jitter: Double = 0.0
) {
    /** Như [nextDelayMs] nhưng có rung ngẫu nhiên ([random] ∈ [0,1)); không bao giờ dưới 1 ms. */
    fun nextDelayJitteredMs(attemptsSoFar: Int, random: Double): Long? {
        val d = nextDelayMs(attemptsSoFar) ?: return null
        if (jitter <= 0.0) return d
        return maxOf(1L, (d * (1.0 + jitter * (2.0 * random.coerceIn(0.0, 1.0) - 1.0))).toLong())
    }

    fun nextDelayMs(attemptsSoFar: Int): Long? = when {
        persistent -> minOf(persistentCapMs, baseMs shl minOf(attemptsSoFar, 20))
        attemptsSoFar >= maxAttempts -> null
        else -> minOf(capMs, baseMs shl attemptsSoFar)
    }
    /** Số lần báo cho giao diện: 0 = không giới hạn. */
    val reportedMax: Int get() = if (persistent) 0 else maxAttempts

    companion object {
        /** Chính sách dùng cho xe thật: nối lại mãi mãi. */
        fun vehicle() = ReconnectPolicy(maxAttempts = 0, baseMs = 1000, capMs = 4_000, persistent = true, persistentCapMs = 4_000, jitter = 0.2)
    }
}

/** Danh sách characteristic thông tin CHUẨN (Bluetooth SIG) được phép đọc để ghi hồ sơ bằng chứng. Không có serial/ID. */
object StandardInfo {
    private const val SUFFIX = "-0000-1000-8000-00805f9b34fb"
    private val labels = mapOf(
        "2a00" to "Tên thiết bị (Device Name)", "2a24" to "Số hiệu model", "2a26" to "Phiên bản firmware",
        "2a27" to "Phiên bản phần cứng", "2a28" to "Phiên bản phần mềm", "2a29" to "Hãng sản xuất", "2a19" to "Mức pin (%)"
    )
    private fun short(uuid: String): String? {
        val u = uuid.lowercase()
        return if (u.length == 36 && u.startsWith("0000") && u.endsWith(SUFFIX)) u.substring(4, 8) else null
    }
    fun isAllowed(uuid: String) = short(uuid)?.let { it in labels } == true
    fun label(uuid: String): String? = short(uuid)?.let { labels[it] }
    fun decode(uuid: String, v: ByteArray): String =
        if (short(uuid) == "2a19") (if (v.isNotEmpty()) "${v[0].toInt() and 0xFF}%" else "?")
        else String(v, Charsets.UTF_8).filter { it.code >= 0x20 }.trim()
}
