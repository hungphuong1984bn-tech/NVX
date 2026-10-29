package vn.nvxconnect.telemetry

import kotlin.math.abs

data class DiagGap(val fromWallMs: Long, val toWallMs: Long) { val ms: Long get() = toWallMs - fromWallMs }

data class SeriesStat(
    val id: String, val title: String, val unit: String, val cls: DiagClass, val source: DiagSource?,
    val count: Int, val min: Double, val max: Double, val mean: Double, val firstWallMs: Long, val lastWallMs: Long,
    val gaps: List<DiagGap>, val points: List<Pair<Long, Double>>
) { val gapTotalMs: Long get() = gaps.sumOf { it.ms } }

data class SimpleStat(val count: Int, val min: Double, val max: Double, val mean: Double)

data class DiagSummary(
    val meta: DiagMeta, val state: DiagSessionState, val startedWallMs: Long, val endedWallMs: Long?,
    val activeMs: Long, val pausedMs: Long, val series: List<SeriesStat>, val missing: List<DiagRecord>,
    val bleEventCounts: Map<String, Int>, val linkLost: Int, val readyCount: Int, val reconnectAttempts: Int, val attemptFailed: Int,
    val errorCounts: Map<String, Int>, val notes: List<DiagRecord>, val connectMs: SimpleStat?, val firstFrameMs: SimpleStat?, val silenceMs: SimpleStat?,
    val clockJumps: Int, val droppedLimit: Long, val droppedLowStorage: Long, val droppedError: Long, val pausedIgnored: Long,
    val interruptedCount: Int, val limitHit: Boolean, val corruptLines: Int, val tornTail: Boolean, val totalRecords: Int, val sampleCount: Int
) {
    val verifiedOnVehicle: Boolean get() = meta.verifiedOnVehicle
    /** Chữ bắt buộc ở đầu mọi báo cáo khi chưa xác minh trên xe thật. */
    val verificationText: String get() = if (verifiedOnVehicle) DiagVerification.VERIFIED_TEXT else DiagVerification.NOT_VERIFIED_TEXT
    val chartable: List<SeriesStat> get() = series.filter { it.id in CHART_IDS && it.points.size >= MIN_CHART_POINTS && it.lastWallMs > it.firstWallMs }

    companion object {
        const val MIN_CHART_POINTS = 5
        val CHART_IDS = setOf(DiagIds.GPS_SPEED, DiagIds.BLE_RSSI, DiagIds.BLE_RATE, DiagIds.BLE_SILENCE) + DiagCatalog.vehicle.map { DiagIds.veh(it.param) }
        /** Chuỗi chỉ ghi khi đổi/nhịp tim: khoảng cách giữa hai mẫu KHÔNG có nghĩa là thiếu dữ liệu. */
        private val NO_GAP_IDS = setOf(DiagIds.BLE_FRAMES, DiagIds.BLE_LOST, DiagIds.BLE_RSSI, DiagIds.BLE_MTU, DiagIds.GPS_DISTANCE, DiagIds.BLE_CONNECT_MS, DiagIds.BLE_FIRST_FRAME_MS)
        const val CLOCK_TOLERANCE_MS = 2_000L

        fun titleOf(id: String): String = DiagCatalog.vehicle.firstOrNull { DiagIds.veh(it.param) == id }?.title
            ?: DiagCatalog.alwaysUnsupported.firstOrNull { it.first == id }?.second
            ?: when (id) {
                DiagIds.GPS_SPEED -> "Tốc độ GPS (điện thoại)"; DiagIds.GPS_DISTANCE -> "Quãng đường GPS (cộng dồn)"
                DiagIds.BLE_RSSI -> "RSSI"; DiagIds.BLE_MTU -> "MTU"; DiagIds.BLE_FRAMES -> "Số gói đã nhận"; DiagIds.BLE_LOST -> "Gói app làm mất"
                DiagIds.BLE_RATE -> "Tốc độ nhận gói"; DiagIds.BLE_SILENCE -> "Thời gian từ gói gần nhất"
                DiagIds.BLE_CONNECT_MS -> "Thời gian nối → sẵn sàng"; DiagIds.BLE_FIRST_FRAME_MS -> "Thời gian sẵn sàng → gói đầu"
                else -> id
            }

        private fun stat(v: List<Double>) = if (v.isEmpty()) null else SimpleStat(v.size, v.min(), v.max(), v.average())

        /**
         * Tính thống kê từ bản ghi THẬT. Không nội suy, không điền khoảng trống: khoảng không có mẫu được LIỆT KÊ thành [DiagGap]
         * (trừ khoảng nằm trong lúc người dùng tạm dừng).
         */
        fun compute(data: DiagSessionData, liveState: DiagSessionState? = null): DiagSummary {
            val recs = data.records.sortedBy { it.seq }
            val interval = data.meta.sampleIntervalMs
            val gapThreshold = maxOf(3_000L, interval * 3)

            // --- thời lượng + cửa sổ tạm dừng + nhảy đồng hồ
            var open = false; var segMono = 0L; var lastMono = 0L; var lastWall = 0L; var active = 0L
            var pauseStart: Long? = null; var paused = 0L; val pauseWindows = ArrayList<LongRange>()
            var jumps = 0; var interrupted = 0; var limitHit = false; var lastSeq = Long.MIN_VALUE
            var prevWall: Long? = null; var prevMono: Long? = null; var ended: Long? = null; var stopped = false; var recovered = false
            for (r in recs) {
                if (r.kind == DiagKind.SESSION) when (r.name) {
                    DiagControl.START, DiagControl.CONTINUE -> {
                        open = true; segMono = r.monoMs; stopped = false; recovered = false; prevWall = null; prevMono = null
                        if (r.name == DiagControl.CONTINUE) interrupted++
                    }
                    DiagControl.RESUME -> { open = true; segMono = r.monoMs; pauseStart?.let { ps -> paused += (r.wallMs - ps).coerceAtLeast(0); pauseWindows += ps..r.wallMs }; pauseStart = null; prevWall = null; prevMono = null }
                    DiagControl.PAUSE -> { if (open) active += (r.monoMs - segMono).coerceAtLeast(0); open = false; pauseStart = r.wallMs }
                    DiagControl.STOP -> {
                        if (open) active += (r.monoMs - segMono).coerceAtLeast(0); open = false; stopped = true; ended = r.wallMs
                        pauseStart?.let { ps -> paused += (r.wallMs - ps).coerceAtLeast(0); pauseWindows += ps..r.wallMs }; pauseStart = null
                    }
                    DiagControl.RECOVERED -> { if (open) active += (lastMono - segMono).coerceAtLeast(0); open = false; recovered = true; ended = lastWall.takeIf { it > 0 } }
                    DiagControl.LIMIT -> limitHit = true
                }
                if (r.kind != DiagKind.SESSION || r.name == DiagControl.START) { /* đếm bên dưới */ }
                if (!(r.kind == DiagKind.SESSION && r.name == DiagControl.RECOVERED)) {
                    if (open && prevWall != null && prevMono != null && abs((r.wallMs - prevWall) - (r.monoMs - prevMono)) > CLOCK_TOLERANCE_MS) jumps++
                    if (open) { prevWall = r.wallMs; prevMono = r.monoMs }
                    lastMono = maxOf(lastMono, r.monoMs); lastWall = maxOf(lastWall, r.wallMs)
                }
                lastSeq = r.seq
            }
            if (open) active += (lastMono - segMono).coerceAtLeast(0)
            if (pauseStart != null) { paused += (lastWall - pauseStart).coerceAtLeast(0); pauseWindows += pauseStart..lastWall }
            val state = liveState ?: when { stopped -> DiagSessionState.STOPPED; recovered -> DiagSessionState.INTERRUPTED; else -> DiagSessionState.UNFINISHED }
            val endWall = ended ?: recs.lastOrNull()?.wallMs

            // --- chuỗi mẫu
            val samples = recs.filter { it.kind == DiagKind.SAMPLE && it.value != null && it.value.isFinite() }
            val series = samples.groupBy { it.name }.map { (id, list) ->
                val sorted = list.sortedBy { it.wallMs }
                val vals = sorted.map { it.value!! }
                val gaps = if (id in NO_GAP_IDS) emptyList() else sorted.zipWithNext().filter { (a, b) -> b.wallMs - a.wallMs > gapThreshold && pauseWindows.none { w -> w.first <= b.wallMs && w.last >= a.wallMs } }
                    .map { (a, b) -> DiagGap(a.wallMs, b.wallMs) }
                SeriesStat(id, titleOf(id), sorted.first().unit ?: "", sorted.first().cls ?: DiagClass.REAL, sorted.first().source,
                    vals.size, vals.min(), vals.max(), vals.average(), sorted.first().wallMs, sorted.last().wallMs, gaps, sorted.map { it.wallMs to it.value!! })
            }.sortedBy { it.id }

            val ble = recs.filter { it.kind == DiagKind.BLE_EVENT }
            val bleCounts = ble.groupingBy { it.name }.eachCount().toSortedMap()
            val stop = recs.lastOrNull { it.kind == DiagKind.SESSION && it.name == DiagControl.STOP }
            fun attrLong(k: String) = stop?.attrs?.get(k)?.toLongOrNull() ?: 0L
            return DiagSummary(
                meta = data.meta, state = state, startedWallMs = data.meta.startedWallMs, endedWallMs = endWall, activeMs = active, pausedMs = paused,
                series = series, missing = recs.filter { it.kind == DiagKind.MISSING },
                bleEventCounts = bleCounts, linkLost = bleCounts["LINK_LOST"] ?: 0, readyCount = bleCounts["STATE_READY"] ?: 0,
                reconnectAttempts = bleCounts["STATE_RECONNECTING"] ?: 0, attemptFailed = bleCounts["ATTEMPT_FAILED"] ?: 0,
                errorCounts = recs.filter { it.kind == DiagKind.ERROR }.groupingBy { it.name }.eachCount().toSortedMap(),
                notes = recs.filter { it.kind == DiagKind.NOTE },
                connectMs = stat(samples.filter { it.name == DiagIds.BLE_CONNECT_MS }.map { it.value!! }),
                firstFrameMs = stat(samples.filter { it.name == DiagIds.BLE_FIRST_FRAME_MS }.map { it.value!! }),
                silenceMs = stat(samples.filter { it.name == DiagIds.BLE_SILENCE }.map { it.value!! }),
                clockJumps = jumps, droppedLimit = attrLong("dropped_limit"), droppedLowStorage = attrLong("dropped_low_storage"), droppedError = attrLong("dropped_error"),
                pausedIgnored = attrLong("paused_ignored"), interruptedCount = interrupted + (if (recovered) 1 else 0), limitHit = limitHit,
                corruptLines = data.corruptLines, tornTail = data.tornTail, totalRecords = recs.size, sampleCount = samples.size)
        }
    }
}
