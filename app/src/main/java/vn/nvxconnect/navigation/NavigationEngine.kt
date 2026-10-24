package vn.nvxconnect.navigation

import vn.nvxconnect.domain.GeoPoint
import kotlin.math.max
import kotlin.math.min

data class NavConfig(
    val maxAccuracyM: Float = 40f,
    val offRouteM: Double = 40.0,
    val offRouteFixes: Int = 3,
    val backOnRouteFixes: Int = 2,
    val arriveRadiusM: Double = 25.0,
    val announceAtM: List<Int> = listOf(300, 100, 30)
)

enum class NavPhase { ACQUIRING, ON_ROUTE, OFF_ROUTE, ARRIVED }

data class NavProgress(
    val stepIndex: Int, val alongM: Double, val lateralM: Double,
    val remainingM: Double,
    /** ƯỚC TÍNH theo dữ liệu bộ máy định tuyến, chưa tính ùn tắc/đèn đỏ. */
    val remainingS: Double,
    val distanceToNextM: Double, val nextStep: RouteStep?
)

sealed interface NavEvent {
    data class Announce(val text: String, val stepIndex: Int, val thresholdM: Int) : NavEvent
    data object OffRoute : NavEvent
    data object BackOnRoute : NavEvent
    data object Arrived : NavEvent
}

data class NavUpdate(val phase: NavPhase, val progress: NavProgress?, val events: List<NavEvent>)

/**
 * Theo dõi tiến độ trên tuyến từ các điểm GPS. Thuần logic, không phụ thuộc Android.
 * Không tự gọi định tuyến lại: khi lệch tuyến chỉ phát NavEvent.OffRoute để lớp trên quyết định.
 */
class NavigationEngine(val route: Route, private val cfg: NavConfig = NavConfig()) {
    private val geo = RouteGeometry(route.points)
    private val stepAlong = DoubleArray(route.steps.size)
    private val fired = HashSet<Pair<Int, Int>>()
    private var lastSeg = 0
    private var maxAlong = 0.0
    private var offCount = 0
    private var backCount = 0
    private var departAnnounced = false
    private var arrived = false
    var phase: NavPhase = NavPhase.ACQUIRING; private set
    var progress: NavProgress? = null; private set

    init {
        var seg = 0; var prev = 0.0
        for (i in route.steps.indices) {
            if (i == 0) { stepAlong[0] = 0.0; continue }
            val loc = route.steps[i].location
            val p = geo.project(loc.lat, loc.lon, seg, geo.segmentCount)
            stepAlong[i] = max(prev, p.alongM); prev = stepAlong[i]; seg = p.segIndex
        }
        // chặng cuối (ARRIVE) luôn nằm ở cuối tuyến
        if (stepAlong.isNotEmpty() && route.steps.last().maneuver == Maneuver.ARRIVE) stepAlong[stepAlong.size - 1] = geo.totalM
    }

    fun update(p: GeoPoint): NavUpdate {
        if (arrived) return NavUpdate(NavPhase.ARRIVED, progress, emptyList())
        if (p.accuracyM > cfg.maxAccuracyM) return NavUpdate(phase, progress, emptyList())
        val events = ArrayList<NavEvent>()

        // Chiếu lên tuyến: cửa sổ quanh vị trí cũ; nếu xa thì quét toàn tuyến để nhận ra đã quay lại tuyến.
        var proj = geo.project(p.lat, p.lon, max(0, lastSeg - 2), min(geo.segmentCount, lastSeg + 150).coerceAtLeast(max(0, lastSeg - 2) + 1))
        val threshold = max(cfg.offRouteM, p.accuracyM.toDouble() * 1.5)
        if (proj.lateralM > threshold || phase == NavPhase.OFF_ROUTE) {
            val full = geo.project(p.lat, p.lon)
            if (full.lateralM <= threshold || full.lateralM < proj.lateralM) proj = full
        }
        val onRoute = proj.lateralM <= threshold

        if (onRoute) {
            offCount = 0
            if (phase == NavPhase.OFF_ROUTE) {
                backCount++
                if (backCount >= cfg.backOnRouteFixes) { phase = NavPhase.ON_ROUTE; backCount = 0; events += NavEvent.BackOnRoute }
            } else if (phase == NavPhase.ACQUIRING) phase = NavPhase.ON_ROUTE
            if (phase != NavPhase.OFF_ROUTE) { lastSeg = proj.segIndex; maxAlong = max(maxAlong, proj.alongM) }
        } else {
            backCount = 0; offCount++
            if (offCount >= cfg.offRouteFixes && phase != NavPhase.OFF_ROUTE) { phase = NavPhase.OFF_ROUTE; events += NavEvent.OffRoute }
        }

        val along = maxAlong
        var idx = 0
        for (i in stepAlong.indices) if (stepAlong[i] <= along + 2.0) idx = i
        val next = route.steps.getOrNull(idx + 1)
        val nextAlong = if (idx + 1 < stepAlong.size) stepAlong[idx + 1] else geo.totalM
        val toNext = max(0.0, nextAlong - along)
        val remaining = max(0.0, geo.totalM - along)
        val span = max(1.0, nextAlong - stepAlong[idx])
        val frac = ((along - stepAlong[idx]) / span).coerceIn(0.0, 1.0)
        var remS = route.steps[idx].durationS * (1 - frac)
        for (j in idx + 1 until route.steps.size) remS += route.steps[j].durationS
        progress = NavProgress(idx, along, proj.lateralM, remaining, remS, toNext, next)

        if (phase == NavPhase.ON_ROUTE) {
            if (!departAnnounced) { departAnnounced = true; events += NavEvent.Announce("Bắt đầu dẫn đường", -1, Int.MAX_VALUE) }
            if (remaining <= cfg.arriveRadiusM && proj.lateralM <= threshold) {
                arrived = true; phase = NavPhase.ARRIVED; events += NavEvent.Arrived
            } else if (next != null) {
                val cand = cfg.announceAtM.filter { toNext <= it && (idx + 1 to it) !in fired }
                if (cand.isNotEmpty()) {
                    cand.forEach { fired += (idx + 1 to it) }
                    val t = cand.min()
                    events += NavEvent.Announce(TurnText.announce(next, toNext, t), idx + 1, t)
                }
            }
        }
        return NavUpdate(phase, progress, events)
    }
}
