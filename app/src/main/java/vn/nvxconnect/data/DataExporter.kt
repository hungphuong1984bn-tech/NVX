package vn.nvxconnect.data

import vn.nvxconnect.domain.VehicleProfile
import vn.nvxconnect.util.MiniJson.quote
import vn.nvxconnect.util.MiniJson.quoteOrNull

/** Xuất toàn bộ dữ liệu người dùng ra JSON. KHÔNG chứa địa chỉ thiết bị BLE hay nhật ký BLE. Mỗi mục ghi rõ nguồn. */
object DataExporter {
    fun toJson(
        profile: VehicleProfile, odoKm: Int, trips: List<Trip>, points: List<TrackPoint>,
        fuel: List<FuelEntry>, maintenance: List<MaintenanceItem>, nowMs: Long, appVersion: String
    ): String = buildString {
        val byTrip = points.groupBy { it.tripId }
        append("{\"format\":\"nvx-connect-data/1\",\"exported_ms\":$nowMs,\"app_version\":${quote(appVersion)},")
        append("\"source_note\":${quote("Hành trình: GPS_ESTIMATE (điện thoại). Hồ sơ, ODO, nhiên liệu, bảo dưỡng: USER_INPUT. Không có dữ liệu từ xe.")},")
        append("\"vehicle_profile\":{\"model\":${quote(profile.model)},\"variant\":${quote(profile.variant)},\"engine_cc\":${profile.engineCc},")
        append("\"purchase_date\":${quoteOrNull(profile.purchaseDate.ifBlank { null })},\"odo_km_user_input\":$odoKm},")
        append("\"trips\":[")
        append(trips.joinToString(",") { t ->
            "{\"id\":${t.id},\"start_ms\":${t.startMs},\"end_ms\":${t.endMs},\"distance_m\":${t.distanceM},\"max_speed_kmh_gps\":${t.maxSpeedKmh}," +
                "\"demo\":${t.isDemo},\"points\":[" + (byTrip[t.id] ?: emptyList()).joinToString(",") { p ->
                    "[${p.timeMs},${p.lat},${p.lon},${p.speedMps},${p.accuracyM}]" } + "]}"
        })
        append("],\"points_columns\":[\"time_ms\",\"lat\",\"lon\",\"speed_mps_gps\",\"accuracy_m\"],")
        append("\"fuel\":[")
        append(fuel.joinToString(",") { "{\"odo_km\":${it.odometerKm},\"liters\":${it.liters},\"price_per_liter_vnd\":${it.pricePerLiter},\"total_vnd\":${it.totalVnd},\"full_tank\":${it.fullTank},\"date_ms\":${it.dateMs},\"demo\":${it.isDemo}}" })
        append("],\"maintenance\":[")
        append(maintenance.joinToString(",") { "{\"name\":${quote(it.name)},\"interval_km\":${it.intervalKm ?: "null"},\"interval_days\":${it.intervalDays ?: "null"},\"last_done_odo_km\":${it.lastDoneOdoKm},\"last_done_ms\":${it.lastDoneMs},\"demo\":${it.isDemo}}" })
        append("]}")
    }
}
