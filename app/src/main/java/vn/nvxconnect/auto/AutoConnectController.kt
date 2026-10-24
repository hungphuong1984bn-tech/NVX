package vn.nvxconnect.auto

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import vn.nvxconnect.bluetooth.AdvertRecord
import vn.nvxconnect.bluetooth.BleException
import vn.nvxconnect.bluetooth.ErrorKind
import vn.nvxconnect.bluetooth.LogKind
import vn.nvxconnect.bluetooth.SavedDevice
import vn.nvxconnect.protocol.ConnState
import vn.nvxconnect.protocol.VehicleConnection

/** Nguồn quảng bá: bọc BleScanner để test được. Flow tự kết thúc sau timeoutMs; lỗi báo bằng BleException. */
fun interface AdvertSource { fun scan(timeoutMs: Long): Flow<AdvertRecord> }

data class AutoConnectConfig(
    val maxCycles: Int = 3,
    val scanWindowMs: Long = 12_000,
    val backoffBaseMs: Long = 3_000,
    val backoffCapMs: Long = 30_000,
    /** Chờ tối đa bấy nhiêu cho một lần kết nối (GattSessionController còn tự thử lại bên trong). */
    val connectGiveUpMs: Long = 90_000,
    /** Tín hiệu đủ mạnh để coi việc kết nối thất bại lặp lại là bất thường (không phải do quá xa). */
    val strongRssi: Int = -80,
    val conflictAfterFailures: Int = 2,
    val conflictAfterNotConnectable: Int = 2
) {
    fun backoff(cycle: Int): Long = minOf(backoffCapMs, backoffBaseMs shl (cycle - 1).coerceIn(0, 20))
}

enum class RunPhase { IDLE, SCANNING, CANDIDATE_FOUND, CONNECTING, CONNECTED, FAILED }

data class AutoRun(
    val phase: RunPhase = RunPhase.IDLE,
    val candidate: AdvertRecord? = null,
    val cycle: Int = 0,
    val failure: AutoFailure? = null,
    val message: String? = null
)

/**
 * Tự tìm và kết nối lại tới HỒ SƠ XE ĐÃ ĐƯỢC NGƯỜI DÙNG LƯU. Không bao giờ tự chọn thiết bị lạ.
 *
 *  - Một lượt chạy tại một thời điểm; quét xong (flow kết thúc) mới kết nối, không quét chồng lên kết nối.
 *  - Nhận diện bằng nhiều tín hiệu (ProfileMatcher): địa chỉ, tên, service, mã hãng; chịu được địa chỉ xoay vòng.
 *  - Tối đa maxCycles chu kỳ quét-kết nối, lùi thời gian giữa các chu kỳ.
 *  - Lỗi xác thực / thiếu quyền / Bluetooth tắt: dừng ngay, không thử lại.
 *  - Nghi ứng dụng khác (Y-Connect) giữ kết nối: dừng hẳn và báo, không liên tục chiếm kết nối.
 *    Dấu hiệu: thiết bị quảng bá "không kết nối được" lặp lại, hoặc kết nối thất bại lặp lại dù tín hiệu mạnh.
 *  - Không ghi gì vào xe: chỉ gọi conn.connect/disconnect.
 */
class AutoConnectController(
    private val scope: CoroutineScope,
    private val conn: VehicleConnection,
    private val source: AdvertSource,
    private val config: AutoConnectConfig = AutoConnectConfig(),
    private val log: (LogKind, String) -> Unit = { _, _ -> }
) {
    private val _run = MutableStateFlow(AutoRun())
    val run: StateFlow<AutoRun> = _run.asStateFlow()
    private var job: Job? = null
    private val lock = Any()

    val isRunning get() = job?.isActive == true

    /**
     * Bắt đầu. false nếu đang chạy, đang có phiên GATT, hoặc đang không được phép (cài đặt tắt tự kết nối).
     * allowed được hỏi lại trước mỗi chu kỳ để việc tắt cài đặt có hiệu lực ngay.
     */
    fun start(profile: SavedDevice, allowed: () -> Boolean): Boolean = synchronized(lock) {
        if (isRunning) return false
        if (!allowed()) { _run.value = AutoRun(message = "Tự kết nối đang TẮT trong cài đặt"); return false }
        if (conn.state.value.isActive) { _run.value = AutoRun(message = "Đã có phiên GATT, không tự kết nối thêm"); return false }
        _run.value = AutoRun(RunPhase.SCANNING, null, 1, null, "Đang tìm ${profile.title}")
        job = scope.launch { runLoop(profile, allowed) }
        true
    }

    /** Dừng vòng tự kết nối nhưng không đụng tới phiên GATT hiện có. */
    fun cancel() = synchronized(lock) {
        job?.cancel(); job = null
        if (_run.value.phase != RunPhase.CONNECTED) _run.value = AutoRun(message = "Đã dừng tự kết nối")
    }

    /** Dừng khẩn cấp: hủy vòng tự kết nối VÀ đóng phiên GATT. */
    fun emergencyStop() {
        cancel()
        conn.disconnect()
        _run.value = AutoRun(message = "Đã dừng khẩn cấp: quét và GATT đã đóng")
    }

    private fun fail(f: AutoFailure, msg: String, cand: AdvertRecord? = null, cycle: Int = _run.value.cycle) {
        log(LogKind.ERROR, "Tự kết nối thất bại: ${f.title} — $msg")
        _run.value = AutoRun(RunPhase.FAILED, cand, cycle, f, msg)
    }

    private suspend fun runLoop(profile: SavedDevice, allowed: () -> Boolean) {
        var failures = 0
        var notConnectable = 0
        try {
            for (cycle in 1..config.maxCycles) {
                if (!allowed()) { _run.value = AutoRun(message = "Tự kết nối bị tắt, đã dừng"); return }
                _run.value = AutoRun(RunPhase.SCANNING, null, cycle, null, "Đang tìm ${profile.title} (chu kỳ $cycle/${config.maxCycles})")
                val hit = try {
                    withTimeoutOrNull(config.scanWindowMs + 2_000) {
                        source.scan(config.scanWindowMs).firstOrNull { ProfileMatcher.match(profile, it).matched }
                    }
                } catch (e: BleException) {
                    when (e.kind) {
                        ErrorKind.PERMISSION_DENIED -> fail(AutoFailure.PERMISSION, e.message ?: e.kind.title)
                        ErrorKind.BLUETOOTH_OFF, ErrorKind.BLUETOOTH_UNSUPPORTED, ErrorKind.LOCATION_SERVICES_OFF -> fail(AutoFailure.BLUETOOTH_OFF, e.message ?: e.kind.title)
                        else -> fail(AutoFailure.GATT_ERROR, e.message ?: e.kind.title)
                    }
                    return
                }
                if (hit == null) {
                    log(LogKind.INFO, "Tự kết nối: chu kỳ $cycle không thấy thiết bị")
                    if (cycle == config.maxCycles) { fail(AutoFailure.NOT_FOUND, "Không thấy thiết bị sau ${config.maxCycles} chu kỳ quét. Bật khóa điện và đứng gần xe."); return }
                    delay(config.backoff(cycle)); continue
                }
                if (hit.connectable == false) {
                    notConnectable++
                    log(LogKind.INFO, "Tự kết nối: thiết bị quảng bá không cho kết nối ($notConnectable)")
                    if (notConnectable >= config.conflictAfterNotConnectable) {
                        fail(AutoFailure.EXCLUSIVE_HOLDER_SUSPECTED,
                            "Thiết bị liên tục quảng bá ở chế độ không cho kết nối: thường do Y-Connect hoặc ứng dụng khác đang giữ kết nối. " +
                                "Hãy đóng Y-Connect (và tắt Bluetooth của nó) hoặc dùng phương thức tích hợp chính thức. App sẽ không chiếm kết nối.", hit, cycle)
                        return
                    }
                    if (cycle == config.maxCycles) { fail(AutoFailure.RETRIES_EXHAUSTED, "Thiết bị chưa cho kết nối", hit, cycle); return }
                    delay(config.backoff(cycle)); continue
                }

                _run.value = AutoRun(RunPhase.CANDIDATE_FOUND, hit, cycle, null, "Đã thấy thiết bị khớp hồ sơ, đang kết nối")
                conn.connect(hit.address)
                _run.value = AutoRun(RunPhase.CONNECTING, hit, cycle, null, "Đang kết nối GATT")
                val outcome = withTimeoutOrNull(config.connectGiveUpMs) {
                    conn.state.first { it is ConnState.Ready || it is ConnState.Error || it is ConnState.Disconnected }
                }
                when (outcome) {
                    is ConnState.Ready -> { _run.value = AutoRun(RunPhase.CONNECTED, hit, cycle, null, "Đã nối GATT (chưa xác nhận giao thức xe)"); return }
                    is ConnState.Disconnected -> { _run.value = AutoRun(message = "Phiên GATT đã bị ngắt, dừng tự kết nối"); return }
                    is ConnState.Error -> when (outcome.kind) {
                        ErrorKind.AUTH_REQUIRED -> { fail(AutoFailure.AUTH_REQUIRED, outcome.reason, hit, cycle); return }
                        ErrorKind.PERMISSION_DENIED -> { fail(AutoFailure.PERMISSION, outcome.reason, hit, cycle); return }
                        ErrorKind.BLUETOOTH_OFF, ErrorKind.BLUETOOTH_UNSUPPORTED -> { fail(AutoFailure.BLUETOOTH_OFF, outcome.reason, hit, cycle); return }
                        ErrorKind.INVALID_ADDRESS -> { fail(AutoFailure.GATT_ERROR, outcome.reason, hit, cycle); return }
                        else -> failures++
                    }
                    else -> { conn.disconnect(); failures++ }   // quá thời gian chờ tổng
                }
                if (failures >= config.conflictAfterFailures && hit.rssi >= config.strongRssi) {
                    conn.disconnect()
                    fail(AutoFailure.EXCLUSIVE_HOLDER_SUSPECTED,
                        "Kết nối thất bại $failures lần dù tín hiệu mạnh (${hit.rssi} dBm): có thể Y-Connect hoặc ứng dụng khác đang giữ kết nối. " +
                            "Hãy đóng Y-Connect rồi bấm thử lại. App dừng tự kết nối để không chiếm kết nối.", hit, cycle)
                    return
                }
                if (cycle == config.maxCycles) { fail(AutoFailure.RETRIES_EXHAUSTED, "Đã thử $cycle lần không được", hit, cycle); return }
                log(LogKind.INFO, "Tự kết nối: thử lại sau ${config.backoff(cycle)} ms")
                delay(config.backoff(cycle))
            }
        } catch (e: CancellationException) {
            throw e
        }
    }
}
