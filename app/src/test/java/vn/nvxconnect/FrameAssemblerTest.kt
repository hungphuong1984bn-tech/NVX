package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.TelemetryTestSupport.REAL_BLOCK
import vn.nvxconnect.TelemetryTestSupport.REAL_HELLO
import vn.nvxconnect.TelemetryTestSupport.REAL_IDLE
import vn.nvxconnect.TelemetryTestSupport.REAL_INFO
import vn.nvxconnect.TelemetryTestSupport.REAL_PARAMS
import vn.nvxconnect.protocol.*

/** Lớp khung: khung chia nhỏ, nhiều khung/notification, byte rác, tổng kiểm sai, mảnh quá hạn. Khung REAL_* là mẫu bắt thật; synth* là tổng hợp. */
class FrameAssemblerTest {
    private val rule = KnownFramingRules.YCC_NUS
    private fun asm(maxPending: Long = 2_000) = FrameAssembler(rule, maxPending)
    private fun hexOf(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

    @Test fun realFramesAreRecognizedWholeWithChecksumTrue() {
        val a = asm()
        for ((i, f) in listOf(REAL_HELLO, REAL_INFO, REAL_BLOCK, REAL_IDLE, REAL_PARAMS).withIndex()) {
            val out = a.feed(f, i * 100L)
            assertEquals(1, out.size); assertEquals(FrameOrigin.WHOLE_NOTIFICATION, out[0].origin); assertEquals(true, out[0].checksumValid)
            assertArrayEquals(f, out[0].bytes)
        }
        assertEquals(0L, a.stats.totalDroppedBytes); assertEquals(0, a.stats.pendingBytes)
    }

    @Test fun frameSplitAcrossNotificationsIsJoinedExactly() {
        val a = asm(); val f = REAL_PARAMS
        assertTrue(a.feed(f.copyOfRange(0, 20), 0).isEmpty()); assertEquals(20, a.stats.pendingBytes)
        assertTrue(a.feed(f.copyOfRange(20, 100), 10).isEmpty())
        val out = a.feed(f.copyOfRange(100, f.size), 20)
        assertEquals(1, out.size); assertEquals(FrameOrigin.JOINED_FRAGMENTS, out[0].origin); assertArrayEquals(f, out[0].bytes)
        assertEquals(1L, a.stats.joinedFrames)
    }

    @Test fun twoFramesInOneNotificationAreSplit() {
        val a = asm(); val out = a.feed(REAL_IDLE + REAL_HELLO, 0)
        assertEquals(2, out.size); assertArrayEquals(REAL_IDLE, out[0].bytes); assertArrayEquals(REAL_HELLO, out[1].bytes)
        assertTrue(out.all { it.origin == FrameOrigin.SPLIT_FROM_MULTI })
        assertEquals(2L, a.stats.splitFrames)
    }

    @Test fun tailOfOneNotificationPlusHeadOfNext() {
        val a = asm(); val two = REAL_IDLE + REAL_PARAMS
        val o1 = a.feed(two.copyOfRange(0, REAL_IDLE.size + 10), 0)           // 1 khung đủ + 10 byte đầu khung sau
        assertEquals(1, o1.size); assertEquals(10, a.stats.pendingBytes)
        val o2 = a.feed(two.copyOfRange(REAL_IDLE.size + 10, two.size), 5)
        assertEquals(1, o2.size); assertEquals(FrameOrigin.JOINED_FRAGMENTS, o2[0].origin); assertArrayEquals(REAL_PARAMS, o2[0].bytes)
    }

    @Test fun garbageBytesAreSkippedAndCounted() {
        val a = asm(); val junk = byteArrayOf(0x00, 0x11, 0x22)
        val out = a.feed(junk + REAL_IDLE, 0)
        assertEquals(1, out.size); assertArrayEquals(REAL_IDLE, out[0].bytes)
        assertEquals(3L, a.stats.resyncDroppedBytes)
    }

    @Test fun corruptChecksumFrameIsNeverEmittedAsValid() {
        val a = asm(); val bad = REAL_IDLE.copyOf().also { it[58] = (it[58] + 1).toByte() }
        val out = a.feed(bad, 0)
        assertTrue("khung sai tổng kiểm không được phát ra", out.none { it.bytes.contentEquals(bad) })
        assertTrue(a.stats.checksumFailures >= 1)
    }

    @Test fun staleFragmentIsDroppedNotGluedToNextFrame() {
        val a = asm(maxPending = 1_000)
        a.feed(REAL_PARAMS.copyOfRange(0, 50), 0)
        val out = a.feed(REAL_IDLE, 5_000)                                     // mảnh dở quá hạn → bỏ, khung mới phải nguyên vẹn
        assertEquals(1, out.size); assertArrayEquals(REAL_IDLE, out[0].bytes)
        assertEquals(1L, a.stats.staleFragmentsDropped); assertEquals(50L, a.stats.resyncDroppedBytes)
    }

    @Test fun resetOnNewGattSessionDropsPendingAndCountsIt() {
        val a = asm(); a.feed(REAL_PARAMS.copyOfRange(0, 30), 0); a.reset()
        assertEquals(0, a.stats.pendingBytes); assertEquals(30L, a.stats.resyncDroppedBytes)
        val out = a.feed(REAL_IDLE, 10); assertEquals(1, out.size)
    }

    @Test fun unknownProtocolIsPassedThroughUntouched() {
        val a = FrameAssembler(FramingRule.PerNotification); val raw = byteArrayOf(1, 2, 3, 4, 5)
        val out = a.feed(raw, 0)
        assertEquals(1, out.size); assertEquals(FrameOrigin.RAW_UNFRAMED, out[0].origin); assertNull("không có tổng kiểm đã xác minh → null, không phải true", out[0].checksumValid)
        assertArrayEquals(raw, out[0].bytes)
        assertTrue(a.feed(ByteArray(0), 1).isEmpty())
    }

    @Test fun onlyDeclaredCharacteristicGetsTheYccRule() {
        assertSame(KnownFramingRules.YCC_NUS, KnownFramingRules.forChar("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"))
        assertSame(FramingRule.PerNotification, KnownFramingRules.forChar("6e400002-b5a3-f393-e0a9-e50e24dcca9e"))
        assertSame(FramingRule.PerNotification, KnownFramingRules.forChar("0000ffe1-0000-1000-8000-00805f9b34fb"))
    }

    @Test fun bufferNeverGrowsPastLongestFrame() {
        val a = asm()
        repeat(50) { a.feed(byteArrayOf(0x6C, 0x18, 0x02, 0x26, 0x01), it * 10L) }   // đầu khung 0x6C lặp: không bao giờ đủ 143 byte hợp lệ
        assertTrue(a.stats.pendingBytes <= 143)
    }

    @Test fun everyByteIsAccountedFor() {
        // Mọi byte vào = byte ra trong khung + byte bỏ (đã đếm) + byte còn chờ: không mất âm thầm.
        val a = asm(); var out = 0L; var inB = 0L
        val feeds = listOf(byteArrayOf(9, 9) + REAL_IDLE.copyOfRange(0, 30), REAL_IDLE.copyOfRange(30, 59) + REAL_HELLO.copyOfRange(0, 3), REAL_HELLO.copyOfRange(3, 8))
        for ((i, f) in feeds.withIndex()) { inB += f.size; a.feed(f, i * 10L).forEach { out += it.bytes.size } }
        assertEquals(inB, out + a.stats.totalDroppedBytes + a.stats.pendingBytes)
    }
}
