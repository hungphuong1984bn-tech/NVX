package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.domain.GeoPoint
import vn.nvxconnect.pro.*

class RideToolsTest {
    private val now = java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.OCTOBER, 25, 15, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
    private fun day(daysAgo: Int, hour: Int = 9) = RideTools.startOfDay(now) - daysAgo * 86_400_000L + hour * 3_600_000L
    private fun ride(id: Long, daysAgo: Int, km: Double, vmax: Double = 50.0, demo: Boolean = false) =
        RideSummary(id, day(daysAgo), day(daysAgo) + 1_800_000, km * 1000, vmax, demo)
    private val rides = listOf(ride(1, 0, 5.0, 60.0), ride(2, 3, 12.0, 80.0), ride(3, 10, 30.0, 70.0), ride(4, 40, 2.0, 40.0), ride(5, 1, 99.0, demo = true))

    @Test fun rangesFilterByDate() {
        fun ids(r: RideRange) = RideTools.filter(rides, "", r, RideSort.NEWEST, now, false).map { it.id }
        assertEquals(listOf(1L), ids(RideRange.TODAY)); assertEquals(listOf(1L, 2L), ids(RideRange.WEEK))
        assertEquals(listOf(1L, 2L, 3L), ids(RideRange.MONTH)); assertEquals(listOf(1L, 2L, 3L, 4L), ids(RideRange.ALL))
    }
    @Test fun demoDataStaysSeparate() {
        assertTrue(RideTools.filter(rides, "", RideRange.ALL, RideSort.NEWEST, now, false).none { it.isDemo })
        assertEquals(listOf(5L), RideTools.filter(rides, "", RideRange.ALL, RideSort.NEWEST, now, true).map { it.id })
    }
    @Test fun sorting() {
        assertEquals(3L, RideTools.filter(rides, "", RideRange.ALL, RideSort.LONGEST, now, false).first().id)
        assertEquals(2L, RideTools.filter(rides, "", RideRange.ALL, RideSort.FASTEST, now, false).first().id)
    }
    @Test fun searchByMinimumKm() {
        assertEquals(setOf(2L, 3L), RideTools.filter(rides, ">10", RideRange.ALL, RideSort.NEWEST, now, false).map { it.id }.toSet())
        assertEquals(setOf(2L, 3L), RideTools.filter(rides, "> 10,0", RideRange.ALL, RideSort.NEWEST, now, false).map { it.id }.toSet())
        assertTrue(RideTools.filter(rides, ">abc", RideRange.ALL, RideSort.NEWEST, now, false).isEmpty())
    }
    @Test fun searchByDateText() {
        val key = RideTools.dateText(day(3)).substring(0, 5)
        assertTrue(RideTools.filter(rides, key, RideRange.ALL, RideSort.NEWEST, now, false).any { it.id == 2L })
        assertTrue(RideTools.filter(rides, "zzz", RideRange.ALL, RideSort.NEWEST, now, false).isEmpty())
    }
    private fun p(t: Long, v: Float) = GeoPoint(10.0, 106.0, v, 5f, t)
    @Test fun stopTimeCountsOnlyStillStretchesWithinGapLimit() {
        val pts = listOf(p(0, 5f), p(1000, 0.1f), p(2000, 0.2f), p(5000, 0.0f), p(6000, 6f), p(7000, 0.0f), p(8000, 0.0f))
        assertEquals(1000L + 3000L + 1000L, RideTools.stopTimeMs(pts))
    }
    @Test fun gpsOutageIsNotCountedAsStop() {
        assertEquals(0L, RideTools.stopTimeMs(listOf(p(0, 0f), p(120_000, 0f))))
        assertEquals(0L, RideTools.stopTimeMs(emptyList())); assertEquals(0L, RideTools.stopTimeMs(listOf(p(0, 0f))))
    }
    @Test fun costFromUserEnteredNumbersAndLabeledLowConfidence() {
        val c = RideTools.cost(100.0, 2.0, 25_000)!!
        assertEquals(2.0, c.liters, 1e-9); assertEquals(50_000L, c.vnd)
        assertTrue(c.source.contains("do bạn nhập")); assertTrue(c.confidence.startsWith("THẤP"))
    }
    @Test fun costIsNullWithoutConfigurationNotZero() {
        assertNull(RideTools.cost(10.0, 0.0, 25_000)); assertNull(RideTools.cost(10.0, 2.0, 0)); assertNull(RideTools.cost(Double.NaN, 2.0, 25_000)); assertNull(RideTools.cost(-1.0, 2.0, 25_000))
    }
    @Test fun vndFormat() { assertTrue(RideTools.vnd(1_234_567).endsWith(" đ")); assertTrue(RideTools.vnd(1_234_567).replace(".", "").replace(",", "").startsWith("1234567")) }
}
