package vn.nvxconnect

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import vn.nvxconnect.telemetry.*
import vn.nvxconnect.util.MiniJson
import vn.nvxconnect.util.MiniJson.list
import vn.nvxconnect.util.MiniJson.obj
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Gói xuất MỘT ZIP: tên tệp, nội dung từ bộ xuất hiện có, không mất bản ghi, kiểm tra ZIP, phát hiện hỏng/bị sửa. Không dùng dữ liệu giả để "độn" gói. */
class DiagBundleTest {
    private lateinit var dir: File
    private val t0 = 1_793_270_000_000L
    private val info = BundleInfo("NVX Connect", "0.13.1", 16, "vn.nvxconnect")
    private val opts = DiagExportOptions(redact = true, includeNotes = true, relativeTime = false)

    @Before fun setUp() { dir = Files.createTempDirectory("bundle").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun rec(seq: Long, kind: DiagKind, name: String, dt: Long, value: Double? = null, text: String? = null, cls: DiagClass? = null, src: DiagSource? = null, unit: String? = null) =
        DiagRecord(seq, t0 + dt, 10_000 + dt, kind, name, value, unit, cls, src, text)

    /** Phiên có [n] mẫu GPS thật cách 1 giây. n < 5 thì không đủ để vẽ biểu đồ. */
    private fun session(id: String, n: Int, label: String = "Chạy thử", note: String? = null, device: String? = "dev-abcdef"): BundleSession {
        val r = ArrayList<DiagRecord>(); var s = 1L
        r += rec(s++, DiagKind.SESSION, DiagControl.START, 0)
        for (i in 0 until n) r += rec(s++, DiagKind.SAMPLE, DiagIds.GPS_SPEED, 1_000L * (i + 1), value = 10.0 + i, cls = DiagClass.DERIVED, src = DiagSource.PHONE_GPS, unit = "km/h")
        r += rec(s++, DiagKind.MISSING, "veh.rpm", 500, text = "Chưa hỗ trợ", cls = DiagClass.UNSUPPORTED)
        r += rec(s++, DiagKind.BLE_EVENT, "STATE_READY", 600, text = "Sẵn sàng")
        r += rec(s++, DiagKind.ERROR, "GATT_133", 700, text = "lỗi GATT")
        if (note != null) r += rec(s++, DiagKind.NOTE, "note", 800, text = note)
        r += rec(s++, DiagKind.SESSION, DiagControl.STOP, 20_000)
        val meta = DiagMeta(id, t0, label, "0.13.1", device, 1000, false, null)
        return BundleSession(DiagSessionData(meta, r, 0, false))
    }

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val m = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; m[e.name] = z.readBytes() } }
        return m
    }
    private fun write(sessions: List<BundleSession>, o: DiagExportOptions = opts, name: String = "b.zip"): File {
        val f = File(dir, name); f.outputStream().use { DiagBundle.write(it, sessions, info, o, t0 + 60_000) }; return f
    }
    private fun rows(csv: ByteArray) = String(csv, Charsets.UTF_8).trimEnd().lines().drop(1)

    @Test fun fileNameFollowsVersionAndTimestampPattern() {
        val utc = TimeZone.getTimeZone("UTC")
        // 2026-10-29 10:53:00 UTC
        assertEquals("NVX-Connect-v0.13.1-20261029-105300.zip", DiagBundle.fileName("0.13.1", 1_793_271_180_000L, utc))
        assertEquals("NVX-Connect-v1.0.0-20261029-175300.zip", DiagBundle.fileName("1.0.0", 1_793_271_180_000L, TimeZone.getTimeZone("Asia/Ho_Chi_Minh")))
        assertTrue(DiagBundle.fileName("0.13/../1", 0L, utc).startsWith("NVX-Connect-v0.13..1-"))   // ký tự đường dẫn bị loại
        assertFalse(DiagBundle.fileName("0.13.1", 0L, utc).contains("/"))
    }

    @Test fun uniqueNameNeverOverwrites() {
        val n = "NVX-Connect-v0.13.1-20261029-105300.zip"
        assertEquals(n, DiagBundle.uniqueName(dir, n))
        File(dir, n).writeText("x"); assertEquals("NVX-Connect-v0.13.1-20261029-105300-2.zip", DiagBundle.uniqueName(dir, n))
        File(dir, "NVX-Connect-v0.13.1-20261029-105300-2.zip").writeText("x"); assertTrue(DiagBundle.uniqueName(dir, n).endsWith("-3.zip"))
    }

    @Test fun singleZipHasAllRequiredPartsAndVerifies() {
        val sessions = listOf(session("s1", 8, note = "giật khi ga"), session("s2", 2))
        val f = write(sessions)
        val chk = DiagBundle.verify(f, listOf("s1", "s2")); assertTrue(chk.problems.toString(), chk.ok)
        val e = entries(f.readBytes())
        for (n in listOf("README.txt", "metadata.json")) assertTrue(n, e.containsKey(n))
        for (id in listOf("s1", "s2")) for (n in listOf("report.html", "telemetry.csv", "events.csv", "metadata.json")) assertTrue("$id/$n", e.containsKey("sessions/$id/$n"))
        assertTrue(String(e["sessions/s1/report.html"]!!, Charsets.UTF_8).contains("<svg"))
    }

    @Test fun chartsOnlyWhenThereAreEnoughRealSamples() {
        val e = entries(write(listOf(session("s1", 8), session("s2", 2))).readBytes())
        assertTrue(e.keys.any { it.startsWith("sessions/s1/charts/") && it.endsWith(".svg") })
        assertFalse("không vẽ giả khi thiếu mẫu", e.keys.any { it.startsWith("sessions/s2/charts/") })
        val meta = MiniJson.parse(String(e["metadata.json"]!!, Charsets.UTF_8)).obj()!!
        val byId = meta["sessions"].list()!!.map { it.obj()!! }.associateBy { it["id"] }
        assertEquals(1, (byId["s1"]!!["charts"] as Number).toInt()); assertEquals(0, (byId["s2"]!!["charts"] as Number).toInt())
    }

    @Test fun noRecordIsLostOrInventedAndKindsAreSplitBetweenTheTwoCsvs() {
        val s = session("s1", 8, note = "ghi chú")
        val e = entries(write(listOf(s)).readBytes())
        val tel = rows(e["sessions/s1/telemetry.csv"]!!); val ev = rows(e["sessions/s1/events.csv"]!!)
        assertEquals(s.data.records.size, tel.size + ev.size)                        // không mất, không thêm
        assertEquals(8 + 1, tel.size)                                                // 8 mẫu + 1 dấu thiếu
        assertTrue(tel.all { it.contains(",SAMPLE,") || it.contains(",MISSING,") })
        assertTrue(ev.none { it.contains(",SAMPLE,") || it.contains(",MISSING,") })
        assertEquals(1 + 1 + 1 + 1 + 1, ev.size)                                      // START, BLE_EVENT, ERROR, NOTE, STOP
        // CSV giống hệt xuất CSV hiện có khi gộp hai nửa theo seq.
        val whole = DiagExporter.exportToString(DiagFormat.CSV, s.data, opts, t0).trimEnd().lines().drop(1).toSet()
        assertEquals(whole, (tel + ev).toSet())
    }

    @Test fun reportAndMetadataComeFromExistingExporters() {
        val s = session("s1", 8)
        val e = entries(write(listOf(s)).readBytes())
        assertEquals(DiagExporter.exportToString(DiagFormat.HTML, s.data, opts, t0 + 60_000), String(e["sessions/s1/report.html"]!!, Charsets.UTF_8))
        val m = MiniJson.parse(String(e["sessions/s1/metadata.json"]!!, Charsets.UTF_8)).obj()!!
        assertEquals(DiagExporter.JSON_FORMAT, m["format"]); assertEquals(0, m["records"].list()!!.size)
        assertEquals(s.data.records.size, ((m["stats"].obj()!!["records"]) as Number).toInt())   // thống kê vẫn tính trên đủ bản ghi
    }

    @Test fun topLevelMetadataCarriesVersionTimeOptionsAndHashes() {
        val e = entries(write(listOf(session("s1", 8))).readBytes())
        val m = MiniJson.parse(String(e["metadata.json"]!!, Charsets.UTF_8)).obj()!!
        assertEquals(DiagBundle.FORMAT, m["format"])
        val app = m["app"].obj()!!; assertEquals("0.13.1", app["version_name"]); assertEquals(16, (app["version_code"] as Number).toInt()); assertEquals("vn.nvxconnect", app["application_id"])
        assertEquals(false, m["synthetic_data"]); assertEquals(true, m["read_only"]); assertEquals(false, m["uploaded"])
        assertNotNull(m["exported_utc"]); assertEquals(true, m["privacy"].obj()!!["redacted"])
        val files = m["files"].list()!!.map { it.obj()!! }
        assertEquals(e.size - 1, files.size)                                         // mọi mục trừ chính metadata.json
        assertTrue(files.all { (it["sha256"] as String).length == 64 })
    }

    @Test fun redactionAppliesToNotesLabelsAndDevice() {
        val s = session("s1", 6, label = "xe VIN 1HGCM82633A004352 mail a@b.com", note = "gọi a@b.com", device = "dev-SECRETKEY99")
        val all = entries(write(listOf(s)).readBytes()).filterKeys { it != "metadata.json" || true }.values.joinToString("\n") { String(it, Charsets.UTF_8) }
        assertFalse(all.contains("a@b.com")); assertFalse(all.contains("1HGCM82633A004352")); assertFalse(all.contains("dev-SECRETKEY99"))
        val open = entries(write(listOf(s), DiagExportOptions(redact = false), "open.zip").readBytes()).values.joinToString("\n") { String(it, Charsets.UTF_8) }
        assertTrue("khi tắt ẩn danh thì giữ nguyên (người dùng chủ động)", open.contains("a@b.com"))
    }

    @Test fun relativeTimeHidesExportInstantAndNotesCanBeExcluded() {
        val f = write(listOf(session("s1", 6, note = "bí mật")), DiagExportOptions(redact = true, includeNotes = false, relativeTime = true))
        val e = entries(f.readBytes())
        val m = MiniJson.parse(String(e["metadata.json"]!!, Charsets.UTF_8)).obj()!!
        assertNull(m["exported_utc"])
        assertFalse(e.values.any { String(it, Charsets.UTF_8).contains("bí mật") })
        assertTrue(DiagBundle.verify(f).ok)
    }

    @Test fun liveSessionIsExportedWithoutBeingStopped() {
        val s = session("s1", 6); val live = BundleSession(s.data, DiagSessionState.RECORDING)
        val e = entries(write(listOf(live)).readBytes())
        assertTrue(String(e["sessions/s1/report.html"]!!, Charsets.UTF_8).contains(DiagSessionState.RECORDING.title))
        assertEquals(s.data.records.size, rows(e["sessions/s1/telemetry.csv"]!!).size + rows(e["sessions/s1/events.csv"]!!).size)
    }

    @Test fun noSessionsMeansNoBundle() {
        try { DiagBundle.write(ByteArrayOutputStream(), emptyList(), info, opts, t0); fail("không được tạo ZIP rỗng/giả") } catch (_: IllegalArgumentException) {}
    }

    @Test fun inputDataIsNotModified() {
        val s = session("s1", 8); val before = s.data.records.toList()
        write(listOf(s)); assertEquals(before, s.data.records)
    }

    // ------------------------------------------------------------------ kiểm tra ZIP
    @Test fun verifyRejectsMissingTruncatedAndGarbageFiles() {
        assertFalse(DiagBundle.verify(File(dir, "none.zip")).ok)
        File(dir, "empty.zip").writeBytes(ByteArray(0)); assertFalse(DiagBundle.verify(File(dir, "empty.zip")).ok)
        File(dir, "junk.zip").writeText("không phải zip, dài hơn hai mươi hai byte cho chắc"); assertFalse(DiagBundle.verify(File(dir, "junk.zip")).ok)
        val b = write(listOf(session("s1", 8))).readBytes()
        File(dir, "cut.zip").writeBytes(b.copyOf(b.size / 2)); assertFalse(DiagBundle.verify(File(dir, "cut.zip")).ok)
    }

    @Test fun verifyDetectsFlippedBytes() {
        val b = write(listOf(session("s1", 8))).readBytes()
        var bad = 0
        for (pos in listOf(b.size / 5, b.size / 3, b.size / 2, b.size * 2 / 3)) {
            val c = b.copyOf(); c[pos] = (c[pos].toInt() xor 0x55).toByte()
            val f = File(dir, "flip$pos.zip"); f.writeBytes(c)
            if (!DiagBundle.verify(f).ok) bad++
        }
        assertEquals("mọi byte bị lật phải bị phát hiện", 4, bad)
    }

    private fun rebuild(src: Map<String, ByteArray>, name: String, drop: String? = null, change: Pair<String, ByteArray>? = null): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, c) in src) { if (n == drop) continue; z.putNextEntry(ZipEntry(n)); z.write(if (change != null && change.first == n) change.second else c); z.closeEntry() }
        }
        return f
    }

    @Test fun verifyDetectsEditedMissingAndExtraEntries() {
        val e = entries(write(listOf(session("s1", 8))).readBytes())
        assertTrue(DiagBundle.verify(rebuild(e, "same.zip")).ok)                                                     // dựng lại y hệt vẫn hợp lệ
        assertFalse(DiagBundle.verify(rebuild(e, "edit.zip", change = "sessions/s1/events.csv" to "x".toByteArray())).ok)
        assertFalse(DiagBundle.verify(rebuild(e, "drop.zip", drop = "sessions/s1/report.html")).ok)
        assertFalse(DiagBundle.verify(rebuild(e + ("extra.txt" to "x".toByteArray()), "extra.zip")).ok)
        assertFalse(DiagBundle.verify(rebuild(e, "nometa.zip", drop = "metadata.json")).ok)
        assertFalse(DiagBundle.verify(rebuild(e, "readme.zip", drop = "README.txt")).ok)
    }

    @Test fun verifyChecksExpectedSessionList() {
        val f = write(listOf(session("s1", 8)))
        assertTrue(DiagBundle.verify(f, listOf("s1")).ok); assertFalse(DiagBundle.verify(f, listOf("s1", "s2")).ok)
    }

    @Test fun verifyRejectsUnsafeEntryNames() {
        val e = entries(write(listOf(session("s1", 8))).readBytes())
        assertFalse(DiagBundle.verify(rebuild(e + ("../evil.txt" to "x".toByteArray()), "evil.zip")).ok)
    }
}
