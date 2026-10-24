package vn.nvxconnect

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import vn.nvxconnect.domain.GeoPoint
import vn.nvxconnect.domain.TripStatsResult
import vn.nvxconnect.location.*
import vn.nvxconnect.navigation.*

class TripEngineTest {
    private class FakeSource : LocationSource {
        val flow = MutableSharedFlow<GeoPoint>(extraBufferCapacity = 100)
        var subscribers = 0
        override fun updates(): Flow<GeoPoint> = flow.onStart { subscribers++ }.onCompletion { subscribers-- }
    }
    private class FakeStore : TripStore {
        val log = mutableListOf<String>(); val points = mutableListOf<GeoPoint>(); var finished: TripStatsResult? = null; var failBegin = false
        override suspend fun begin(startMs: Long): Long { if (failBegin) throw IllegalStateException("db"); log += "begin"; return 7 }
        override suspend fun append(tripId: Long, points: List<GeoPoint>) { log += "append${points.size}"; this.points += points }
        override suspend fun finish(tripId: Long, startMs: Long, endMs: Long, stats: TripStatsResult, endLat: Double?, endLon: Double?) { log += "finish"; finished = stats }
        override suspend fun discard(tripId: Long) { log += "discard" }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var src: FakeSource
    private lateinit var store: FakeStore
    private lateinit var eng: TripEngine
    private var now = 1_000_000L
    @Before fun setUp() { src = FakeSource(); store = FakeStore(); eng = TripEngine(scope, src, store, { true }, { now }, flushEvery = 3) }
    @After fun tearDown() { scope.cancel() }

    private fun pt(lon: Double, t: Long, v: Float = 10f, acc: Float = 5f) = GeoPoint(0.0, lon, v, acc, t)
    private fun send(vararg p: GeoPoint) = p.forEach { now = it.timeMs + 1_000_000; check(src.flow.tryEmit(it)) }
    private fun settle() { Thread.sleep(60) }

    @Test fun recordsStatsAndSavesInBatchesThenFinishes() {
        assertTrue(eng.start()); assertFalse(eng.start())
        send(pt(0.000, 0), pt(0.001, 5000), pt(0.002, 10_000), pt(0.003, 15_000))
        assertEquals(TripPhase.RECORDING, eng.phase.value)
        assertEquals(4, eng.track.value.size); assertTrue(eng.live.value!!.distanceM in 300.0..360.0)
        assertTrue(eng.stop()); settle()
        assertEquals(listOf("begin", "append3", "append1", "finish"), store.log)
        assertEquals(4, store.points.size); assertTrue(store.finished!!.distanceM > 300)
        assertEquals(TripPhase.IDLE, eng.phase.value); assertEquals(0, src.subscribers)
    }

    @Test fun pauseStopsRecordingAndResumeDoesNotBridgeTheGap() {
        eng.start(); send(pt(0.000, 0), pt(0.001, 5000))
        assertTrue(eng.pause()); assertEquals(TripPhase.PAUSED, eng.phase.value)
        send(pt(0.050, 8000), pt(0.051, 9000))          // đi xa trong lúc tạm dừng: KHÔNG ghi
        assertEquals(2, eng.track.value.size)
        assertTrue(eng.resume())
        send(pt(0.060, 12_000), pt(0.061, 17_000))
        assertEquals(4, eng.track.value.size)
        val d = eng.live.value!!.distanceM
        assertTrue("không được nối đoạn trước/sau khi tạm dừng, d=$d", d < 300)
        assertEquals(10_000L, eng.live.value!!.durationMs)
        eng.stop(); assertFalse(eng.pause()); assertFalse(eng.resume())
    }

    @Test fun poorAccuracyPointsAreNotRecordedButUpdateGpsStatus() {
        eng.start(); send(pt(0.0, 0), pt(0.001, 1000, acc = 300f))
        assertEquals(1, eng.track.value.size); assertEquals(0.001, eng.lastFix.value!!.lon, 1e-9)
        assertEquals(GpsStatus.WEAK, eng.gps.value)
    }

    @Test fun tooShortTripIsDiscarded() {
        eng.start(); send(pt(0.0, 0)); eng.stop(); settle()
        assertEquals(listOf("begin", "discard"), store.log)
    }

    @Test fun databaseFailureDoesNotCrashAndKeepsRecordingInMemory() {
        store.failBegin = true; eng.start(); send(pt(0.0, 0), pt(0.001, 5000))
        assertEquals(2, eng.track.value.size); eng.stop(); settle()
        assertTrue(store.log.isEmpty())
    }

    @Test fun locationIsOnlyListenedToWhenNeeded() {
        assertEquals(0, src.subscribers); assertEquals(GpsStatus.INACTIVE, eng.gps.value)
        eng.acquirePreview(); assertEquals(1, src.subscribers)
        send(pt(0.0, 0)); assertEquals(0, eng.track.value.size)        // xem trước: không ghi điểm
        assertNotNull(eng.lastFix.value); assertEquals(GpsStatus.GOOD, eng.gps.value)
        eng.start(); assertEquals(1, src.subscribers)                  // dùng chung một luồng
        eng.stop(); assertEquals(1, src.subscribers)                   // preview còn giữ
        eng.releasePreview(); assertEquals(0, src.subscribers); eng.releasePreview()
    }

    @Test fun gpsOffIsReported() {
        val e = TripEngine(scope, src, store, { false }, { now })
        e.acquirePreview(); assertEquals(GpsStatus.OFF, e.gps.value)
        assertEquals(GpsStatus.OFF, GpsStatusRules.evaluate(false, 0, 1f))
        assertEquals(GpsStatus.SEARCHING, GpsStatusRules.evaluate(true, null, null))
        assertEquals(GpsStatus.SEARCHING, GpsStatusRules.evaluate(true, 20_000, 5f))
        assertEquals(GpsStatus.WEAK, GpsStatusRules.evaluate(true, 9_000, 5f))
        assertEquals(GpsStatus.GOOD, GpsStatusRules.evaluate(true, 1_000, 5f))
    }

    @Test fun speedAlertIsThrottledAndUsesUserLimit() {
        val evs = mutableListOf<EngineEvent>()
        val j = scope.launch { eng.events.collect { evs += it } }
        eng.speedLimitKmh = 40; eng.start()
        send(pt(0.0, 0, 15f)); now += 100; send(pt(0.0001, 1000, 15f)); send(pt(0.0002, 2000, 5f))
        assertEquals(1, evs.count { it is EngineEvent.SpeedOver }); j.cancel()
    }

    @Test fun navigationRunsInsideTripAndEmitsEvents() {
        val pts = (0..10).map { LatLon(21.0 + it * 0.001, 105.0) }
        val route = Route(pts, listOf(RouteStep(Maneuver.DEPART, "A", 1110.0, 100.0, pts[0]), RouteStep(Maneuver.ARRIVE, "", 0.0, 0.0, pts.last())), 1110.0, 100.0, "T")
        val evs = mutableListOf<EngineEvent>(); val j = scope.launch { eng.events.collect { evs += it } }
        eng.startNavigation(route)
        assertEquals(TripPhase.RECORDING, eng.phase.value); assertEquals(NavPhase.ACQUIRING, eng.nav.value!!.phase)
        var t = 0L; var lat = 21.0
        while (lat <= 21.0100001) { send(GeoPoint(lat, 105.0, 8f, 5f, t)); lat += 0.0005; t += 4000 }
        assertEquals(NavPhase.ARRIVED, eng.nav.value!!.phase)
        assertTrue(evs.any { it is EngineEvent.Announce }); assertTrue(evs.contains(EngineEvent.Arrived))
        send(GeoPoint(21.0, 105.002, 8f, 5f, t)); eng.stopNavigation(); assertNull(eng.nav.value); j.cancel()
    }

    @Test fun recoveryPlan() {
        assertNull(TripRecovery.plan(listOf(pt(0.0, 0))))
        val p = TripRecovery.plan(listOf(pt(0.0, 0), pt(0.001, 5000), pt(0.002, 10_000, acc = 300f)))!!
        assertEquals(5000L, p.endMs); assertTrue(p.stats.distanceM > 100)
    }
}
