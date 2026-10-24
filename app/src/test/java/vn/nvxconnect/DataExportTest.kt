package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.data.*
import vn.nvxconnect.domain.VehicleProfile
import vn.nvxconnect.util.MiniJson
import vn.nvxconnect.util.MiniJson.list
import vn.nvxconnect.util.MiniJson.obj

class DataExportTest {
    @Test fun exportIsValidJsonWithSourceLabelsAndNoBleData() {
        val json = DataExporter.toJson(
            VehicleProfile("Yamaha \"NVX\"\n155", "V3 SP", 155, "2025-06-30"), 1234,
            listOf(Trip(id = 1, startMs = 10, endMs = 20, distanceM = 1500.5, maxSpeedKmh = 40.0, endLat = null, endLon = null)),
            listOf(TrackPoint(tripId = 1, lat = 21.0, lon = 105.0, speedMps = 5f, accuracyM = 6f, timeMs = 11)),
            listOf(FuelEntry(odometerKm = 1000, liters = 4.5, pricePerLiter = 22000, totalVnd = 99000, fullTank = true, dateMs = 5)),
            listOf(MaintenanceItem(name = "Nhớt", intervalKm = 3000, intervalDays = null, lastDoneOdoKm = 0, lastDoneMs = 0)), 99, "0.3.0")
        val root = MiniJson.parse(json).obj()!!
        assertEquals(1, root["trips"].list()!!.size)
        assertEquals("Yamaha \"NVX\"\n155", root["vehicle_profile"].obj()!!["model"])
        assertTrue(json.contains("GPS_ESTIMATE") && json.contains("USER_INPUT"))
        assertFalse(json.contains("ble", ignoreCase = true) && json.contains("address"))
        assertNull(root["vehicle_profile"].obj()!!["rpm"])
    }
    @Test fun miniJsonRejectsMalformedAndDeepInput() {
        for (bad in listOf("{", "[1,]", "{\"a\":}", "\"abc", "tru", "{\"a\":1} x", "[\"\u0001\"]")) assertThrows(bad, MiniJson.JsonException::class.java) { MiniJson.parse(bad) }
        assertThrows(MiniJson.JsonException::class.java) { MiniJson.parse("[".repeat(500) + "]".repeat(500)) }
        assertEquals(listOf(1L, 2.5, "é", null, true), MiniJson.parse("[1, 2.5, \"\\u00e9\", null, true]"))
    }
}
