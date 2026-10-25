package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.TelemetryTestSupport.NUS_NOTIFY
import vn.nvxconnect.TelemetryTestSupport.NUS_SERVICE
import vn.nvxconnect.TelemetryTestSupport.newRecorder
import vn.nvxconnect.bluetooth.AdvertRecord
import vn.nvxconnect.telemetry.*

class TelemetryRecorderTest {
    private val addr = "AA:BB:CC:DD:EE:01"

    @Test fun flagsEmptyUniformOversizeAndDuplicate() {
        val r = newRecorder()
        val e = r.record(addr, PayloadSource.NOTIFICATION, NUS_SERVICE, NUS_NOTIFY, ByteArray(0), 1)!!
        assertTrue(PayloadFlag.EMPTY in e.flags)
        val u = r.record(addr, PayloadSource.NOTIFICATION, NUS_SERVICE, NUS_NOTIFY, ByteArray(8) { 0xFF.toByte() }, 2)!!
        assertTrue(PayloadFlag.UNIFORM in u.flags)
        val big = r.record(addr, PayloadSource.NOTIFICATION, NUS_SERVICE, NUS_NOTIFY, ByteArray(600) { it.toByte() }, 3)!!
        assertTrue(PayloadFlag.OVERSIZE in big.flags)
        val a = r.record(addr, PayloadSource.NOTIFICATION, NUS_SERVICE, NUS_NOTIFY, byteArrayOf(1, 2, 3), 4)!!
        val b = r.record(addr, PayloadSource.NOTIFICATION, NUS_SERVICE, NUS_NOTIFY, byteArrayOf(1, 2, 3), 5)!!
        assertFalse(PayloadFlag.DUPLICATE in a.flags); assertTrue(PayloadFlag.DUPLICATE in b.flags)
        val s = r.streamStats().values.single()
        assertEquals(5, s.count); assertEquals(1, s.empty); assertEquals(1, s.duplicates); assertEquals(3, s.abnormal)
    }

    @Test fun recordsOnlyWhileRecordingAndCountsIgnored() {
        val r = newRecorder()
        r.stop()
        assertNull(r.record(addr, PayloadSource.NOTIFICATION, null, NUS_NOTIFY, byteArrayOf(1), 1))
        assertEquals(1, r.ignoredWhileStopped); assertEquals(0, r.payloadCount())
        r.start("lần hai"); assertNotNull(r.record(addr, PayloadSource.NOTIFICATION, null, NUS_NOTIFY, byteArrayOf(1), 2))
        assertEquals("s2", r.sessionId)
    }

    @Test fun ringKeepsPinnedHeadAndReportsDroppedOldest() {
        val r = newRecorder(maxRecords = 10, pinned = 3)
        for (i in 1..25) r.record(addr, PayloadSource.NOTIFICATION, null, NUS_NOTIFY, byteArrayOf(i.toByte(), 1), i.toLong())
        val snap = r.snapshot()
        assertEquals(10, snap.size); assertEquals(15, r.droppedOldest)
        assertEquals(listOf(1L, 2L, 3L), snap.take(3).map { it.seq })      // đầu phiên được giữ
        assertEquals(25L, snap.last().seq)                                    // mới nhất được giữ
        assertEquals(25L, r.totalPayloads())                                  // bộ đếm không bị cắt
    }

    @Test fun subscribeSuccessIsNotData() {
        val r = newRecorder()
        r.event(addr, ConnEventType.SUBSCRIBE_OK, NUS_NOTIFY)
        assertEquals(0L, r.receivedOn(NUS_NOTIFY))
        assertNull(r.lastPayloadMs)
        r.record(addr, PayloadSource.NOTIFICATION, null, NUS_NOTIFY, byteArrayOf(9), 50)
        assertEquals(1L, r.receivedOn(NUS_NOTIFY)); assertEquals(50L, r.lastPayloadMs)
    }

    @Test fun advertThrottledAnonymizedAndNotCountedAsLiveData() {
        val r = newRecorder()
        fun adv(t: Long) = AdvertRecord(addr, "YCC-1234", -60, listOf(NUS_SERVICE.uppercase()), true, t, manufacturerData = mapOf(0x0059 to "01 02 03"))
        r.recordAdvert(adv(0)); r.recordAdvert(adv(300)); r.recordAdvert(adv(1500))
        val adverts = r.snapshot(RecordFilter(source = PayloadSource.ADVERTISEMENT))
        assertEquals(2, adverts.size)                                         // 300 ms bị giới hạn tần suất
        assertEquals("dev-01", adverts.first().deviceId); assertEquals("mfr:0059", adverts.first().charUuid)
        assertEquals("dev-01", r.deviceList().single().deviceId)
        assertNull(r.lastPayloadMs); assertEquals(0L, r.receivedOn("mfr:0059"))
        assertFalse(r.eventsSnapshot().any { it.deviceId.contains("AA:BB") })  // không rò MAC
    }

    @Test fun filterByDeviceUuidSourceAndFlag() {
        val r = newRecorder()
        r.record(addr, PayloadSource.NOTIFICATION, null, NUS_NOTIFY, byteArrayOf(1, 2), 1)
        r.record(addr, PayloadSource.READ, null, "00002a00-0000-1000-8000-00805f9b34fb", byteArrayOf(0x41), 2)
        r.record("AA:BB:CC:DD:EE:02", PayloadSource.NOTIFICATION, null, NUS_NOTIFY, byteArrayOf(1, 2), 3)
        assertEquals(1, r.snapshot(RecordFilter(source = PayloadSource.READ)).size)
        assertEquals(2, r.snapshot(RecordFilter(charUuid = NUS_NOTIFY.uppercase())).size)
        assertEquals(1, r.snapshot(RecordFilter(deviceId = "dev-02")).size)
        assertEquals(0, r.snapshot(RecordFilter(flag = PayloadFlag.EMPTY)).size)
    }

    @Test fun clearResetsEverything() {
        val r = newRecorder(); r.record(addr, PayloadSource.NOTIFICATION, null, NUS_NOTIFY, byteArrayOf(1), 1); r.clear()
        assertEquals(0, r.payloadCount()); assertTrue(r.streamStats().isEmpty()); assertEquals(0, r.droppedOldest)
    }
}
