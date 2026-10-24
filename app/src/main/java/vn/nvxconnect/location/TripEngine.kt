package vn.nvxconnect.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vn.nvxconnect.domain.GeoPoint
import vn.nvxconnect.domain.SpeedAlert
import vn.nvxconnect.domain.TripStats
import vn.nvxconnect.domain.TripStatsResult
import vn.nvxconnect.navigation.*

enum class TripPhase { IDLE, RECORDING, PAUSED }
/** INACTIVE = app không nghe GPS. OFF = GPS hệ thống đang tắt. */
enum class GpsStatus(val label: String) {
    INACTIVE("Chưa nghe GPS"), OFF("GPS đang tắt"), SEARCHING("Đang tìm tín hiệu GPS"), WEAK("Tín hiệu GPS yếu"), GOOD("GPS tốt")
}

object GpsStatusRules {
    fun evaluate(gpsEnabled: Boolean, fixAgeMs: Long?, accuracyM: Float?): GpsStatus = when {
        !gpsEnabled -> GpsStatus.OFF
        fixAgeMs == null || fixAgeMs > 15_000 -> GpsStatus.SEARCHING
        fixAgeMs > 8_000 || (accuracyM ?: Float.MAX_VALUE) > 30f -> GpsStatus.WEAK
        else -> GpsStatus.GOOD
    }
}

interface LocationSource { fun updates(): Flow<GeoPoint> }

interface TripStore {
    suspend fun begin(startMs: Long): Long
    suspend fun append(tripId: Long, points: List<GeoPoint>)
    suspend fun finish(tripId: Long, startMs: Long, endMs: Long, stats: TripStatsResult, endLat: Double?, endLon: Double?)
    suspend fun discard(tripId: Long)
}

sealed interface EngineEvent {
    data class Message(val text: String) : EngineEvent
    data class SpeedOver(val kmh: Double, val limitKmh: Int) : EngineEvent
    data class Announce(val text: String) : EngineEvent
    data object OffRoute : EngineEvent
    data object BackOnRoute : EngineEvent
    data object Arrived : EngineEvent
    data class TripSaved(val tripId: Long, val distanceM: Double) : EngineEvent
}

data class NavSnapshot(val route: Route, val phase: NavPhase, val progress: NavProgress?)

/**
 * Ghi hành trình + dẫn đường. Sống ở phạm vi ứng dụng (không thuộc Activity/ViewModel) để TripService
 * giữ tiến trình sống khi màn hình tắt. Mọi hàm công khai phải gọi trên cùng dispatcher của [scope] (Main).
 * Dữ liệu là GPS_ESTIMATE: không phải tốc độ hay quãng đường của xe.
 */
class TripEngine(
    private val scope: CoroutineScope,
    private val source: LocationSource,
    private val store: TripStore,
    private val gpsEnabled: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val flushEvery: Int = 10,
    private val maxAccuracyM: Float = 50f,
    private val navConfig: NavConfig = NavConfig()
) {
    private val _phase = MutableStateFlow(TripPhase.IDLE)
    val phase: StateFlow<TripPhase> = _phase.asStateFlow()
    private val _live = MutableStateFlow<TripStatsResult?>(null)
    val live: StateFlow<TripStatsResult?> = _live.asStateFlow()
    private val _track = MutableStateFlow<List<GeoPoint>>(emptyList())
    val track: StateFlow<List<GeoPoint>> = _track.asStateFlow()
    private val _lastFix = MutableStateFlow<GeoPoint?>(null)
    val lastFix: StateFlow<GeoPoint?> = _lastFix.asStateFlow()
    private val _gps = MutableStateFlow(GpsStatus.INACTIVE)
    val gps: StateFlow<GpsStatus> = _gps.asStateFlow()
    private val _nav = MutableStateFlow<NavSnapshot?>(null)
    val nav: StateFlow<NavSnapshot?> = _nav.asStateFlow()
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    @Volatile var speedLimitKmh: Int = 0
    private val segments = ArrayList<MutableList<GeoPoint>>()
    private var newSegment = true
    private val buffer = ArrayList<GeoPoint>()
    private var tripId: Long? = null
    private var startMs = 0L
    private val persist = Mutex()
    private var collector: Job? = null
    private var ticker: Job? = null
    private var previewCount = 0
    private var lastFixAt: Long? = null
    private var navEngine: NavigationEngine? = null
    private var lastSpeedEventAt = 0L

    // ---------------------------------------------------------------- vòng đời hành trình
    fun start(): Boolean {
        if (_phase.value != TripPhase.IDLE) return false
        segments.clear(); buffer.clear(); newSegment = true
        _track.value = emptyList(); _live.value = null
        startMs = clock(); tripId = null
        val s = startMs
        _phase.value = TripPhase.RECORDING
        scope.launch {
            persist.withLock {
                tripId = try { store.begin(s) } catch (e: Exception) {
                    _events.tryEmit(EngineEvent.Message("Không tạo được bản ghi hành trình: ${e.message}. Hành trình chỉ giữ trong bộ nhớ.")); null
                }
            }
        }
        updateCollector()
        return true
    }

    fun pause(): Boolean {
        if (_phase.value != TripPhase.RECORDING) return false
        _phase.value = TripPhase.PAUSED; flush(); newSegment = true
        return true
    }

    fun resume(): Boolean {
        if (_phase.value != TripPhase.PAUSED) return false
        _phase.value = TripPhase.RECORDING; newSegment = true
        return true
    }

    /** Kết thúc, lưu nếu đủ dữ liệu. Trả về false nếu đang không ghi. */
    fun stop(): Boolean {
        if (_phase.value == TripPhase.IDLE) return false
        val end = clock(); val s = startMs
        val stats = TripStats.computeSegments(segments)
        val good = segments.sumOf { it.size }
        val last = segments.lastOrNull()?.lastOrNull()
        val batch = takeBuffer()
        stopNavigation()
        _phase.value = TripPhase.IDLE; _live.value = null
        segments.clear(); _track.value = emptyList()
        scope.launch {
            persist.withLock {
                val id = tripId ?: return@withLock
                try {
                    if (good < 2 || stats.distanceM <= 0.0) {
                        store.discard(id); _events.tryEmit(EngineEvent.Message("Hành trình quá ngắn hoặc thiếu dữ liệu GPS, không lưu."))
                    } else {
                        if (batch.isNotEmpty()) store.append(id, batch)
                        store.finish(id, s, end, stats, last?.lat, last?.lon)
                        _events.tryEmit(EngineEvent.TripSaved(id, stats.distanceM))
                    }
                } catch (e: Exception) { _events.tryEmit(EngineEvent.Message("Lỗi lưu hành trình: ${e.message}")) }
                tripId = null
            }
        }
        updateCollector()
        return true
    }

    // ---------------------------------------------------------------- xem trước vị trí (tab bản đồ)
    fun acquirePreview() { previewCount++; updateCollector() }
    fun releasePreview() { if (previewCount > 0) previewCount--; updateCollector() }

    // ---------------------------------------------------------------- dẫn đường
    /** Bắt đầu dẫn đường theo tuyến; nếu chưa ghi hành trình thì bắt đầu ghi luôn. */
    fun startNavigation(route: Route) {
        navEngine = NavigationEngine(route, navConfig)
        _nav.value = NavSnapshot(route, NavPhase.ACQUIRING, null)
        if (_phase.value == TripPhase.IDLE) start()
        _lastFix.value?.let { feedNav(it) }
    }
    fun stopNavigation() { navEngine = null; _nav.value = null }

    // ---------------------------------------------------------------- xử lý điểm GPS
    private fun updateCollector() {
        val want = _phase.value != TripPhase.IDLE || previewCount > 0
        if (want && collector == null) {
            collector = scope.launch {
                source.updates().catch { _events.tryEmit(EngineEvent.Message("Lỗi GPS: ${it.message}")) }.collect { onFix(it) }
            }
            ticker = scope.launch { while (true) { refreshGps(); delay(2000) } }
            refreshGps()
        } else if (!want && collector != null) {
            collector?.cancel(); ticker?.cancel(); collector = null; ticker = null
            _gps.value = GpsStatus.INACTIVE
        }
    }

    private fun refreshGps() {
        val fix = _lastFix.value
        val age = lastFixAt?.let { clock() - it }
        _gps.value = GpsStatusRules.evaluate(gpsEnabled(), age, fix?.accuracyM)
    }

    internal fun onFix(p: GeoPoint) {
        _lastFix.value = p; lastFixAt = clock(); refreshGps()
        if (_phase.value == TripPhase.RECORDING && p.accuracyM <= maxAccuracyM) {
            if (newSegment || segments.isEmpty()) { segments += ArrayList<GeoPoint>(); newSegment = false }
            segments.last().add(p); buffer.add(p)
            _track.value = segments.flatten()
            _live.value = TripStats.computeSegments(segments)
            if (buffer.size >= flushEvery) flush()
            val limit = speedLimitKmh
            if (SpeedAlert.isOver(p.speedMps, p.accuracyM, limit) && clock() - lastSpeedEventAt >= 5000) {
                lastSpeedEventAt = clock(); _events.tryEmit(EngineEvent.SpeedOver(p.speedMps * 3.6, limit))
            }
        }
        if (_phase.value != TripPhase.IDLE) feedNav(p)
    }

    private fun feedNav(p: GeoPoint) {
        val e = navEngine ?: return
        val u = e.update(p)
        _nav.value = NavSnapshot(e.route, u.phase, u.progress)
        for (ev in u.events) _events.tryEmit(when (ev) {
            is NavEvent.Announce -> EngineEvent.Announce(ev.text)
            NavEvent.OffRoute -> EngineEvent.OffRoute
            NavEvent.BackOnRoute -> EngineEvent.BackOnRoute
            NavEvent.Arrived -> EngineEvent.Arrived
        })
    }

    private fun takeBuffer(): List<GeoPoint> { val b = buffer.toList(); buffer.clear(); return b }
    private fun flush() {
        val batch = takeBuffer(); if (batch.isEmpty()) return
        scope.launch {
            persist.withLock {
                val id = tripId ?: return@withLock
                try { store.append(id, batch) } catch (e: Exception) { _events.tryEmit(EngineEvent.Message("Lỗi lưu điểm GPS: ${e.message}")) }
            }
        }
    }
}

/** Khôi phục chuyến bị gián đoạn (tiến trình bị tắt khi đang ghi). Thuần logic để test. */
object TripRecovery {
    data class Plan(val endMs: Long, val stats: TripStatsResult, val endLat: Double?, val endLon: Double?)
    /** null = không đủ điểm tốt, nên xóa chuyến. */
    fun plan(points: List<GeoPoint>): Plan? {
        val good = points.filter { it.accuracyM <= 50f }
        if (good.size < 2) return null
        val stats = TripStats.compute(good)
        if (stats.distanceM <= 0.0) return null
        return Plan(good.last().timeMs, stats, good.last().lat, good.last().lon)
    }
}
