package vn.nvxconnect.navigation

import vn.nvxconnect.domain.TripStats
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Hình học của một tuyến: khoảng cách tích lũy dọc tuyến và phép chiếu một điểm GPS lên tuyến. */
class RouteGeometry(val points: List<LatLon>) {
    init { require(points.size >= 2) { "Tuyến cần ít nhất 2 điểm" } }

    /** cum[i] = khoảng cách (m) từ điểm đầu đến đỉnh i. */
    val cum = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + TripStats.haversineM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
    }
    val totalM get() = cum.last()
    val segmentCount get() = points.size - 1

    data class Projection(val alongM: Double, val lateralM: Double, val segIndex: Int)

    /** Chiếu (lat, lon) lên các đoạn [fromSeg, toSegExclusive) và lấy đoạn gần nhất. */
    fun project(lat: Double, lon: Double, fromSeg: Int = 0, toSegExclusive: Int = segmentCount): Projection {
        val a = max(0, fromSeg); val b = min(segmentCount, toSegExclusive)
        require(a < b) { "Khoảng đoạn rỗng" }
        var best = Projection(0.0, Double.MAX_VALUE, a)
        for (i in a until b) {
            val p1 = points[i]; val p2 = points[i + 1]
            val mLat = 110_540.0; val mLon = 111_320.0 * cos(Math.toRadians(p1.lat))
            val dx = (p2.lon - p1.lon) * mLon; val dy = (p2.lat - p1.lat) * mLat
            val px = (lon - p1.lon) * mLon; val py = (lat - p1.lat) * mLat
            val len2 = dx * dx + dy * dy
            val t = if (len2 < 1e-9) 0.0 else ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
            val lateral = hypot(px - t * dx, py - t * dy)
            if (lateral < best.lateralM) best = Projection(cum[i] + t * (cum[i + 1] - cum[i]), lateral, i)
        }
        return best
    }
}
