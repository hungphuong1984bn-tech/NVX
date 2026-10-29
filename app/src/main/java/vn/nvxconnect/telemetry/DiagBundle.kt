package vn.nvxconnect.telemetry

import vn.nvxconnect.util.MiniJson
import vn.nvxconnect.util.MiniJson.int
import vn.nvxconnect.util.MiniJson.list
import vn.nvxconnect.util.MiniJson.obj
import vn.nvxconnect.util.MiniJson.text
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Một phiên đã đọc sẵn để đóng gói. [live] khác null nếu phiên đang ghi/tạm dừng (đọc để xuất, KHÔNG dừng phiên). */
class BundleSession(val data: DiagSessionData, val live: DiagSessionState? = null)

/** Thông tin ứng dụng ghi vào metadata (lấy từ metadata build, không gõ tay). */
data class BundleInfo(val appName: String, val versionName: String, val versionCode: Int, val applicationId: String)

data class BundleEntry(val name: String, val bytes: Long, val sha256: String)
data class BundleManifest(val entries: List<BundleEntry>, val sessionIds: List<String>)
data class BundleCheck(val ok: Boolean, val problems: List<String>, val entryCount: Int = 0)

/**
 * Đóng gói MỘT tệp ZIP duy nhất từ dữ liệu phiên chẩn đoán SẴN CÓ. Không thu thập thêm, không tạo số liệu: mọi nội dung đến từ
 * [DiagExporter] (HTML/CSV/JSON/biểu đồ SVG) và [DiagSummary]. Phiên không có mẫu thì không có biểu đồ (không vẽ giả).
 *
 * Cấu trúc:
 *   README.txt, metadata.json                      (tổng: phiên bản app, thời điểm xuất, tùy chọn ẩn danh, danh sách tệp + SHA-256)
 *   sessions/<id>/report.html                       báo cáo (đã có biểu đồ SVG nhúng)
 *   sessions/<id>/telemetry.csv                     bản ghi SAMPLE + MISSING (số liệu và dấu thiếu dữ liệu)
 *   sessions/<id>/events.csv                        bản ghi SESSION + BLE_EVENT + ERROR + NOTE
 *   sessions/<id>/metadata.json                     định dạng JSON hiện có, bỏ phần "records" (bản ghi đã nằm trong hai CSV)
 *   sessions/<id>/charts/<chuỗi>.svg                chỉ khi đủ mẫu thật (>= [DiagSummary.MIN_CHART_POINTS])
 */
object DiagBundle {
    const val FORMAT = "nvx-connect-export-bundle/1"
    private val TELEMETRY_KINDS = setOf(DiagKind.SAMPLE, DiagKind.MISSING)

    /** Ví dụ: NVX-Connect-v0.13.1-20261029-105300.zip (giờ theo múi giờ [tz]). */
    fun fileName(versionName: String, nowMs: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val f = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = tz }
        return "NVX-Connect-v${versionName.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }}-${f.format(Date(nowMs))}.zip"
    }

    /** Tránh ghi đè tệp cùng tên (xuất 2 lần trong 1 giây): thêm hậu tố -2, -3… */
    fun uniqueName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val base = name.removeSuffix(".zip")
        var n = 2
        while (File(dir, "$base-$n.zip").exists()) n++
        return "$base-$n.zip"
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun chartFile(id: String) = id.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }.joinToString("")

    /** Ghi ZIP vào [out] (không đóng [out]). Trả về danh mục để đối chiếu khi kiểm tra. */
    fun write(out: OutputStream, sessions: List<BundleSession>, info: BundleInfo, opts: DiagExportOptions, nowMs: Long): BundleManifest {
        require(sessions.isNotEmpty()) { "Không có phiên nào để xuất" }
        val ids = sessions.map { it.data.meta.id }
        require(ids.toSet().size == ids.size) { "Mã phiên bị trùng" }
        val zip = ZipOutputStream(out)
        val entries = ArrayList<BundleEntry>()
        fun put(name: String, bytes: ByteArray) {
            val e = ZipEntry(name).apply { time = nowMs }
            zip.putNextEntry(e); zip.write(bytes); zip.closeEntry()
            entries.add(BundleEntry(name, bytes.size.toLong(), sha256(bytes)))
        }
        fun render(block: (Appendable) -> Unit): ByteArray = StringBuilder().also(block).toString().toByteArray(Charsets.UTF_8)

        put("README.txt", readme(sessions, info, opts).toByteArray(Charsets.UTF_8))
        val sessionJson = ArrayList<String>()
        for (bs in sessions) {
            val data = bs.data; val m = data.meta; val dir = "sessions/${m.id}/"
            val summary = DiagSummary.compute(data, bs.live)
            put(dir + "report.html", render { DiagExporter.export(DiagFormat.HTML, data, opts, nowMs, it, bs.live) })
            put(dir + "telemetry.csv", render { DiagExporter.csv(data.copy(records = data.records.filter { r -> r.kind in TELEMETRY_KINDS }), opts, it) })
            put(dir + "events.csv", render { DiagExporter.csv(data.copy(records = data.records.filter { r -> r.kind !in TELEMETRY_KINDS }), opts, it) })
            put(dir + "metadata.json", render { DiagExporter.json(data.copy(records = emptyList()), summary, opts, nowMs, it) })
            val thr = maxOf(3_000L, m.sampleIntervalMs * 3); val used = HashSet<String>()
            val charts = ArrayList<String>()
            for (x in summary.chartable) {
                var f = chartFile(x.id); if (!used.add(f)) { var n = 2; while (!used.add("$f-$n")) n++; f = "$f-$n" }
                put(dir + "charts/$f.svg", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + DiagExporter.svgChart(x, m.startedWallMs, thr) + "\n").toByteArray(Charsets.UTF_8))
                charts.add("charts/$f.svg")
            }
            val files = listOf("report.html", "telemetry.csv", "events.csv", "metadata.json") + charts
            sessionJson.add("{\"id\":" + MiniJson.quote(m.id) + ",\"folder\":" + MiniJson.quote(dir) + ",\"label\":" + MiniJson.quote(DiagExporter.scrub(m.label, opts, m.id) ?: "") +
                ",\"state\":" + MiniJson.quote(summary.state.name) + ",\"verified_on_vehicle\":" + summary.verifiedOnVehicle +
                ",\"records\":" + summary.totalRecords + ",\"samples\":" + summary.sampleCount + ",\"charts\":" + charts.size +
                ",\"files\":[" + files.joinToString(",") { MiniJson.quote(it) } + "]}")
        }
        val fileList = entries.joinToString(",") { "{\"name\":" + MiniJson.quote(it.name) + ",\"bytes\":" + it.bytes + ",\"sha256\":" + MiniJson.quote(it.sha256) + "}" }
        val meta = "{\"format\":" + MiniJson.quote(FORMAT) +
            ",\"app\":{\"name\":" + MiniJson.quote(info.appName) + ",\"version_name\":" + MiniJson.quote(info.versionName) + ",\"version_code\":" + info.versionCode + ",\"application_id\":" + MiniJson.quote(info.applicationId) + "}" +
            ",\"exported_utc\":" + (if (opts.relativeTime) "null" else MiniJson.quote(DurableEventLog.iso(nowMs))) +
            ",\"read_only\":true,\"uploaded\":false,\"synthetic_data\":false" +
            ",\"privacy\":{\"redacted\":${opts.redact},\"notes_included\":${opts.includeNotes},\"relative_time\":${opts.relativeTime}}" +
            ",\"sessions\":[" + sessionJson.joinToString(",") + "],\"files\":[" + fileList + "]}\n"
        put("metadata.json", meta.toByteArray(Charsets.UTF_8))
        zip.finish(); zip.flush()
        return BundleManifest(entries.toList(), ids)
    }

    fun toBytes(sessions: List<BundleSession>, info: BundleInfo, opts: DiagExportOptions, nowMs: Long): Pair<ByteArray, BundleManifest> {
        val bo = ByteArrayOutputStream(); val mf = write(bo, sessions, info, opts, nowMs); return bo.toByteArray() to mf
    }

    private fun readme(sessions: List<BundleSession>, info: BundleInfo, opts: DiagExportOptions): String = buildString {
        fun l(x: String = "") = append(x).append('\n')
        l("NVX CONNECT — GÓI DỮ LIỆU KỸ THUẬT (một tệp ZIP)")
        l("Ứng dụng: ${info.appName} ${info.versionName} (mã phiên bản ${info.versionCode})")
        l("Số phiên trong gói: ${sessions.size}")
        l()
        l("NỘI DUNG")
        l("  metadata.json                    tổng hợp: phiên bản app, thời điểm xuất, tùy chọn ẩn danh, danh sách tệp kèm SHA-256")
        l("  sessions/<mã phiên>/report.html  báo cáo mở bằng trình duyệt (biểu đồ nhúng sẵn)")
        l("  sessions/<mã phiên>/telemetry.csv  số liệu (SAMPLE) và dấu thiếu dữ liệu (MISSING); ô trống = không có dữ liệu, KHÔNG phải số 0")
        l("  sessions/<mã phiên>/events.csv   sự kiện phiên/BLE, lỗi và ghi chú sự cố")
        l("  sessions/<mã phiên>/metadata.json  thống kê phiên theo định dạng nvx-connect-diag-log/1 (bản ghi chi tiết nằm ở hai tệp CSV)")
        l("  sessions/<mã phiên>/charts/*.svg biểu đồ riêng từng tham số; chỉ có khi đủ mẫu thật (>= ${DiagSummary.MIN_CHART_POINTS})")
        l()
        l("NGUYÊN TẮC")
        l("  - Chỉ chứa dữ liệu app đã ghi được. Không có số liệu giả/nội suy; khoảng thiếu dữ liệu được liệt kê, không lấp.")
        l("  - Chế độ CHỈ ĐỌC: app không gửi lệnh tới xe. Gói không được tự tải lên máy chủ; bạn chọn nơi gửi qua khung chia sẻ của Android.")
        l("  - Ẩn danh: ${if (opts.redact) "BẬT (mã thiết bị, MAC, VIN, email trong ghi chú đã che)" else "TẮT — gói có thể chứa thông tin nhận dạng"}. Ghi chú sự cố: ${if (opts.includeNotes) "có" else "đã loại"}. Thời gian tương đối: ${if (opts.relativeTime) "bật" else "tắt"}.")
        l("  - Nhật ký gốc trong app không bị xóa hay sửa khi xuất.")
        l()
        l("TRẠNG THÁI XÁC MINH TỪNG PHIÊN")
        for (s in sessions) l("  ${s.data.meta.id}: " + if (s.data.meta.verifiedOnVehicle) DiagVerification.VERIFIED_TEXT else DiagVerification.NOT_VERIFIED_TEXT)
    }

    /**
     * Kiểm tra ZIP đã ghi, chỉ dựa vào chính tệp (tự mô tả bằng metadata.json): mở được, mọi mục đọc hết không lỗi CRC,
     * SHA-256/kích thước khớp danh mục, đủ tệp bắt buộc cho từng phiên, tên mục an toàn, JSON/HTML/CSV/SVG hợp lệ ở mức cấu trúc.
     * [expectedSessionIds] (nếu có) phải khớp đúng danh sách phiên trong gói.
     */
    fun verify(file: File, expectedSessionIds: List<String>? = null): BundleCheck {
        val problems = ArrayList<String>()
        if (!file.isFile || file.length() < 22) return BundleCheck(false, listOf("Tệp ZIP không tồn tại hoặc rỗng"))
        val actual = HashMap<String, BundleEntry>(); val content = HashMap<String, ByteArray>()
        try {
            ZipFile(file).use { z ->
                val names = z.entries().toList().map { it.name }
                if (names.size != names.toSet().size) problems.add("Có mục trùng tên trong ZIP")
                names.filter { it.startsWith("/") || it.contains("..") || it.contains("\\") }.forEach { problems.add("Tên mục không an toàn: $it") }
            }
            ZipInputStream(file.inputStream().buffered()).use { zin ->       // ZipInputStream kiểm tra CRC khi đọc hết từng mục
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    val b = zin.readBytes()
                    actual[e.name] = BundleEntry(e.name, b.size.toLong(), sha256(b))
                    if (e.name.endsWith(".json") || e.name.endsWith(".html") || e.name.endsWith(".csv") || e.name.endsWith(".svg") || e.name == "README.txt") content[e.name] = b
                }
            }
        } catch (e: Exception) { return BundleCheck(false, problems + ("ZIP hỏng hoặc không đọc được: " + (e.message ?: e.javaClass.simpleName))) }

        val metaBytes = content["metadata.json"]
        if (metaBytes == null) return BundleCheck(false, problems + "Thiếu metadata.json", actual.size)
        val meta = try { MiniJson.parse(String(metaBytes, Charsets.UTF_8)).obj() } catch (e: Exception) { null }
        if (meta == null) return BundleCheck(false, problems + "metadata.json không phải JSON hợp lệ", actual.size)
        if (meta["format"].text() != FORMAT) problems.add("Sai định dạng metadata: ${meta["format"]}")
        if (meta["app"].obj()?.get("version_name").text().isNullOrBlank()) problems.add("metadata thiếu phiên bản ứng dụng")
        if (actual["README.txt"] == null) problems.add("Thiếu README.txt")

        // Danh mục tệp trong metadata phải khớp CHÍNH XÁC những gì có trong ZIP (trừ chính metadata.json).
        val listed = meta["files"].list().orEmpty().mapNotNull { it.obj() }
        val listedNames = listed.mapNotNull { it["name"].text() }.toSet()
        for (f in listed) {
            val n = f["name"].text() ?: continue; val a = actual[n]
            if (a == null) { problems.add("Thiếu tệp đã liệt kê: $n"); continue }
            if ((f["bytes"] as? Number)?.toLong() != a.bytes) problems.add("Sai kích thước: $n")
            if (f["sha256"].text() != a.sha256) problems.add("Sai SHA-256: $n")
            if (a.bytes == 0L) problems.add("Tệp rỗng: $n")
        }
        for (n in actual.keys) if (n != "metadata.json" && n !in listedNames) problems.add("Tệp không có trong danh mục: $n")

        val sessions = meta["sessions"].list().orEmpty().mapNotNull { it.obj() }
        if (sessions.isEmpty()) problems.add("Gói không có phiên nào")
        val ids = sessions.mapNotNull { it["id"].text() }
        if (expectedSessionIds != null && ids.sorted() != expectedSessionIds.sorted()) problems.add("Danh sách phiên trong gói không khớp dự kiến")
        for (s in sessions) {
            val id = s["id"].text() ?: continue; val dir = "sessions/$id/"
            for (req in listOf("report.html", "telemetry.csv", "events.csv", "metadata.json")) if (actual[dir + req] == null) problems.add("Phiên $id thiếu $req")
            content[dir + "report.html"]?.let { val t = String(it, Charsets.UTF_8).trim(); if (!t.startsWith("<!doctype html", ignoreCase = true) || !t.endsWith("</html>")) problems.add("report.html của $id không trọn vẹn") }
            for (c in listOf("telemetry.csv", "events.csv")) content[dir + c]?.let { if (!String(it, Charsets.UTF_8).startsWith(DiagExporter.CSV_HEADER + "\n")) problems.add("$c của $id sai tiêu đề cột") }
            content[dir + "metadata.json"]?.let { b -> val o = try { MiniJson.parse(String(b, Charsets.UTF_8)).obj() } catch (e: Exception) { null }
                if (o == null || o["format"].text() != DiagExporter.JSON_FORMAT) problems.add("metadata.json của $id không hợp lệ") }
            val declared = s["charts"].int() ?: 0
            val found = actual.keys.count { it.startsWith(dir + "charts/") }
            if (declared != found) problems.add("Số biểu đồ của $id không khớp ($declared khai báo, $found trong gói)")
            for ((n, b) in content) if (n.startsWith(dir + "charts/")) { val t = String(b, Charsets.UTF_8); if (!t.contains("<svg") || !t.trimEnd().endsWith("</svg>")) problems.add("Biểu đồ hỏng: $n") }
        }
        return BundleCheck(problems.isEmpty(), problems, actual.size)
    }
}
