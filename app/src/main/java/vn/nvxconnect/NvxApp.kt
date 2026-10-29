package vn.nvxconnect

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import vn.nvxconnect.data.AppDatabase
import vn.nvxconnect.data.AppSettings
import vn.nvxconnect.data.RoomTripStore
import vn.nvxconnect.data.SettingsRepository
import vn.nvxconnect.location.TripEngine
import vn.nvxconnect.location.TripRecorder
import vn.nvxconnect.navigation.*

/** Giữ các đối tượng sống theo tiến trình: engine hành trình, phiên dẫn đường, giọng nói. */
class NvxApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val db by lazy { AppDatabase.get(this) }
    val settingsRepo by lazy { SettingsRepository(this) }
    val voice by lazy { VoiceGuide(this) }
    private val http = UrlConnectionFetcher()
    @Volatile var latest: AppSettings = AppSettings(); private set
    /** Điểm cắm giao thức dẫn đường lên TFT. Rỗng: chưa có giao thức được xác minh/cho phép (xem docs/TFT_NAVIGATION.md). */
    val navAdapters by lazy { vn.nvxconnect.protocol.NavAdapterRegistry() }

    /** Registry adapter giao thức xe dùng chung (rỗng tới khi có bằng chứng); dữ liệu đã giải mã đi vào [telemetry]. */
    val adapters by lazy { vn.nvxconnect.protocol.AdapterRegistry(requireProfile = true) }
    val telemetry by lazy { vn.nvxconnect.protocol.TelemetryHub() }
    /** Lớp xác thực đứng trước [telemetry]: kiểm tra khoảng hợp lệ, gắn trạng thái VERIFIED/STALE/..., giữ nhật ký nguồn. */
    val vehicleData by lazy { vn.nvxconnect.vehicledata.VehicleDataRepository(telemetry) }
    /** Tiến độ hướng dẫn, lưu cục bộ (SharedPreferences). Xóa cùng "Xóa toàn bộ dữ liệu". */
    val helpProgress by lazy { vn.nvxconnect.helpcenter.HelpProgressTracker(vn.nvxconnect.data.PrefsHelpProgressStore(this)) }
    /** Giá trị ỨNG VIÊN (giả thuyết) từ kênh YCC; tách khỏi [telemetry], không bao giờ là LIVE_VERIFIED. */
    val yccCandidates by lazy { vn.nvxconnect.protocol.YccCandidateHub() }
    /** Cấu hình giao diện Pro (chủ đề, cockpit, chu kỳ vẽ, giá nhiên liệu...). Đồng bộ để áp dụng ngay khi mở app. */
    val proPrefs by lazy { vn.nvxconnect.data.ProPrefs(this) }
    /**
     * Lịch sử mẫu THẬT cho Telemetry Center. Chỉ nhận giá trị đã có sẵn ở [telemetry] (đã xác minh), [yccCandidates] (GIẢ THUYẾT, khóa "cand:")
     * và tốc độ GPS ("gps:speed"). Không nội suy, không sinh số. Tần suất ghi tách khỏi tần suất vẽ và tần suất log gói.
     */
    val history by lazy { vn.nvxconnect.pro.SeriesStore(maxPerSeries = 3_600, minIntervalMs = 250L) }
    // ---- kết nối xe + thu thập dữ liệu: phạm vi TIẾN TRÌNH (không thuộc Activity/ViewModel) để tiếp tục khi tắt màn hình ----
    /** Phạm vi cho GATT, hàng đợi ghi và dịch vụ nền. Dispatchers.Default: không chiếm luồng chính. */
    val linkScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Đường thu thập gói: hàng đợi có giới hạn → ghi lô xuống tệp bền vững ở filesDir/capture (không có MAC). */
    val capture by lazy { vn.nvxconnect.telemetry.CapturePipeline(java.io.File(filesDir, "capture"), linkScope) }
    /** DUY NHẤT một phiên GATT cho cả ứng dụng (ViewModel chỉ quan sát/điều khiển, không tự tạo phiên). */
    /** Nhật ký sự kiện kết nối bền vững (JSONL, xoay vòng, có bộ đếm mất). Chỉ chứa mã thiết bị ẩn danh, không có MAC. */
    val eventLog by lazy { vn.nvxconnect.telemetry.DurableEventLog(java.io.File(filesDir, "events")) }
    private val eventSalt: String by lazy {
        val sp = getSharedPreferences("nvx_events", MODE_PRIVATE)
        sp.getString("salt", null) ?: java.util.UUID.randomUUID().toString().also { sp.edit().putString("salt", it).apply() }
    }
    val vehicleConnection: vn.nvxconnect.protocol.BleVehicleConnection by lazy {
        vn.nvxconnect.protocol.BleVehicleConnection.create(this, linkScope, capture,
            vn.nvxconnect.telemetry.ConnectionEventRecorder(eventLog, { capture.sessionId }, { vehicleConnection.address?.let { vn.nvxconnect.telemetry.DurableEventLog.deviceKey(it, eventSalt) } }))
    }
    val sessionIntent by lazy { vn.nvxconnect.service.SessionIntentStore(this) }
    val vehicleServiceHost by lazy { vn.nvxconnect.service.VehicleServiceHost(this, vehicleConnection, linkScope, sessionIntent) }
    /** Lần chạy trước kết thúc khi vẫn đang muốn kết nối (tiến trình bị hệ điều hành tắt, hoặc người dùng vuốt tắt app). */
    val previousSessionUnclean: Boolean get() = sessionIntent.previousSessionUnclean
    // ---- Advanced Vehicle Data Logger (CHỈ ĐỌC, lưu cục bộ filesDir/diag, không tải lên máy chủ) ----
    /** Kho nhật ký phiên chẩn đoán: mỗi phiên một tệp, không tự xóa; chỉ người dùng xác nhận mới xóa. */
    val diagStore by lazy { vn.nvxconnect.telemetry.DiagSessionStore(java.io.File(filesDir, "diag")) }
    val diagLogger by lazy { vn.nvxconnect.telemetry.AdvancedLogger(diagStore, AppInfo.VERSION) }
    private val diagGps by lazy {
        tripEngine.lastFix.map { p -> if (p != null && p.accuracyM <= 50f && p.speedMps.isFinite() && p.speedMps >= 0f) vn.nvxconnect.telemetry.GpsSample(p.speedMps * 3.6, p.timeMs, p.accuracyM.toDouble()) else null }
            .stateIn(linkScope, SharingStarted.Eagerly, null)
    }
    private fun diagResolve(): vn.nvxconnect.protocol.VehicleLinkStatus = vn.nvxconnect.protocol.VehicleLinkStatus.resolve(vehicleConnection.state.value, adapters, vehicleConnection.hint.value)
    private fun diagAdapter() = (vehicleConnection.state.value as? vn.nvxconnect.protocol.ConnState.Ready)?.let { adapters.find(it.services) }
    val diagCollector by lazy {
        vn.nvxconnect.telemetry.DiagCollector(diagLogger, vn.nvxconnect.telemetry.DiagInputs(
            connState = vehicleConnection.state, rssi = vehicleConnection.rssi, mtu = vehicleConnection.mtu, hint = vehicleConnection.hint,
            linkHistory = vehicleConnection.linkHistory, capture = capture.stats, gps = diagGps,
            tracked = { now -> vehicleData.snapshot(diagResolve().protocolVerified, diagAdapter()?.supportedParams, now) },
            protocolVerified = { diagResolve().protocolVerified },
            verifiedOnVehicle = { vn.nvxconnect.telemetry.DiagVerification.verifiedOnVehicle(adapters.evidences()) },
            adapterId = { diagAdapter()?.evidence?.id },
            deviceKey = { vehicleConnection.address?.let { vn.nvxconnect.telemetry.DurableEventLog.deviceKey(it, eventSalt) } }))
    }
    val tftPipeline by lazy { vn.nvxconnect.protocol.TftDisplayPipeline { navAdapters.resolve() } }

    val tripEngine by lazy {
        TripEngine(appScope, TripRecorder(this), RoomTripStore(db.dao()), gpsEnabled = { TripRecorder.isGpsEnabled(this) })
    }
    val navSession by lazy {
        NavigationSession(appScope, tripEngine,
            planner = { RoutingClient(latest.routingBaseUrl, http, AppInfo.USER_AGENT) },
            speaker = voice, beeper = Beeper { beep() },
            prefs = { SessionPrefs(latest.voiceGuidance, latest.alertSound) })
    }
    @Volatile private var geocode: Pair<String, GeocodeClient>? = null
    fun geocoder(): GeocodeClient {
        val url = latest.geocodeBaseUrl
        geocode?.let { if (it.first == url) return it.second }
        return GeocodeClient(url, http, AppInfo.USER_AGENT).also { geocode = url to it }
    }
    fun planner(): RoutePlanner = RoutingClient(latest.routingBaseUrl, http, AppInfo.USER_AGENT)

    override fun onCreate() {
        super.onCreate()
        appScope.launch { settingsRepo.settings.collect { latest = it; tripEngine.speedLimitKmh = it.speedLimitKmh } }
        appScope.launch { while (isActive) { delay(1000); tickTft() } }
        navSession // bắt đầu lắng nghe sự kiện ngay từ khi mở app
        sessionIntent; vehicleServiceHost.start()   // đọc cờ "phiên trước bị dừng" TRƯỚC khi ghi đè, rồi theo dõi trạng thái để bật/tắt dịch vụ nền
        startHistoryCollectors()
        // Logger chẩn đoán: khôi phục phiên bị đóng đột ngột (ngoài luồng chính), rồi gắn bộ thu. Bộ thu chỉ lấy mẫu khi đang ghi.
        linkScope.launch(Dispatchers.IO) { runCatching { diagLogger.recoverOnStartup() } }
        diagCollector.attach(linkScope)
        appScope.launch(Dispatchers.IO) { runCatching { RoomTripStore(db.dao()).recoverUnfinished() } }
    }

    /** Ghi lịch sử mẫu thật cho biểu đồ. Chạy trên Dispatchers.Default để không chiếm main thread. */
    private fun startHistoryCollectors() {
        appScope.launch(Dispatchers.Default) {
            telemetry.state.collect { t -> for ((p, v) in t.values) history.add("veh:" + p.name, v.timeMs, v.value) }
        }
        appScope.launch(Dispatchers.Default) {
            yccCandidates.state.collect { m -> for ((id, c) in m) history.add("cand:$id", c.timeMs, c.value) }
        }
        appScope.launch(Dispatchers.Default) {
            tripEngine.lastFix.collect { p -> if (p != null && p.accuracyM <= 50f && p.speedMps.isFinite()) history.add("gps:speed", p.timeMs, p.speedMps * 3.6) }
        }
    }

    /** Mỗi giây: chỉ khi CÓ adapter TFT đã xác minh mới dựng khung và gửi. Hiện không có adapter nên không làm gì. */
    private fun tickTft() {
        if (navAdapters.count == 0) return
        val s = latest; val p = tripEngine.nav.value?.progress; val ns = p?.nextStep
        val frame = vn.nvxconnect.protocol.TftComposer.compose(
            vn.nvxconnect.protocol.TftPrefs(s.tftMapOnly, s.tftSpeed, s.tftRoute, s.tftTrip, s.tftVehicle, s.tftClock), telemetry.state.value,
            maneuver = if (p != null && ns != null) TurnText.instruction(ns) + " · sau " + Fmt.distance(p.distanceToNextM) else null,
            remaining = p?.let { Fmt.distance(it.remainingM) + " · " + Fmt.duration(it.remainingS) },
            clockText = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date()), nowMs = System.currentTimeMillis())
        tftPipeline.push(frame, telemetry.linkVerified)
    }

    private fun beep() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, 80); tg.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
            Handler(Looper.getMainLooper()).postDelayed({ tg.release() }, 600)
        }
    }
}
