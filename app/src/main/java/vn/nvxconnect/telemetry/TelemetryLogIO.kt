package vn.nvxconnect.telemetry

import vn.nvxconnect.bluetooth.GattServiceInfo
import vn.nvxconnect.bluetooth.HexUtil
import vn.nvxconnect.bluetooth.LogExporter
import vn.nvxconnect.protocol.FrameRedactor
import vn.nvxconnect.util.MiniJson
import vn.nvxconnect.util.MiniJson.list
import vn.nvxconnect.util.MiniJson.obj
import vn.nvxconnect.util.MiniJson.text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Một characteristic trong cây GATT đã nạp (từ log xuất ra). */
data class GattCharRow(val deviceId: String, val serviceUuid: String, val charUuid: String, val properties: List<String>, val descriptors: List<String>) {
    val canNotify get() = "NOTIFY" in properties
    val canIndicate get() = "INDICATE" in properties
    val canRead get() = "READ" in properties
    val canWrite get() = "WRITE" in properties || "WRITE_NO_RESP" in properties
}

/** Kết quả nạp log. [warnings] ghi mọi dòng bị bỏ/không hiểu: không bao giờ bỏ qua âm thầm. */
data class ImportedLog(
    val format: String, val createdUtc: String?, val records: List<PayloadRecord>, val events: List<ConnEvent>,
    val gatt: List<GattCharRow>, val devices: List<DeviceInfo>, val droppedOldest: Long, val warnings: List<String>
)

object TelemetryLogExporter {
    const val FORMAT = "nvx-connect-telemetry-log/3"
    const val RESEARCH_NOTE = "Log khảo sát BLE. Các khung 0x6B/0x6C (nếu có) là khung ĐANG NGHIÊN CỨU, chưa mặc định là dữ liệu động cơ đã xác minh. " +
        "Mọi trường chỉ là RAW/giả thuyết cho tới khi có đối chiếu độc lập."

    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    private fun hexOut(hex: String, redact: Boolean): String {
        if (!redact) return hex
        val b = HexUtil.fromHex(hex) ?: return "(hex không hợp lệ)"
        return FrameRedactor.hexRedacted(b)
    }
    private fun strList(l: Collection<String>) = l.joinToString(",", "[", "]") { MiniJson.quote(it) }

    /** gatt: khóa là deviceId ĐÃ ẩn danh (cùng hàm ẩn danh với recorder). */
    fun toJson(rec: RawFrameRecorder, gatt: Map<String, List<GattServiceInfo>>, appVersion: String, nowMs: Long,
               redact: Boolean = true, filter: RecordFilter = RecordFilter(), salt: String = ""): String = buildString {
        fun t(s: String) = if (redact && salt.isNotEmpty()) LogExporter.redactText(s, salt) else s
        append("{\"format\":").append(MiniJson.quote(FORMAT))
        append(",\"created_utc\":").append(MiniJson.quote(iso(nowMs)))
        append(",\"app_version\":").append(MiniJson.quote(appVersion))
        append(",\"redacted\":").append(redact)
        append(",\"note\":").append(MiniJson.quote(RESEARCH_NOTE))
        append(",\"limits\":{\"dropped_oldest\":${rec.droppedOldest},\"dropped_events\":${rec.droppedEvents},\"ignored_while_stopped\":${rec.ignoredWhileStopped}}")
        append(",\"devices\":[").append(rec.deviceList().joinToString(",") { d ->
            "{\"id\":${MiniJson.quote(d.deviceId)},\"name\":${MiniJson.quoteOrNull(d.name)},\"service_uuids\":${strList(d.serviceUuids)},\"last_rssi\":${d.lastRssi ?: "null"}}" })
        append("],\"gatt\":[").append(gatt.entries.flatMap { (dev, svcs) -> svcs.map { s ->
            "{\"device\":${MiniJson.quote(dev)},\"service\":${MiniJson.quote(s.uuid)},\"characteristics\":[" + s.characteristics.joinToString(",") { c ->
                "{\"uuid\":${MiniJson.quote(c.uuid)},\"properties\":${strList(c.properties)},\"descriptors\":${strList(c.descriptors.map { it.uuid })}}" } + "]}" } }.joinToString(","))
        append("],\"events\":[").append(rec.eventsSnapshot().joinToString(",") { e ->
            "{\"time_ms\":${e.timeMs},\"session\":${MiniJson.quote(e.sessionId)},\"device\":${MiniJson.quote(e.deviceId)},\"type\":${MiniJson.quote(e.type.name)}," +
                "\"uuid\":${MiniJson.quoteOrNull(e.uuid)},\"detail\":${MiniJson.quoteOrNull(e.detail?.let { t(it) })}}" })
        append("],\"payloads\":[").append(rec.snapshot(filter).joinToString(",") { r ->
            "{\"seq\":${r.seq},\"time_ms\":${r.timeMs},\"time_utc\":${MiniJson.quote(iso(r.timeMs))},\"session\":${MiniJson.quote(r.sessionId)},\"device\":${MiniJson.quote(r.deviceId)}," +
                "\"source\":${MiniJson.quote(r.source.name)},\"service\":${MiniJson.quoteOrNull(r.serviceUuid)},\"char\":${MiniJson.quoteOrNull(r.charUuid)},\"rssi\":${r.rssi ?: "null"}," +
                "\"length\":${r.length},\"flags\":${strList(r.flags.map { it.name })},\"hex\":${MiniJson.quote(hexOut(r.hex, redact))}}" })
        append("],\"stream_stats\":[").append(rec.streamStats().entries.joinToString(",") { (k, s) ->
            "{\"device\":${MiniJson.quote(k.deviceId)},\"char\":${MiniJson.quoteOrNull(k.charUuid)},\"source\":${MiniJson.quote(k.source.name)},\"count\":${s.count},\"bytes\":${s.bytes}," +
                "\"empty\":${s.empty},\"duplicates\":${s.duplicates},\"abnormal\":${s.abnormal},\"first_ms\":${s.firstMs},\"last_ms\":${s.lastMs}}" })
        append("]}")
    }

    const val CSV_HEADER = "seq,time_ms,time_utc,session,device,source,service_uuid,char_uuid,rssi,length,flags,hex"

    fun toCsv(rec: RawFrameRecorder, redact: Boolean = true, filter: RecordFilter = RecordFilter()): String = buildString {
        appendLine(CSV_HEADER)
        for (r in rec.snapshot(filter)) {
            appendLine(listOf(r.seq.toString(), r.timeMs.toString(), LogExporter.csvCell(iso(r.timeMs)), LogExporter.csvCell(r.sessionId), LogExporter.csvCell(r.deviceId),
                r.source.name, LogExporter.csvCell(r.serviceUuid ?: ""), LogExporter.csvCell(r.charUuid ?: ""), (r.rssi?.toString() ?: ""), r.length.toString(),
                LogExporter.csvCell(r.flags.joinToString("|") { it.name }), LogExporter.csvCell(hexOut(r.hex, redact))).joinToString(","))
        }
    }
}

object TelemetryLogImport {
    private fun Any?.long(): Long? = (this as? Number)?.toLong() ?: (this as? String)?.toLongOrNull()

    /** Nạp log JSON (định dạng /2 của bản cũ hoặc /3) hoặc CSV (cũ hoặc mới). Không ném lỗi: sự cố đi vào [ImportedLog.warnings]. */
    fun parse(text: String): ImportedLog {
        val t = text.trim().removePrefix("\uFEFF")
        return try {
            when {
                t.startsWith("{") -> parseJson(t)
                t.isNotEmpty() -> parseCsv(t)
                else -> empty("Tệp rỗng")
            }
        } catch (e: MiniJson.JsonException) { empty("JSON không hợp lệ: ${e.message}") }
        catch (e: RuntimeException) { empty("Không đọc được tệp: ${e.message}") }
    }

    private fun empty(w: String) = ImportedLog("unknown", null, emptyList(), emptyList(), emptyList(), emptyList(), 0, listOf(w))

    private fun parseJson(t: String): ImportedLog {
        val root = MiniJson.parse(t).obj() ?: return empty("Gốc JSON không phải đối tượng")
        val fmt = root["format"].text() ?: "unknown"
        val warns = ArrayList<String>()
        return when {
            fmt.endsWith("/3") -> parseV3(root, fmt, warns)
            fmt.endsWith("/2") || fmt.startsWith("nvx-connect-ble-log") -> parseV2(root, fmt, warns)
            else -> empty("Định dạng không nhận ra: $fmt")
        }
    }

    private fun gattRowsV3(root: Map<String, Any?>): List<GattCharRow> = root["gatt"].list().orEmpty().flatMap { s ->
        val o = s.obj() ?: return@flatMap emptyList<GattCharRow>()
        o["characteristics"].list().orEmpty().mapNotNull { c ->
            val co = c.obj() ?: return@mapNotNull null
            GattCharRow(o["device"].text() ?: "unknown", (o["service"].text() ?: "").lowercase(), (co["uuid"].text() ?: return@mapNotNull null).lowercase(),
                co["properties"].list().orEmpty().mapNotNull { it.text() }, co["descriptors"].list().orEmpty().mapNotNull { it.text() })
        }
    }

    private fun parseV3(root: Map<String, Any?>, fmt: String, warns: MutableList<String>): ImportedLog {
        val recs = ArrayList<PayloadRecord>()
        for ((i, p) in root["payloads"].list().orEmpty().withIndex()) {
            val o = p.obj(); if (o == null) { warns += "payloads[$i] không phải đối tượng"; continue }
            val src = runCatching { PayloadSource.valueOf(o["source"].text() ?: "") }.getOrNull()
            val ms = o["time_ms"].long(); val hex = o["hex"].text()
            if (src == null || ms == null || hex == null) { warns += "payloads[$i] thiếu source/time_ms/hex"; continue }
            val flags = o["flags"].list().orEmpty().mapNotNull { f -> runCatching { PayloadFlag.valueOf(f.text() ?: "") }.getOrNull() }.toSet()
            recs += PayloadRecord(o["seq"].long() ?: (i + 1L), ms, o["session"].text() ?: "s?", o["device"].text() ?: "unknown", src,
                o["service"].text(), o["char"].text(), (o["rssi"] as? Number)?.toInt(), hex, flags)
        }
        val events = root["events"].list().orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val ty = runCatching { ConnEventType.valueOf(o["type"].text() ?: "") }.getOrNull() ?: return@mapNotNull null
            ConnEvent(o["time_ms"].long() ?: return@mapNotNull null, o["session"].text() ?: "s?", o["device"].text() ?: "unknown", ty, o["uuid"].text(), o["detail"].text())
        }
        val devs = root["devices"].list().orEmpty().mapNotNull { d ->
            val o = d.obj() ?: return@mapNotNull null
            DeviceInfo(o["id"].text() ?: return@mapNotNull null, o["name"].text(), o["service_uuids"].list().orEmpty().mapNotNull { it.text() }, (o["rssi"] as? Number)?.toInt() ?: (o["last_rssi"] as? Number)?.toInt(), 0L)
        }
        val dropped = root["limits"].obj()?.get("dropped_oldest").long() ?: 0L
        return ImportedLog(fmt, root["created_utc"].text(), recs.sortedBy { it.seq }, events, gattRowsV3(root), devs, dropped, warns)
    }

    /** Log bản cũ (/2): không có thiết bị theo từng gói; gán thiết bị duy nhất nếu chỉ có một cây GATT, ngược lại "unknown". */
    private fun parseV2(root: Map<String, Any?>, fmt: String, warns: MutableList<String>): ImportedLog {
        val gattObj = root["gatt"].obj().orEmpty()
        val rows = gattObj.flatMap { (dev, svcs) -> svcs.list().orEmpty().flatMap { s ->
            val so = s.obj() ?: return@flatMap emptyList<GattCharRow>()
            so["characteristics"].list().orEmpty().mapNotNull { c ->
                val co = c.obj() ?: return@mapNotNull null
                GattCharRow(dev, (so["service"].text() ?: "").lowercase(), (co["uuid"].text() ?: return@mapNotNull null).lowercase(),
                    co["properties"].list().orEmpty().mapNotNull { it.text() }, co["descriptors"].list().orEmpty().mapNotNull { it.text() })
            }
        } }
        val dev = gattObj.keys.singleOrNull() ?: "unknown"
        if (gattObj.size > 1) warns += "Log /2 có ${gattObj.size} thiết bị GATT: không gán được thiết bị cho từng gói, dùng 'unknown'"
        val recs = ArrayList<PayloadRecord>(); var seq = 0L
        for ((i, e) in root["events"].list().orEmpty().withIndex()) {
            val o = e.obj() ?: continue
            val hex = o["hex"].text() ?: continue
            val kind = o["kind"].text(); val ms = o["time_ms"].long() ?: run { warns += "events[$i] thiếu time_ms"; null } ?: continue
            val src = when { kind == "FRAME" -> PayloadSource.NOTIFICATION; kind == "GATT" && (o["message"].text() ?: "").startsWith("Đọc") -> PayloadSource.READ; else -> continue }
            val cu = o["uuid"].text()?.lowercase()
            recs += PayloadRecord(++seq, ms, "s1", dev, src, rows.firstOrNull { it.charUuid == cu }?.serviceUuid, cu, null, hex)
        }
        val adverts = root["adverts"].list().orEmpty().mapNotNull { a ->
            val o = a.obj() ?: return@mapNotNull null
            DeviceInfo(o["address"].text() ?: return@mapNotNull null, o["name"].text(), o["service_uuids"].list().orEmpty().mapNotNull { it.text() }, (o["rssi"] as? Number)?.toInt(), o["seen_at_ms"].long() ?: 0L)
        }.distinctBy { it.deviceId }
        return ImportedLog(fmt, root["created_utc"].text(), refineIndicate(recs, rows), emptyList(), rows, adverts, 0, warns)
    }

    private fun refineIndicate(recs: List<PayloadRecord>, rows: List<GattCharRow>): List<PayloadRecord> = recs.map { r ->
        val g = rows.firstOrNull { it.charUuid == r.charUuid }
        if (r.source == PayloadSource.NOTIFICATION && g != null && g.canIndicate && !g.canNotify) r.copy(source = PayloadSource.INDICATION) else r
    }

    /** Tách CSV có ngoặc kép (RFC 4180 rút gọn). */
    internal fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                q && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> q = !q
                c == ',' && !q -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString(); return out
    }
    private fun unquoteCell(s: String) = if (s.length >= 2 && s[0] == '\'' && s[1] in "=+-@\t\r") s.substring(1) else s

    private fun parseCsv(t: String): ImportedLog {
        val lines = t.lines().filter { it.isNotBlank() }
        val header = splitCsv(lines.first()).map { it.trim() }
        val warns = ArrayList<String>(); val recs = ArrayList<PayloadRecord>()
        fun col(n: String) = header.indexOf(n)
        return when {
            header.contains("source") && header.contains("hex") && header.contains("time_ms") -> {
                val iSeq = col("seq"); val iMs = col("time_ms"); val iSes = col("session"); val iDev = col("device"); val iSrc = col("source")
                val iSvc = col("service_uuid"); val iCh = col("char_uuid"); val iRssi = col("rssi"); val iFlags = col("flags"); val iHex = col("hex")
                for ((n, line) in lines.drop(1).withIndex()) {
                    val c = splitCsv(line)
                    val src = runCatching { PayloadSource.valueOf(c.getOrNull(iSrc) ?: "") }.getOrNull()
                    val ms = c.getOrNull(iMs)?.toLongOrNull()
                    if (src == null || ms == null || c.size <= iHex) { warns += "Dòng ${n + 2}: thiếu source/time_ms/hex"; continue }
                    val flags = (c.getOrNull(iFlags) ?: "").split('|').filter { it.isNotEmpty() }.mapNotNull { f -> runCatching { PayloadFlag.valueOf(f) }.getOrNull() }.toSet()
                    recs += PayloadRecord(c.getOrNull(iSeq)?.toLongOrNull() ?: (n + 1L), ms, c.getOrNull(iSes)?.ifEmpty { null } ?: "s?", c.getOrNull(iDev)?.ifEmpty { null } ?: "unknown", src,
                        c.getOrNull(iSvc)?.ifEmpty { null }?.let(::unquoteCell), c.getOrNull(iCh)?.ifEmpty { null }?.let(::unquoteCell), c.getOrNull(iRssi)?.toIntOrNull(), unquoteCell(c[iHex]), flags)
                }
                ImportedLog(TelemetryLogExporter.FORMAT + " (csv)", null, recs.sortedBy { it.seq }, emptyList(), emptyList(), emptyList(), 0, warns)
            }
            header.contains("kind") && header.contains("hex") && header.contains("time_ms") -> {
                val iMs = col("time_ms"); val iKind = col("kind"); val iU = col("uuid"); val iHex = col("hex"); val iMsg = col("message"); var seq = 0L
                for ((n, line) in lines.drop(1).withIndex()) {
                    val c = splitCsv(line); val hex = c.getOrNull(iHex)?.let(::unquoteCell) ?: continue
                    if (hex.isEmpty()) continue
                    val ms = c.getOrNull(iMs)?.toLongOrNull() ?: run { warns += "Dòng ${n + 2}: thiếu time_ms"; null } ?: continue
                    val kind = c.getOrNull(iKind)
                    val src = when { kind == "FRAME" -> PayloadSource.NOTIFICATION; kind == "GATT" && (c.getOrNull(iMsg) ?: "").startsWith("Đọc") -> PayloadSource.READ; else -> continue }
                    recs += PayloadRecord(++seq, ms, "s1", "unknown", src, null, c.getOrNull(iU)?.ifEmpty { null }?.lowercase(), null, hex)
                }
                warns += "CSV bản cũ không có thiết bị/service từng gói: thiết bị = 'unknown'"
                ImportedLog("nvx-connect-ble-log (csv /2)", null, recs, emptyList(), emptyList(), emptyList(), 0, warns)
            }
            else -> empty("CSV: không nhận ra tiêu đề cột (${header.take(6).joinToString(",")}…)")
        }
    }
}
