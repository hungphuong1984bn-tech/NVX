package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.bluetooth.*
import vn.nvxconnect.protocol.FrameAnalyzer
import vn.nvxconnect.protocol.RawFrame

class BleUpgradeTest {
    // ---- GattStatus ----
    @Test fun status133IsRetriableButAuthIsNot() {
        assertTrue(GattStatus.retriable(133)); assertTrue(GattStatus.retriable(8))
        assertFalse(GattStatus.retriable(5)); assertFalse(GattStatus.retriable(15))
        assertTrue(GattStatus.needsPairing(15))
        assertTrue(GattStatus.describe(133).contains("133"))
    }

    // ---- KnownUuids ----
    @Test fun labelsStandardAndNordicUart() {
        assertEquals("Generic Access", KnownUuids.label("00001800-0000-1000-8000-00805f9b34fb"))
        assertEquals("Generic Access", KnownUuids.label("00001800-0000-1000-8000-00805F9B34FB"))
        assertTrue(KnownUuids.label("6e400001-b5a3-f393-e0a9-e50e24dcca9e")!!.contains("Nordic UART"))
        assertTrue(KnownUuids.label(KnownUuids.NUS_TX)!!.contains("notify"))
        assertNull(KnownUuids.label("12345678-0000-1000-8000-00805f9b34fb"))
        assertNull(KnownUuids.label("zzz"))
    }

    // ---- DeviceTable ----
    private fun adv(addr: String, rssi: Int, t: Long, name: String? = null, conn: Boolean? = true, svc: List<String> = emptyList()) =
        AdvertRecord(addr, name, rssi, svc, conn, t)

    @Test fun deviceTableSmoothsMergesAndMarksStale() {
        val t = DeviceTable(alpha = 0.5, staleMs = 1000)
        t.update(adv("A", -40, 0, name = "YCC***"))
        val e = t.update(adv("A", -60, 500, name = null, svc = listOf("u1")))
        assertEquals(-50.0, e.smoothedRssi, 1e-9)
        assertEquals("YCC***", e.record.name)          // giữ tên đã biết
        assertEquals(listOf("u1"), e.record.advertisedServiceUuids)
        assertEquals(2, e.count)
        t.update(adv("B", -90, 0))
        val rows = t.rows(nowMs = 1200)                  // A thấy lúc 500 -> còn; B lúc 0 -> mất tín hiệu
        assertEquals("A", rows[0].entry.record.address); assertFalse(rows[0].stale)
        assertTrue(rows[1].stale)
    }

    // ---- GattOpQueue ----
    @Test fun queueRunsOneAtATimeInOrder() {
        val log = mutableListOf<String>()
        val q = GattOpQueue()
        q.enqueue("mtu") { log += "mtu"; true }
        q.enqueue("discover") { log += "discover"; true }
        assertEquals(listOf("mtu"), log)
        q.complete("discover")                           // sai tên: không nhả
        assertEquals(listOf("mtu"), log)
        q.complete("mtu")
        assertEquals(listOf("mtu", "discover"), log)
        q.complete(); assertEquals(0, q.size())
    }
    @Test fun queueSkipsOpsThatFailToStart() {
        val failed = mutableListOf<String>(); val ran = mutableListOf<String>()
        val q = GattOpQueue { failed += it }
        q.enqueue("a") { false }
        q.enqueue("b") { ran += "b"; true }
        assertEquals(listOf("a"), failed); assertEquals(listOf("b"), ran)
        q.clear(); assertNull(q.runningName())
    }

    // ---- FrameAnalyzer ----
    private fun f(t: Long, vararg b: Int, c: String = "c1") = RawFrame(t, c, ByteArray(b.size) { b[it].toByte() })

    @Test fun analyzerFindsConstantAndVaryingBytesAndInterval() {
        val fs = (0 until 6).map { f(it * 100L, 0xAA, 0x55, it, 0x10) }
        val r = FrameAnalyzer.analyze(fs).single()
        assertEquals(4, r.dominantLength); assertEquals(100L, r.medianIntervalMs)
        assertEquals(0xAA, r.positions[0].constant); assertNull(r.positions[2].constant)
        assertEquals("AA 55 ?? 10", FrameAnalyzer.template(r))
    }
    @Test fun analyzerDetectsSumChecksumOnlyWhenAllFramesMatch() {
        fun withSum(i: Int): RawFrame { val a = 0xAA; val b = i; return f(i * 10L, a, b, (a + b) and 0xFF) }
        val good = FrameAnalyzer.analyze((1..6).map { withSum(it) }).single()
        assertTrue(good.checksumHypotheses.any { it.startsWith("SUM8(0..n-2)") })
        val bad = FrameAnalyzer.analyze((1..6).map { withSum(it) } + f(999, 0xAA, 9, 0x00)).single()
        assertTrue(bad.checksumHypotheses.none { it.startsWith("SUM8(0..n-2)") })
    }
    @Test fun analyzerMakesNoClaimWithTooFewFramesOrConstantLastByte() {
        assertTrue(FrameAnalyzer.analyze((1..3).map { f(it * 1L, 1, 2, 3) }).single().checksumHypotheses.isEmpty())
        assertTrue(FrameAnalyzer.analyze((1..6).map { f(it * 1L, 1, it, 7) }).single().checksumHypotheses.isEmpty())
    }
    @Test fun analyzerMasksLongConstantAscii() {
        val ascii = "ABCDEFGH".map { it.code }.toIntArray()
        val fs = (0 until 3).map { f(it * 1L, *ascii, it) }
        val t = FrameAnalyzer.template(FrameAnalyzer.analyze(fs).single())
        assertEquals("** ** ** ** ** ** ** ** ??", t)
        assertFalse(FrameAnalyzer.render(FrameAnalyzer.analyze(fs)).contains("41 42 43"))
    }
    @Test fun analyzerSeparatesCharacteristics() {
        val fs = listOf(f(0, 1, 2, c = "a"), f(1, 1, 2, 3, c = "b"))
        assertEquals(2, FrameAnalyzer.analyze(fs).size)
        assertTrue(FrameAnalyzer.render(emptyList()).contains("Chưa có gói"))
    }
}
