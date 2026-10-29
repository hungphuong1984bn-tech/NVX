package vn.nvxconnect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import vn.nvxconnect.bluetooth.ErrorKind
import vn.nvxconnect.domain.DataSource
import vn.nvxconnect.protocol.*
import vn.nvxconnect.telemetry.*
import vn.nvxconnect.util.MiniJson
import vn.nvxconnect.util.MiniJson.list
import vn.nvxconnect.util.MiniJson.obj
import vn.nvxconnect.vehicledata.DataStatus
import vn.nvxconnect.vehicledata.TrackedValue
import java.io.File
import java.nio.file.Files

/** Advanced Vehicle Data Logger: ghi log, xuất file, timestamp, dữ liệu thiếu, mất kết nối, khôi phục phiên, giới hạn dung lượng. */
class AdvancedDiagLoggerTest {
    private lateinit var dir: File
    private var wall = 1_700_000_000_000L
    private var mono = 5_000L
    private fun advance(ms: Long) { wall += ms; mono += ms }

    @Before fun setUp() { dir = Files.createTempDirectory("diag").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun store(maxSession: Long = 16L shl 20, maxTotal: Long = 128L shl 20, free: () -> Long = { Long.MAX_VALUE }, reserve: Long = 64L * 1024) =
        DiagSessionStore(dir, maxSession, maxTotal, minFreeBytes = 1024, reserveBytes = reserve, freeSpace = free, syncEveryMs = 0)
    private fun logger(s: DiagSessionStore = store()) = AdvancedLogger(s, "test-1.0", { wall }, { mono })
    private val ctx = DiagStartContext("dev-abcdef1234", false, null)

    private fun link(ready: Boolean = true, protocol: Boolean = true) = DiagLink(if (ready) "READY" else "DISCONNECTED", if (ready) "Sẵn sàng" else "Chưa kết nối", ready,
        if (ready) -60 else null, 247, 10, 0, 18.0, wall - 100, 120, protocol, false)
    private fun tv(p: VehicleParam, st: DataStatus, v: Double?, stamp: Long? = null, unit: String = "") =
        TrackedValue(p, st, v, unit, if (v != null) DataSource.LIVE_VERIFIED else DataSource.UNAVAILABLE, stamp, stamp?.let { wall - it }, "ev1", "ghi chú", null)
    private fun snap(vararg t: TrackedValue, gps: GpsSample? = null, protocol: Boolean = true, ready: Boolean = true) =
        DiagSnapshotBuilder.build(wall, t.toList(), gps, null, link(ready, protocol))
    private fun records(l: AdvancedLogger) = store().read(l.status.value.sessionId ?: l.status.value.lastFinishedId!!)!!.records

    // ------------------------------------------------------------------ mã hóa dòng
    @Test fun codecRoundTripKeepsNastyTextAndRejectsBadCrc() {
        val r = DiagRecord(7, 1_700_000_000_123, 99, DiagKind.NOTE, "note", null, null, null, null, "dòng \"nháy\" \\ \nxuống ✓", mapOf("k" to "v\"1"))
        val line = DiagCodec.encode(r)
        assertEquals(r, (DiagCodec.decode(line) as DiagCodec.Line.Rec).rec)
        assertNull(DiagCodec.decode(line.replace("note", "n0te")))                  // sai CRC
        assertNull(DiagCodec.decode(line.dropLast(5)))                               // cụt
        assertNull(DiagCodec.decode("rác"))
        try { DiagCodec.encode(r.copy(value = Double.NaN)); fail("không được ghi NaN") } catch (_: IllegalArgumentException) {}
    }

    // ------------------------------------------------------------------ máy trạng thái + timestamp
    @Test fun lifecycleAndActiveTimeExcludesPause() {
        val l = logger()
        assertFalse(l.pause()); assertFalse(l.resume()); assertFalse(l.stop()); assertFalse(l.note("x"))
        assertTrue(l.start("Thử ga", 1000, ctx)); assertFalse("không mở 2 phiên", l.start("b", 1000, ctx))
        advance(10_000); assertTrue(l.pause()); assertEquals(DiagState.PAUSED, l.state)
        advance(20_000); assertTrue(l.note("tiếng kêu lạ khi ga")); assertTrue(l.resume())
        advance(5_000); assertTrue(l.stop("xong")); assertEquals(DiagState.IDLE, l.state)
        val data = store().read(l.status.value.lastFinishedId!!)!!
        val s = DiagSummary.compute(data)
        assertEquals(15_000, s.activeMs); assertEquals(20_000, s.pausedMs); assertEquals(DiagSessionState.STOPPED, s.state)
        val ctl = data.records.filter { it.kind == DiagKind.SESSION }.map { it.name }
        assertEquals(listOf("START", "PAUSE", "RESUME", "STOP"), ctl)
        assertEquals("giờ UTC thật của lúc bắt đầu", 1_700_000_000_000L, data.records.first().wallMs); assertEquals(5_000L, data.records.first().monoMs)
        assertEquals("seq tăng dần liên tục", (1L..data.records.size).toList(), data.records.map { it.seq })
        assertFalse("không lưu MAC", File(dir, "diag-${data.meta.id}.nvxdiag").readText().contains(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")))
    }

    // ------------------------------------------------------------------ mẫu thật, không lặp, không nội suy
    @Test fun onlyNewRealSamplesAreLoggedAndStaleValueIsNeverRepeated() {
        val l = logger(); l.start("t", 1000, ctx)
        val stamp = wall - 200
        assertEquals(1, l.onSnapshot(snap(tv(VehicleParam.SPEED_KMH, DataStatus.VERIFIED, 42.0, stamp, "km/h"))).let { n -> n - ble(l) })
        advance(1000); val before = samples(l, "veh.speed_kmh")
        l.onSnapshot(snap(tv(VehicleParam.SPEED_KMH, DataStatus.VERIFIED, 42.0, stamp, "km/h")))                      // cùng dấu thời gian giải mã: KHÔNG ghi lại
        assertEquals(before, samples(l, "veh.speed_kmh"))
        advance(1000); l.onSnapshot(snap(tv(VehicleParam.SPEED_KMH, DataStatus.VERIFIED, 43.0, wall - 50, "km/h")))
        val sp = records(l).filter { it.name == "veh.speed_kmh" && it.kind == DiagKind.SAMPLE }
        assertEquals(listOf(42.0, 43.0), sp.map { it.value }); assertTrue(sp.all { it.cls == DiagClass.REAL && it.source == DiagSource.VEHICLE })
        assertEquals("mẫu mang giờ giải mã thật, không phải giờ ghi", stamp, sp[0].wallMs)
    }
    private fun ble(l: AdvancedLogger) = records(l).count { it.kind == DiagKind.SAMPLE && it.name.startsWith("ble.") }
    private fun samples(l: AdvancedLogger, id: String) = records(l).count { it.kind == DiagKind.SAMPLE && it.name == id }

    @Test fun samplingIntervalThrottles() {
        val l = logger(); l.start("t", 2000, ctx)
        l.onSnapshot(snap()); val a = records(l).size
        advance(500); l.onSnapshot(snap()); assertEquals("chưa tới chu kỳ: không ghi", a, records(l).size)
        advance(1600); l.onSnapshot(snap(gps = GpsSample(30.0, wall - 10, 5.0))); assertTrue(records(l).size > a)
    }

    @Test fun missingDataIsMarkedOnceAndNeverFilled() {
        val l = logger(); l.start("t", 1000, ctx)
        l.onSnapshot(snap(protocol = false, ready = false))                          // chưa có giao thức xác minh
        val m = records(l).filter { it.kind == DiagKind.MISSING }
        assertTrue(m.any { it.name == "veh.speed_kmh" && it.cls == DiagClass.UNSUPPORTED })
        assertTrue(m.any { it.name == "veh.current_a" && it.cls == DiagClass.UNSUPPORTED })
        assertTrue(m.any { it.name == "gps.speed_kmh" && it.cls == DiagClass.NO_DATA })
        assertTrue("không có mẫu số cho tham số thiếu", records(l).none { it.kind == DiagKind.SAMPLE && it.name.startsWith("veh.") })
        val n = m.size; advance(1000); l.onSnapshot(snap(protocol = false, ready = false))
        assertEquals("trạng thái không đổi → không ghi lặp", n, records(l).count { it.kind == DiagKind.MISSING })
        // có dữ liệu rồi quá hạn → đánh dấu INVALID, giữ giá trị cũ ngoài mẫu
        advance(1000); l.onSnapshot(snap(tv(VehicleParam.RPM, DataStatus.VERIFIED, 3000.0, wall - 10, "rpm")))
        advance(1000); l.onSnapshot(snap(tv(VehicleParam.RPM, DataStatus.STALE, 3000.0, wall - 9000, "rpm")))
        val inv = records(l).last { it.kind == DiagKind.MISSING && it.name == "veh.rpm" }
        assertEquals(DiagClass.INVALID, inv.cls); assertNull("không ghi giá trị cũ như mẫu", inv.value)
        assertEquals(1, samples(l, "veh.rpm"))
    }

    @Test fun pausedSnapshotsAndEventsAreIgnoredButCounted() {
        val l = logger(); l.start("t", 1000, ctx); l.pause()
        assertEquals(0, l.onSnapshot(snap())); assertFalse(l.onBleEvent("X")); l.stop()
        val stop = records(l).last { it.name == "STOP" }
        assertEquals("2", stop.attrs["paused_ignored"])
    }

    // ------------------------------------------------------------------ builder phân loại
    @Test fun builderClassifiesRealDerivedUnsupportedInvalid() {
        val s = snap(tv(VehicleParam.BATTERY_V, DataStatus.VERIFIED, 12.6, wall - 10, "V"), tv(VehicleParam.RPM, DataStatus.STALE, 2000.0, wall - 9000, "rpm"),
            tv(VehicleParam.DTC, DataStatus.UNSUPPORTED, null), tv(VehicleParam.FUEL_LEVEL, DataStatus.UNVERIFIED, null),
            gps = GpsSample(35.0, wall - 1000, 8.0))
        assertEquals(DiagClass.REAL, s.row("veh.battery_v")!!.cls); assertTrue(s.row("veh.battery_v")!!.usable)
        assertEquals(DiagClass.INVALID, s.row("veh.rpm")!!.cls); assertNull(s.row("veh.rpm")!!.value); assertEquals(2000.0, s.row("veh.rpm")!!.lastUnusableValue!!, 0.0)
        assertEquals(DiagClass.UNSUPPORTED, s.row("veh.dtc")!!.cls); assertEquals(DiagClass.INVALID, s.row("veh.fuel_level")!!.cls)
        assertEquals(DiagClass.DERIVED, s.row("gps.speed_kmh")!!.cls); assertEquals(DiagSource.PHONE_GPS, s.row("gps.speed_kmh")!!.source)
        listOf("veh.current_a", "veh.soc_pct", "veh.controller_state").forEach { assertEquals(DiagClass.UNSUPPORTED, s.row(it)!!.cls) }
        val old = snap(gps = GpsSample(35.0, wall - 60_000, 8.0)); assertEquals(DiagClass.NO_DATA, old.row("gps.speed_kmh")!!.cls)
        val disc = snap(ready = false); assertEquals(DiagClass.NO_DATA, disc.row("ble.rssi_dbm")!!.cls)
    }

    @Test fun gpsDistanceSkipsGapsInsteadOfInterpolating() {
        val a = GpsDistanceAccumulator(3000)
        a.add(GpsSample(36.0, 0, 5.0)); a.add(GpsSample(36.0, 1000, 5.0)); a.add(GpsSample(36.0, 2000, 5.0))
        assertEquals(20.0, a.meters, 0.001)
        a.add(GpsSample(36.0, 12_000, 5.0))                                          // mất GPS 10 s: không cộng
        assertEquals(20.0, a.meters, 0.001); assertEquals(1, a.skippedGaps.toInt())
        a.add(GpsSample(Double.NaN, 13_000, 5.0)); assertEquals(4, a.samples.toInt())
    }

    @Test fun verifiedOnVehicleNeedsRealEvidence() {
        assertFalse(DiagVerification.verifiedOnVehicle(emptyList()))
        val e = ProtocolEvidence("x", "1", listOf("u"), "f", "b", null, listOf("00"), null, null, true)
        assertFalse("thiếu thông tin xe/kết quả thử", DiagVerification.verifiedOnVehicle(listOf(e)))
        assertTrue(DiagVerification.verifiedOnVehicle(listOf(e.copy(testedOnVehicle = "NVX 155", testResult = "đạt"))))
    }

    // ------------------------------------------------------------------ mất kết nối (collector với luồng giả)
    @Test fun collectorRecordsDisconnectsReconnectsAndResponseTimes() {
        val st = MutableStateFlow<ConnState>(ConnState.Disconnected)
        val hist = MutableStateFlow<List<LinkEvent>>(emptyList())
        val cap = MutableStateFlow(CaptureStats())
        val inp = DiagInputs(st, MutableStateFlow(-60), MutableStateFlow(247), MutableStateFlow(LinkHint.NONE), hist, cap, MutableStateFlow(null),
            { emptyList() }, { true }, { false }, { null }, { "dev-abcdef1234" })
        val l = logger(); val c = DiagCollector(l, inp) { wall }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var lostWall = 0L
        try {
            c.attach(scope)
            l.start("mất kết nối", 60_000, c.startContext())
            st.value = ConnState.Connecting; advance(800)
            st.value = ConnState.Ready(emptyList()); advance(300)
            cap.value = CaptureStats(received = 5, lastFrameWallMs = wall)
            advance(5000)
            val lost = LinkEvent(wall, 1, LinkEventKind.LINK_LOST, 19, "Xe tự ngắt", upMs = 5300, framesInSession = 5)
            lostWall = lost.wallMs; hist.value = listOf(lost); st.value = ConnState.Reconnecting(1, 0)
            advance(2000); st.value = ConnState.Error(ErrorKind.BLUETOOTH_OFF, "Bluetooth tắt")
            l.stop()
        } finally { scope.cancel() }
        val data = store().read(l.status.value.lastFinishedId!!)!!; val s = DiagSummary.compute(data)
        assertEquals(1, s.linkLost); assertEquals(1, s.readyCount); assertEquals(1, s.reconnectAttempts)
        assertEquals(800.0, s.connectMs!!.mean, 0.0); assertEquals(300.0, s.firstFrameMs!!.mean, 0.0)
        val lostRec = data.records.single { it.name == "LINK_LOST" }
        assertEquals("19", lostRec.attrs["gatt_status"]); assertEquals("5300", lostRec.attrs["up_ms"]); assertEquals(lostWall, lostRec.wallMs)
        assertEquals(1, s.errorCounts["BLE_BLUETOOTH_OFF"]); assertTrue(data.records.any { it.name == "STATE_AT_START" })
        assertEquals("không có mẫu số bịa khi không có dữ liệu", 0, data.records.count { it.kind == DiagKind.SAMPLE && it.name.startsWith("veh.") })
    }

    // ------------------------------------------------------------------ khôi phục phiên sau khi đóng đột ngột
    @Test fun interruptedSessionIsRecoveredWithoutLosingRecords() {
        val l = logger(); l.start("sự cố", 1000, ctx)
        l.onSnapshot(snap(tv(VehicleParam.SPEED_KMH, DataStatus.VERIFIED, 20.0, wall - 5, "km/h"))); l.note("vừa nghe tiếng lạ")
        val id = l.status.value.sessionId!!
        val f = File(dir, "diag-$id.nvxdiag"); val before = f.readLines().size
        f.appendBytes("D1,deadbeef,{\"t\":\"r\",\"seq\":99".toByteArray())          // "chết" giữa lúc đang ghi dòng
        val s2 = store(); val l2 = logger(s2)
        assertEquals(DiagSessionState.UNFINISHED, s2.list().single().state)
        advance(60_000)
        val rec = l2.recoverOnStartup().single()
        assertEquals(id, rec.sessionId); assertTrue(rec.tornTailBytes > 0); assertEquals(before - 1L, rec.records)   // trừ dòng meta
        assertEquals(DiagSessionState.INTERRUPTED, s2.list().single().state); assertEquals(1, l2.recovered.value.size)
        val data = s2.read(id)!!; assertEquals(0, data.corruptLines); assertFalse(data.tornTail)
        assertTrue(data.records.any { it.kind == DiagKind.NOTE && it.text == "vừa nghe tiếng lạ" }); assertEquals(DiagControl.RECOVERED, data.records.last().name)
        assertTrue("gọi lại không ghi thêm", l2.recoverOnStartup().isEmpty())
        val sum = DiagSummary.compute(data); assertEquals(DiagSessionState.INTERRUPTED, sum.state); assertEquals(1, sum.interruptedCount)
        // tiếp tục phiên
        assertTrue(l2.continueInterrupted(id, ctx)); advance(1000); l2.note("tiếp tục"); advance(2000); assertTrue(l2.stop())
        val d2 = s2.read(id)!!; assertTrue(d2.records.map { it.name }.containsAll(listOf("RECOVERED", "CONTINUE", "STOP")))
        assertEquals("seq không trùng", d2.records.size, d2.records.map { it.seq }.toSet().size)
        assertFalse("phiên đã kết thúc không tiếp tục được", l2.continueInterrupted(id, ctx))
    }

    @Test fun activeSessionOfThisProcessIsNotTouchedByRecovery() {
        val l = logger(); l.start("a", 1000, ctx)
        assertTrue(l.recoverOnStartup().isEmpty()); assertEquals(DiagState.RECORDING, l.state)
    }

    // ------------------------------------------------------------------ giới hạn dung lượng: không tự xóa log
    @Test fun limitDropsSamplesWithCountButNeverDeletesAndStillClosesSession() {
        val first = logger(store()); first.start("cũ", 1000, ctx); first.stop()
        val oldId = first.status.value.lastFinishedId!!
        val s = store(maxSession = 4_000, reserve = 1_200); val l = logger(s); advance(5000); l.start("đầy", 1000, ctx)
        repeat(60) { advance(1000); l.onSnapshot(snap(tv(VehicleParam.SPEED_KMH, DataStatus.VERIFIED, it.toDouble(), wall - 1, "km/h"))) }
        assertEquals(DiagHealth.LIMIT_REACHED, l.status.value.health); assertTrue(l.status.value.counters.droppedLimit > 0)
        assertTrue(l.note("ghi chú vẫn vào được nhờ phần dự trữ")); assertTrue(l.stop())
        val data = s.read(l.status.value.lastFinishedId!!)!!
        assertTrue(data.records.any { it.name == "LIMIT" }); assertEquals("STOP", data.records.last().name)
        assertTrue("tệp không vượt giới hạn cứng", File(dir, "diag-${data.meta.id}.nvxdiag").length() <= 4_000)
        assertTrue("không tự xóa phiên cũ", File(dir, "diag-$oldId.nvxdiag").exists()); assertEquals(2, s.list().size)
        assertTrue(DiagSummary.compute(data).limitHit); assertTrue(DiagSummary.compute(data).droppedLimit > 0)
    }

    @Test fun lowStorageDropsSamplesAndCountsThem() {
        var free = Long.MAX_VALUE; val l = logger(store(free = { free })); l.start("t", 250, ctx)
        free = 500                                                                   // dưới cả ngưỡng 1 MB dành cho bản ghi quan trọng
        advance(1000); l.onSnapshot(snap(gps = GpsSample(30.0, wall - 1, 5.0)))
        assertEquals(DiagHealth.LOW_STORAGE, l.status.value.health); assertTrue(l.status.value.counters.droppedLowStorage > 0)
        free = Long.MAX_VALUE; l.stop()
    }

    @Test fun deleteIsExplicitAndIdsCannotEscapeTheFolder() {
        val l = logger(); l.start("t", 1000, ctx); l.stop(); val id = l.status.value.lastFinishedId!!
        val s = store(); assertEquals(1, s.list().size)
        try { s.delete("../evil"); fail() } catch (_: IllegalArgumentException) {}
        assertTrue(s.delete(id)); assertTrue(s.list().isEmpty()); assertFalse(s.delete(id))
    }

    @Test fun listUsesTailScanForHugeSessionsAndReportsLastRecord() {
        val l = logger(); l.start("dài", 250, ctx)
        repeat(3000) { advance(250); l.onSnapshot(snap(tv(VehicleParam.SPEED_KMH, DataStatus.VERIFIED, (it % 90).toDouble(), wall - 1, "km/h"), gps = GpsSample(it * 1.0, wall - 1, 4.0))) }
        val info = store().list(mapOf(l.status.value.sessionId!! to DiagSessionState.RECORDING)).single()
        assertEquals(DiagSessionState.RECORDING, info.state); assertEquals(wall, info.lastRecordWallMs); assertTrue(info.sizeBytes > 128 * 1024)
        l.stop(); assertEquals(DiagSessionState.STOPPED, store().list().single().state)
    }

    // ------------------------------------------------------------------ xuất file
    private fun sampleSession(notes: Boolean = true): DiagSessionData {
        val l = logger(); l.start("Chạy thử =cmd", 1000, DiagStartContext("dev-abcdef1234", false, null))
        for (i in 0 until 8) { advance(1000); l.onSnapshot(snap(tv(VehicleParam.SPEED_KMH, DataStatus.VERIFIED, 20.0 + i, wall - 5, "km/h"), gps = GpsSample(18.0 + i, wall - 5, 4.0))) }
        advance(10_000)                                                              // khoảng thiếu 10 s
        for (i in 0 until 3) { advance(1000); l.onSnapshot(snap(tv(VehicleParam.SPEED_KMH, DataStatus.VERIFIED, 50.0 + i, wall - 5, "km/h"))) }
        if (notes) l.note("=HYPERLINK(\"http://x\") <script>alert(1)</script> AA:BB:CC:DD:EE:FF VIN 1HGCM82633A004352 a@b.com")
        l.stop(); return store().read(l.status.value.lastFinishedId!!)!!
    }
    private val now get() = wall + 1000

    @Test fun csvHasHeaderBlankMissingValuesAndNoInjection() {
        val d = sampleSession(); val csv = DiagExporter.exportToString(DiagFormat.CSV, d, DiagExportOptions(), now)
        val lines = csv.trim().lines(); assertEquals(DiagExporter.CSV_HEADER, lines[0]); assertEquals(d.records.size + 1, lines.size)
        val missing = lines.filter { it.contains(",MISSING,") }; assertTrue(missing.isNotEmpty())
        assertTrue("thiếu = ô giá trị trống, không phải 0", missing.all { Regex("^[^,]*,[^,]*,[^,]*,[^,]*,[^,]*,MISSING,\"[^\"]*\",,").containsMatchIn(it) })
        assertTrue(lines.any { it.contains(",SAMPLE,\"veh.speed_kmh\",20,") }); assertFalse(csv.contains("AA:BB:CC:DD:EE:FF")); assertFalse(csv.contains("1HGCM82633A004352")); assertFalse(csv.contains("a@b.com"))
        assertFalse("ô không được bắt đầu bằng = (công thức)", Regex("(^|,)\"=").containsMatchIn(csv))
        assertTrue(csv.contains("2023-11-14T22:13:2"))                                // UTC ISO có mili giây
        val rel = DiagExporter.exportToString(DiagFormat.CSV, d, DiagExportOptions(relativeTime = true), now); assertFalse(rel.contains("2023-11-14")); assertFalse(rel.contains("1700000"))
        val nn = DiagExporter.exportToString(DiagFormat.CSV, d, DiagExportOptions(includeNotes = false), now); assertTrue(nn.lines().none { it.contains(",NOTE,") })
    }

    @Test fun jsonIsValidAndDeclaresVerificationPrivacyAndGaps() {
        val d = sampleSession(); val j = DiagExporter.exportToString(DiagFormat.JSON, d, DiagExportOptions(), now)
        val o = MiniJson.parse(j).obj()!!
        assertEquals(DiagExporter.JSON_FORMAT, o["format"]); assertEquals(false, o["verified_on_vehicle"]); assertEquals("Chưa xác minh trên xe thật", o["verification"])
        assertEquals(true, o["read_only"]); assertEquals(false, o["uploaded"])
        val priv = o["privacy"].obj()!!; assertEquals(true, priv["redacted"]); assertEquals(false, priv["gps_coordinates_stored"]); assertEquals(false, priv["mac_address_stored"])
        assertEquals("(đã ẩn)", o["session"].obj()!!["device"]); assertFalse(j.contains("dev-abcdef1234"))
        assertEquals(d.records.size, o["records"].list()!!.size)
        val sp = o["series"].list()!!.map { it.obj()!! }.single { it["id"] == "veh.speed_kmh" }
        assertEquals(11, (sp["count"] as Number).toInt()); assertEquals(1, sp["gaps"].list()!!.size); assertTrue((sp["gaps_total_ms"] as Number).toLong() >= 10_000)
        val raw = DiagExporter.exportToString(DiagFormat.JSON, d, DiagExportOptions(redact = false), now); assertTrue(raw.contains("dev-abcdef1234")); assertTrue(raw.contains("AA:BB:CC:DD:EE:FF"))
    }

    @Test fun reportsStateUnverifiedEscapeUserTextAndDrawChartsWithBreaks() {
        val d = sampleSession()
        val html = DiagExporter.exportToString(DiagFormat.HTML, d, DiagExportOptions(), now)
        assertTrue(html.contains("Chưa xác minh trên xe thật")); assertFalse("ghi chú người dùng phải được escape", html.contains("<script>alert"))
        assertTrue(html.contains("&lt;script&gt;")); assertFalse(html.contains("AA:BB:CC:DD:EE:FF")); assertTrue(html.contains("Content-Security-Policy"))
        assertTrue("có biểu đồ khi đủ mẫu", html.contains("<svg"))
        val speedChart = html.substringAfter("Tốc độ (xe) (km/h)").substringBefore("</svg>"); assertEquals("khoảng thiếu 10 s ngắt đường thành 2 đoạn", 2, Regex("<polyline|<circle").findAll(speedChart).count()); assertTrue(speedChart.contains("Thiếu dữ liệu"))
        val txt = DiagExporter.exportToString(DiagFormat.TXT, d, DiagExportOptions(), now)
        assertTrue(txt.contains("CHƯA XÁC MINH TRÊN XE THẬT")); assertTrue(txt.contains("THIẾU DỮ LIỆU")); assertTrue(txt.contains("CHỈ ĐỌC")); assertTrue(txt.contains("[email ẩn]"))
    }

    @Test fun noChartAndHonestMessageWhenNotEnoughSamples() {
        val l = logger(); l.start("ít", 1000, ctx); advance(1000); l.onSnapshot(snap(tv(VehicleParam.SPEED_KMH, DataStatus.VERIFIED, 10.0, wall - 5, "km/h"))); l.stop()
        val d = store().read(l.status.value.lastFinishedId!!)!!
        val html = DiagExporter.exportToString(DiagFormat.HTML, d, DiagExportOptions(), now)
        assertFalse(html.contains("<svg")); assertTrue(html.contains("Chưa đủ dữ liệu để vẽ biểu đồ"))
    }

    @Test fun clockJumpIsDetectedFromWallVersusMonotonic() {
        val l = logger(); l.start("t", 1000, ctx); advance(1000); l.note("a")
        wall += 3_600_000                                                            // người dùng chỉnh giờ hệ thống +1 h
        mono += 1000; l.note("b"); l.stop()
        assertEquals(1, DiagSummary.compute(store().read(l.status.value.lastFinishedId!!)!!).clockJumps)
    }

    @Test fun exportStreamsToFileAndNeverUploads() {
        val d = sampleSession(); val out = File(dir, "x.csv")
        out.bufferedWriter().use { DiagExporter.export(DiagFormat.CSV, d, DiagExportOptions(), now, it) }
        assertEquals(d.records.size + 1, out.readLines().size); assertEquals("nvx-diag-${d.meta.id}.csv", DiagExporter.fileName(d.meta, DiagFormat.CSV))
    }

    @Test fun noteIsSanitizedAndTruncated() {
        val l = logger(); l.start("t", 1000, ctx)
        assertFalse(l.note("   \n  ")); assertTrue(l.note("a\u0000b\nc" + "x".repeat(2000)))
        val n = records(l).single { it.kind == DiagKind.NOTE }.text!!; assertEquals(1000, n.length); assertFalse(n.contains('\u0000')); assertFalse(n.contains('\n'))
    }
}
