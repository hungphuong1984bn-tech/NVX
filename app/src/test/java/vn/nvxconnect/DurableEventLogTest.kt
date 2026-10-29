package vn.nvxconnect

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import vn.nvxconnect.telemetry.*
import java.io.File
import java.nio.file.Files

class DurableEventLogTest {
    private lateinit var dir: File
    @Before fun setUp() { dir = Files.createTempDirectory("evlog").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun ev(i: Int, kind: String = EventKinds.DATA_FIRST, outcome: EventOutcome = EventOutcome.SUCCESS, detail: String = "d$i") =
        ConnectionEvent(1_700_000_000_000L + i, i * 1000L, "s1", 1, "dev-abc", kind, outcome, null, detail, mapOf("k" to "v$i"))
    private fun log(maxSeg: Long = 256 * 1024, maxTotal: Long = 4L * 1024 * 1024, free: () -> Long = { Long.MAX_VALUE }, maxQ: Int = 2000) =
        DurableEventLog(dir, maxSeg, maxTotal, 1024, maxQ, free, java.util.concurrent.Executors.newSingleThreadExecutor())

    @Test fun roundTripPreservesEveryFieldIncludingNastyText() {
        val e = ev(1, detail = "dòng \"nháy\" \\ xuống\ndòng\ttab ✓ unicode").copy(gattStatus = 19, outcome = EventOutcome.FAILURE, deviceKey = null)
        val d = DurableEventLog.decode(DurableEventLog.encode(e))!!
        assertEquals(e, d)
    }
    @Test fun utcTimestampHasMillisecondPrecision() {
        assertEquals("2023-11-14T22:13:20.123Z", DurableEventLog.iso(1_700_000_000_123L))
        assertTrue(DurableEventLog.encode(ev(0)).contains("\"utc\":\"2023-11-14T22:13:20.000Z\""))
    }
    @Test fun appendThenReadAllReturnsEventsInOrderWithNoLoss() {
        val l = log(); repeat(500) { l.append(ev(it)) }; l.flushBlocking()
        val (events, bad) = l.readAll()
        assertEquals(0, bad); assertEquals(500, events.size); assertEquals((0 until 500).toList(), events.map { it.monoNs.toInt() / 1000 })
        val s = l.stats(); assertEquals(500, s.accepted); assertEquals(500, s.persisted); assertEquals(0, s.droppedQueueFull + s.droppedStorage)
        l.close()
    }
    @Test fun survivesRestartAndAppendsToSameSegment() {
        val a = log(); repeat(5) { a.append(ev(it)) }; a.close()
        val b = log(); repeat(5) { b.append(ev(5 + it)) }; b.flushBlocking()
        assertEquals(10, b.readAll().first.size); b.close()
    }
    @Test fun tornLastLineIsDetectedCountedAndRepairedOnReopen() {
        val a = log(); repeat(3) { a.append(ev(it)) }; a.close()
        val f = dir.listFiles()!!.single(); f.appendBytes("E1,deadbeef,{\"v\":1,\"utc".toByteArray())      // ghi dở do tiến trình chết
        val b = log()
        assertEquals(1L, b.stats().corruptLinesOnOpen); assertTrue(b.stats().warnings.any { it.contains("cắt") })
        b.append(ev(99)); b.flushBlocking()
        val (events, bad) = b.readAll(); assertEquals(0, bad); assertEquals(4, events.size); assertEquals(99, events.last().monoNs.toInt() / 1000); b.close()
    }
    @Test fun rotationDeletesOldestSegmentsAndCountsLostEvents() {
        val l = log(maxSeg = 2_000, maxTotal = 8_000); repeat(300) { l.append(ev(it)) }; l.flushBlocking()
        val s = l.stats(); val (events, _) = l.readAll()
        assertTrue("phải đã xoay vòng", s.segmentsDeleted > 0); assertTrue(s.totalBytes <= 8_000 + 2_000)
        assertEquals("không mất âm thầm: còn trên đĩa + bị xóa = đã ghi", 300L, events.size + s.eventsInDeletedSegments)
        assertTrue(s.warnings.any { it.startsWith("Xoay vòng") }); assertEquals(299, events.last().monoNs.toInt() / 1000)
        l.close()
    }
    @Test fun lowStorageDropsAreCountedNotSilent() {
        var free = Long.MAX_VALUE; val l = log(free = { free })
        l.append(ev(0)); l.flushBlocking(); free = 10; l.append(ev(1)); l.append(ev(2)); l.flushBlocking()
        val s = l.stats(); assertEquals(EventLogHealth.LOW_STORAGE, s.health); assertEquals(2, s.droppedStorage); assertEquals(1, s.persisted)
        assertTrue(s.warnings.any { it.startsWith("Dung lượng") })
        free = Long.MAX_VALUE; l.append(ev(3)); l.flushBlocking(); assertEquals(EventLogHealth.OK, l.stats().health); l.close()
    }
    @Test fun queueOverflowIsCounted() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val blocker = java.util.concurrent.Executors.newSingleThreadExecutor(); blocker.execute { gate.await() }          // chặn luồng ghi
        val l = DurableEventLog(dir, 256 * 1024, 4L * 1024 * 1024, 1024, 10, { Long.MAX_VALUE }, blocker)
        repeat(25) { l.append(ev(it)) }
        assertEquals(15, l.stats().droppedQueueFull); assertTrue(l.stats().warnings.any { it.startsWith("Hàng đợi") })
        gate.countDown(); l.flushBlocking(); assertEquals(10, l.stats().persisted); blocker.shutdown()
    }
    @Test fun appendNeverBlocksCallerEvenWhenWriterIsStuck() {
        val gate = java.util.concurrent.CountDownLatch(1); val blocker = java.util.concurrent.Executors.newSingleThreadExecutor(); blocker.execute { gate.await() }
        val l = DurableEventLog(dir, 256 * 1024, 4L * 1024 * 1024, 1024, 100, { Long.MAX_VALUE }, blocker)
        val t0 = System.nanoTime(); repeat(50) { l.append(ev(it)) }; assertTrue((System.nanoTime() - t0) / 1_000_000 < 500)
        gate.countDown(); l.flushBlocking(); blocker.shutdown()
    }
    @Test fun exportCarriesMetadataAndCountersAndNoRawAddress() {
        val l = log(); val mac = "AA:BB:CC:11:22:33"
        l.append(ev(0).copy(deviceKey = DurableEventLog.deviceKey(mac, "salt-1"))); l.append(ev(1, kind = EventKinds.SUBSCRIBE, outcome = EventOutcome.NOT_TESTED))
        val out = l.exportJsonLines(1_700_000_001_000L); val lines = out.trim().lines()
        assertTrue(lines[0].contains("\"meta\":true") && lines[0].contains("\"droppedQueueFull\":0") && lines[0].contains("\"events\":2"))
        assertEquals(3, lines.size); assertFalse(out.contains(mac)); assertFalse(out.uppercase().contains("AA:BB:CC"))
        assertTrue(out.contains("NOT_TESTED")); l.close()
    }
    @Test fun deviceKeyIsStableSaltedAndNotReversible() {
        val a = DurableEventLog.deviceKey("aa:bb:cc:11:22:33", "s"); assertEquals(a, DurableEventLog.deviceKey("AA:BB:CC:11:22:33", "s"))
        assertNotEquals(a, DurableEventLog.deviceKey("AA:BB:CC:11:22:33", "other")); assertNotEquals(a, DurableEventLog.deviceKey("AA:BB:CC:11:22:34", "s"))
        assertTrue(a.startsWith("dev-") && !a.contains(":"))
    }
    @Test fun recorderFillsSessionAndDeviceButNotAddress() {
        val l = log(); val r = ConnectionEventRecorder(l, { "sess-9" }, { "dev-xyz" })
        r.onEvent(ev(0).copy(sessionId = "", deviceKey = null)); l.flushBlocking()
        val e = l.readAll().first.single(); assertEquals("sess-9", e.sessionId); assertEquals("dev-xyz", e.deviceKey); l.close()
    }
    @Test fun corruptedMiddleLineIsSkippedAndReportedNotFatal() {
        val l = log(); repeat(3) { l.append(ev(it)) }; l.flushBlocking()
        val f = dir.listFiles()!!.single(); val lines = f.readLines().toMutableList(); lines[1] = lines[1].replace("d1", "dX"); f.writeText(lines.joinToString("\n") + "\n")
        val (events, bad) = l.readAll(); assertEquals(2, events.size); assertEquals(1, bad); l.close()
    }
    @Test fun clearRemovesEverything() { val l = log(); l.append(ev(0)); l.flushBlocking(); l.clear(); assertTrue(l.readAll().first.isEmpty()); assertTrue(dir.listFiles()!!.isEmpty()); l.close() }
}
