package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.domain.DataSource
import vn.nvxconnect.protocol.*
import vn.nvxconnect.vehicledata.*

class VehicleDataRepositoryTest {
    private var now = 1_000_000L
    private val hub = TelemetryHub()
    private val repo = VehicleDataRepository(hub, { now }, maxLog = 5)

    private fun v(p: VehicleParam, x: Double, unit: String, t: Long = now, src: DataSource = DataSource.LIVE_VERIFIED) =
        DecodedItem.Value(p, x, unit, t, "ev-test", src)

    private fun snap(link: Boolean = true, supported: Set<VehicleParam>? = null, at: Long = now) = repo.snapshot(link, supported, at).associateBy { it.param }

    @Test fun nothingReceivedIsUnavailableNotZero() {
        val s = snap()
        VehicleParam.entries.forEach { assertEquals(DataStatus.UNAVAILABLE, s[it]!!.status); assertNull(s[it]!!.value); assertFalse(s[it]!!.displayable) }
    }

    @Test fun validFreshValueIsVerifiedWithSourceUnitAndAge() {
        assertEquals(1, repo.ingest(listOf(v(VehicleParam.RPM, 3500.0, "rpm", now - 800)), linkVerified = true))
        val t = snap()[VehicleParam.RPM]!!
        assertEquals(DataStatus.VERIFIED, t.status); assertEquals(3500.0, t.value!!, 0.0); assertEquals("rpm", t.unit)
        assertEquals(DataSource.LIVE_VERIFIED, t.source); assertEquals(800L, t.ageMs); assertEquals("ev-test", t.evidenceId); assertTrue(t.displayable)
        assertNotNull(t.range)
    }

    @Test fun outOfRangeValueIsRejectedAndNeverReachesHub() {
        assertEquals(0, repo.ingest(listOf(v(VehicleParam.RPM, -50.0, "rpm"), v(VehicleParam.COOLANT_TEMP_C, 900.0, "°C")), true))
        assertTrue(hub.state.value.values.isEmpty())
        val s = snap()
        assertEquals(DataStatus.UNVERIFIED, s[VehicleParam.RPM]!!.status); assertNull(s[VehicleParam.RPM]!!.value)
        assertTrue(s[VehicleParam.RPM]!!.note.contains("Ngoài khoảng"))
        assertEquals(DataStatus.UNVERIFIED, s[VehicleParam.COOLANT_TEMP_C]!!.status)
    }

    @Test fun nanAndInfinityAreRejected() {
        assertEquals(0, repo.ingest(listOf(v(VehicleParam.SPEED_KMH, Double.NaN, "km/h"), v(VehicleParam.BATTERY_V, Double.POSITIVE_INFINITY, "V")), true))
        assertEquals(DataStatus.UNVERIFIED, snap()[VehicleParam.SPEED_KMH]!!.status)
    }

    @Test fun unverifiedLinkAndNonLiveSourcesAreRejected() {
        assertEquals(0, repo.ingest(listOf(v(VehicleParam.RPM, 1000.0, "rpm")), linkVerified = false))
        assertEquals(0, repo.ingest(listOf(v(VehicleParam.SPEED_KMH, 40.0, "km/h", src = DataSource.GPS_ESTIMATE)), true))
        assertEquals(0, repo.ingest(listOf(v(VehicleParam.SPEED_KMH, 40.0, "km/h", src = DataSource.DEMO)), true))
        assertTrue(hub.state.value.values.isEmpty())
        assertTrue(repo.sourceLog().all { !it.accepted })
    }

    @Test fun gpsSpeedCanNeverBecomeVehicleSpeed() {
        repo.ingest(listOf(v(VehicleParam.SPEED_KMH, 55.0, "km/h", src = DataSource.GPS_ESTIMATE)), true)
        val t = snap()[VehicleParam.SPEED_KMH]!!
        assertFalse(t.displayable); assertNotEquals(DataStatus.VERIFIED, t.status)
    }

    @Test fun staleAfterFiveSecondsAndNotDisplayable() {
        repo.ingest(listOf(v(VehicleParam.BATTERY_V, 12.6, "V")), true)
        assertEquals(DataStatus.VERIFIED, snap(at = now + 5_000)[VehicleParam.BATTERY_V]!!.status)
        val late = snap(at = now + 5_001)[VehicleParam.BATTERY_V]!!
        assertEquals(DataStatus.STALE, late.status); assertFalse(late.displayable); assertEquals(12.6, late.value!!, 0.0)
        assertTrue(late.note.contains("cũ"))
    }

    @Test fun losingLinkVerificationMakesValueStaleEvenIfRecent() {
        repo.ingest(listOf(v(VehicleParam.RPM, 2000.0, "rpm")), true)
        val t = snap(link = false)[VehicleParam.RPM]!!
        assertEquals(DataStatus.STALE, t.status); assertFalse(t.displayable)
    }

    @Test fun futureTimestampIsNotFresh() {
        repo.ingest(listOf(v(VehicleParam.RPM, 2000.0, "rpm", t = now + 60_000)), true)
        assertNotEquals(DataStatus.VERIFIED, snap()[VehicleParam.RPM]!!.status)
    }

    @Test fun unsupportedComesOnlyFromAVerifiedAdapterDeclaration() {
        val s = snap(supported = setOf(VehicleParam.RPM))
        assertEquals(DataStatus.UNSUPPORTED, s[VehicleParam.SPEED_KMH]!!.status)
        assertEquals(DataStatus.UNAVAILABLE, s[VehicleParam.RPM]!!.status)
        assertEquals(DataStatus.UNAVAILABLE, snap(supported = null)[VehicleParam.SPEED_KMH]!!.status)
    }

    @Test fun fuelRangeDependsOnUnitAndUnknownUnitIsRejected() {
        assertEquals(1, repo.ingest(listOf(v(VehicleParam.FUEL_LEVEL, 80.0, "%")), true))
        assertEquals(0, repo.ingest(listOf(v(VehicleParam.FUEL_LEVEL, 80.0, "L")), true)) // 80 L vượt bình xăng
        assertEquals(0, repo.ingest(listOf(v(VehicleParam.FUEL_LEVEL, 3.0, "gallon")), true))
        assertTrue(repo.sourceLog().last().reason.contains("Đơn vị"))
    }

    @Test fun unknownFramesAreCountedButNeverBecomeValues() {
        val f = RawFrame(now, "uuid", byteArrayOf(1, 2, 3))
        assertEquals(0, repo.ingest(listOf(DecodedItem.Unknown(f)), true))
        assertEquals(1L, repo.stats().third); assertTrue(hub.state.value.values.isEmpty())
    }

    @Test fun sourceLogIsBoundedAndTraceable() {
        repeat(8) { repo.ingest(listOf(v(VehicleParam.RPM, 1000.0 + it, "rpm")), true) }
        val log = repo.sourceLog()
        assertEquals(5, log.size); assertEquals(1007.0, log.last().value, 0.0); assertTrue(log.all { it.evidenceId == "ev-test" && it.accepted })
        assertEquals(8L, repo.stats().first)
    }

    @Test fun acceptedValueClearsEarlierRejection() {
        repo.ingest(listOf(v(VehicleParam.RPM, -1.0, "rpm")), true)
        assertEquals(DataStatus.UNVERIFIED, snap()[VehicleParam.RPM]!!.status)
        repo.ingest(listOf(v(VehicleParam.RPM, 1500.0, "rpm")), true)
        assertEquals(DataStatus.VERIFIED, snap()[VehicleParam.RPM]!!.status)
    }

    @Test fun csvHasHeaderAndIsFormulaSafe() {
        repo.ingest(listOf(DecodedItem.Value(VehicleParam.RPM, 1000.0, "=cmd", now, "+evil", DataSource.LIVE_VERIFIED)), true)
        val csv = repo.sourceLogCsv()
        assertTrue(csv.startsWith("time_ms,param,value,unit,evidence_id,accepted,reason\n"))
        assertFalse(csv.contains(",=cmd,")); assertFalse(csv.contains(",+evil,"))
    }

    @Test fun clearResetsEverything() {
        repo.ingest(listOf(v(VehicleParam.RPM, 1000.0, "rpm")), true)
        repo.clear()
        assertTrue(hub.state.value.values.isEmpty()); assertTrue(repo.sourceLog().isEmpty()); assertEquals(DataStatus.UNAVAILABLE, snap()[VehicleParam.RPM]!!.status)
    }
}
