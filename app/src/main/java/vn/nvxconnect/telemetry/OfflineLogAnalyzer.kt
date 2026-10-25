package vn.nvxconnect.telemetry

import vn.nvxconnect.util.MiniJson
import java.util.Locale

/** OBSERVED = đếm/đo trực tiếp trong log; HYPOTHESIS = suy luận từ mẫu (chưa kiểm chứng); NOT_PROVEN = điều log KHÔNG chứng minh được. */
enum class FindingKind { OBSERVED, HYPOTHESIS, NOT_PROVEN }

data class AnalysisFinding(val subject: String, val kind: FindingKind, val statement: String, val evidence: String)

data class TypeGroupReport(
    val typeByte: Int, val length: Int, val count: Int, val distinctPayloads: Int,
    val checksumScheme: String?, val checksumPass: Int, val checksumFail: Int,
    val counterPos: Int?, val counterSteps: Int, val counterOk: Int, val estimatedMissing: Int, val repeatedPayloads: Int,
    val constantPositions: Map<Int, Int>, val variablePositions: List<Int>
)

data class StreamReport(
    val deviceId: String, val serviceUuid: String?, val charUuid: String?, val source: PayloadSource, val properties: List<String>,
    val count: Int, val firstMs: Long, val lastMs: Long, val sessions: List<String>, val lengthCounts: Map<Int, Int>,
    val medianIntervalMs: Long?, val p95IntervalMs: Long?, val maxGapMs: Long?, val longGaps: Int, val ratePerSec: Double?,
    val empty: Int, val abnormal: Int, val groups: List<TypeGroupReport>, val subscribedOk: Boolean?
)

enum class DeviceStage(val title: String) {
    DISCOVERED("Đã phát hiện"), CONNECTED("Đã kết nối"), TRANSMITTED("Đã truyền dữ liệu"),
    FUNCTION_IDENTIFIED("Đã xác định chức năng"), ENGINE_LINK_PROVEN("Đã chứng minh liên quan động cơ")
}

data class DeviceStageRow(val deviceId: String, val name: String?, val stage: DeviceStage, val note: String)

data class CharacteristicRow(
    val deviceId: String, val advertisedName: String?, val serviceUuid: String?, val charUuid: String?, val properties: List<String>,
    val receivedPayload: Boolean, val payloadCount: Int, val structure: String, val evidenceSource: String,
    val vehicleLink: String, val structureConfidence: String, val nextStep: String
)

data class SessionComparison(
    val charUuid: String, val typeByte: Int, val length: Int, val sessions: List<String>, val countsBySession: Map<String, Int>,
    val stable: List<Int>, val sessionSpecific: List<Int>, val varying: List<Int>
)

data class AnalysisReport(
    val totalPayloads: Int, val bySource: Map<PayloadSource, Int>, val droppedOldest: Long, val warnings: List<String>,
    val streams: List<StreamReport>, val rows: List<CharacteristicRow>, val devices: List<DeviceStageRow>,
    val comparisons: List<SessionComparison>, val findings: List<AnalysisFinding>
)

/**
 * Phân tích log ngoại tuyến. Thuần Kotlin. KHÔNG gán nghĩa vật lý (RPM, nhiệt độ…): chỉ cấu trúc, tần suất, tổng kiểm, bộ đếm, byte đổi/không đổi.
 * Một byte đổi theo thời gian KHÔNG phải bằng chứng nó là RPM hay nhiệt độ; việc đó thuộc [TelemetryValidationEngine].
 */
object OfflineLogAnalyzer {
    const val NOT_PROVEN_LINK = "CHƯA CHỨNG MINH"

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private val CHECKSUMS: List<Pair<String, (ByteArray) -> Boolean>> = listOf(
        "SUM8(toàn gói) ≡ 0" to { b -> b.sumOf { it.toInt() and 0xFF } and 0xFF == 0 },
        "SUM8(byte 0..n-2) = byte cuối" to { b -> (0 until b.size - 1).sumOf { u(b, it) } and 0xFF == u(b, b.size - 1) },
        "SUM8(byte 1..n-2) = byte cuối" to { b -> (1 until b.size - 1).sumOf { u(b, it) } and 0xFF == u(b, b.size - 1) },
        "XOR8(byte 0..n-2) = byte cuối" to { b -> (0 until b.size - 1).fold(0) { a, i -> a xor u(b, i) } == u(b, b.size - 1) },
        "XOR8(toàn gói) ≡ 0" to { b -> b.fold(0) { a, x -> a xor (x.toInt() and 0xFF) } == 0 }
    )

    /** Hàm kiểm tổng kiểm theo tên sơ đồ (dùng cho giả thuyết giải mã). */
    fun checksumByName(name: String?): ((ByteArray) -> Boolean)? = CHECKSUMS.firstOrNull { it.first == name }?.second

    fun analyze(logs: List<ImportedLog>, verifiedCharUuids: Set<String> = emptySet()): AnalysisReport {
        val multi = logs.size > 1
        fun ses(li: Int, s: String) = if (multi) "L${li + 1}:$s" else s
        val records = logs.flatMapIndexed { li, l -> l.records.map { if (multi) it.copy(sessionId = ses(li, it.sessionId)) else it } }
        val events = logs.flatMapIndexed { li, l -> l.events.map { if (multi) it.copy(sessionId = ses(li, it.sessionId)) else it } }
        val gatt = logs.flatMap { it.gatt }
        val devices = logs.flatMap { it.devices }.distinctBy { it.deviceId }
        val warnings = logs.flatMap { it.warnings }
        val dropped = logs.sumOf { it.droppedOldest }

        val findings = ArrayList<AnalysisFinding>()
        val streams = records.groupBy { Triple(it.deviceId, it.charUuid, it.source) }.map { (k, rs) -> analyzeStream(k.first, k.second, k.third, rs, gatt, events) }
            .sortedWith(compareBy({ it.deviceId }, { it.charUuid }, { it.source }))
        val bySource = records.groupingBy { it.source }.eachCount()

        if (records.isEmpty()) findings += AnalysisFinding("log", FindingKind.NOT_PROVEN, "Log không chứa payload nào: không có gì để phân tích hay kết luận.", "0 payload trong ${logs.size} tệp")
        if (dropped > 0) findings += AnalysisFinding("log", FindingKind.OBSERVED, "Log đã bị cắt $dropped bản ghi cũ nhất do giới hạn dung lượng (đầu phiên được giữ riêng).", "limits.dropped_oldest=$dropped")
        if (multi) findings += AnalysisFinding("log", FindingKind.OBSERVED, "Nhiều tệp: ID thiết bị đã ẩn danh khác nhau giữa các lần xuất, nên so sánh phiên theo UUID characteristic, không theo thiết bị.", "${logs.size} tệp")

        for (s in streams) {
            val who = "${s.deviceId} · ${s.charUuid ?: "-"} · ${s.source}"
            val rate = s.ratePerSec?.let { " · ${"%.1f".format(Locale.US, it)} gói/s" } ?: ""
            findings += AnalysisFinding(who, FindingKind.OBSERVED, "${s.count} payload từ ${s.source.name.lowercase()}$rate; độ dài ${s.lengthCounts.entries.joinToString(", ") { "${it.key}B×${it.value}" }}",
                "phiên ${s.sessions.joinToString(",")}")
            if (s.empty > 0 || s.abnormal > 0) findings += AnalysisFinding(who, FindingKind.OBSERVED, "${s.empty} payload rỗng, ${s.abnormal} payload bất thường (rỗng/quá dài/toàn byte giống nhau).", "cờ trong log")
            if (s.longGaps > 0) findings += AnalysisFinding(who, FindingKind.OBSERVED, "${s.longGaps} khoảng trống dài giữa các gói (lớn nhất ${s.maxGapMs} ms; trung vị ${s.medianIntervalMs} ms).", "thời gian nhận trong phiên")
            for (g in s.groups) {
                val t = "loại 0x%02X (${g.length}B, ${g.count} gói, ${g.distinctPayloads} payload phân biệt)".format(g.typeByte)
                if (g.checksumScheme != null) {
                    val weak = if (g.distinctPayloads < 5) " — ít payload phân biệt nên bằng chứng yếu" else ""
                    findings += AnalysisFinding(who, FindingKind.OBSERVED, "$t: ${g.checksumScheme} khớp ${g.checksumPass}/${g.count} gói, sai ${g.checksumFail}$weak.", "tính trực tiếp trên hex")
                } else if (g.length >= 3 && g.count >= 3) {
                    findings += AnalysisFinding(who, FindingKind.NOT_PROVEN, "$t: không có sơ đồ tổng kiểm đơn giản nào khớp ≥50% gói.", "đã thử SUM8/XOR8")
                }
                g.counterPos?.let {
                    findings += AnalysisFinding(who, FindingKind.OBSERVED, "$t: byte $it tăng +1 ở ${g.counterOk}/${g.counterSteps} bước liên tiếp (ứng viên bộ đếm); ước tính thiếu ${g.estimatedMissing} gói.", "so sánh gói liền kề cùng phiên")
                }
                findings += AnalysisFinding(who, FindingKind.OBSERVED, "$t: ${g.variablePositions.size} vị trí byte thay đổi, ${g.constantPositions.size} không đổi, ${g.repeatedPayloads} cặp gói liền kề trùng byte-by-byte. Chưa gán nghĩa cho trường nào.", "thống kê vị trí byte")
            }
        }
        for ((dev, cu) in events.filter { it.type == ConnEventType.SUBSCRIBE_OK && it.uuid != null }.map { it.deviceId to it.uuid!! }.distinct()) {
            val got = records.count { it.charUuid == cu && it.source != PayloadSource.ADVERTISEMENT }
            findings += if (got == 0) AnalysisFinding("$dev · $cu", FindingKind.OBSERVED, "Đăng ký nghe thành công NHƯNG chưa nhận được payload nào (đăng ký thành công không phải là có dữ liệu).", "SUBSCRIBE_OK, 0 payload")
            else AnalysisFinding("$dev · $cu", FindingKind.OBSERVED, "Đăng ký nghe thành công và đã nhận $got payload.", "SUBSCRIBE_OK, $got payload")
        }
        val comparisons = compare(records)
        for (c in comparisons) findings += AnalysisFinding("so sánh phiên · ${c.charUuid} · 0x%02X".format(c.typeByte), FindingKind.OBSERVED,
            "${c.sessions.size} phiên: ${c.stable.size} byte ổn định mọi phiên, ${c.sessionSpecific.size} byte cố định trong từng phiên nhưng khác giữa các phiên (ID/mã phiên/đồng hồ?), ${c.varying.size} byte thay đổi trong phiên.", "so sánh tập giá trị theo phiên")

        val rows = buildRows(streams, gatt, devices, verifiedCharUuids, events)
        val stages = buildStages(records, events, gatt, devices, verifiedCharUuids)
        return AnalysisReport(records.size, bySource, dropped, warnings, streams, rows, stages, comparisons, findings)
    }

    private fun analyzeStream(dev: String, cu: String?, src: PayloadSource, rs: List<PayloadRecord>, gatt: List<GattCharRow>, events: List<ConnEvent>): StreamReport {
        val sorted = rs.sortedBy { it.seq }
        val props = gatt.firstOrNull { it.deviceId == dev && it.charUuid == cu }?.properties ?: gatt.firstOrNull { it.charUuid == cu }?.properties ?: emptyList()
        val diffs = sorted.groupBy { it.sessionId }.values.flatMap { l -> l.sortedBy { it.timeMs }.zipWithNext { a, b -> b.timeMs - a.timeMs } }.filter { it >= 0 }.sorted()
        val median = diffs.getOrNull(diffs.size / 2); val p95 = if (diffs.isEmpty()) null else diffs[minOf(diffs.size - 1, (diffs.size * 95) / 100)]
        val longGaps = if (median == null) 0 else diffs.count { it > maxOf(1000L, median * 10) }
        val spanMs = sorted.maxOf { it.timeMs } - sorted.minOf { it.timeMs }
        val rate = if (sorted.size >= 2 && spanMs > 0) (sorted.size - 1) * 1000.0 / spanMs else null
        val lengths = sorted.groupingBy { it.length }.eachCount().toSortedMap()
        val subOk = if (cu == null) null else events.any { it.deviceId == dev && it.uuid == cu && it.type == ConnEventType.SUBSCRIBE_OK }.takeIf { it } ?: events.any { it.uuid == cu && it.type == ConnEventType.SUBSCRIBE_OK }.takeIf { it }
        val groups = if (src == PayloadSource.ADVERTISEMENT) emptyList() else sorted.mapNotNull { r -> r.bytes?.takeIf { it.isNotEmpty() }?.let { r to it } }
            .groupBy { (_, b) -> u(b, 0) to b.size }.map { (k, fs) -> analyzeGroup(k.first, k.second, fs) }.sortedBy { it.typeByte }
        return StreamReport(dev, sorted.firstOrNull { it.serviceUuid != null }?.serviceUuid, cu, src, props, sorted.size, sorted.minOf { it.timeMs }, sorted.maxOf { it.timeMs },
            sorted.map { it.sessionId }.distinct(), lengths, median, p95, diffs.lastOrNull(), longGaps, rate, sorted.count { PayloadFlag.EMPTY in it.flags },
            sorted.count { r -> r.flags.any { it != PayloadFlag.DUPLICATE } }, groups, subOk)
    }

    private fun analyzeGroup(type: Int, len: Int, frames: List<Pair<PayloadRecord, ByteArray>>): TypeGroupReport {
        val n = frames.size
        val distinct = frames.map { it.first.hex }.distinct().size
        var best: Pair<String, Int>? = null
        if (len >= 3) for ((name, f) in CHECKSUMS) { val pass = frames.count { f(it.second) }; if (pass * 2 >= n && (best == null || pass > best.second)) best = name to pass }
        val consts = HashMap<Int, Int>(); val vars = ArrayList<Int>()
        for (i in 0 until len) { val vals = frames.map { u(it.second, i) }.toSet(); if (vals.size == 1) consts[i] = vals.first() else vars += i }
        val perSession = frames.groupBy { it.first.sessionId }.values.map { l -> l.sortedBy { it.first.seq } }
        var counterPos: Int? = null; var cSteps = 0; var cOk = 0; var cMissing = 0
        val steps = perSession.sumOf { maxOf(0, it.size - 1) }
        if (steps >= 9) {
            var bestPct = 0.0
            for (i in vars) {
                var ok = 0; var missing = 0
                for (l in perSession) for (j in 1 until l.size) {
                    val d = (u(l[j].second, i) - u(l[j - 1].second, i)) and 0xFF
                    if (d == 1) ok++ else if (d in 2..255) missing += d - 1
                }
                val pct = ok.toDouble() / steps
                if (pct >= 0.9 && pct > bestPct) { bestPct = pct; counterPos = i; cSteps = steps; cOk = ok; cMissing = missing }
            }
        }
        val repeated = perSession.sumOf { l -> (1 until l.size).count { l[it].first.hex == l[it - 1].first.hex } }
        return TypeGroupReport(type, len, n, distinct, best?.first, best?.second ?: 0, if (best == null) 0 else n - best.second,
            counterPos, cSteps, cOk, cMissing, repeated, consts.toSortedMap(), vars)
    }

    private fun compare(records: List<PayloadRecord>): List<SessionComparison> {
        val out = ArrayList<SessionComparison>()
        val groups = records.filter { it.source != PayloadSource.ADVERTISEMENT && it.charUuid != null }.mapNotNull { r -> r.bytes?.takeIf { it.isNotEmpty() }?.let { r to it } }
            .groupBy { (r, b) -> Triple(r.charUuid!!, u(b, 0), b.size) }
        for ((k, fs) in groups) {
            val bySes = fs.groupBy { it.first.sessionId }
            if (bySes.size < 2) continue
            val stable = ArrayList<Int>(); val specific = ArrayList<Int>(); val varying = ArrayList<Int>()
            for (i in 0 until k.third) {
                val perSes = bySes.values.map { l -> l.map { u(it.second, i) }.toSet() }
                when {
                    perSes.any { it.size > 1 } -> varying += i
                    perSes.map { it.first() }.toSet().size == 1 -> stable += i
                    else -> specific += i
                }
            }
            out += SessionComparison(k.first, k.second, k.third, bySes.keys.sorted(), bySes.mapValues { it.value.size }, stable, specific, varying)
        }
        return out.sortedWith(compareBy({ it.charUuid }, { it.typeByte }))
    }

    private fun buildRows(streams: List<StreamReport>, gatt: List<GattCharRow>, devices: List<DeviceInfo>, verified: Set<String>, events: List<ConnEvent>): List<CharacteristicRow> {
        val nameOf = { d: String -> devices.firstOrNull { it.deviceId == d }?.name }
        val keys = LinkedHashSet<Pair<String, String?>>()
        gatt.forEach { keys += it.deviceId to it.charUuid }
        streams.forEach { keys += it.deviceId to it.charUuid }
        return keys.map { (dev, cu) ->
            val g = gatt.firstOrNull { it.deviceId == dev && it.charUuid == cu } ?: gatt.firstOrNull { it.charUuid == cu && it.deviceId == "unknown" || it.charUuid == cu && dev == "unknown" }
            val ss = streams.filter { (it.deviceId == dev || dev == "unknown" || it.deviceId == "unknown") && it.charUuid == cu }
            val live = ss.filter { it.source != PayloadSource.ADVERTISEMENT }
            val count = live.sumOf { it.count }
            val structure = when {
                ss.any { it.source == PayloadSource.ADVERTISEMENT } && live.isEmpty() -> "chỉ có dữ liệu quảng bá (advertisement)"
                live.isEmpty() -> "chưa có payload"
                else -> live.flatMap { it.groups }.joinToString("; ") { g2 ->
                    "0x%02X×%dB".format(g2.typeByte, g2.length) + (g2.checksumScheme?.let { " [${it} ${g2.checksumPass}/${g2.count}]" } ?: "") + (g2.counterPos?.let { " [bộ đếm@$it]" } ?: "")
                }.ifEmpty { "payload không có cấu trúc khung nhận diện được" }
            }
            val strong = live.flatMap { it.groups }.let { gs -> gs.isNotEmpty() && gs.all { it.checksumScheme != null && it.checksumFail == 0 && it.distinctPayloads >= 30 } }
            val conf = when { count == 0 -> "Không có dữ liệu"; strong -> "Trung bình (cấu trúc khung khớp; chưa biết ý nghĩa)"; else -> "Thấp" }
            val props = g?.properties ?: ss.firstOrNull()?.properties ?: emptyList()
            val subscribedOk = events.any { it.type == ConnEventType.SUBSCRIBE_OK && it.uuid == cu && (it.deviceId == dev || dev == "unknown") }
            val next = when {
                count == 0 && (("NOTIFY" in props) || ("INDICATE" in props)) && subscribedOk -> "Đã đăng ký nhưng 0 gói: kiểm tra ghép đôi/mã hóa, khóa điện đã bật chưa, ghi lại thêm."
                count == 0 && (("NOTIFY" in props) || ("INDICATE" in props)) -> "Bật nghe (notify/indicate) khi xe bật khóa điện và ghi log."
                count == 0 && "READ" in props -> "Chỉ đọc nếu characteristic nằm trong danh sách thông tin chuẩn được phép."
                count == 0 -> "Không có hành động an toàn: không ghi vào characteristic khi chưa có giao thức được xác minh."
                else -> "Ghi cùng dữ liệu tham chiếu độc lập theo SAMPLE_LOG_GUIDE.md rồi chạy bộ xác minh."
            }
            CharacteristicRow(dev, nameOf(dev), g?.serviceUuid ?: ss.firstOrNull()?.serviceUuid, cu, props, count > 0, count, structure,
                if (ss.isEmpty()) "chỉ có cây GATT" else ss.joinToString(", ") { "${it.source.name.lowercase()}×${it.count}" },
                if (cu != null && cu in verified) "ĐÃ CHỨNG MINH qua trường VERIFIED" else NOT_PROVEN_LINK, conf, next)
        }
    }

    private fun buildStages(records: List<PayloadRecord>, events: List<ConnEvent>, gatt: List<GattCharRow>, devices: List<DeviceInfo>, verified: Set<String>): List<DeviceStageRow> {
        val ids = LinkedHashSet<String>()
        devices.forEach { ids += it.deviceId }; records.forEach { ids += it.deviceId }; gatt.forEach { ids += it.deviceId }
        events.filter { it.deviceId != "-" }.forEach { ids += it.deviceId }
        return ids.map { id ->
            val connected = events.any { it.deviceId == id && it.type in setOf(ConnEventType.CONNECTED, ConnEventType.DISCOVERY_DONE) } || gatt.any { it.deviceId == id }
            val live = records.filter { it.deviceId == id && it.source != PayloadSource.ADVERTISEMENT }
            val proven = live.any { it.charUuid != null && it.charUuid in verified }
            val stage = when {
                proven -> DeviceStage.ENGINE_LINK_PROVEN
                live.isNotEmpty() -> DeviceStage.TRANSMITTED
                connected -> DeviceStage.CONNECTED
                else -> DeviceStage.DISCOVERED
            }
            val note = when (stage) {
                DeviceStage.TRANSMITTED -> "Truyền ${live.size} payload; chức năng CHƯA xác định, liên quan động cơ CHƯA chứng minh. UUID kiểu Nordic UART không tự chứng minh là ECU Yamaha."
                DeviceStage.CONNECTED -> "Đã nối GATT nhưng chưa có payload."
                DeviceStage.DISCOVERED -> "Chỉ thấy quảng bá."
                else -> "Có trường đã VERIFIED trên kênh này."
            }
            DeviceStageRow(id, devices.firstOrNull { it.deviceId == id }?.name, stage, note)
        }
    }

    // ------------------------------------------------------------------ kết xuất
    fun toMarkdown(r: AnalysisReport): String = buildString {
        appendLine("# Báo cáo phân tích log (ngoại tuyến)")
        appendLine()
        appendLine("Tổng payload: ${r.totalPayloads} (${r.bySource.entries.joinToString(", ") { "${it.key}=${it.value}" }}). Bị cắt do giới hạn: ${r.droppedOldest}.")
        r.warnings.forEach { appendLine("- ⚠ $it") }
        appendLine()
        appendLine("## Thiết bị")
        r.devices.forEach { appendLine("- `${it.deviceId}` ${it.name ?: ""} — **${it.stage.title}**. ${it.note}") }
        appendLine()
        appendLine("## Bảng characteristic")
        appendLine("| Thiết bị | Service | Characteristic | Thuộc tính | Có payload | Cấu trúc | Nguồn | Liên hệ với xe | Độ tin cậy cấu trúc | Bước tiếp |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|")
        r.rows.forEach { c -> appendLine("| ${c.deviceId} | ${c.serviceUuid ?: "-"} | ${c.charUuid ?: "-"} | ${c.properties.joinToString("/")} | ${if (c.receivedPayload) "có (${c.payloadCount})" else "không"} | ${c.structure} | ${c.evidenceSource} | ${c.vehicleLink} | ${c.structureConfidence} | ${c.nextStep} |") }
        appendLine()
        appendLine("## Kết luận kèm bằng chứng")
        r.findings.forEach { appendLine("- [${it.kind}] **${it.subject}** — ${it.statement} _(bằng chứng: ${it.evidence})_") }
    }

    fun toJson(r: AnalysisReport): String = buildString {
        fun q(s: String?) = MiniJson.quoteOrNull(s)
        append("{\"format\":\"nvx-connect-analysis/1\",\"total_payloads\":${r.totalPayloads},\"dropped_oldest\":${r.droppedOldest},")
        append("\"warnings\":[").append(r.warnings.joinToString(",") { MiniJson.quote(it) }).append("],")
        append("\"devices\":[").append(r.devices.joinToString(",") { "{\"id\":${q(it.deviceId)},\"stage\":${q(it.stage.name)},\"note\":${q(it.note)}}" }).append("],")
        append("\"characteristics\":[").append(r.rows.joinToString(",") { c ->
            "{\"device\":${q(c.deviceId)},\"service\":${q(c.serviceUuid)},\"char\":${q(c.charUuid)},\"properties\":[${c.properties.joinToString(",") { MiniJson.quote(it) }}]," +
                "\"received_payload\":${c.receivedPayload},\"payload_count\":${c.payloadCount},\"structure\":${q(c.structure)},\"evidence_source\":${q(c.evidenceSource)}," +
                "\"vehicle_link\":${q(c.vehicleLink)},\"structure_confidence\":${q(c.structureConfidence)},\"next_step\":${q(c.nextStep)}}" }).append("],")
        append("\"findings\":[").append(r.findings.joinToString(",") { "{\"kind\":${q(it.kind.name)},\"subject\":${q(it.subject)},\"statement\":${q(it.statement)},\"evidence\":${q(it.evidence)}}" }).append("]}")
    }
}
