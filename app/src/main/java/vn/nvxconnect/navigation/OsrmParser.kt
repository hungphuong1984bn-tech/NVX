package vn.nvxconnect.navigation

import vn.nvxconnect.util.MiniJson
import vn.nvxconnect.util.MiniJson.dbl
import vn.nvxconnect.util.MiniJson.int
import vn.nvxconnect.util.MiniJson.list
import vn.nvxconnect.util.MiniJson.obj
import vn.nvxconnect.util.MiniJson.text

/** Đọc phản hồi OSRM /route/v1 với overview=full&geometries=polyline6&steps=true. */
object OsrmParser {
    fun parse(body: String): NetResult<Route> {
        val root = try { MiniJson.parse(body).obj() } catch (e: MiniJson.JsonException) { null }
            ?: return NetResult.Fail(NetError.BAD_RESPONSE, "Phản hồi định tuyến không phải JSON hợp lệ")
        when (val code = root["code"].text()) {
            "Ok" -> {}
            "NoRoute" -> return NetResult.Fail(NetError.NO_RESULT, "Không tìm thấy đường đi giữa hai điểm này")
            "NoSegment" -> return NetResult.Fail(NetError.NO_RESULT, "Một trong hai điểm quá xa đường đi gần nhất")
            "InvalidQuery", "InvalidUrl", "InvalidValue", "InvalidOptions" ->
                return NetResult.Fail(NetError.INVALID_REQUEST, "Yêu cầu định tuyến không hợp lệ ($code)")
            else -> return NetResult.Fail(NetError.BAD_RESPONSE, "Máy chủ định tuyến trả mã lạ: $code")
        }
        val r0 = root["routes"].list()?.firstOrNull().obj() ?: return NetResult.Fail(NetError.NO_RESULT, "Không có tuyến nào được trả về")
        val points = try { PolylineCodec.decode(r0["geometry"].text() ?: "", 6) } catch (e: IllegalArgumentException) {
            return NetResult.Fail(NetError.BAD_RESPONSE, "Hình học tuyến lỗi: ${e.message}")
        }
        if (points.size < 2 || points.any { !it.valid }) return NetResult.Fail(NetError.BAD_RESPONSE, "Hình học tuyến không hợp lệ")
        val dist = r0["distance"].dbl(); val dur = r0["duration"].dbl()
        if (dist == null || dur == null || dist.isNaN() || dur.isNaN() || dist < 0 || dur < 0)
            return NetResult.Fail(NetError.BAD_RESPONSE, "Thiếu khoảng cách hoặc thời gian của tuyến")
        val steps = ArrayList<RouteStep>()
        for (leg in r0["legs"].list().orEmpty()) for (st in leg.obj()?.get("steps").list().orEmpty()) {
            val s = st.obj() ?: continue
            val man = s["maneuver"].obj() ?: continue
            val loc = man["location"].list()
            val lon = loc?.getOrNull(0).dbl(); val lat = loc?.getOrNull(1).dbl()
            if (lat == null || lon == null) continue
            val ll = LatLon(lat, lon)
            if (!ll.valid) continue
            steps += RouteStep(
                maneuver = mapManeuver(man["type"].text(), man["modifier"].text()),
                street = s["name"].text().orEmpty(), distanceM = s["distance"].dbl() ?: 0.0, durationS = s["duration"].dbl() ?: 0.0,
                location = ll, roundaboutExit = man["exit"].int())
        }
        if (steps.isEmpty()) return NetResult.Fail(NetError.BAD_RESPONSE, "Tuyến không có chỉ dẫn từng chặng")
        return NetResult.Ok(Route(points, steps, dist, dur, "OSRM"))
    }

    fun mapManeuver(type: String?, modifier: String?): Maneuver {
        when (type) {
            "depart" -> return Maneuver.DEPART
            "arrive" -> return Maneuver.ARRIVE
            "roundabout", "rotary" -> return Maneuver.ROUNDABOUT
            "exit roundabout", "exit rotary" -> return Maneuver.STRAIGHT
            "merge" -> return Maneuver.MERGE
            "fork" -> return when (modifier) { "left", "slight left", "sharp left" -> Maneuver.FORK_LEFT
                "right", "slight right", "sharp right" -> Maneuver.FORK_RIGHT; else -> Maneuver.STRAIGHT }
        }
        return when (modifier) {
            "uturn" -> Maneuver.UTURN
            "sharp left" -> Maneuver.SHARP_LEFT
            "left" -> Maneuver.TURN_LEFT
            "slight left" -> Maneuver.SLIGHT_LEFT
            "straight" -> Maneuver.STRAIGHT
            "slight right" -> Maneuver.SLIGHT_RIGHT
            "right" -> Maneuver.TURN_RIGHT
            "sharp right" -> Maneuver.SHARP_RIGHT
            else -> Maneuver.UNKNOWN
        }
    }
}

object NominatimParser {
    /** Đọc phản hồi /search?format=jsonv2. Bỏ qua kết quả thiếu/ sai tọa độ. */
    fun parse(body: String): NetResult<List<Place>> {
        val arr = try { MiniJson.parse(body).list() } catch (e: MiniJson.JsonException) { null }
            ?: return NetResult.Fail(NetError.BAD_RESPONSE, "Phản hồi tìm kiếm không phải JSON hợp lệ")
        val places = arr.mapNotNull { it.obj() }.mapNotNull { o ->
            val lat = o["lat"].dbl(); val lon = o["lon"].dbl()
            if (lat == null || lon == null) return@mapNotNull null
            val ll = LatLon(lat, lon); if (!ll.valid) return@mapNotNull null
            val display = o["display_name"].text() ?: return@mapNotNull null
            Place(o["name"].text()?.takeIf { it.isNotBlank() } ?: display.substringBefore(','), display, ll, o["type"].text())
        }
        return if (places.isEmpty()) NetResult.Fail(NetError.NO_RESULT, "Không tìm thấy địa điểm phù hợp") else NetResult.Ok(places)
    }
}

object CoordinateParser {
    private val RE = Regex("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*[,; ]\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$")
    /** "21.0285, 105.8542" hoặc "21.0285 105.8542". Trả về null nếu không phải tọa độ hợp lệ. */
    fun parse(s: String): LatLon? {
        val m = RE.matchEntire(s) ?: return null
        val ll = LatLon(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
        return if (ll.valid) ll else null
    }
}
