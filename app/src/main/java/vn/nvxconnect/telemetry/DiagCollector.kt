package vn.nvxconnect.telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import vn.nvxconnect.protocol.ConnState
import vn.nvxconnect.protocol.LinkEvent
import vn.nvxconnect.protocol.LinkEventKind
import vn.nvxconnect.protocol.LinkHint
import vn.nvxconnect.vehicledata.TrackedValue

/**
 * Nguồn dữ liệu cho bộ ghi — TOÀN BỘ là luồng/hàm CHỈ ĐỌC trạng thái đã có (kết nối, thống kê thu thập, kho dữ liệu xe đã xác thực, GPS).
 * Không có thành viên nào gửi lệnh tới xe.
 */
class DiagInputs(
    val connState: StateFlow<ConnState>, val rssi: StateFlow<Int?>, val mtu: StateFlow<Int>, val hint: StateFlow<LinkHint>,
    val linkHistory: StateFlow<List<LinkEvent>>, val capture: StateFlow<CaptureStats>, val gps: StateFlow<GpsSample?>,
    val tracked: (nowMs: Long) -> List<TrackedValue>,
    val protocolVerified: () -> Boolean, val verifiedOnVehicle: () -> Boolean, val adapterId: () -> String?, val deviceKey: () -> String?
)

/**
 * Nối nguồn dữ liệu với [AdvancedLogger]:
 *  - Vòng lấy mẫu CHỈ chạy khi đang ghi (RECORDING): không ghi/không thức giấc khi rảnh → tiết kiệm pin.
 *  - Sự kiện BLE (đổi trạng thái, mất liên kết, nối lại, lỗi) ghi ngay khi xảy ra, kèm giờ thật của sự kiện.
 *  - Đo thời gian phản hồi từ mốc thật: bắt đầu nối → Ready; Ready → gói đầu tiên. Không có mốc thì không ghi số.
 * Lớp này không phụ thuộc Android nên kiểm thử được bằng luồng giả.
 */
class DiagCollector(
    private val logger: AdvancedLogger, private val inp: DiagInputs,
    private val wall: () -> Long = System::currentTimeMillis
) {
    val gpsDistance = GpsDistanceAccumulator()
    @Volatile private var baseReceived = 0L
    @Volatile private var baseLost = 0L

    // đo phản hồi
    private var connectStartWall: Long? = null
    private var readyWall: Long? = null
    private var awaitingFirstFrame = false
    private var lastStateKey: String? = null
    private var lastLinkEventKey: String? = null

    /** Ảnh chụp hiện tại — dùng chung cho màn hình trực tiếp và bộ ghi (một nguồn sự thật duy nhất). */
    fun snapshot(nowMs: Long = wall()): DiagSnapshot {
        val st = inp.connState.value; val cap = inp.capture.value
        val ready = st is ConnState.Ready
        val link = DiagLink(
            stateCode = st.code, stateText = stateText(st), ready = ready, rssi = inp.rssi.value, mtu = inp.mtu.value,
            framesTotal = (cap.received - baseReceived).coerceAtLeast(0), framesLost = (cap.lostByApp - baseLost).coerceAtLeast(0),
            ratePerSec = cap.ratePerSec.takeIf { ready }, lastFrameWallMs = cap.lastFrameWallMs, maxGapMs = cap.maxGapMs.takeIf { cap.received > 1 },
            protocolVerified = inp.protocolVerified(), verifiedOnVehicle = inp.verifiedOnVehicle(), adapterId = inp.adapterId(), hint = inp.hint.value.name)
        return DiagSnapshotBuilder.build(nowMs, inp.tracked(nowMs), inp.gps.value, gpsDistance.takeIf { it.samples >= 2 }?.meters, link)
    }

    fun startContext() = DiagStartContext(inp.deviceKey(), inp.verifiedOnVehicle(), inp.adapterId())

    /** Gắn các bộ thu vào [scope] (phạm vi tiến trình). Trả công việc cha; hủy nó sẽ dừng mọi bộ thu. */
    fun attach(scope: CoroutineScope): Job = scope.launch {
        lastLinkEventKey = inp.linkHistory.value.lastOrNull()?.let(::keyOf)   // không phát lại lịch sử cũ vào phiên mới
        launch { watchSessions() }
        launch { watchConnection() }
        launch { watchLinkHistory() }
        launch { watchFirstFrame() }
        launch { watchGps() }
    }

    private suspend fun watchSessions() {
        var prevSession: String? = null
        logger.status.map { Triple(it.state, it.sessionId, it.intervalMs) }.distinctUntilChanged().collectLatest { (state, sid, interval) ->
            if (state != DiagState.RECORDING) return@collectLatest
            if (sid != prevSession) {                      // phiên mới (hoặc phiên tiếp tục): đặt mốc đếm và ghi trạng thái ban đầu
                prevSession = sid
                val cap = inp.capture.value; baseReceived = cap.received; baseLost = cap.lostByApp; gpsDistance.reset()
                inp.gps.value?.let { gpsDistance.add(it) }
                val st = inp.connState.value
                logger.onBleEvent("STATE_AT_START", stateText(st), mapOf("code" to st.code, "protocol_verified" to inp.protocolVerified().toString()))
            }
            while (true) {                                  // lấy mẫu ngay, rồi mỗi chu kỳ
                logger.onSnapshot(snapshot(wall()))
                delay(interval)
            }
        }
    }

    private suspend fun watchGps() {
        inp.gps.collect { g -> if (g != null && logger.state == DiagState.RECORDING) gpsDistance.add(g) }
    }

    private suspend fun watchConnection() {
        inp.connState.map { it.code + (if (it is ConnState.Reconnecting) ":${it.attempt}" else "") + (if (it is ConnState.Error) ":${it.reason}" else "") }
            .distinctUntilChanged().collect { key ->
                val st = inp.connState.value; val now = wall()
                val first = lastStateKey == null; lastStateKey = key
                when (st) {
                    is ConnState.Connecting, is ConnState.Reconnecting -> { if (connectStartWall == null) connectStartWall = now; readyWall = null; awaitingFirstFrame = false }
                    is ConnState.Ready -> {
                        readyWall = now; awaitingFirstFrame = true
                        connectStartWall?.let { logger.onMeasure(DiagIds.BLE_CONNECT_MS, (now - it).toDouble(), "ms", DiagSource.PHONE_BLE, attrs = mapOf("note" to "từ lúc bắt đầu nối tới GATT sẵn sàng"), wallMs = now) }
                        connectStartWall = null
                    }
                    is ConnState.Disconnected, is ConnState.Error -> { connectStartWall = null; readyWall = null; awaitingFirstFrame = false }
                    else -> {}
                }
                if (first && !(logger.state == DiagState.RECORDING)) return@collect
                val attrs = HashMap<String, String>(); attrs["code"] = st.code
                if (st is ConnState.Reconnecting) { attrs["attempt"] = st.attempt.toString(); attrs["max_attempts"] = st.maxAttempts.toString() }
                if (st is ConnState.Error) { attrs["kind"] = st.kind.name }
                logger.onBleEvent("STATE_" + st.code, stateText(st), attrs, now)
                if (st is ConnState.Error) logger.onError("BLE_" + st.kind.name, st.reason, wallMs = now)
            }
    }

    private suspend fun watchFirstFrame() {
        inp.capture.collect { c ->
            val rw = readyWall; val lf = c.lastFrameWallMs
            if (awaitingFirstFrame && rw != null && lf != null && lf >= rw) {
                awaitingFirstFrame = false
                logger.onMeasure(DiagIds.BLE_FIRST_FRAME_MS, (lf - rw).toDouble(), "ms", DiagSource.PHONE_BLE, attrs = mapOf("note" to "từ lúc GATT sẵn sàng tới gói đầu tiên"), wallMs = lf)
            }
        }
    }

    private suspend fun watchLinkHistory() {
        inp.linkHistory.collect { list ->
            val lastKey = lastLinkEventKey
            val idx = if (lastKey == null) -1 else list.indexOfLast { keyOf(it) == lastKey }
            val fresh = if (lastKey == null) list else if (idx >= 0) list.drop(idx + 1) else list   // lịch sử bị cắt đầu: coi toàn bộ là mới
            for (e in fresh) {
                val attrs = HashMap<String, String>()
                attrs["epoch"] = e.epoch.toString(); e.status?.let { attrs["gatt_status"] = it.toString() }; e.upMs?.let { attrs["up_ms"] = it.toString() }
                if (e.attempt > 0) attrs["attempt"] = e.attempt.toString(); attrs["frames_in_session"] = e.framesInSession.toString()
                logger.onBleEvent(e.kind.name, e.reason, attrs, e.wallMs)
                if (e.kind == LinkEventKind.ATTEMPT_FAILED || e.kind == LinkEventKind.AUTH_REQUIRED || e.kind == LinkEventKind.BLUETOOTH_OFF)
                    logger.onError("LINK_" + e.kind.name, e.reason, attrs, e.wallMs)
            }
            list.lastOrNull()?.let { lastLinkEventKey = keyOf(it) }
        }
    }

    private fun keyOf(e: LinkEvent) = "${e.wallMs}/${e.epoch}/${e.kind.name}/${e.attempt}"

    companion object {
        fun stateText(s: ConnState): String = when (s) {
            is ConnState.Disconnected -> "Chưa kết nối"; is ConnState.Connecting -> "Đang kết nối"; is ConnState.Connected -> "Đã nối GATT, chưa khám phá dịch vụ"
            is ConnState.DiscoveringServices -> "Đang khám phá dịch vụ"; is ConnState.Ready -> "Sẵn sàng (GATT)"
            is ConnState.Reconnecting -> if (s.maxAttempts == 0) "Đang nối lại (lần ${s.attempt})" else "Đang nối lại ${s.attempt}/${s.maxAttempts}"
            is ConnState.Error -> "Lỗi: ${s.reason}"
        }
    }
}
