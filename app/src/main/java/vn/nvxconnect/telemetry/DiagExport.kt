package vn.nvxconnect.telemetry

import vn.nvxconnect.bluetooth.LogExporter
import vn.nvxconnect.util.MiniJson
import java.util.Locale

/**
 * Tùy chọn xuất. Mặc định là an toàn nhất có thể mà vẫn hữu ích: ẩn mã thiết bị, che MAC/VIN/email trong ghi chú.
 * [relativeTime] = bỏ ngày giờ tuyệt đối, chỉ giữ giây tính từ đầu phiên.
 */
data class DiagExportOptions(val redact: Boolean = true, val includeNotes: Boolean = true, val relativeTime: Boolean = false)

enum class DiagFormat(val ext: String, val mime: String, val title: String) {
    CSV("csv", "text/csv", "CSV"), JSON("json", "application/json", "JSON"), TXT("txt", "text/plain", "Báo cáo TXT"), HTML("html", "text/html", "Báo cáo HTML")
}

/**
 * Xuất phiên ra CSV/JSON/TXT/HTML, ghi tuần tự vào [Appendable] (không dựng cả chuỗi lớn trong bộ nhớ khi xuất tệp).
 * Quy tắc trung thực: chỉ xuất bản ghi đã có; khoảng thiếu được LIỆT KÊ chứ không lấp; biểu đồ ngắt đường tại khoảng trống, không nối/nội suy.
 * Không xuất tọa độ GPS (nhật ký không bao giờ lưu tọa độ), không xuất địa chỉ MAC (nhật ký chỉ có mã băm ẩn danh).
 */
object DiagExporter {
    const val JSON_FORMAT = "nvx-connect-diag-log/1"
    private val VIN = Regex("\\b[A-HJ-NPR-Z0-9]{17}\\b")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    fun fileName(meta: DiagMeta, f: DiagFormat) = "nvx-diag-${meta.id}.${f.ext}"

    fun export(f: DiagFormat, data: DiagSessionData, opts: DiagExportOptions, nowMs: Long, out: Appendable, liveState: DiagSessionState? = null) {
        val s = DiagSummary.compute(data, liveState)
        when (f) { DiagFormat.CSV -> csv(data, opts, out); DiagFormat.JSON -> json(data, s, opts, nowMs, out); DiagFormat.TXT -> txt(data, s, opts, nowMs, out); DiagFormat.HTML -> html(data, s, opts, nowMs, out) }
    }

    fun exportToString(f: DiagFormat, data: DiagSessionData, opts: DiagExportOptions, nowMs: Long, liveState: DiagSessionState? = null): String =
        StringBuilder().also { export(f, data, opts, nowMs, it, liveState) }.toString()

    // ------------------------------------------------------------------------------------------ che thông tin
    internal fun scrub(text: String?, opts: DiagExportOptions, salt: String): String? {
        if (text == null) return null
        if (!opts.redact) return text
        return LogExporter.redactText(text, salt).let { VIN.replace(it, "[VIN ẩn]") }.let { EMAIL.replace(it, "[email ẩn]") }
    }
    private fun deviceText(m: DiagMeta, opts: DiagExportOptions) = if (m.deviceKey == null) "(không có)" else if (opts.redact) "(đã ẩn)" else m.deviceKey
    private fun iso(ms: Long) = DurableEventLog.iso(ms)
    private fun fmt(v: Double): String = if (v == Math.rint(v) && abs1(v) < 1e12) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
    private fun abs1(v: Double) = if (v < 0) -v else v
    private fun dur(ms: Long): String { val s = ms / 1000; return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) }
    private fun attrsText(r: DiagRecord, opts: DiagExportOptions, salt: String) = r.attrs.entries.joinToString(";") { it.key + "=" + (scrub(it.value, opts, salt) ?: "") }
    private fun noteVisible(r: DiagRecord, opts: DiagExportOptions) = r.kind != DiagKind.NOTE || opts.includeNotes

    // ------------------------------------------------------------------------------------------ CSV
    const val CSV_HEADER = "time_utc,time_ms,t_rel_s,mono_ms,seq,kind,name,value,unit,class,source,text,attrs"

    fun csv(data: DiagSessionData, opts: DiagExportOptions, out: Appendable) {
        val t0 = data.meta.startedWallMs; val salt = data.meta.id
        out.append(CSV_HEADER).append('\n')
        for (r in data.records.sortedBy { it.seq }) {
            if (!noteVisible(r, opts)) continue
            val cells = listOf(
                if (opts.relativeTime) "" else iso(r.wallMs), if (opts.relativeTime) "" else r.wallMs.toString(),
                fmt((r.wallMs - t0) / 1000.0), r.monoMs.toString(), r.seq.toString(), r.kind.name, LogExporter.csvCell(r.name),
                r.value?.let { fmt(it) } ?: "",              // thiếu dữ liệu = ô TRỐNG (không điền 0, không nội suy)
                LogExporter.csvCell(r.unit ?: ""), r.cls?.name ?: "", r.source?.name ?: "",
                LogExporter.csvCell(scrub(r.text, opts, salt) ?: ""), LogExporter.csvCell(attrsText(r, opts, salt)))
            out.append(cells.joinToString(",")).append('\n')
        }
    }

    // ------------------------------------------------------------------------------------------ JSON
    private fun jn(v: Double) = if (v.isFinite()) v.toString() else "null"

    fun json(data: DiagSessionData, s: DiagSummary, opts: DiagExportOptions, nowMs: Long, out: Appendable) {
        val m = data.meta; val salt = m.id; val t0 = m.startedWallMs
        fun q(x: String) = MiniJson.quote(x)
        fun ts(ms: Long) = if (opts.relativeTime) "null" else q(iso(ms))
        out.append("{\"format\":").append(q(JSON_FORMAT))
        out.append(",\"exported_utc\":").append(ts(nowMs)).append(",\"app_version\":").append(q(m.appVersion))
        out.append(",\"verified_on_vehicle\":").append(s.verifiedOnVehicle.toString()).append(",\"verification\":").append(q(s.verificationText))
        out.append(",\"read_only\":true,\"uploaded\":false")
        out.append(",\"privacy\":{\"redacted\":${opts.redact},\"notes_included\":${opts.includeNotes},\"relative_time\":${opts.relativeTime},\"gps_coordinates_stored\":false,\"mac_address_stored\":false}")
        out.append(",\"session\":{\"id\":").append(q(m.id)).append(",\"label\":").append(q(scrub(m.label, opts, salt) ?: ""))
        out.append(",\"state\":").append(q(s.state.name)).append(",\"started_utc\":").append(ts(s.startedWallMs))
        out.append(",\"ended_utc\":").append(s.endedWallMs?.let { ts(it) } ?: "null").append(",\"active_ms\":${s.activeMs},\"paused_ms\":${s.pausedMs}")
        out.append(",\"sample_interval_ms\":${m.sampleIntervalMs},\"device\":").append(q(deviceText(m, opts))).append(",\"adapter\":").append(MiniJson.quoteOrNull(m.adapterId)).append('}')
        out.append(",\"stats\":{\"records\":${s.totalRecords},\"samples\":${s.sampleCount},\"link_lost\":${s.linkLost},\"ready_count\":${s.readyCount},\"reconnect_attempts\":${s.reconnectAttempts}")
        out.append(",\"attempt_failed\":${s.attemptFailed},\"interruptions\":${s.interruptedCount},\"clock_jumps\":${s.clockJumps},\"limit_hit\":${s.limitHit}")
        out.append(",\"dropped_limit\":${s.droppedLimit},\"dropped_low_storage\":${s.droppedLowStorage},\"dropped_error\":${s.droppedError},\"paused_ignored\":${s.pausedIgnored}")
        out.append(",\"corrupt_lines\":${s.corruptLines},\"torn_tail\":${s.tornTail}")
        out.append(",\"errors\":{").append(s.errorCounts.entries.joinToString(",") { q(it.key) + ":" + it.value }).append("}")
        out.append(",\"ble_events\":{").append(s.bleEventCounts.entries.joinToString(",") { q(it.key) + ":" + it.value }).append("}")
        fun ss(k: String, x: SimpleStat?) = if (x == null) "" else ",\"$k\":{\"count\":${x.count},\"min\":${jn(x.min)},\"max\":${jn(x.max)},\"mean\":${jn(x.mean)}}"
        out.append(ss("connect_ms", s.connectMs)).append(ss("first_frame_ms", s.firstFrameMs)).append(ss("silence_ms", s.silenceMs)).append('}')
        out.append(",\"series\":[")
        out.append(s.series.joinToString(",") { x ->
            "{\"id\":${q(x.id)},\"title\":${q(x.title)},\"unit\":${q(x.unit)},\"class\":${q(x.cls.name)},\"source\":${x.source?.let { q(it.name) } ?: "null"},\"count\":${x.count}," +
                "\"min\":${jn(x.min)},\"max\":${jn(x.max)},\"mean\":${jn(x.mean)},\"gaps_total_ms\":${x.gapTotalMs},\"gaps\":[" +
                x.gaps.joinToString(",") { g -> "{\"from_rel_s\":${jn((g.fromWallMs - t0) / 1000.0)},\"to_rel_s\":${jn((g.toWallMs - t0) / 1000.0)},\"ms\":${g.ms}}" } + "]}"
        })
        out.append("],\"missing_marks\":[")
        out.append(s.missing.joinToString(",") { r -> "{\"rel_s\":${jn((r.wallMs - t0) / 1000.0)},\"id\":${q(r.name)},\"class\":${r.cls?.let { q(it.name) } ?: "null"},\"reason\":${q(scrub(r.text, opts, salt) ?: "")}}" })
        out.append("],\"records\":[")
        var first = true
        for (r in data.records.sortedBy { it.seq }) {
            if (!noteVisible(r, opts)) continue
            if (!first) out.append(','); first = false
            out.append("{\"seq\":${r.seq},\"time_utc\":").append(ts(r.wallMs)).append(",\"time_ms\":").append(if (opts.relativeTime) "null" else r.wallMs.toString())
            out.append(",\"t_rel_s\":${jn((r.wallMs - t0) / 1000.0)},\"mono_ms\":${r.monoMs},\"kind\":${q(r.kind.name)},\"name\":${q(r.name)}")
            out.append(",\"value\":${r.value?.let { jn(it) } ?: "null"},\"unit\":${MiniJson.quoteOrNull(r.unit)},\"class\":${MiniJson.quoteOrNull(r.cls?.name)},\"source\":${MiniJson.quoteOrNull(r.source?.name)}")
            out.append(",\"text\":${MiniJson.quoteOrNull(scrub(r.text, opts, salt))},\"attrs\":{")
            out.append(r.attrs.entries.joinToString(",") { q(it.key) + ":" + q(scrub(it.value, opts, salt) ?: "") }).append("}}")
        }
        out.append("]}\n")
    }

    // ------------------------------------------------------------------------------------------ TXT
    fun txt(data: DiagSessionData, s: DiagSummary, opts: DiagExportOptions, nowMs: Long, out: Appendable) {
        val m = data.meta; val salt = m.id; val t0 = m.startedWallMs
        fun t(ms: Long) = if (opts.relativeTime) "+" + fmt((ms - t0) / 1000.0) + " s" else iso(ms)
        fun line(x: String = "") { out.append(x).append('\n') }
        line("NVX CONNECT — BÁO CÁO CHẨN ĐOÁN XE (Advanced Vehicle Data Logger)")
        line("*** ${s.verificationText.uppercase()} ***" + if (!s.verifiedOnVehicle) " — số liệu chỉ phản ánh những gì app quan sát được, chưa phải kết luận về xe." else "")
        line("Chế độ: CHỈ ĐỌC — app không gửi lệnh điều khiển hay thay đổi ECU; dữ liệu không được tải lên máy chủ.")
        line()
        line("PHIÊN"); line("  Mã phiên      : ${m.id}"); line("  Nhãn          : ${scrub(m.label, opts, salt)}")
        line("  Trạng thái    : ${s.state.title}"); line("  Bắt đầu       : ${t(s.startedWallMs)}"); line("  Kết thúc/cuối : ${s.endedWallMs?.let { t(it) } ?: "—"}")
        line("  Thời gian ghi : ${dur(s.activeMs)} (không tính tạm dừng ${dur(s.pausedMs)})"); line("  Chu kỳ mẫu   : ${m.sampleIntervalMs} ms")
        line("  Thiết bị      : ${deviceText(m, opts)}"); line("  Phiên bản app : ${m.appVersion}")
        line()
        line("TỔNG QUAN: ${s.totalRecords} bản ghi, ${s.sampleCount} mẫu số liệu, ${s.notes.size} ghi chú, ${s.errorCounts.values.sum()} lỗi")
        line("  Ngắt kết nối (mất liên kết): ${s.linkLost} · Sẵn sàng: ${s.readyCount} · Lần thử nối lại: ${s.reconnectAttempts} · Nối thất bại: ${s.attemptFailed}")
        line("  Gián đoạn do app đóng đột ngột: ${s.interruptedCount} · Nhảy đồng hồ hệ thống: ${s.clockJumps}")
        s.connectMs?.let { line("  Thời gian nối → sẵn sàng : n=${it.count}, min ${fmt(it.min)} / TB ${fmt(it.mean)} / max ${fmt(it.max)} ms") }
        s.firstFrameMs?.let { line("  Sẵn sàng → gói đầu tiên  : n=${it.count}, min ${fmt(it.min)} / TB ${fmt(it.mean)} / max ${fmt(it.max)} ms") }
        s.silenceMs?.let { line("  Khoảng lặng từ gói gần nhất: n=${it.count}, TB ${fmt(it.mean)} / max ${fmt(it.max)} ms") }
        if (s.errorCounts.isNotEmpty()) { line("  Lỗi theo loại: " + s.errorCounts.entries.joinToString(", ") { "${it.key}×${it.value}" }) }
        line()
        line("TOÀN VẸN DỮ LIỆU")
        if (s.limitHit || s.droppedLimit + s.droppedLowStorage + s.droppedError > 0) line("  CẢNH BÁO: đã chạm giới hạn/thiếu bộ nhớ; mẫu bị loại: giới hạn ${s.droppedLimit}, thiếu bộ nhớ ${s.droppedLowStorage}, lỗi ghi ${s.droppedError}")
        else line("  Không ghi nhận mẫu bị loại do giới hạn dung lượng.")
        line("  Dòng hỏng: ${s.corruptLines}${if (s.tornTail) " (dòng cuối ghi dở)" else ""} · Sự kiện bị bỏ lúc tạm dừng: ${s.pausedIgnored}")
        line()
        line("SỐ LIỆU THEO THAM SỐ (không nội suy; thiếu = để trống)")
        if (s.series.isEmpty()) line("  Chưa có mẫu số liệu nào.")
        for (x in s.series) {
            line("  • ${x.title} [${x.id}] — ${x.cls.title} · ${x.source?.title ?: "?"}")
            line("      ${x.count} mẫu · min ${fmt(x.min)} · TB ${fmt(x.mean)} · max ${fmt(x.max)} ${x.unit} · từ ${t(x.firstWallMs)} đến ${t(x.lastWallMs)}")
            if (x.gaps.isNotEmpty()) line("      THIẾU DỮ LIỆU: ${x.gaps.size} khoảng, tổng ${dur(x.gapTotalMs)} — " + x.gaps.take(5).joinToString("; ") { "${t(it.fromWallMs)}→${t(it.toWallMs)}" } + if (x.gaps.size > 5) "; …" else "")
        }
        line()
        line("DẤU THIẾU DỮ LIỆU / KHÔNG HỖ TRỢ (ghi khi trạng thái thay đổi)")
        if (s.missing.isEmpty()) line("  (không có)")
        for (r in s.missing) line("  ${t(r.wallMs)}  ${r.name}  [${r.cls?.title}]  ${scrub(r.text, opts, salt) ?: ""}")
        line()
        line("GHI CHÚ SỰ CỐ CỦA NGƯỜI DÙNG")
        if (!opts.includeNotes) line("  (đã loại khỏi báo cáo theo tùy chọn)") else if (s.notes.isEmpty()) line("  (không có)")
        else for (r in s.notes) line("  ${t(r.wallMs)}  ${scrub(r.text, opts, salt)}")
        line()
        line("SỰ KIỆN BLE VÀ LỖI (theo thời gian)")
        val ev = data.records.filter { it.kind == DiagKind.BLE_EVENT || it.kind == DiagKind.ERROR || (it.kind == DiagKind.SESSION) }.sortedBy { it.seq }
        for (r in ev.take(400)) line("  ${t(r.wallMs)}  ${r.kind.name.padEnd(9)} ${r.name}  ${scrub(r.text, opts, salt) ?: ""}")
        if (ev.size > 400) line("  … còn ${ev.size - 400} sự kiện (xem CSV/JSON)")
        line()
        line("Xuất lúc: ${if (opts.relativeTime) "(ẩn)" else iso(nowMs)} · Ẩn danh: ${if (opts.redact) "bật" else "TẮT"}")
    }

    // ------------------------------------------------------------------------------------------ HTML
    internal fun esc(s: String) = buildString { for (c in s) when (c) { '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&#39;"); else -> append(c) } }

    private const val W = 640.0; private const val H = 150.0; private const val PL = 52.0; private const val PR = 10.0; private const val PT = 10.0; private const val PB = 22.0
    private const val MAX_PTS = 1500

    /** Biểu đồ SVG: đường bị NGẮT tại khoảng trống (không nối qua chỗ thiếu), mẫu lẻ vẽ chấm, không làm mượt/nội suy. Chọn bớt mẫu thật (bước đều) nếu quá nhiều. */
    internal fun svgChart(x: SeriesStat, t0: Long, threshold: Long): String {
        val pts = x.points.let { if (it.size > MAX_PTS) { val step = it.size / MAX_PTS + 1; it.filterIndexed { i, _ -> i % step == 0 || i == it.size - 1 } } else it }
        val tMin = pts.first().first; val tMax = pts.last().first
        val span = (tMax - tMin).coerceAtLeast(1).toDouble()
        val lo0 = x.min; val hi0 = x.max; val pad = if (hi0 - lo0 < 1e-9) 1.0 else (hi0 - lo0) * 0.08
        val lo = lo0 - pad; val hi = hi0 + pad
        fun px(t: Long) = PL + (t - tMin) / span * (W - PL - PR)
        fun py(v: Double) = PT + (1 - (v - lo) / (hi - lo)) * (H - PT - PB)
        val color = if (x.cls == DiagClass.DERIVED) "#2563eb" else "#15803d"
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${W.toInt()} ${H.toInt()}\" role=\"img\" aria-label=\"${esc("Biểu đồ " + x.title)}\">")
        sb.append("<rect x=\"$PL\" y=\"$PT\" width=\"${W - PL - PR}\" height=\"${H - PT - PB}\" fill=\"none\" stroke=\"#cbd5e1\"/>")
        sb.append("<text x=\"4\" y=\"${PT + 8}\" font-size=\"10\" fill=\"#475569\">${esc(fmt(hi0))}</text><text x=\"4\" y=\"${H - PB}\" font-size=\"10\" fill=\"#475569\">${esc(fmt(lo0))}</text>")
        sb.append("<text x=\"$PL\" y=\"${H - 6}\" font-size=\"10\" fill=\"#475569\">${esc(fmt((tMin - t0) / 1000.0))} s</text>")
        sb.append("<text x=\"${W - PR}\" y=\"${H - 6}\" font-size=\"10\" text-anchor=\"end\" fill=\"#475569\">${esc(fmt((tMax - t0) / 1000.0))} s</text>")
        var seg = ArrayList<Pair<Long, Double>>()
        fun flush() {
            if (seg.size == 1) sb.append("<circle cx=\"${"%.1f".format(Locale.US, px(seg[0].first))}\" cy=\"${"%.1f".format(Locale.US, py(seg[0].second))}\" r=\"2.5\" fill=\"$color\"/>")
            else if (seg.size > 1) sb.append("<polyline fill=\"none\" stroke=\"$color\" stroke-width=\"1.6\" points=\"" + seg.joinToString(" ") { "%.1f,%.1f".format(Locale.US, px(it.first), py(it.second)) } + "\"/>")
            seg = ArrayList()
        }
        for (p in pts) { if (seg.isNotEmpty() && p.first - seg.last().first > threshold) flush(); seg.add(p) }
        flush()
        // vùng thiếu dữ liệu: nền vàng nhạt
        for (g in x.gaps) { if (g.toWallMs < tMin || g.fromWallMs > tMax) continue
            val a = px(g.fromWallMs.coerceAtLeast(tMin)); val b = px(g.toWallMs.coerceAtMost(tMax))
            sb.append("<rect x=\"${"%.1f".format(Locale.US, a)}\" y=\"$PT\" width=\"${"%.1f".format(Locale.US, (b - a).coerceAtLeast(1.0))}\" height=\"${H - PT - PB}\" fill=\"#f59e0b\" opacity=\"0.18\"><title>Thiếu dữ liệu ${g.ms} ms</title></rect>") }
        sb.append("</svg>")
        return sb.toString()
    }

    fun html(data: DiagSessionData, s: DiagSummary, opts: DiagExportOptions, nowMs: Long, out: Appendable) {
        val m = data.meta; val salt = m.id; val t0 = m.startedWallMs
        fun t(ms: Long) = esc(if (opts.relativeTime) "+" + fmt((ms - t0) / 1000.0) + " s" else iso(ms))
        fun e(x: String?) = esc(scrub(x, opts, salt) ?: "")
        val thr = maxOf(3_000L, m.sampleIntervalMs * 3)
        out.append("<!doctype html><html lang=\"vi\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        out.append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\"><title>NVX Connect — báo cáo ${esc(m.id)}</title>")
        out.append("<style>body{font:14px/1.5 system-ui,sans-serif;margin:16px;max-width:760px;color:#0f172a}h1{font-size:20px}h2{font-size:16px;border-bottom:1px solid #e2e8f0;padding-top:12px}")
        out.append("table{border-collapse:collapse;width:100%}td,th{border:1px solid #e2e8f0;padding:4px 6px;text-align:left;font-size:13px}.warn{background:#fef3c7;border:1px solid #f59e0b;padding:8px;border-radius:6px}")
        out.append(".ok{background:#dcfce7;border:1px solid #16a34a;padding:8px;border-radius:6px}.mut{color:#475569}</style></head><body>")
        out.append("<h1>NVX Connect — Báo cáo chẩn đoán xe</h1>")
        out.append(if (s.verifiedOnVehicle) "<p class=\"ok\">${esc(s.verificationText)}</p>" else "<p class=\"warn\"><b>${esc(s.verificationText)}</b> — số liệu chỉ phản ánh những gì app quan sát được, chưa phải kết luận về xe.</p>")
        out.append("<p class=\"mut\">Chế độ CHỈ ĐỌC: app không gửi lệnh điều khiển hay thay đổi ECU; dữ liệu không được tải lên máy chủ.</p>")
        out.append("<h2>Phiên</h2><table>")
        fun row(k: String, v: String) { out.append("<tr><th>${esc(k)}</th><td>$v</td></tr>") }
        row("Mã phiên", esc(m.id)); row("Nhãn", e(m.label)); row("Trạng thái", esc(s.state.title)); row("Bắt đầu", t(s.startedWallMs)); row("Kết thúc/cuối", s.endedWallMs?.let { t(it) } ?: "—")
        row("Thời gian ghi", esc("${dur(s.activeMs)} (không tính tạm dừng ${dur(s.pausedMs)})")); row("Chu kỳ mẫu", "${m.sampleIntervalMs} ms"); row("Thiết bị", esc(deviceText(m, opts))); row("Phiên bản app", esc(m.appVersion))
        out.append("</table><h2>Thống kê phiên</h2><table>")
        row("Bản ghi / mẫu số liệu", "${s.totalRecords} / ${s.sampleCount}"); row("Ngắt kết nối (mất liên kết)", s.linkLost.toString()); row("Sẵn sàng / thử nối lại / nối thất bại", "${s.readyCount} / ${s.reconnectAttempts} / ${s.attemptFailed}")
        row("Lỗi", esc(if (s.errorCounts.isEmpty()) "không có" else s.errorCounts.entries.joinToString(", ") { "${it.key}×${it.value}" }))
        row("Gián đoạn do app đóng đột ngột", s.interruptedCount.toString()); row("Nhảy đồng hồ hệ thống", s.clockJumps.toString())
        s.connectMs?.let { row("Nối → sẵn sàng (ms)", esc("n=${it.count} · min ${fmt(it.min)} · TB ${fmt(it.mean)} · max ${fmt(it.max)}")) }
        s.firstFrameMs?.let { row("Sẵn sàng → gói đầu (ms)", esc("n=${it.count} · min ${fmt(it.min)} · TB ${fmt(it.mean)} · max ${fmt(it.max)}")) }
        out.append("</table>")
        val lost = s.droppedLimit + s.droppedLowStorage + s.droppedError
        if (s.limitHit || lost > 0) out.append("<p class=\"warn\"><b>Mẫu bị loại:</b> giới hạn dung lượng ${s.droppedLimit}, thiếu bộ nhớ ${s.droppedLowStorage}, lỗi ghi ${s.droppedError}. Nhật ký cũ không bị xóa.</p>")
        if (s.corruptLines > 0 || s.tornTail) out.append("<p class=\"warn\">Có ${s.corruptLines} dòng hỏng${if (s.tornTail) " (dòng cuối ghi dở)" else ""}; các dòng còn lại vẫn nguyên vẹn.</p>")

        out.append("<h2>Số liệu theo tham số</h2>")
        if (s.series.isEmpty()) out.append("<p class=\"mut\">Chưa có mẫu số liệu nào.</p>") else {
            out.append("<table><tr><th>Tham số</th><th>Loại</th><th>Mẫu</th><th>Min</th><th>TB</th><th>Max</th><th>Thiếu dữ liệu</th></tr>")
            for (x in s.series) out.append("<tr><td>${esc(x.title)}<br><span class=\"mut\">${esc(x.id)} · ${esc(x.source?.title ?: "")}</span></td><td>${esc(x.cls.title)}</td><td>${x.count}</td><td>${esc(fmt(x.min))} ${esc(x.unit)}</td><td>${esc(fmt(x.mean))}</td><td>${esc(fmt(x.max))}</td><td>${if (x.gaps.isEmpty()) "—" else "${x.gaps.size} khoảng / ${esc(dur(x.gapTotalMs))}"}</td></tr>")
            out.append("</table>")
        }
        out.append("<h2>Biểu đồ</h2>")
        val charts = s.chartable
        if (charts.isEmpty()) out.append("<p class=\"mut\">Chưa đủ dữ liệu để vẽ biểu đồ (cần ít nhất ${DiagSummary.MIN_CHART_POINTS} mẫu thật cho một tham số). App không tạo mẫu giả để lấp.</p>")
        else {
            out.append("<p class=\"mut\">Đường bị ngắt tại khoảng thiếu dữ liệu (nền vàng); không nối, không nội suy.</p>")
            for (x in charts) out.append("<h3 style=\"font-size:14px\">${esc(x.title)} (${esc(x.unit)}) <span class=\"mut\">— ${esc(x.cls.title)}</span></h3>").append(svgChart(x, t0, thr))
        }
        out.append("<h2>Dấu thiếu dữ liệu / không hỗ trợ</h2>")
        if (s.missing.isEmpty()) out.append("<p class=\"mut\">Không có.</p>") else {
            out.append("<table><tr><th>Thời điểm</th><th>Tham số</th><th>Loại</th><th>Lý do</th></tr>")
            for (r in s.missing) out.append("<tr><td>${t(r.wallMs)}</td><td>${esc(r.name)}</td><td>${esc(r.cls?.title ?: "")}</td><td>${e(r.text)}</td></tr>")
            out.append("</table>")
        }
        out.append("<h2>Ghi chú sự cố của người dùng</h2>")
        if (!opts.includeNotes) out.append("<p class=\"mut\">Đã loại khỏi báo cáo theo tùy chọn.</p>") else if (s.notes.isEmpty()) out.append("<p class=\"mut\">Không có.</p>")
        else { out.append("<ul>"); for (r in s.notes) out.append("<li>${t(r.wallMs)} — ${e(r.text)}</li>"); out.append("</ul>") }
        out.append("<h2>Sự kiện BLE và lỗi</h2>")
        val ev = data.records.filter { it.kind == DiagKind.BLE_EVENT || it.kind == DiagKind.ERROR || it.kind == DiagKind.SESSION }.sortedBy { it.seq }
        out.append("<table><tr><th>Thời điểm</th><th>Loại</th><th>Tên</th><th>Chi tiết</th></tr>")
        for (r in ev.take(400)) out.append("<tr><td>${t(r.wallMs)}</td><td>${esc(r.kind.name)}</td><td>${esc(r.name)}</td><td>${e(r.text)}</td></tr>")
        out.append("</table>")
        if (ev.size > 400) out.append("<p class=\"mut\">… còn ${ev.size - 400} sự kiện (xem CSV/JSON).</p>")
        out.append("<p class=\"mut\">Xuất lúc ${esc(if (opts.relativeTime) "(ẩn)" else iso(nowMs))} · Ẩn danh: ${if (opts.redact) "bật" else "TẮT"}</p></body></html>\n")
    }
}
