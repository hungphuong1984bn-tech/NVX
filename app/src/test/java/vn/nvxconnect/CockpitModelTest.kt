package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.pro.*
import vn.nvxconnect.protocol.LiveValue
import vn.nvxconnect.protocol.VehicleParam
import vn.nvxconnect.protocol.VehicleTelemetry

class CockpitModelTest {
    private val now = 100_000L
    private fun tele(p: VehicleParam, v: Double, t: Long, unit: String = "rpm") = VehicleTelemetry(mapOf(p to LiveValue(v, unit, t, "ev")))

    // ---- trạng thái ngắt kết nối / chưa xác minh
    @Test fun unverifiedLinkNeverShowsVehicleNumbersEvenIfHubHasData() {
        val g = CockpitModel.vehicle(CockpitMetric.RPM, tele(VehicleParam.RPM, 3000.0, now - 100), linkVerified = false, nowMs = now)
        assertEquals(GaugeState.NO_SOURCE, g.state); assertFalse(g.showsValue); assertNull(g.value); assertNull(g.fraction)
    }
    @Test fun verifiedButNoValueYetIsWaitingNotZero() {
        val g = CockpitModel.vehicle(CockpitMetric.RPM, VehicleTelemetry(), true, now)
        assertEquals(GaugeState.WAITING, g.state); assertNull(g.value)
    }
    // ---- dữ liệu cũ
    @Test fun freshVerifiedValueIsLive() {
        val g = CockpitModel.vehicle(CockpitMetric.RPM, tele(VehicleParam.RPM, 3000.0, now - 1000), true, now)
        assertEquals(GaugeState.LIVE, g.state); assertEquals(3000.0, g.value!!, 1e-9); assertEquals(0.3f, g.fraction!!, 1e-4f)
    }
    @Test fun staleValueIsHiddenNotShownAsLive() {
        val g = CockpitModel.vehicle(CockpitMetric.RPM, tele(VehicleParam.RPM, 3000.0, now - 6000), true, now)
        assertEquals(GaugeState.STALE, g.state); assertFalse(g.showsValue); assertNull(g.value); assertEquals(6000L, g.ageMs)
        assertTrue(g.note.contains("cũ"))
    }
    @Test fun valueFromTheFutureIsNotLive() {
        assertNotEquals(GaugeState.LIVE, CockpitModel.vehicle(CockpitMetric.RPM, tele(VehicleParam.RPM, 3000.0, now + 5000), true, now).state)
    }
    // ---- GPS không bao giờ là tốc độ ECU
    @Test fun gpsSpeedConvertsAndIsLabeledGps() {
        val g = CockpitModel.gpsSpeed(10f, 5f, now - 500, now)
        assertEquals(GaugeState.LIVE, g.state); assertEquals(36.0, g.value!!, 1e-6); assertEquals("km/h", g.unit)
        assertTrue(g.note.contains("GPS")); assertTrue(g.note.contains("không phải tốc độ ECU"))
    }
    @Test fun gpsStaleOrMissingOrInaccurate() {
        assertEquals(GaugeState.NO_SOURCE, CockpitModel.gpsSpeed(null, null, null, now).state)
        assertEquals(GaugeState.STALE, CockpitModel.gpsSpeed(10f, 5f, now - 4000, now).state)
        assertEquals(GaugeState.WAITING, CockpitModel.gpsSpeed(10f, 80f, now - 100, now).state)
        assertEquals(GaugeState.WAITING, CockpitModel.gpsSpeed(Float.NaN, 5f, now - 100, now).state)
    }
    @Test fun gpsSpeedMetricHasNoVehicleParam() { assertNull(CockpitMetric.GPS_SPEED.param) }
    @Test fun vehicleSpeedIsNotACockpitMetricSoGpsCannotImpersonateIt() {
        assertTrue(CockpitMetric.values().none { it.param == VehicleParam.SPEED_KMH })
    }
    // ---- bố cục tùy chỉnh
    @Test fun hiddenMetricsAreRemovedButGpsSpeedAlwaysStays() {
        val g = CockpitModel.gauges(setOf("rpm", "gps_speed"), VehicleTelemetry(), false, 5f, 5f, now - 100, now)
        assertTrue(g.any { it.metric == CockpitMetric.GPS_SPEED }); assertTrue(g.none { it.metric == CockpitMetric.RPM }); assertEquals(4, g.size)
    }
    @Test fun hiddenEncodeDecodeRoundTripAndIgnoreUnknown() {
        val enc = CockpitModel.encodeHidden(setOf("rpm", "voltage", "bogus", "gps_speed"))
        assertEquals("rpm,voltage", enc); assertEquals(setOf("rpm", "voltage"), CockpitModel.decodeHidden(enc))
        assertEquals(emptySet<String>(), CockpitModel.decodeHidden(null)); assertEquals(emptySet<String>(), CockpitModel.decodeHidden(""))
    }
    @Test fun qualitySummary() {
        val live = CockpitModel.vehicle(CockpitMetric.RPM, tele(VehicleParam.RPM, 1.0, now), true, now)
        val stale = CockpitModel.vehicle(CockpitMetric.VOLTAGE, tele(VehicleParam.BATTERY_V, 12.0, now - 9000, "V"), true, now)
        assertEquals("Một phần dữ liệu xe đã cũ", CockpitModel.quality(listOf(live, stale)))
        assertEquals("Dữ liệu xe trực tiếp", CockpitModel.quality(listOf(live)))
        assertEquals("Dữ liệu xe đã cũ", CockpitModel.quality(listOf(stale)))
        assertEquals("Chưa có nguồn dữ liệu xe đã xác minh", CockpitModel.quality(CockpitModel.gauges(emptySet(), VehicleTelemetry(), false, null, null, null, now)))
    }
}
