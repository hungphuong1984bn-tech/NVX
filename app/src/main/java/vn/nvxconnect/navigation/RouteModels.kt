package vn.nvxconnect.navigation

data class LatLon(val lat: Double, val lon: Double) {
    val valid get() = lat in -90.0..90.0 && lon in -180.0..180.0 && !lat.isNaN() && !lon.isNaN()
}

enum class Maneuver {
    DEPART, TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT, STRAIGHT, UTURN,
    ROUNDABOUT, FORK_LEFT, FORK_RIGHT, MERGE, ARRIVE, UNKNOWN
}

/** Một chặng: thao tác tại [location] rồi đi [distanceM] mét (tới thao tác kế tiếp). */
data class RouteStep(
    val maneuver: Maneuver, val street: String, val distanceM: Double, val durationS: Double,
    val location: LatLon, val roundaboutExit: Int? = null
)

/** engine: tên bộ máy định tuyến (để ghi nguồn). durationS là ƯỚC TÍNH của bộ máy, chưa tính ùn tắc. */
data class Route(
    val points: List<LatLon>, val steps: List<RouteStep>, val distanceM: Double, val durationS: Double,
    val engine: String, val destination: LatLon = points.last()
)

data class TurnInstruction(val maneuver: Maneuver, val street: String, val distanceToManeuverM: Double, val roundaboutExit: Int?)

data class Place(val name: String, val displayName: String, val location: LatLon, val type: String?)

enum class NetError { OFFLINE, RATE_LIMITED, NO_RESULT, BLOCKED, SERVER, BAD_RESPONSE, INVALID_REQUEST }

sealed interface NetResult<out T> {
    data class Ok<T>(val value: T) : NetResult<T>
    data class Fail(val kind: NetError, val message: String) : NetResult<Nothing>
}
