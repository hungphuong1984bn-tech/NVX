package vn.nvxconnect

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.domain.GeoPoint
import vn.nvxconnect.navigation.*
import java.io.IOException

class NavigationTest {
    // ---- polyline ----
    @Test fun polylineMatchesGoogleReferenceVector() {
        val p = PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, p.size)
        assertEquals(38.5, p[0].lat, 1e-9); assertEquals(-120.2, p[0].lon, 1e-9)
        assertEquals(40.7, p[1].lat, 1e-9); assertEquals(-120.95, p[1].lon, 1e-9)
        assertEquals(43.252, p[2].lat, 1e-9); assertEquals(-126.453, p[2].lon, 1e-9)
    }
    @Test fun polylineRoundTripAndMalformedInput() {
        val pts = listOf(LatLon(21.028511, 105.804817), LatLon(21.0301, 105.8)); 
        val back = PolylineCodec.decode(PolylineCodec.encode(pts, 6), 6)
        assertEquals(pts[0].lat, back[0].lat, 1e-6); assertEquals(pts[1].lon, back[1].lon, 1e-6)
        assertThrows(IllegalArgumentException::class.java) { PolylineCodec.decode("_p~iF~ps|U_ulLnnq", 5) }
        assertThrows(IllegalArgumentException::class.java) { PolylineCodec.decode("\u0001\u0002", 5) }
        assertTrue(PolylineCodec.decode("", 6).isEmpty())
    }

    // ---- tuyến thử: đi thẳng hướng Bắc ~1.11 km, rẽ phải ở giữa ----
    private val lon = 105.0
    private fun at(lat: Double) = LatLon(lat, lon)
    private val pts = (0..10).map { at(21.0 + it * 0.001) }
    private val route = Route(pts, listOf(
        RouteStep(Maneuver.DEPART, "Đường A", 555.0, 60.0, pts[0]),
        RouteStep(Maneuver.TURN_RIGHT, "Đường B", 555.0, 60.0, at(21.005)),
        RouteStep(Maneuver.ARRIVE, "", 0.0, 0.0, pts.last())), 1110.0, 120.0, "TEST")
    private fun gp(lat: Double, lo: Double = lon, acc: Float = 5f, t: Long = 0) = GeoPoint(lat, lo, 8f, acc, t)

    @Test fun geometryProjectsOntoRoute() {
        val g = RouteGeometry(pts)
        assertEquals(1111.9, g.totalM, 3.0)
        val p = g.project(21.0055, lon + 0.0001)
        assertEquals(611.0, p.alongM, 3.0); assertEquals(10.4, p.lateralM, 1.5)
    }

    @Test fun engineAnnouncesDepartThenTurnOnceEachThenArrives() {
        val e = NavigationEngine(route)
        val texts = mutableListOf<String>(); var arrived = 0
        var lat = 21.0
        while (lat <= 21.0100001) {
            val u = e.update(gp(lat))
            u.events.forEach { when (it) { is NavEvent.Announce -> texts += it.text; NavEvent.Arrived -> arrived++; else -> {} } }
            lat += 0.0002
        }
        assertEquals("Bắt đầu dẫn đường", texts.first())
        val turn = texts.filter { it.contains("rẽ phải", true) }
        assertEquals(turn.toString(), 3, turn.size)                    // 300 m, 100 m, 30 m — mỗi mức đúng một lần
        assertTrue(turn[0].startsWith("Sau 300 mét, rẽ phải vào Đường B")); assertTrue(turn[2] == "Rẽ phải vào Đường B")
        assertEquals(1, arrived); assertEquals(NavPhase.ARRIVED, e.phase)
        assertTrue(texts.any { it.contains("đến nơi") })
    }

    @Test fun progressRemainingDistanceTimeAndStep() {
        val e = NavigationEngine(route)
        e.update(gp(21.0)); val u = e.update(gp(21.0025))
        val p = u.progress!!
        assertEquals(0, p.stepIndex); assertEquals(Maneuver.TURN_RIGHT, p.nextStep!!.maneuver)
        assertEquals(278.0, p.distanceToNextM, 4.0); assertEquals(834.0, p.remainingM, 5.0)
        assertEquals(60.0 * (1 - 278.0 / 555.0) + 60.0, p.remainingS, 3.0)
        assertEquals(1, e.update(gp(21.0065)).progress!!.stepIndex)
    }

    @Test fun offRouteNeedsConsecutiveGoodFixesThenRecovers() {
        val e = NavigationEngine(route)
        e.update(gp(21.0)); e.update(gp(21.001))
        val off = lon + 0.001 // ~104 m
        assertTrue(e.update(gp(21.0015, off)).events.isEmpty())
        assertTrue(e.update(gp(21.0016, off)).events.isEmpty())
        assertTrue(e.update(gp(21.0017, off)).events.contains(NavEvent.OffRoute))
        assertTrue(e.update(gp(21.0018, off)).events.isEmpty())          // không phát lặp
        assertEquals(NavPhase.OFF_ROUTE, e.phase)
        assertTrue(e.update(gp(21.002)).events.isEmpty())
        assertTrue(e.update(gp(21.0021)).events.contains(NavEvent.BackOnRoute))
    }

    @Test fun poorAccuracyFixesAreIgnoredAndOneJumpDoesNotTriggerOffRoute() {
        val e = NavigationEngine(route)
        e.update(gp(21.0)); e.update(gp(21.001))
        repeat(10) { assertTrue(e.update(gp(21.0015, lon + 0.01, acc = 200f)).events.isEmpty()) }
        assertTrue(e.update(gp(21.0015, lon + 0.001)).events.isEmpty())  // 1 điểm lệch đơn lẻ
        e.update(gp(21.002)); assertEquals(NavPhase.ON_ROUTE, e.phase)
    }

    // ---- câu chỉ dẫn ----
    @Test fun distanceWordsRoundSensibly() {
        assertEquals("30 mét", TurnText.distanceWords(28.0)); assertEquals("300 mét", TurnText.distanceWords(278.0))
        assertEquals("150 mét", TurnText.distanceWords(140.0)); assertEquals("1,2 ki-lô-mét", TurnText.distanceWords(1234.0))
        assertEquals("2 ki-lô-mét", TurnText.distanceWords(2000.0)); assertEquals("1 ki-lô-mét", TurnText.distanceWords(990.0))
    }
    @Test fun instructionsInVietnamese() {
        assertEquals("Rẽ trái vào Đường Láng", TurnText.instruction(Maneuver.TURN_LEFT, "Đường Láng"))
        assertEquals("Rẽ trái", TurnText.instruction(Maneuver.TURN_LEFT, ""))
        assertEquals("Quay đầu xe", TurnText.instruction(Maneuver.UTURN, "x"))
        assertEquals("Vào vòng xuyến, ra ở lối ra thứ 2", TurnText.instruction(Maneuver.ROUNDABOUT, "", 2))
        assertEquals("12 phút", Fmt.duration(700.0)); assertEquals("1 giờ 5 phút", Fmt.duration(3900.0))
        assertEquals("850 m", Fmt.distance(850.0)); assertEquals("1.5 km", Fmt.distance(1500.0))
    }

    // ---- OSRM ----
    private fun osrmJson(code: String = "Ok", geometry: String = PolylineCodec.encode(pts, 6)) = """
      {"code":"$code","routes":[{"geometry":"$geometry","distance":1112.5,"duration":130.2,"legs":[{"steps":[
        {"name":"Đường A","distance":555,"duration":60,"maneuver":{"type":"depart","location":[105.0,21.0]}},
        {"name":"Đường B","distance":557,"duration":70,"maneuver":{"type":"turn","modifier":"right","location":[105.0,21.005]}},
        {"name":"","distance":0,"duration":0,"maneuver":{"type":"arrive","location":[105.0,21.01]}}]}]}]}"""
    @Test fun osrmParsesRouteAndSteps() {
        val r = (OsrmParser.parse(osrmJson()) as NetResult.Ok).value
        assertEquals(11, r.points.size); assertEquals(3, r.steps.size)
        assertEquals(Maneuver.TURN_RIGHT, r.steps[1].maneuver); assertEquals("Đường B", r.steps[1].street)
        assertEquals(1112.5, r.distanceM, 1e-9); assertEquals("OSRM", r.engine)
    }
    @Test fun osrmErrorsAreMapped() {
        assertEquals(NetError.NO_RESULT, (OsrmParser.parse(osrmJson("NoRoute")) as NetResult.Fail).kind)
        assertEquals(NetError.INVALID_REQUEST, (OsrmParser.parse(osrmJson("InvalidQuery")) as NetResult.Fail).kind)
        assertEquals(NetError.BAD_RESPONSE, (OsrmParser.parse("<html>") as NetResult.Fail).kind)
        assertEquals(NetError.BAD_RESPONSE, (OsrmParser.parse(osrmJson(geometry = "_p~iF~ps|U_ulLnnq")) as NetResult.Fail).kind)
        assertEquals(NetError.BAD_RESPONSE, (OsrmParser.parse("""{"code":"Ok","routes":[{"geometry":"","distance":1,"duration":1}]}""") as NetResult.Fail).kind)
    }
    @Test fun maneuverMapping() {
        assertEquals(Maneuver.TURN_LEFT, OsrmParser.mapManeuver("end of road", "left"))
        assertEquals(Maneuver.ROUNDABOUT, OsrmParser.mapManeuver("roundabout", "right"))
        assertEquals(Maneuver.FORK_LEFT, OsrmParser.mapManeuver("fork", "slight left"))
        assertEquals(Maneuver.UNKNOWN, OsrmParser.mapManeuver("notification", null))
    }

    // ---- khách HTTP giả ----
    private class Fake(val code: Int = 200, val body: String = "", val fail: Boolean = false) : HttpFetcher {
        val urls = mutableListOf<String>(); val headers = mutableListOf<Map<String, String>>()
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            urls += url; this.headers += headers
            if (fail) throw IOException("offline"); return HttpResponse(code, body)
        }
    }
    @Test fun routingClientBuildsUrlAndMapsFailures() = runBlocking {
        val ok = Fake(body = osrmJson())
        val r = RoutingClient("https://osrm.example/", ok, "NVXConnect/test").route(LatLon(21.0, 105.0), LatLon(21.01, 105.0))
        assertTrue(r is NetResult.Ok)
        assertTrue(ok.urls[0].startsWith("https://osrm.example/route/v1/driving/105.000000,21.000000;105.000000,21.010000?"))
        assertEquals("NVXConnect/test", ok.headers[0]["User-Agent"])
        fun kind(f: Fake) = runBlocking { (RoutingClient("https://x", f, "ua").route(LatLon(0.0, 0.0), LatLon(1.0, 1.0)) as NetResult.Fail).kind }
        assertEquals(NetError.OFFLINE, kind(Fake(fail = true)))
        assertEquals(NetError.RATE_LIMITED, kind(Fake(429)))
        assertEquals(NetError.BLOCKED, kind(Fake(403)))
        assertEquals(NetError.SERVER, kind(Fake(503)))
        assertEquals(NetError.NO_RESULT, kind(Fake(400, osrmJson("NoRoute"))))
        val bad = RoutingClient("http://evil.example.com", ok, "ua").route(LatLon(0.0, 0.0), LatLon(1.0, 1.0))
        assertEquals(NetError.INVALID_REQUEST, (bad as NetResult.Fail).kind)
    }

    private val nomJson = """[{"lat":"21.0285","lon":"105.8542","display_name":"Hồ Hoàn Kiếm, Hà Nội, Việt Nam","name":"Hồ Hoàn Kiếm","type":"water"},
        {"lat":"x","lon":"1","display_name":"hỏng"},{"lat":"95","lon":"1","display_name":"ngoài phạm vi"}]"""
    @Test fun geocodeParsesCachesAndRespectsPolicy() = runBlocking {
        val f = Fake(body = nomJson)
        val c = GeocodeClient("https://nominatim.example", f, "ua", MinIntervalLimiter(0))
        val r = (c.search("hồ gươm") as NetResult.Ok).value
        assertEquals(1, r.size); assertEquals(21.0285, r[0].location.lat, 1e-9)
        assertTrue(f.urls[0].contains("q=h%E1%BB%93+g%C6%B0%C6%A1m") && f.urls[0].contains("format=jsonv2"))
        c.search("HỒ GƯƠM"); assertEquals("lần 2 phải lấy từ cache", 1, f.urls.size)
        assertEquals(NetError.INVALID_REQUEST, (c.search("a") as NetResult.Fail).kind)
        assertEquals(NetError.RATE_LIMITED, (GeocodeClient("https://n", Fake(429), "ua", MinIntervalLimiter(0)).search("abc") as NetResult.Fail).kind)
        assertEquals(NetError.NO_RESULT, (GeocodeClient("https://n", Fake(body = "[]"), "ua", MinIntervalLimiter(0)).search("abc") as NetResult.Fail).kind)
    }
    @Test fun limiterSpacesRequests() {
        var t = 1000L; val l = MinIntervalLimiter(1000) { t }
        assertEquals(0L, l.reserve()); assertEquals(1000L, l.reserve())
        t = 5000; assertEquals(0L, l.reserve())
    }
    @Test fun urlPolicyAndCoordinates() {
        assertEquals("https://a.b", UrlPolicy.normalize(" https://a.b/ "))
        assertNull(UrlPolicy.normalize("http://example.com")); assertNull(UrlPolicy.normalize("ftp://x")); assertNull(UrlPolicy.normalize("https://"))
        assertNotNull(UrlPolicy.normalize("http://192.168.1.5:5000")); assertNotNull(UrlPolicy.normalize("http://localhost:5000"))
        assertEquals(LatLon(21.0285, 105.8542), CoordinateParser.parse("21.0285, 105.8542"))
        assertEquals(LatLon(-10.5, 20.0), CoordinateParser.parse("-10.5 20"))
        assertNull(CoordinateParser.parse("91, 20")); assertNull(CoordinateParser.parse("hà nội"))
    }
}
