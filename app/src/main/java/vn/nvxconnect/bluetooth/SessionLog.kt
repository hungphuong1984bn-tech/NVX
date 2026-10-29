package vn.nvxconnect.bluetooth

import vn.nvxconnect.util.MiniJson
import vn.nvxconnect.protocol.FrameRedactor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class LogKind { STATE, GATT, FRAME, SCAN, INFO, ERROR }

/** Một dòng nhật ký có cấu trúc. hex = dữ liệu thô (chỉ có khi kind = FRAME hoặc đọc characteristic). */
data class LogEntry(
    val timeMs: Long, val kind: LogKind, val message: String,
    val uuid: String? = null, val state: String? = null, val hex: String? = null
)

/** Bộ đệm vòng, an toàn đa luồng. */
class SessionLogBuffer(private val capacity: Int = 5000) {
    private val q = ArrayDeque<LogEntry>()
    private var dropped = 0L
    /** Bản ghi cũ nhất bị đẩy ra khi đầy được ĐẾM (không mất âm thầm). Xuất log phải kèm số này. */
    @Synchronized fun add(e: LogEntry) { q.addLast(e); while (q.size > capacity) { q.removeFirst(); dropped++ } }
    @Synchronized fun snapshot(): List<LogEntry> = q.toList()
    /** Xóa theo yêu cầu người dùng; bộ đếm [droppedCount] được giữ để báo cáo không che mất việc đã loại bản ghi. */
    @Synchronized fun clear() = q.clear()
    @Synchronized fun size() = q.size
    @Synchronized fun droppedCount() = dropped
    @Synchronized fun isTruncated() = dropped > 0
}

object HexUtil {
    fun toHex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it) }
    fun fromHex(s: String): ByteArray? {
        val t = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return try { ByteArray(t.size) { t[it].toInt(16).toByte() } } catch (_: NumberFormatException) { null }
    }
}

/** Xuất nhật ký phiên + danh sách quảng bá + cây GATT ra JSON/CSV, che thông tin nhạy cảm theo mặc định. */
object LogExporter {
    private val MAC = Regex("(?i)\\b[0-9a-f]{2}(:[0-9a-f]{2}){5}\\b")
    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    /** Che mọi địa chỉ MAC xuất hiện trong văn bản tự do. */
    fun redactText(s: String, salt: String): String = MAC.replace(s) { SurveyRedactor.address(it.value, salt) }

    private fun hexOut(hex: String?, redact: Boolean): String? {
        if (hex == null) return null
        if (!redact) return hex
        val b = HexUtil.fromHex(hex) ?: return "(hex không hợp lệ)"
        return FrameRedactor.hexRedacted(b)
    }

    /** Chống chèn công thức khi mở CSV bằng bảng tính: ô bắt đầu bằng = + - @ được thêm dấu nháy đơn. */
    internal fun csvCell(s: String): String {
        val safe = if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    fun toCsv(entries: List<LogEntry>, salt: String, redact: Boolean = true): String = buildString {
        appendLine("time_utc,time_ms,kind,state,uuid,hex,message")
        for (e in entries) {
            val msg = if (redact) redactText(e.message, salt) else e.message
            appendLine(listOf(csvCell(iso(e.timeMs)), e.timeMs.toString(), e.kind.name, csvCell(e.state ?: ""),
                csvCell(e.uuid ?: ""), csvCell(hexOut(e.hex, redact) ?: ""), csvCell(msg)).joinToString(","))
        }
    }

    fun toJson(
        entries: List<LogEntry>, adverts: List<AdvertRecord>, services: Map<String, List<GattServiceInfo>>,
        salt: String, nowMs: Long, appVersion: String, redact: Boolean = true,
        analysis: List<Pair<String, String>> = emptyList()
    ): String = buildString {
        fun addr(a: String) = if (redact) SurveyRedactor.address(a, salt) else a
        fun nm(n: String?) = if (redact) SurveyRedactor.name(n) else n
        append("{")
        append("\"format\":\"nvx-connect-ble-log/2\",")
        append("\"created_utc\":").append(MiniJson.quote(iso(nowMs))).append(',')
        append("\"app_version\":").append(MiniJson.quote(appVersion)).append(',')
        append("\"redacted\":").append(redact).append(',')
        append("\"note\":").append(MiniJson.quote("Log khảo sát BLE. Chưa có giao thức xe đã xác minh; dữ liệu là RAW/UNKNOWN.")).append(',')
        append("\"adverts\":[")
        append(adverts.joinToString(",") { r ->
            "{\"seen_at_ms\":${r.seenAtMillis},\"address\":${MiniJson.quote(addr(r.address))}," +
                "\"address_kind\":${MiniJson.quote(r.addressKind.name)},\"name\":${MiniJson.quoteOrNull(nm(r.name))}," +
                "\"rssi\":${r.rssi},\"connectable\":${r.connectable ?: "null"},\"tx_power_dbm\":${r.txPowerDbm ?: "null"}," +
                "\"service_uuids\":[${r.advertisedServiceUuids.joinToString(",") { MiniJson.quote(it) }}]," +
                "\"manufacturer_data\":{${r.manufacturerData.entries.joinToString(",") { (k, v) ->
                    "\"${"%04x".format(k)}\":${MiniJson.quote(if (redact) "(đã che, ${v.split(' ').size} byte)" else v)}" }}}}"
        })
        append("],\"gatt\":{")
        append(services.entries.joinToString(",") { (a, svcs) ->
            MiniJson.quote(addr(a)) + ":[" + svcs.joinToString(",") { s ->
                "{\"service\":${MiniJson.quote(s.uuid)},\"characteristics\":[" + s.characteristics.joinToString(",") { c ->
                    "{\"uuid\":${MiniJson.quote(c.uuid)},\"properties\":[${c.properties.joinToString(",") { MiniJson.quote(it) }}]," +
                        "\"descriptors\":[${c.descriptors.joinToString(",") { MiniJson.quote(it.uuid) }}]}"
                } + "]}"
            } + "]"
        })
        append("},\"events\":[")
        append(entries.joinToString(",") { e ->
            val msg = if (redact) redactText(e.message, salt) else e.message
            "{\"time_ms\":${e.timeMs},\"time_utc\":${MiniJson.quote(iso(e.timeMs))},\"kind\":${MiniJson.quote(e.kind.name)}," +
                "\"state\":${MiniJson.quoteOrNull(e.state)},\"uuid\":${MiniJson.quoteOrNull(e.uuid)}," +
                "\"hex\":${MiniJson.quoteOrNull(hexOut(e.hex, redact))},\"message\":${MiniJson.quote(msg)}}"
        })
        append("],\"analysis\":{\"note\":").append(MiniJson.quote("Phân tích tự động khi xuất. Chỉ OBSERVED/HYPOTHESIS/UNKNOWN; không có VERIFIED vì cần người xác nhận."))
        append(",\"findings\":[")
        append(analysis.joinToString(",") { (lv, tx) -> "{\"level\":${MiniJson.quote(lv)},\"text\":${MiniJson.quote(if (redact) redactText(tx, salt) else tx)}}" })
        append("]}}")
    }
}
