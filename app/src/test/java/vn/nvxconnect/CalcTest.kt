package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.data.*
import vn.nvxconnect.domain.*

class CalcTest {
    private fun f(odo: Int, l: Double, full: Boolean) = FuelEntry(odometerKm = odo, liters = l, pricePerLiter = 22000,
        totalVnd = Math.round(l * 22000), fullTank = full, dateMs = 0)

    @Test fun haversineOneDegreeLatitude() {
        val d = TripStats.haversineM(GeoPoint(0.0, 0.0, 0f, 5f, 0), GeoPoint(1.0, 0.0, 0f, 5f, 0))
        assertEquals(111_195.0, d, 300.0)
    }
    @Test fun statsIgnoreBadAccuracy() {
        val s = TripStats.compute(listOf(GeoPoint(0.0, 0.0, 10f, 5f, 0), GeoPoint(5.0, 5.0, 99f, 500f, 1000), GeoPoint(0.0, 0.001, 10f, 5f, 12_000)))
        assertTrue(s.distanceM < 200 && s.distanceM > 100); assertEquals(36.0, s.maxKmh, 0.01)
    }
    @Test fun fuelFullToFull() {
        val r = FuelCalc.compute(listOf(f(1000, 5.0, true), f(1050, 1.5, false), f(1100, 2.5, true)))!!
        assertEquals(4.0, r.lPer100Km, 1e-9); assertEquals(880.0, r.vndPerKm, 1e-9)
    }
    @Test fun fuelNeedsTwoFullTanks() { assertNull(FuelCalc.compute(listOf(f(1000, 5.0, true), f(1100, 2.0, false)))) }
    @Test fun maintenanceStatus() {
        val i = MaintenanceItem(name = "dau", intervalKm = 3000, intervalDays = null, lastDoneOdoKm = 1000, lastDoneMs = 1)
        assertEquals(MaintStatus.OVERDUE, Maintenance.due(i, 4100, 0).status)
        assertEquals(MaintStatus.SOON, Maintenance.due(i, 3600, 0).status)
        assertEquals(MaintStatus.OK, Maintenance.due(i, 1500, 0).status)
    }
    @Test fun maintenanceUnrecordedWhenNeverDone() {
        val i = MaintenanceItem(name = "bugi", intervalKm = 12000, intervalDays = null, lastDoneOdoKm = 0, lastDoneMs = 0)
        assertEquals(MaintStatus.UNRECORDED, Maintenance.due(i, 5000, 1_000_000).status)
    }
    @Test fun speedAlert() {
        assertTrue(SpeedAlert.isOver(15f, 5f, 50))      // 54 km/h > 50
        assertFalse(SpeedAlert.isOver(10f, 5f, 50))     // 36 km/h
        assertFalse(SpeedAlert.isOver(30f, 100f, 50))   // GPS kém chính xác
        assertFalse(SpeedAlert.isOver(30f, 5f, 0))      // tắt
    }

    // ---- hành trình: tách đoạn, GPS trôi, nhiễu tốc độ ----
    private fun g(lon: Double, t: Long, v: Float = 10f, acc: Float = 5f) = GeoPoint(0.0, lon, v, acc, t)

    @Test fun gapSplitsSegmentsAndIsNotBridged() {
        val pts = listOf(g(0.000, 0), g(0.001, 10_000), g(0.100, 200_000), g(0.101, 210_000)) // nhảy ~11 km sau 190 s
        val segs = TripStats.splitSegments(pts); assertEquals(2, segs.size)
        val s = TripStats.compute(pts)
        assertTrue("không được nối thẳng qua khoảng đứt", s.distanceM < 300)
        assertEquals(20_000L, s.durationMs)   // thời gian đứt không tính
    }
    @Test fun stationaryJitterIsNotCounted() {
        val pts = (0..20).map { GeoPoint(0.0, 0.00001 * (it % 2), 0.1f, 8f, it * 2000L) }   // trôi ~1 m, gần như đứng yên
        assertEquals(0.0, TripStats.compute(pts).distanceM, 0.001)
    }
    @Test fun speedSpikesAreIgnoredForMax() {
        val s = TripStats.compute(listOf(g(0.0, 0, 10f), g(0.0005, 5000, 200f), g(0.001, 10_000, 12f)))
        assertEquals(12 * 3.6, s.maxKmh, 0.01)
    }
    @Test fun bearingNorthAndEast() {
        assertEquals(0.0, TripStats.bearingDeg(0.0, 0.0, 1.0, 0.0), 0.01)
        assertEquals(90.0, TripStats.bearingDeg(0.0, 0.0, 0.0, 1.0), 0.01)
    }

    // ---- bảo dưỡng một lần, nhiên liệu, hồ sơ ----
    @Test fun oneOffMaintenanceCompletes() {
        val pending = MaintenanceItem(name = "Thay lốp", intervalKm = null, intervalDays = null, lastDoneOdoKm = 0, lastDoneMs = 0)
        assertEquals(MaintStatus.UNRECORDED, Maintenance.due(pending, 100, 1).status)
        assertEquals(MaintStatus.COMPLETED, Maintenance.due(pending.copy(lastDoneMs = 5), 100, 10).status)
    }
    @Test fun maintenanceByDays() {
        val day = 86_400_000L
        val i = MaintenanceItem(name = "x", intervalKm = null, intervalDays = 30, lastDoneOdoKm = 0, lastDoneMs = 1)
        assertEquals(MaintStatus.OK, Maintenance.due(i, 0, 5 * day).status)
        assertEquals(MaintStatus.SOON, Maintenance.due(i, 0, 20 * day).status)
        assertEquals(MaintStatus.OVERDUE, Maintenance.due(i, 0, 40 * day).status)
    }
    @Test fun fuelValidation() {
        val ex = listOf(f(1000, 5.0, true))
        assertNull(FuelValidator.validate(1100, 4.0, 22000, null, ex))
        assertNotNull(FuelValidator.validate(null, 4.0, 22000, null, ex)); assertNotNull(FuelValidator.validate(1100, 0.0, 22000, null, ex))
        assertNotNull(FuelValidator.validate(1100, 99.0, 22000, null, ex)); assertNotNull(FuelValidator.validate(1100, 4.0, 0, null, ex))
        assertNotNull(FuelValidator.validate(1100, 4.0, 22000, -5, ex)); assertNotNull(FuelValidator.validate(1000, 4.0, 22000, null, ex))
    }
    @Test fun fuelCalcIgnoresZeroDistance() { assertNull(FuelCalc.compute(listOf(f(1000, 5.0, true), f(1000, 2.0, true)))) }
    @Test fun profileValidation() {
        val today = java.time.LocalDate.of(2026, 10, 21)
        assertNull(ProfileValidator.validate(VehicleProfile(purchaseDate = "2025-06-30"), today))
        assertNull(ProfileValidator.validate(VehicleProfile(purchaseDate = ""), today))
        assertNotNull(ProfileValidator.validate(VehicleProfile(purchaseDate = "30/06/2025"), today))
        assertNotNull(ProfileValidator.validate(VehicleProfile(purchaseDate = "2027-01-01"), today))
        assertNotNull(ProfileValidator.validate(VehicleProfile(engineCc = 5), today)); assertNotNull(ProfileValidator.validate(VehicleProfile(model = " "), today))
    }
    @Test fun dataSourcesAreDistinct() { assertEquals(6, DataSource.values().size); assertNotEquals(DataSource.GPS_ESTIMATE, DataSource.LIVE_VERIFIED) }
}
