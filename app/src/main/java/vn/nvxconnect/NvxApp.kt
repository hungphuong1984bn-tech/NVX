package vn.nvxconnect

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
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
    val adapters by lazy { vn.nvxconnect.protocol.AdapterRegistry() }
    val telemetry by lazy { vn.nvxconnect.protocol.TelemetryHub() }
    /** Lớp xác thực đứng trước [telemetry]: kiểm tra khoảng hợp lệ, gắn trạng thái VERIFIED/STALE/..., giữ nhật ký nguồn. */
    val vehicleData by lazy { vn.nvxconnect.vehicledata.VehicleDataRepository(telemetry) }
    /** Tiến độ hướng dẫn, lưu cục bộ (SharedPreferences). Xóa cùng "Xóa toàn bộ dữ liệu". */
    val helpProgress by lazy { vn.nvxconnect.helpcenter.HelpProgressTracker(vn.nvxconnect.data.PrefsHelpProgressStore(this)) }
    /** Giá trị ỨNG VIÊN (giả thuyết) từ kênh YCC; tách khỏi [telemetry], không bao giờ là LIVE_VERIFIED. */
    val yccCandidates by lazy { vn.nvxconnect.protocol.YccCandidateHub() }
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
        appScope.launch(Dispatchers.IO) { runCatching { RoomTripStore(db.dao()).recoverUnfinished() } }
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
