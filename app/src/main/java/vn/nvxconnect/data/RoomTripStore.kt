package vn.nvxconnect.data

import vn.nvxconnect.domain.GeoPoint
import vn.nvxconnect.domain.TripStatsResult
import vn.nvxconnect.location.TripRecovery
import vn.nvxconnect.location.TripStore

/** Lưu hành trình vào Room: tạo bản ghi (endMs = 0) khi bắt đầu, thêm điểm theo đợt, đóng khi kết thúc. */
class RoomTripStore(private val dao: AppDao) : TripStore {
    override suspend fun begin(startMs: Long): Long =
        dao.insertTrip(Trip(startMs = startMs, endMs = 0, distanceM = 0.0, maxSpeedKmh = 0.0, endLat = null, endLon = null))

    override suspend fun append(tripId: Long, points: List<GeoPoint>) {
        dao.insertPoints(points.map { TrackPoint(tripId = tripId, lat = it.lat, lon = it.lon, speedMps = it.speedMps, accuracyM = it.accuracyM, timeMs = it.timeMs) })
    }

    override suspend fun finish(tripId: Long, startMs: Long, endMs: Long, stats: TripStatsResult, endLat: Double?, endLon: Double?) {
        dao.upsertTrip(Trip(id = tripId, startMs = startMs, endMs = maxOf(endMs, startMs + 1), distanceM = stats.distanceM,
            maxSpeedKmh = stats.maxKmh, endLat = endLat, endLon = endLon))
    }

    override suspend fun discard(tripId: Long) { dao.deletePoints(tripId); dao.deleteTrip(tripId) }

    /** Gọi khi mở app: đóng các chuyến còn endMs = 0 (do tiến trình bị tắt) bằng dữ liệu đã lưu. */
    suspend fun recoverUnfinished(): Int {
        var n = 0
        for (t in dao.unfinishedTrips()) {
            val pts = dao.points(t.id).map { GeoPoint(it.lat, it.lon, it.speedMps, it.accuracyM, it.timeMs) }
            val plan = TripRecovery.plan(pts)
            if (plan == null) discard(t.id)
            else { dao.upsertTrip(t.copy(endMs = maxOf(plan.endMs, t.startMs + 1), distanceM = plan.stats.distanceM, maxSpeedKmh = plan.stats.maxKmh, endLat = plan.endLat, endLon = plan.endLon)); n++ }
        }
        return n
    }
}
