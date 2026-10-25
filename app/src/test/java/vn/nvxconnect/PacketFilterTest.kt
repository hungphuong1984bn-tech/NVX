package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.pro.*
import vn.nvxconnect.telemetry.PayloadFlag
import vn.nvxconnect.telemetry.PayloadRecord
import vn.nvxconnect.telemetry.PayloadSource

class PacketFilterTest {
    private fun r(seq: Long, hex: String, char: String? = "6e400003-b5a3-f393-e0a9-e50e24dcca9e", src: PayloadSource = PayloadSource.NOTIFICATION,
                  flags: Set<PayloadFlag> = emptySet(), session: String = "s1") =
        PayloadRecord(seq, 1000 + seq, session, "dev", src, "6e400001-b5a3-f393-e0a9-e50e24dcca9e", char, null, hex, flags)
    private val all = listOf(
        r(1, "6B 01 02"), r(2, "6C 0A 0B 0C 0D"), r(3, "6B 01 03", char = "00002a00-0000-1000-8000-00805f9b34fb", src = PayloadSource.READ),
        r(4, "FF FF FF FF", flags = setOf(PayloadFlag.UNIFORM)), r(5, "6B 01 02", flags = setOf(PayloadFlag.DUPLICATE), session = "s2"))

    @Test fun emptyQueryReturnsAllNewestFirst() {
        val q = PacketQuery(); assertFalse(q.active)
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), PacketFilter.apply(all, q).map { it.seq })
    }
    @Test fun hexSearchIgnoresSpacingAndCase() {
        assertEquals(setOf(1L, 3L, 5L), PacketFilter.apply(all, PacketQuery(text = "6b01")).map { it.seq }.toSet())
        assertEquals(setOf(1L, 5L), PacketFilter.apply(all, PacketQuery(text = "6B 01 02")).map { it.seq }.toSet())
    }
    @Test fun uuidFilterMatchesCharacteristicSubstring() {
        assertEquals(listOf(3L), PacketFilter.apply(all, PacketQuery(uuid = "2A00")).map { it.seq })
        assertEquals(4, PacketFilter.apply(all, PacketQuery(uuid = "6e400003")).size)
    }
    @Test fun lengthRangeFilter() {
        assertEquals(listOf(2L), PacketFilter.apply(all, PacketQuery(minLen = 5)).map { it.seq })
        assertEquals(setOf(1L, 3L, 5L), PacketFilter.apply(all, PacketQuery(minLen = 3, maxLen = 3)).map { it.seq }.toSet())
        assertEquals(listOf(3L), PacketFilter.apply(all, PacketQuery(minLen = 3, maxLen = 3, source = PayloadSource.READ)).map { it.seq })
        assertEquals(setOf(1L, 3L, 5L), PacketFilter.apply(all, PacketQuery(maxLen = 3)).map { it.seq }.toSet())
    }
    @Test fun statusFilters() {
        assertEquals(listOf(4L), PacketFilter.apply(all, PacketQuery(flag = PayloadFlag.UNIFORM)).map { it.seq })
        assertEquals(listOf(3L), PacketFilter.apply(all, PacketQuery(source = PayloadSource.READ)).map { it.seq })
        assertEquals(listOf(5L), PacketFilter.apply(all, PacketQuery(sessionId = "s2")).map { it.seq })
    }
    @Test fun sortingByLength() {
        assertEquals(2L, PacketFilter.apply(all, PacketQuery(sort = PacketSort.LONGEST)).first().seq)
        assertEquals(5L, PacketFilter.apply(all, PacketQuery(sort = PacketSort.SHORTEST)).first().seq)
        assertEquals(1L, PacketFilter.apply(all, PacketQuery(sort = PacketSort.OLDEST)).first().seq)
    }
    @Test fun limitTruncatesAfterSort() { assertEquals(listOf(5L, 4L), PacketFilter.apply(all, PacketQuery(), limit = 2).map { it.seq }) }
    @Test fun combinedFiltersAreAnded() { assertTrue(PacketFilter.apply(all, PacketQuery(text = "6b", flag = PayloadFlag.UNIFORM)).isEmpty()) }
    @Test fun noMatchGivesEmptyNotAll() { assertTrue(PacketFilter.apply(all, PacketQuery(text = "deadbeef")).isEmpty()) }
    @Test fun histogramCountsLengths() { assertEquals(listOf(3 to 3, 4 to 1, 5 to 1), PacketFilter.lengthHistogram(all)) }
}
