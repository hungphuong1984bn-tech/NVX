package vn.nvxconnect.protocol

import kotlinx.coroutines.flow.Flow
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

    /** Chỉ một phiên GATT hoạt động: gọi lại cho cùng thiết bị thì bỏ qua; khác thiết bị thì đóng phiên cũ trước. */
    fun connect(address: String)
    fun disconnect()
    /** Chỉ đăng ký notify/indicate trên characteristic người dùng chọn và có thuộc tính tương ứng. */
    fun subscribe(charUuid: String): Boolean
    fun unsubscribe(charUuid: String): Boolean
    /** Chỉ đọc được các characteristic thông tin chuẩn trong danh sách cho phép (StandardInfo). */
    fun readStandardInfo(charUuid: String): Boolean
}

/** Lùi thời gian 1s,2s,4s... tối đa 30s; null = bỏ cuộc. */
class ReconnectPolicy(val maxAttempts: Int = 5, private val baseMs: Long = 1000, private val capMs: Long = 30_000) {
    fun nextDelayMs(attemptsSoFar: Int): Long? =
        if (attemptsSoFar >= maxAttempts) null else minOf(capMs, baseMs shl attemptsSoFar)
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
