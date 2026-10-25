package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.pro.*

class ChartMathTest {
    private fun s(vararg p: Pair<Long, Double>) = p.map { Sample(it.first, it.second) }

    @Test fun statsOnRealSamplesOnly() {
        val st = ChartMath.stats(s(0L to 1.0, 1000L to 3.0, 2000L to 2.0))
        assertEquals(3, st.count); assertEquals(1.0, st.min!!, 1e-9); assertEquals(3.0, st.max!!, 1e-9); assertEquals(2.0, st.mean!!, 1e-9)
        assertEquals(0L, st.firstMs); assertEquals(2000L, st.lastMs)
    }
    @Test fun emptyStatsHaveNullsNotZeros() {
        val st = ChartMath.stats(emptyList()); assertEquals(0, st.count); assertNull(st.min); assertNull(st.max); assertNull(st.mean)
    }
    @Test fun nonFiniteSamplesIgnoredInStats() {
        val st = ChartMath.stats(s(0L to 1.0, 1L to Double.NaN, 2L to Double.POSITIVE_INFINITY, 3L to 3.0))
        assertEquals(2, st.count); assertEquals(2.0, st.mean!!, 1e-9)
    }
    @Test fun windowIsInclusiveAndOrdered() {
        val w = ChartMath.window(s(0L to 0.0, 10L to 1.0, 20L to 2.0, 30L to 3.0), 10, 20)
        assertEquals(listOf(10L, 20L), w.map { it.timeMs })
    }
    @Test fun gapSplitsSegmentsAndIsNeverBridged() {
        val seg = ChartMath.segments(s(0L to 1.0, 500L to 2.0, 5000L to 3.0, 5500L to 4.0), gapMs = 2000)
        assertEquals(2, seg.size); assertEquals(2, seg[0].samples.size); assertEquals(2, seg[1].samples.size)
        val (n, total) = ChartMath.gaps(s(0L to 1.0, 500L to 2.0, 5000L to 3.0), 2000)
        assertEquals(1, n); assertEquals(4500L, total)
    }
    @Test fun noGapWhenWithinThreshold() {
        assertEquals(1, ChartMath.segments(s(0L to 1.0, 1000L to 2.0, 2000L to 3.0), 2000).size)
        assertEquals(0 to 0L, ChartMath.gaps(s(0L to 1.0, 1000L to 2.0), 2000))
    }
    @Test fun badSampleBreaksTheLineInsteadOfBeingInterpolated() {
        val seg = ChartMath.segments(s(0L to 1.0, 100L to Double.NaN, 200L to 3.0), 2000)
        assertEquals(2, seg.size); assertEquals(1, seg[0].samples.size); assertEquals(1, seg[1].samples.size)
    }
    @Test fun segmentsHandleEmpty() { assertTrue(ChartMath.segments(emptyList(), 1000).isEmpty()); assertEquals(0 to 0L, ChartMath.gaps(emptyList(), 1000)) }

    @Test fun downsampleKeepsPeaksAndIsSubsetInTimeOrder() {
        val data = (0 until 1000).map { Sample(it * 10L, if (it == 500) 999.0 else if (it == 700) -999.0 else (it % 7).toDouble()) }
        val d = ChartMath.downsample(data, 100)
        assertTrue(d.size <= 100); assertTrue(d.any { it.value == 999.0 }); assertTrue(d.any { it.value == -999.0 })
        assertTrue(d.all { x -> data.any { it === x } })
        assertEquals(d.sortedBy { it.timeMs }, d)
    }
    @Test fun downsampleLeavesSmallInputUntouched() {
        val data = s(0L to 1.0, 1L to 2.0); assertSame(data, ChartMath.downsample(data, 100))
    }
    @Test fun nearestRespectsTolerance() {
        val d = s(0L to 1.0, 1000L to 2.0)
        assertEquals(2.0, ChartMath.nearest(d, 900, 200)!!.value, 1e-9)
        assertNull(ChartMath.nearest(d, 5000, 200)); assertNull(ChartMath.nearest(emptyList(), 0, 100))
    }
    @Test fun selectionAcceptsEitherOrder() {
        val d = s(0L to 1.0, 10L to 5.0, 20L to 9.0, 30L to 100.0)
        val a = ChartMath.selectionStats(d, 20, 0); val b = ChartMath.selectionStats(d, 0, 20)
        assertEquals(a, b); assertEquals(3, a.count); assertEquals(9.0, a.max!!, 1e-9)
    }

    @Test fun storeRejectsNonFiniteAndNonMonotonic() {
        val st = SeriesStore(minIntervalMs = 0)
        assertFalse(st.add("a", 0, Double.NaN)); assertTrue(st.add("a", 10, 1.0)); assertFalse(st.add("a", 10, 2.0)); assertFalse(st.add("a", 5, 2.0))
        assertEquals(1, st.series("a").size)
    }
    @Test fun storeThrottlesWritesAndCountsSkips() {
        val st = SeriesStore(minIntervalMs = 250)
        assertTrue(st.add("a", 0, 1.0)); assertFalse(st.add("a", 100, 2.0)); assertTrue(st.add("a", 300, 3.0))
        assertEquals(1L, st.skipped("a")); assertEquals(2, st.series("a").size)
    }
    @Test fun storeCapsAndReportsDroppedHonestly() {
        val st = SeriesStore(maxPerSeries = 5, minIntervalMs = 0)
        for (i in 0 until 12) st.add("a", i.toLong() * 10, i.toDouble())
        assertEquals(5, st.series("a").size); assertEquals(7L, st.dropped("a")); assertEquals(7.0, st.series("a").first().value, 1e-9)
    }
    @Test fun storeClear() {
        val st = SeriesStore(minIntervalMs = 0); st.add("a", 1, 1.0); st.add("b", 1, 1.0)
        st.clear("a"); assertTrue(st.series("a").isEmpty()); assertEquals(listOf("b"), st.keys()); st.clear(); assertTrue(st.keys().isEmpty())
    }
    @Test fun windowChoicesIncludeAll() { assertTrue(0L in ChartMath.WINDOWS_MS); assertEquals("Tất cả", ChartMath.windowLabel(0)) }
}

class SeriesCsvTest {
    @Test fun roundTrip() {
        val m = mapOf("a" to listOf(Sample(1, 1.5), Sample(2, 2.5)), "b" to listOf(Sample(5, -1.0)))
        val r = SeriesCsv.decode(SeriesCsv.encode(m)); assertEquals(m, r.series); assertTrue(r.warnings.isEmpty())
    }
    @Test fun badRowsAreDroppedWithWarningNotInvented() {
        val r = SeriesCsv.decode("series,time_ms,value\na,1,2\na,x,3\na,2,NaN\nb,3\n,4,5\na,0,9")
        assertEquals(listOf(Sample(0, 9.0), Sample(1, 2.0)), r.series["a"]); assertEquals(1, r.series.size); assertEquals(4, r.warnings.size)
    }
    @Test fun nonFiniteNotEncoded() { assertFalse(SeriesCsv.encode(mapOf("a" to listOf(Sample(1, Double.NaN)))).contains("NaN")) }
    @Test fun emptyInput() { assertTrue(SeriesCsv.decode("").series.isEmpty()) }
}
