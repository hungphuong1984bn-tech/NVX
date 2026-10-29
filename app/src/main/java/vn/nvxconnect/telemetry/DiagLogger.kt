package vn.nvxconnect.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

enum class DiagState { IDLE, RECORDING, PAUSED }

/** Thông tin bắt buộc khi bắt đầu một phiên. [verifiedOnVehicle] = có adapter đã xác minh TRÊN XE THẬT (xem [DiagVerification]). */
data class DiagStartContext(val deviceKey: String?, val verifiedOnVehicle: Boolean, val adapterId: String?)

data class DiagStatus(
    val state: DiagState = DiagState.IDLE, val sessionId: String? = null, val label: String = "", val startedWallMs: Long? = null,
    /** Thời gian ĐÃ GHI (không tính lúc tạm dừng), cập nhật mỗi lần có thao tác/mẫu. */
    val activeMs: Long = 0, val samples: Long = 0, val bleEvents: Long = 0, val notes: Long = 0, val errors: Long = 0, val missingMarks: Long = 0,
    val counters: DiagCounters = DiagCounters(), val health: DiagHealth = DiagHealth.OK, val lastWriteWallMs: Long? = null,
    val intervalMs: Long = 1_000L, val sessionBytes: Long = 0, val ignoredWhilePaused: Long = 0,
    /** Phiên vừa kết thúc gần nhất trong lần chạy app này (để giao diện cuộn tới/xuất ngay). */
    val lastFinishedId: String? = null
)

/** "Đã xác minh trên xe thật" chỉ khi có adapter đăng ký với bằng chứng xe thật + kết quả kiểm thử. Mặc định (không có adapter) là chưa xác minh. */
object DiagVerification {
    const val NOT_VERIFIED_TEXT = "Chưa xác minh trên xe thật"
    const val VERIFIED_TEXT = "Giao thức đã được xác minh trên xe thật (xem hồ sơ bằng chứng)"
    fun verifiedOnVehicle(evidences: List<vn.nvxconnect.protocol.ProtocolEvidence>): Boolean =
        evidences.any { it.verified && !it.testedOnVehicle.isNullOrBlank() && !it.testResult.isNullOrBlank() }
}

/**
 * Bộ ghi phiên chẩn đoán: máy trạng thái IDLE → RECORDING ⇄ PAUSED → IDLE.
 *  - CHỈ nhận dữ liệu đã quan sát (ảnh chụp [DiagSnapshot], sự kiện BLE, lỗi, ghi chú). Không có đường gửi gì xuống xe.
 *  - Mẫu từ xe chỉ được ghi khi là mẫu MỚI (dấu thời gian giải mã khác lần ghi trước): không lặp lại một số cũ để giả làm nhiều mẫu.
 *  - Thiếu dữ liệu không bị lấp: chỉ ghi dấu MISSING khi trạng thái đổi (chưa hỗ trợ / chưa có / quá hạn / không hợp lệ).
 *  - Mọi thời điểm có cả giờ hệ thống (UTC) lẫn đồng hồ đơn điệu để tính thời lượng đúng cả khi người dùng chỉnh giờ.
 */
class AdvancedLogger(
    private val store: DiagSessionStore,
    private val appVersion: String,
    private val wall: () -> Long = System::currentTimeMillis,
    private val mono: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private val _status = MutableStateFlow(DiagStatus())
    val status: StateFlow<DiagStatus> = _status.asStateFlow()
    private val _recovered = MutableStateFlow<List<DiagRecovery>>(emptyList())
    /** Các phiên vừa được khôi phục khi khởi động (giao diện hiển thị rồi gọi [dismissRecovered]). */
    val recovered: StateFlow<List<DiagRecovery>> = _recovered.asStateFlow()

    private var writer: DiagWriter? = null
    private var segStartMono = 0L; private var activeBase = 0L
    private var lastSampleMono = Long.MIN_VALUE
    private val lastLoggedStamp = HashMap<String, Long>()
    private val lastLogged = HashMap<String, Pair<Double, Long>>()
    private val lastClass = HashMap<String, DiagClass>()
    private var samples = 0L; private var bleEvents = 0L; private var notes = 0L; private var errors = 0L; private var missing = 0L; private var pausedIgnored = 0L

    val state: DiagState get() = _status.value.state
    val activeSessionId: String? @Synchronized get() = if (writer != null) _status.value.sessionId else null

    // ---------------------------------------------------------------------------------------- vòng đời phiên
    /** Bắt đầu phiên mới. false nếu đang có phiên mở hoặc không tạo được tệp (lý do ở [DiagStatus] không đổi; gọi lại sau khi giải phóng bộ nhớ). */
    @Synchronized fun start(label: String, intervalMs: Long, ctx: DiagStartContext): Boolean {
        if (_status.value.state != DiagState.IDLE) return false
        val interval = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        val now = wall()
        val w = try { store.create(DiagMeta(newId(now), now, clean(label, 80), appVersion, ctx.deviceKey, interval, ctx.verifiedOnVehicle, ctx.adapterId)) } catch (_: Exception) { return false }
        writer = w; resetCounters(); lastSampleMono = Long.MIN_VALUE
        segStartMono = mono(); activeBase = 0
        _status.value = DiagStatus(DiagState.RECORDING, w.meta.id, w.meta.label, now, intervalMs = interval, lastFinishedId = _status.value.lastFinishedId)
        control(DiagControl.START, w.meta.label, mapOf("interval_ms" to interval.toString(), "verified_on_vehicle" to ctx.verifiedOnVehicle.toString(), "app" to appVersion))
        return true
    }

    @Synchronized fun pause(): Boolean {
        val s = _status.value; if (s.state != DiagState.RECORDING || writer == null) return false
        activeBase += mono() - segStartMono
        control(DiagControl.PAUSE, null, counterAttrs()); writer?.flush()
        _status.value = _status.value.copy(state = DiagState.PAUSED, activeMs = activeBase)
        return true
    }

    @Synchronized fun resume(): Boolean {
        val s = _status.value; if (s.state != DiagState.PAUSED || writer == null) return false
        segStartMono = mono(); lastSampleMono = Long.MIN_VALUE
        lastLogged.clear()   // mẫu đầu tiên sau khi tiếp tục luôn được ghi, không bị coi là "không đổi"
        control(DiagControl.RESUME, null, mapOf("paused_ignored" to pausedIgnored.toString()))
        _status.value = _status.value.copy(state = DiagState.RECORDING)
        return true
    }

    @Synchronized fun stop(reason: String? = null): Boolean {
        val s = _status.value; val w = writer ?: return false
        if (s.state == DiagState.IDLE) return false
        if (s.state == DiagState.RECORDING) activeBase += mono() - segStartMono
        control(DiagControl.STOP, reason, counterAttrs() + ("active_ms" to activeBase.toString()))
        w.close(); writer = null
        _status.value = DiagStatus(lastFinishedId = s.sessionId, health = DiagHealth.OK, intervalMs = s.intervalMs)
        return true
    }

    /** Người dùng chọn Tiếp tục một phiên đã bị gián đoạn: mở lại tệp để ghi tiếp, đánh dấu CONTINUE (khoảng giữa KHÔNG được lấp). */
    @Synchronized fun continueInterrupted(id: String, ctx: DiagStartContext): Boolean {
        if (_status.value.state != DiagState.IDLE) return false
        val info = store.list().firstOrNull { it.id == id } ?: return false
        if (info.state != DiagSessionState.INTERRUPTED) return false
        val w = store.reopen(id) ?: return false
        writer = w; resetCounters(); lastSampleMono = Long.MIN_VALUE
        segStartMono = mono(); activeBase = 0
        val now = wall()
        _status.value = DiagStatus(DiagState.RECORDING, id, w.meta.label, w.meta.startedWallMs, intervalMs = w.meta.sampleIntervalMs, lastFinishedId = _status.value.lastFinishedId)
        control(DiagControl.CONTINUE, "Tiếp tục sau khi bị gián đoạn. Khoảng thời gian không ghi KHÔNG được lấp.",
            mapOf("gap_since_wall_ms" to (info.lastRecordWallMs?.let { (now - it).toString() } ?: ""), "verified_on_vehicle" to ctx.verifiedOnVehicle.toString()))
        return true
    }

    /** Khôi phục các phiên bị đóng đột ngột (gọi một lần lúc khởi động, trên luồng nền). */
    @Synchronized fun recoverOnStartup(): List<DiagRecovery> {
        val r = store.recoverInterrupted(skipIds = setOfNotNull(_status.value.sessionId.takeIf { writer != null }))
        if (r.isNotEmpty()) _recovered.value = _recovered.value + r
        return r
    }
    fun dismissRecovered() { _recovered.value = emptyList() }

    // ---------------------------------------------------------------------------------------- ghi dữ liệu
    /** Ghi chú sự cố do người dùng nhập. Cho phép cả khi đang tạm dừng. Văn bản được làm sạch ký tự điều khiển và cắt ở 1000 ký tự. */
    @Synchronized fun note(text: String): Boolean {
        if (_status.value.state == DiagState.IDLE) return false
        val t = clean(text, MAX_NOTE); if (t.isBlank()) return false
        return write(DiagKind.NOTE, "note", text = t)
    }

    /**
     * Ảnh chụp theo chu kỳ. Bỏ qua nếu chưa tới [DiagStatus.intervalMs] kể từ lần ghi trước (tiết kiệm pin/dung lượng).
     * Trả số mẫu đã ghi lần này.
     */
    @Synchronized fun onSnapshot(snap: DiagSnapshot): Int {
        val st = _status.value
        if (st.state == DiagState.PAUSED) { pausedIgnored++; return 0 }
        if (st.state != DiagState.RECORDING || writer == null) return 0
        val nowMono = mono()
        if (lastSampleMono != Long.MIN_VALUE && nowMono - lastSampleMono < st.intervalMs) return 0
        lastSampleMono = nowMono
        var n = 0
        for (r in snap.rows) {
            if (r.usable) {
                if (shouldLog(r, nowMono, st.intervalMs)) {
                    val wallMs = r.updatedWallMs ?: snap.wallMs
                    val monoMs = nowMono - (if (r.updatedWallMs != null) (snap.wallMs - r.updatedWallMs).coerceAtLeast(0) else 0L)
                    val attrs = HashMap<String, String>(); r.ageMs?.let { attrs["age_ms"] = it.toString() }
                    if (write(DiagKind.SAMPLE, r.id, r.value, r.unit.ifBlank { null }, r.cls, r.source, null, attrs, wallMs, monoMs)) { n++; lastLogged[r.id] = r.value!! to nowMono; r.updatedWallMs?.let { lastLoggedStamp[r.id] = it } }
                }
            } else if (lastClass[r.id] != r.cls) {
                val attrs = HashMap<String, String>(); r.ageMs?.let { attrs["last_sample_age_ms"] = it.toString() }
                if (write(DiagKind.MISSING, r.id, null, r.unit.ifBlank { null }, r.cls, null, r.note, attrs, snap.wallMs, nowMono)) missing++
            }
            lastClass[r.id] = r.cls
        }
        refresh()
        return n
    }

    /** Sự kiện BLE/liên kết (kết nối, mất kết nối, thử nối lại...). Không ghi khi đang tạm dừng (có đếm). */
    @Synchronized fun onBleEvent(name: String, text: String? = null, attrs: Map<String, String> = emptyMap(), wallMs: Long = wall()): Boolean {
        if (!gate()) return false
        return write(DiagKind.BLE_EVENT, name, text = text?.let { clean(it, 300) }, attrs = attrs, wallMs = wallMs)
    }

    @Synchronized fun onError(name: String, text: String?, attrs: Map<String, String> = emptyMap(), wallMs: Long = wall()): Boolean {
        if (!gate()) return false
        return write(DiagKind.ERROR, name, text = text?.let { clean(it, 300) }, attrs = attrs, wallMs = wallMs)
    }

    /** Một phép đo đơn lẻ có đơn vị (ví dụ thời gian kết nối → sẵn sàng). */
    @Synchronized fun onMeasure(name: String, value: Double, unit: String, source: DiagSource, cls: DiagClass = DiagClass.REAL, attrs: Map<String, String> = emptyMap(), wallMs: Long = wall()): Boolean {
        if (!gate() || !value.isFinite()) return false
        return write(DiagKind.SAMPLE, name, value, unit, cls, source, null, attrs, wallMs)
    }

    // ---------------------------------------------------------------------------------------- nội bộ
    private fun gate(): Boolean {
        val s = _status.value.state
        if (s == DiagState.PAUSED) { pausedIgnored++; return false }
        return s == DiagState.RECORDING && writer != null
    }

    private fun shouldLog(r: DiagRow, nowMono: Long, interval: Long): Boolean {
        val v = r.value ?: return false
        if (r.updatedWallMs != null) return lastLoggedStamp[r.id] != r.updatedWallMs      // mẫu thật mới (dấu thời gian giải mã khác)
        if (r.id == DiagIds.BLE_RATE || r.id == DiagIds.BLE_SILENCE) return true         // đại lượng liên tục theo chu kỳ
        val prev = lastLogged[r.id] ?: return true
        val heartbeat = maxOf(HEARTBEAT_MS, interval * 5)
        return prev.first != v || nowMono - prev.second >= heartbeat                        // đếm/đo ít đổi: chỉ khi đổi hoặc nhịp tim
    }

    private fun control(name: String, text: String?, attrs: Map<String, String>) { write(DiagKind.SESSION, name, text = text, attrs = attrs) }

    private fun write(kind: DiagKind, name: String, value: Double? = null, unit: String? = null, cls: DiagClass? = null, source: DiagSource? = null,
                      text: String? = null, attrs: Map<String, String> = emptyMap(), wallMs: Long = wall(), monoMs: Long? = null): Boolean {
        val w = writer ?: return false
        // Sự kiện đến muộn (giờ thật trong quá khứ): đồng hồ đơn điệu lùi tương ứng để hai đồng hồ luôn nhất quán.
        val monoMs = monoMs ?: (mono() - (wall() - wallMs).coerceAtLeast(0))
        val res = w.append(DiagRecord(0, wallMs, monoMs, kind, name, value, unit, cls, source, text, attrs))
        if (res == DiagAppend.OK) when (kind) {
            DiagKind.SAMPLE -> samples++; DiagKind.BLE_EVENT -> bleEvents++; DiagKind.NOTE -> notes++; DiagKind.ERROR -> errors++; else -> {}
        }
        refresh(if (res == DiagAppend.OK) wallMs else null)
        return res == DiagAppend.OK
    }

    private fun refresh(lastWall: Long? = null) {
        val w = writer ?: return
        val st = _status.value
        val active = if (st.state == DiagState.RECORDING) activeBase + (mono() - segStartMono) else activeBase
        _status.value = st.copy(activeMs = active, samples = samples, bleEvents = bleEvents, notes = notes, errors = errors, missingMarks = missing,
            counters = w.counters, health = w.health, lastWriteWallMs = lastWall ?: st.lastWriteWallMs, sessionBytes = w.bytes, ignoredWhilePaused = pausedIgnored)
    }

    private fun counterAttrs(): Map<String, String> {
        val c = writer?.counters ?: DiagCounters()
        return mapOf("samples" to samples.toString(), "ble_events" to bleEvents.toString(), "errors" to errors.toString(), "missing_marks" to missing.toString(),
            "dropped_limit" to c.droppedLimit.toString(), "dropped_low_storage" to c.droppedLowStorage.toString(), "dropped_error" to c.droppedError.toString(),
            "paused_ignored" to pausedIgnored.toString())
    }

    private fun resetCounters() {
        samples = 0; bleEvents = 0; notes = 0; errors = 0; missing = 0; pausedIgnored = 0
        lastLoggedStamp.clear(); lastLogged.clear(); lastClass.clear()
    }

    private fun newId(now: Long): String {
        val base = "d" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(now))
        var id = base; var n = 1
        while (store.fileFor(id).exists()) { n++; id = "$base-$n" }
        return id
    }

    companion object {
        const val MIN_INTERVAL_MS = 250L; const val MAX_INTERVAL_MS = 60_000L
        const val HEARTBEAT_MS = 10_000L; const val MAX_NOTE = 1_000
        /** Bỏ ký tự điều khiển (trừ xuống dòng/tab → khoảng trắng), cắt độ dài. */
        fun clean(s: String, max: Int): String = s.map { if (it == '\n' || it == '\r' || it == '\t') ' ' else if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(max)
    }
}
