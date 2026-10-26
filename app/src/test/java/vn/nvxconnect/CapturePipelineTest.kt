package vn.nvxconnect

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import vn.nvxconnect.protocol.YccLinkMonitor
import vn.nvxconnect.telemetry.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/** Kiểm thử đường thu thập: hàng đợi có giới hạn, ghi bền vững, phục hồi sau lỗi, xuất log. Dùng tệp thật trong thư mục tạm. */
class CapturePipelineTest {
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private val CH = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    private val SV = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"

    @Before fun setUp() { dir = Files.createTempDirectory("nvxcap").toFile(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    private fun frame(seq: Long, bytes: ByteArray = byteArrayOf(0x6B, seq.toByte()), session: String = "s1", epoch: Int = 1, mono: Long = seq * 1_000_000) =
        CapturedFrame(seq, session, epoch, 1_700_000_000_000 + seq, mono, CH, SV, PayloadSource.NOTIFICATION, -60, "READY", bytes)

    private fun pipe(capacity: Int = 4096, free: () -> Long = { Long.MAX_VALUE }, segment: Long = 4L shl 20, max: Long = 256L shl 20, sync: Boolean = false) =
        CapturePipeline(dir, scope, capacity = capacity, segmentMaxBytes = segment, maxTotalBytes = max, minFreeBytes = 1, freeSpace = free, statsEveryMs = 50, syncEachBatch = sync)

    private fun feed(p: CapturePipeline, n: Int, epoch: Int = 1, startMono: Long = 1_000_000_000L, wall: (Int) -> Long = { 1_700_000_000_000 + it }) {
        for (i in 0 until n) p.onFrame(epoch, CH, SV, byteArrayOf(0x6B, (i and 0xFF).toByte(), (i shr 8).toByte()), wall(i), startMono + i * 50_000_000L, -60, "READY", false)
    }

    // ---- định dạng + CRC + dữ liệu thô nguyên vẹn
    @Test fun codecRoundTripKeepsRawBytesExactly() {
        val raw = ByteArray(256) { it.toByte() }
        val back = CaptureCodec.decode(CaptureCodec.encode(frame(7, raw)))!!
        assertArrayEquals(raw, back.bytes); assertEquals(7, back.seq); assertEquals(CH, back.charUuid); assertEquals(-60, back.rssi)
    }
    @Test fun codecRejectsCorruptAndEmptyPayloadIsValid() {
        val line = CaptureCodec.encode(frame(1))
        assertNull(CaptureCodec.decode(line.replace("6B01", "6B02")))     // sai CRC
        assertNull(CaptureCodec.decode(line.dropLast(3)))                 // cụt
        assertNull(CaptureCodec.decode("garbage"))
        assertEquals(0, CaptureCodec.decode(CaptureCodec.encode(frame(2, ByteArray(0))))!!.bytes.size)   // gói rỗng vẫn đọc được
    }

    // ---- ghi bền vững + phục hồi sau khi tiến trình bị dừng
    @Test fun tornLastLineAfterProcessKillIsDetectedAndRepaired() {
        val a = DurableFrameLog(dir, minFreeBytes = 1)
        a.append((1L..5L).map { frame(it) })
        File(dir, "seg-00000001.nvxlog").appendText("V1,deadbeef,6,s1,1,170")      // ghi dở: không có '\n'
        val b = DurableFrameLog(dir, minFreeBytes = 1)                             // "tiến trình mới"
        assertEquals(1, b.readList().second.corruptLines)
        b.append(listOf(frame(6)))
        val (frames, rep) = b.readList()
        assertEquals(6, frames.size); assertEquals(0, rep.corruptLines); assertEquals(1L, b.repairedTails)
        assertEquals((1L..6L).toList(), frames.map { it.seq })
    }
    @Test fun batchesFlushedBeforeKillSurvive() {
        val p = pipe(sync = true); feed(p, 300); runBlocking { assertTrue(p.drain()) }
        val reopened = DurableFrameLog(dir, minFreeBytes = 1)               // không đóng p: như bị kill
        assertEquals(300, reopened.readList().first.size)
    }

    // ---- trùng lặp
    @Test fun duplicateReplayIsSkippedButIdenticalPayloadsWithDifferentSeqAreKept() {
        val l = DurableFrameLog(dir, minFreeBytes = 1)
        val same = byteArrayOf(1, 2, 3)
        l.append(listOf(frame(1, same), frame(2, same), frame(3, same)))
        l.append(listOf(frame(2, same), frame(3, same), frame(4, same)))   // thử lại sau lỗi ghi dở → ghi trùng 2,3
        val (f, rep) = l.readList()
        assertEquals(listOf(1L, 2L, 3L, 4L), f.map { it.seq }); assertEquals(2, rep.duplicates)
    }

    // ---- hàng đợi đầy: không bỏ im lặng
    @Test fun fullIngressCountsEveryDropWithReasonAndSeqRange() {
        val ing = FrameIngress(capacity = 8)
        for (i in 1..20) ing.offer(frame(ing.nextSeq()))
        assertEquals(20, ing.received.get()); assertEquals(8, ing.accepted.get()); assertEquals(12, ing.droppedIngress)
        val d = ing.dropEvents().single()
        assertEquals(DropReason.INGRESS_FULL, d.reason); assertEquals(12, d.count); assertEquals(9L, d.firstSeq); assertEquals(20L, d.lastSeq)
    }
    @Test fun noPacketVanishesSilently_receivedEqualsPersistedPlusLost() {
        val p = pipe(capacity = 64)
        feed(p, 20_000)
        runBlocking { p.drain(10_000) }; p.publish()
        val s = p.stats.value
        assertEquals(20_000, s.received); assertEquals(s.received, s.persisted + s.lostByApp)
        val (frames, rep) = p.log.readList()
        assertEquals(s.persisted, frames.size.toLong()); assertEquals(0, rep.corruptLines)
        // gói mất lộ ra thành khoảng trống số thứ tự, đúng bằng số đã đếm
        val gaps = frames.zipWithNext().sumOf { (a, b) -> b.seq - a.seq - 1 } + (frames.first().seq - 1) + (20_000 - frames.last().seq)
        assertEquals(s.lostByApp, gaps)
    }

    // ---- tốc độ cao (callback không bị chặn) ----
    @Test fun highRateNotifyDoesNotBlockCallbackAndKeepsOrder() {
        val p = pipe(capacity = 4096)
        val t0 = System.nanoTime(); feed(p, 100_000); val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("callback bị chặn: $ms ms cho 100k gói", ms < 5_000)
        runBlocking { p.drain(20_000) }; p.publish()
        val s = p.stats.value
        assertEquals(s.received, s.persisted + s.lostByApp)
        var last = 0L; p.log.readAll { assertTrue(it.seq > last); last = it.seq }
    }

    // ---- lỗi ghi / bộ nhớ đầy / hồi phục ----
    @Test fun lowStorageDegradesThenRecoversWithoutLosingBufferedFrames() {
        var free = 0L
        val p = pipe(free = { free }); feed(p, 500)
        Thread.sleep(600); p.publish()
        assertEquals(WriterHealth.DEGRADED, p.stats.value.writer); assertNotNull(p.stats.value.writerError); assertTrue(p.stats.value.retryBuffered > 0)
        free = Long.MAX_VALUE
        runBlocking { assertTrue(p.drain(8_000)) }; p.publish()
        assertEquals(WriterHealth.OK, p.stats.value.writer)
        assertEquals(500, p.log.readList().first.size); assertEquals(0, p.stats.value.lostByApp)
    }
    @Test fun ioFailureOverflowsRetryBufferAndCountsStorageDrops() {
        val ing = FrameIngress(1024); val log = DurableFrameLog(dir, minFreeBytes = 1, freeSpace = { 0 })
        val w = FrameWriter(ing, log, scope, retryBufferMax = 100, retryBaseMs = 10, retryCapMs = 20); w.start()
        for (i in 1..400) ing.offer(frame(ing.nextSeq()))
        Thread.sleep(500)
        assertEquals(WriterHealth.DEGRADED, w.health.value); assertTrue(w.retryBuffered <= 100)
        assertEquals(400L, w.retryBuffered + ing.droppedStorage + ing.queueDepth)   // mọi gói hoặc đang giữ, hoặc đã đếm là mất
        assertTrue(ing.dropEvents().any { it.reason == DropReason.STORAGE_LOW })
    }
    @Test fun directoryReplacedByFileIsIoErrorThenRecovers() {
        val p = pipe(); feed(p, 50); runBlocking { assertTrue(p.drain()) }
        p.log.close(); dir.deleteRecursively(); dir.writeText("not a dir")
        feed(p, 50, startMono = 9_000_000_000L); Thread.sleep(500); p.publish()
        assertEquals(WriterHealth.DEGRADED, p.stats.value.writer)
        dir.delete(); dir.mkdirs()
        runBlocking { assertTrue(p.drain(8_000)) }; p.publish()
        assertEquals(WriterHealth.OK, p.stats.value.writer); assertEquals(50, p.log.readList().first.size)   // 50 đầu mất theo thư mục bị thay, 50 sau được giữ
    }

    // ---- xoay vòng log ----
    @Test fun rotationEvictsOldestSegmentsAndReportsIt() {
        val l = DurableFrameLog(dir, segmentMaxBytes = 2_000, maxTotalBytes = 6_000, minFreeBytes = 1, syncEachBatch = false)
        for (b in 0 until 60) l.append((1..5).map { frame((b * 5 + it).toLong()) })
        assertTrue(l.evictedSegments > 0); assertTrue(l.totalBytes() <= 6_000 + 2_100)
        assertEquals(300L, l.readList().first.last().seq)
    }

    // ---- thời gian ----
    @Test fun outOfOrderWallClockAndGapsAreMeasuredFromMonotonicClock() {
        val p = pipe()
        feed(p, 10, wall = { if (it < 5) 1_700_000_000_000 + it else 1_700_000_000_000 - 60_000 + it })   // đồng hồ hệ thống nhảy lùi 60 s
        p.onFrame(1, CH, SV, ByteArray(0), 1_700_000_000_100, 1_000_000_000L + 9 * 50_000_000L + 2_000_000_000L, null, "READY", false)   // gói rỗng sau 2 s im lặng
        p.publish(); val s = p.stats.value
        assertTrue(s.wallClockJumps >= 1); assertEquals(1, s.emptyFrames); assertTrue(s.maxGapMs in 2_000..2_100)
    }
    @Test fun reconnectResetsGapBaselineSoOfflineTimeIsNotAGap() {
        val p = pipe(); feed(p, 5, epoch = 1)
        feed(p, 5, epoch = 2, startMono = 100_000_000_000L)    // 100 s sau: xe đã ngoài vùng phủ sóng
        p.publish(); assertTrue(p.stats.value.maxGapMs < 1_000)
    }

    // ---- khung sai checksum / gói bất thường ----
    @Test fun invalidFramesAreCountedNotDecoded() {
        val m = YccLinkMonitor()
        assertNull(m.accept(byteArrayOf(0x6B, 1, 2, 3), 1)); assertNull(m.accept(ByteArray(0), 2))
        assertEquals(2, m.stats.invalid); assertEquals(0, m.stats.valid)
    }

    // ---- xuất log lớn ----
    @Test fun largeExportStreamsAndKeepsRawBytes() {
        val p = pipe(capacity = 8192); feed(p, 60_000)
        runBlocking { p.drain(20_000) }; p.publish()
        val csv = File(dir.parentFile, "export-${System.nanoTime()}.csv")
        try {
            val rep = csv.outputStream().use { CaptureExport.csv(p.log, it) }
            val lines = csv.readLines()
            assertEquals(rep.frames + 1, lines.size); assertEquals(CaptureExport.CSV_HEADER, lines[0])
            val cols = lines[1].split(",")
            assertEquals(CaptureExport.CSV_HEADER.split(",").size, cols.size)
            val first = p.log.readList(1).first.first()
            assertEquals(first.hex, cols.last())
        } finally { csv.delete() }
        val bo = ByteArrayOutputStream(); CaptureExport.jsonl(p.log, bo, mapOf("app" to "t"), p.stats.value)
        val txt = bo.toString("UTF-8"); assertTrue(txt.lines().first().contains("lostByApp")); assertTrue(txt.contains("\"end\":true"))
    }

    // ---- quyền riêng tư ----
    @Test fun storedLogContainsNoDeviceAddressAndCanBeCleared() {
        val p = pipe(); feed(p, 20); runBlocking { p.drain() }
        val all = dir.listFiles()!!.joinToString { it.readText() }
        assertFalse(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").containsMatchIn(all))
        p.clearStored(); assertEquals(0, p.log.readList().first.size); assertEquals(0L, p.log.totalBytes())
    }

    // ---- khôi phục seq/phiên ----
    @Test fun newSessionGetsNewIdAndFramesCarryIt() {
        val p = pipe(); val s1 = p.sessionId; feed(p, 3); p.onSessionStart(); feed(p, 3)
        assertNotEquals(s1, p.sessionId); runBlocking { p.drain() }
        assertEquals(setOf(s1, p.sessionId), p.log.readList().first.map { it.sessionId }.toSet())
    }
}
