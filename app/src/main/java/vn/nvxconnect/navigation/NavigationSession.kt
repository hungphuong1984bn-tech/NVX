package vn.nvxconnect.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vn.nvxconnect.location.EngineEvent
import vn.nvxconnect.location.NavSnapshot
import vn.nvxconnect.location.TripEngine

fun interface Speaker { fun speak(text: String) }
fun interface Beeper { fun beep() }

/** Cài đặt phía điều phối, đọc lại mỗi lần dùng để thay đổi trong Cài đặt có hiệu lực ngay. */
data class SessionPrefs(val voice: Boolean, val alertSound: Boolean)

/**
 * Nối TripEngine với giọng nói, tính lại tuyến khi lệch và âm cảnh báo. Sống ở phạm vi ứng dụng nên vẫn chạy khi
 * màn hình tắt. Tính lại tuyến chỉ khi bộ máy định tuyến trả được kết quả; không bịa tuyến khi mất mạng.
 */
class NavigationSession(
    private val scope: CoroutineScope,
    private val engine: TripEngine,
    private val planner: () -> RoutePlanner,
    private val speaker: Speaker,
    private val beeper: Beeper,
    private val prefs: () -> SessionPrefs,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryDelayMs: Long = 20_000,
    private val maxAttempts: Int = 5
) {
    private val _status = MutableStateFlow<String?>(null)
    /** Thông báo trạng thái cho giao diện (đang tính lại, lỗi định tuyến...). */
    val status: StateFlow<String?> = _status.asStateFlow()
    private var destination: LatLon? = null
    private var rerouteJob: Job? = null

    init { scope.launch { engine.events.collect { handle(it) } } }

    fun begin(route: Route) {
        destination = route.destination; _status.value = null
        engine.startNavigation(route)
    }

    fun end() { rerouteJob?.cancel(); rerouteJob = null; destination = null; _status.value = null; engine.stopNavigation() }

    private fun say(t: String) { if (prefs().voice) speaker.speak(t) }

    private fun handle(e: EngineEvent) {
        when (e) {
            is EngineEvent.Announce -> say(e.text)
            EngineEvent.OffRoute -> { say("Bạn đã đi lệch lộ trình, đang tính lại"); startReroute() }
            EngineEvent.BackOnRoute -> { rerouteJob?.cancel(); rerouteJob = null; _status.value = null; say("Đã quay lại lộ trình") }
            EngineEvent.Arrived -> { say("Bạn đã đến nơi"); destination = null; rerouteJob?.cancel(); _status.value = null }
            is EngineEvent.SpeedOver -> if (prefs().alertSound) beeper.beep()
            is EngineEvent.Message, is EngineEvent.TripSaved -> {}
        }
    }

    private fun startReroute() {
        if (rerouteJob?.isActive == true) return
        val dest = destination ?: return
        rerouteJob = scope.launch {
            var attempt = 0
            while (attempt < maxAttempts && engine.nav.value?.phase == NavPhase.OFF_ROUTE) {
                attempt++
                val from = engine.lastFix.value?.let { LatLon(it.lat, it.lon) }
                if (from == null) { delay(retryDelayMs); continue }
                _status.value = "Đang tính lại lộ trình ($attempt/$maxAttempts)…"
                when (val r = planner().route(from, dest)) {
                    is NetResult.Ok -> { engine.startNavigation(r.value); _status.value = null; say("Đã có lộ trình mới"); return@launch }
                    is NetResult.Fail -> {
                        _status.value = "Chưa tính lại được: ${r.message}"
                        if (attempt == 1) say("Chưa tính lại được lộ trình")
                        if (r.kind == NetError.INVALID_REQUEST || r.kind == NetError.BLOCKED) return@launch
                    }
                }
                delay(retryDelayMs)
            }
            if (engine.nav.value?.phase == NavPhase.OFF_ROUTE) _status.value = "Không tính lại được lộ trình. Hãy tự chọn lại điểm đến."
        }
    }

    val snapshot: StateFlow<NavSnapshot?> get() = engine.nav
}
