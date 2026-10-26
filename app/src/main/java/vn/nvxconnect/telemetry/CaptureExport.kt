package vn.nvxconnect.telemetry

import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Xuất nhật ký thu thập (đọc tuần tự từ đĩa, ghi thẳng ra [OutputStream] → xuất log lớn không nạp hết vào bộ nhớ).
 * Không chứa MAC/tên thiết bị. Dữ liệu thô giữ nguyên; các cột phái sinh (gap) chỉ để tiện đọc.
 */
object CaptureExport {
    const val FORMAT = "nvx-capture-jsonl/1"
    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).also { it.timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    private fun esc(s: String): String = buildString { for (c in s) when { c == '"' -> append("\\\""); c == '\\' -> append("\\\\"); c.code < 0x20 -> append(' '); else -> append(c) } }

    const val CSV_HEADER = "seq,session,epoch,wall_ms,wall_iso_utc,mono_ns,gap_ms_same_epoch_char,char_uuid,service_uuid,source,rssi,conn_state,length,hex"

    /** CSV: một dòng/gói. gap_ms = khoảng cách (từ đồng hồ đơn điệu) tới gói liền trước CÙNG phiên GATT và CÙNG characteristic. */
    fun csv(log: DurableFrameLog, out: OutputStream): DurableFrameLog.ReadReport {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write(CSV_HEADER); w.newLine()
        val last = HashMap<String, Long>()
        val rep = log.readAll { f ->
            val k = "${f.sessionId}|${f.epoch}|${f.charUuid}"
            val gap = last.put(k, f.monoNs)?.let { ((f.monoNs - it) / 1_000_000).toString() } ?: ""
            w.write("${f.seq},${f.sessionId},${f.epoch},${f.wallMs},${iso(f.wallMs)},${f.monoNs},$gap,${f.charUuid},${f.serviceUuid ?: ""},${f.source.name},${f.rssi ?: ""},${f.connState},${f.bytes.size},${f.hex}")
            w.newLine()
        }
        w.flush(); return rep
    }

    /** JSON Lines: dòng đầu là metadata (kể cả số gói ứng dụng làm mất), các dòng sau là gói. */
    fun jsonl(log: DurableFrameLog, out: OutputStream, meta: Map<String, String>, stats: CaptureStats?): DurableFrameLog.ReadReport {
        val w = out.bufferedWriter(Charsets.UTF_8)
        val head = StringBuilder("{\"format\":\"$FORMAT\"")
        for ((k, v) in meta) head.append(",\"${esc(k)}\":\"${esc(v)}\"")
        head.append(",\"exportedAtMs\":${System.currentTimeMillis()}")
        if (stats != null) {
            head.append(",\"stats\":{\"received\":${stats.received},\"persisted\":${stats.persisted},\"droppedIngress\":${stats.droppedIngress},\"droppedStorage\":${stats.droppedStorage},\"lostByApp\":${stats.lostByApp},\"maxGapMs\":${stats.maxGapMs},\"wallClockJumps\":${stats.wallClockJumps},\"evictedSegments\":${stats.evictedSegments}}")
            head.append(",\"drops\":[")
            head.append(stats.recentDrops.joinToString(",") { "{\"firstMs\":${it.firstMs},\"lastMs\":${it.lastMs},\"reason\":\"${it.reason.name}\",\"count\":${it.count},\"firstSeq\":${it.firstSeq},\"lastSeq\":${it.lastSeq}}" })
            head.append("]")
        }
        head.append("}")
        w.write(head.toString()); w.newLine()
        val rep = log.readAll { f ->
            w.write("{\"seq\":${f.seq},\"session\":\"${f.sessionId}\",\"epoch\":${f.epoch},\"wallMs\":${f.wallMs},\"monoNs\":${f.monoNs},\"char\":\"${f.charUuid}\",\"service\":${f.serviceUuid?.let { "\"$it\"" } ?: "null"},\"source\":\"${f.source.name}\",\"rssi\":${f.rssi ?: "null"},\"state\":\"${f.connState}\",\"len\":${f.bytes.size},\"hex\":\"${f.hex}\"}")
            w.newLine()
        }
        w.write("{\"end\":true,\"frames\":${rep.frames},\"duplicatesSkipped\":${rep.duplicates},\"corruptLines\":${rep.corruptLines},\"segments\":${rep.segments}}"); w.newLine()
        w.flush(); return rep
    }
}
