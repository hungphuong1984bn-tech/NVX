package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.domain.DataSource
import vn.nvxconnect.navigation.TurnInstruction
import vn.nvxconnect.protocol.*

class TelemetryTftTest {
    private fun v(p: VehicleParam, x: Double, unit: String, t: Long, src: DataSource = DataSource.LIVE_VERIFIED) = DecodedItem.Value(p, x, unit, t, "ev", src)

    @Test fun hubRejectsWhenProtocolNotVerified() {
        val h = TelemetryHub()
        assertEquals(0, h.ingest(listOf(v(VehicleParam.FUEL_LEVEL, 50.0, "%", 1000)), verified = false))
        assertTrue(h.state.value.values.isEmpty())
    }

    @Test fun hubRejectsNonLiveAndNonFinite() {
        val h = TelemetryHub()
        val n = h.ingest(listOf(v(VehicleParam.SPEED_KMH, 40.0, "km/h", 1, DataSource.GPS_ESTIMATE), v(VehicleParam.RPM, Double.NaN, "rpm", 1),
            DecodedItem.Unknown(RawFrame(1, "u", byteArrayOf(1)))), verified = true)
        assertEquals(0, n); assertTrue(h.state.value.values.isEmpty())
    }

    @Test fun hubAcceptsLiveAndResetClears() {
        val h = TelemetryHub()
        assertEquals(2, h.ingest(listOf(v(VehicleParam.FUEL_LEVEL, 62.0, "%", 1000), v(VehicleParam.SPEED_KMH, 41.5, "km/h", 1000)), verified = true))
        assertEquals("62 %", formatLive(h.state.value.values.getValue(VehicleParam.FUEL_LEVEL)))
        assertEquals("41.5 km/h", formatLive(h.state.value.values.getValue(VehicleParam.SPEED_KMH)))
        h.reset(); assertTrue(h.state.value.values.isEmpty())
    }

    @Test fun staleValuesAreUnavailable() {
        val h = TelemetryHub(); h.ingest(listOf(v(VehicleParam.FUEL_LEVEL, 62.0, "%", 1000)), true)
        assertNotNull(h.state.value.fresh(VehicleParam.FUEL_LEVEL, 5000))
        assertNull(h.state.value.fresh(VehicleParam.FUEL_LEVEL, 6001))
        assertNull(h.state.value.fresh(VehicleParam.FUEL_LEVEL, 500)) // thời gian đi lùi: không tin
    }

    @Test fun composerNeverInventsVehicleFields() {
        val f = TftComposer.compose(TftPrefs(), VehicleTelemetry(), null, null, "09:41", 0)
        assertNull(f.field("speed")); assertNull(f.field("fuel"))
        assertEquals("09:41", f.field("clock")?.text)
    }

    @Test fun composerUsesLiveVehicleDataAndMapOnlyHidesIt() {
        val h = TelemetryHub(); h.ingest(listOf(v(VehicleParam.SPEED_KMH, 60.0, "km/h", 1000), v(VehicleParam.FUEL_LEVEL, 70.0, "%", 1000)), true)
        val full = TftComposer.compose(TftPrefs(), h.state.value, "Rẽ trái · sau 300 m", "3,2 km", "09:41", 2000)
        assertEquals(DataSource.LIVE_VERIFIED, full.field("speed")?.source); assertEquals("70 %", full.field("fuel")?.text)
        assertEquals(DataSource.GPS_ESTIMATE, full.field("route")?.source)
        val map = TftComposer.compose(TftPrefs(mapOnly = true), h.state.value, "Rẽ trái", "3,2 km", "09:41", 2000)
        assertEquals(listOf("route"), map.fields.map { it.key })
    }

    @Test fun composerRespectsToggles() {
        val h = TelemetryHub(); h.ingest(listOf(v(VehicleParam.SPEED_KMH, 60.0, "km/h", 1000)), true)
        val f = TftComposer.compose(TftPrefs(speed = false, clock = false), h.state.value, null, null, "09:41", 2000)
        assertTrue(f.isEmpty)
    }

    private class FakeBridge(override val status: TftBridgeStatus) : TftNavigationBridge {
        var sent = 0
        override val limitation = "limit"
        override fun sendManeuver(instruction: TurnInstruction): TftSendResult = TftSendResult.Rejected(limitation)
        override fun sendFrame(frame: TftFrame): TftSendResult { sent++; return TftSendResult.Sent }
    }

    @Test fun pipelineSendsNothingWithoutVerifiedBridge() {
        val b = FakeBridge(TftBridgeStatus.UNSUPPORTED); val p = TftDisplayPipeline { b }
        val frame = TftFrame(listOf(TftField("clock", "Thời gian", "09:41", DataSource.CALCULATED)))
        assertTrue(p.push(frame, true) is TftDisplayPipeline.Outcome.Idle); assertEquals(0, b.sent)
        assertTrue(TftDisplayPipeline { UnsupportedTftBridge }.push(frame, true) is TftDisplayPipeline.Outcome.Idle)
    }

    @Test fun pipelineGatesOnLinkAndEmptyFrameThenSends() {
        val b = FakeBridge(TftBridgeStatus.VERIFIED_READY); val p = TftDisplayPipeline { b }
        val frame = TftFrame(listOf(TftField("clock", "Thời gian", "09:41", DataSource.CALCULATED)))
        assertTrue(p.push(frame, false) is TftDisplayPipeline.Outcome.Idle)
        assertTrue(p.push(TftFrame(emptyList()), true) is TftDisplayPipeline.Outcome.Idle)
        assertEquals(0, b.sent)
        assertTrue(p.push(frame, true) is TftDisplayPipeline.Outcome.Sent); assertEquals(1, b.sent)
    }

    @Test fun defaultBridgeRejectsFrames() {
        assertTrue(UnsupportedTftBridge.sendFrame(TftFrame(emptyList())) is TftSendResult.Rejected)
    }
}
