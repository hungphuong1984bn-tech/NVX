package vn.nvxconnect

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.domain.GeoPoint
import vn.nvxconnect.domain.TripStatsResult
import vn.nvxconnect.location.*
import vn.nvxconnect.navigation.*

class NavigationSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel() }

    private val pts = (0..10).map { LatLon(21.0 + it * 0.001, 105.0) }
    private fun route(pp: List<LatLon> = pts) = Route(pp, listOf(RouteStep(Maneuver.DEPART, "A", 1110.0, 100.0, pp[0]), RouteStep(Maneuver.ARRIVE, "", 0.0, 0.0, pp.last())), 1110.0, 100.0, "T")
    private class Src : LocationSource { val f = MutableSharedFlow<GeoPoint>(extraBufferCapacity = 100); override fun updates(): Flow<GeoPoint> = f }
    private class St : TripStore {
        override suspend fun begin(startMs: Long) = 1L; override suspend fun append(tripId: Long, points: List<GeoPoint>) {}
        override suspend fun finish(tripId: Long, startMs: Long, endMs: Long, stats: TripStatsResult, endLat: Double?, endLon: Double?) {}
        override suspend fun discard(tripId: Long) {}
    }
    private class Planner(val results: MutableList<NetResult<Route>>) : RoutePlanner {
        var calls = 0; var from: LatLon? = null
        override suspend fun route(from: LatLon, to: LatLon): NetResult<Route> { calls++; this.from = from; return results.removeAt(0) }
    }
    private fun wait(p: () -> Boolean) { val end = System.currentTimeMillis() + 3000; while (System.currentTimeMillis() < end) { if (p()) return; Thread.sleep(5) }; fail("timeout") }

    private fun setup(planner: Planner, voice: Boolean = true, sound: Boolean = true): Triple<TripEngine, Src, Pair<MutableList<String>, IntArray>> {
        val src = Src(); val engine = TripEngine(Dispatchers.Unconfined.let { CoroutineScope(SupervisorJob() + it) }, src, St())
        val said = mutableListOf<String>(); val beeps = IntArray(1)
        NavigationSession(scope, engine, { planner }, { said += it }, { beeps[0]++ }, { SessionPrefs(voice, sound) }, retryDelayMs = 30).also { sessions += it }; Thread.sleep(60)
        return Triple(engine, src, said to beeps)
    }
    private val sessions = mutableListOf<NavigationSession>()
    private fun fix(src: Src, lat: Double, lon: Double = 105.0, t: Long = 0) { src.f.tryEmit(GeoPoint(lat, lon, 8f, 5f, t)) }

    @Test fun announcementsAreSpokenOnlyWhenVoiceEnabled() {
        for (voice in listOf(true, false)) {
            val (eng, src, out) = setup(Planner(mutableListOf()), voice = voice)
            sessions.last().begin(route())
            fix(src, 21.0); Thread.sleep(80)
            assertEquals(voice, out.first.contains("Bắt đầu dẫn đường"))
            sessions.last().end(); eng.stop()
        }
    }

    @Test fun offRouteTriggersRerouteFromCurrentPositionAndSpeaksOnce() {
        val newPts = (0..10).map { LatLon(21.002, 105.001 + it * 0.001) }
        val planner = Planner(mutableListOf(NetResult.Ok(route(newPts))))
        val (eng, src, out) = setup(planner)
        sessions.last().begin(route())
        fix(src, 21.0); fix(src, 21.001)
        repeat(3) { fix(src, 21.002, 105.002, it + 5L) }        // ~200 m lệch, 3 điểm liên tiếp
        wait { planner.calls == 1 }
        wait { eng.nav.value?.route?.points?.first()?.lon == 105.001 }
        assertEquals(21.002, planner.from!!.lat, 1e-6)
        wait { out.first.contains("Đã có lộ trình mới") && sessions.last().status.value == null }
        assertEquals(1, out.first.count { it.contains("lệch lộ trình") })
    }

    @Test fun rerouteFailureIsReportedAndRetriedWithoutInventingARoute() {
        val planner = Planner(mutableListOf(NetResult.Fail(NetError.OFFLINE, "Không có mạng"), NetResult.Fail(NetError.OFFLINE, "Không có mạng"), NetResult.Fail(NetError.OFFLINE, "x"),
            NetResult.Fail(NetError.OFFLINE, "x"), NetResult.Fail(NetError.OFFLINE, "x")))
        val (eng, src, _) = setup(planner)
        sessions.last().begin(route()); val original = eng.nav.value!!.route
        fix(src, 21.0); fix(src, 21.001); repeat(3) { fix(src, 21.002, 105.002, it + 5L) }
        wait { planner.calls >= 2 }
        assertTrue(sessions.last().status.value!!.isNotBlank())
        wait { planner.calls == 5 && sessions.last().status.value!!.startsWith("Không tính lại được") }
        assertSame(original, eng.nav.value!!.route)
    }

    @Test fun returningToRouteCancelsReroute() {
        val planner = Planner(mutableListOf(NetResult.Fail(NetError.OFFLINE, "x"), NetResult.Fail(NetError.OFFLINE, "x"), NetResult.Fail(NetError.OFFLINE, "x")))
        val (eng, src, out) = setup(planner)
        sessions.last().begin(route()); fix(src, 21.0); fix(src, 21.001)
        repeat(3) { fix(src, 21.002, 105.002, it + 5L) }; wait { planner.calls >= 1 }
        fix(src, 21.003); fix(src, 21.0031)
        wait { out.first.contains("Đã quay lại lộ trình") }
        val c = planner.calls; Thread.sleep(150); assertTrue(planner.calls <= c + 1)
    }

    @Test fun speedAlertBeepsOnlyIfSoundEnabled() {
        for (sound in listOf(true, false)) {
            val (eng, src, out) = setup(Planner(mutableListOf()), sound = sound)
            eng.speedLimitKmh = 20; eng.start()
            src.f.tryEmit(GeoPoint(0.0, 0.0, 15f, 5f, 0)); Thread.sleep(80)
            assertEquals(if (sound) 1 else 0, out.second[0]); eng.stop()
        }
    }
}
