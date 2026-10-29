package vn.nvxconnect.telemetry

import vn.nvxconnect.bluetooth.GattServiceInfo
import vn.nvxconnect.protocol.ConnState
import vn.nvxconnect.protocol.LinkDiagnostics
import vn.nvxconnect.protocol.LinkEvent
import vn.nvxconnect.protocol.LinkEventKind
import vn.nvxconnect.protocol.LinkHint
import vn.nvxconnect.protocol.LinkStage
import vn.nvxconnect.protocol.LinkStageInput
import vn.nvxconnect.protocol.LinkStages
import vn.nvxconnect.protocol.SubscriptionState
import java.util.Locale

enum class MonitorTone { GOOD, WARN, BAD, NEUTRAL }
data class MonitorRow(val label: String, val value: String, val tone: MonitorTone = MonitorTone.NEUTRAL)
data class MonitorSection(val title: String, val rows: List<MonitorRow>)

/** Bốn mức phải được hiển thị TÁCH BẠCH. Không mức nào suy ra mức khác. */
data class MonitorLevels(val bluetooth: MonitorRow, val receiving: MonitorRow, val recording: MonitorRow, val verified: MonitorRow,
    /** Mức liên kết 5 bậc (CONNECTED → GATT READY → SUBSCRIPTION SUCCESS → DATA RECEIVED → PROTOCOL VERIFIED). */
    val stage: MonitorRow = MonitorRow("Mức liên kết", "Chưa kết nối"))

data class MonitorSnapshot(val levels: MonitorLevels, val sections: List<MonitorSection>)

data class MonitorInput(
    val state: ConnState, val hint: LinkHint, val rssi: Int?, val mtu: Int,
    val subscriptions: Map<String, SubscriptionState>, val deviceLabel: String?, val deviceName: String?,
    val readySinceNs: Long?, val nowNs: Long, val nowMs: Long,
    val capture: CaptureStats, val history: List<LinkEvent>, val diag: LinkDiagnostics,
    val invalidFrames: Long, val counterGaps: Long,
    val verifiedFields: Int, val hypothesisFields: Int, val backgroundService: String?,
    /** true chỉ khi AdapterRegistry có adapter đã đăng ký kèm hồ sơ tương thích khớp thiết bị này. */
    val adapterVerified: Boolean = false
)

/** Dựng bảng giám sát từ số liệu THẬT. Không có số nào được bịa: thiếu dữ liệu → "Chưa có dữ liệu". */
object LinkMonitorModel {
    const val RECEIVING_WINDOW_MS = 3_000L
    private const val NONE = "Chưa có dữ liệu"

    fun duration(ms: Long): String { val s = ms / 1000; return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) }

    fun build(i: MonitorInput): MonitorSnapshot {
        val ready = i.state as? ConnState.Ready
        val c = i.capture
        val lastAgo = c.lastFrameWallMs?.let { i.nowMs - it }
        val receiving = ready != null && lastAgo != null && lastAgo in 0..RECEIVING_WINDOW_MS

        val bt = when {
            ready != null -> MonitorRow("Bluetooth", "Đã kết nối (GATT sẵn sàng)", MonitorTone.GOOD)
            i.state.isActive -> MonitorRow("Bluetooth", "Đang kết nối…", MonitorTone.WARN)
            i.state is ConnState.Error -> MonitorRow("Bluetooth", "Lỗi: ${i.state.reason}", MonitorTone.BAD)
            else -> MonitorRow("Bluetooth", "Chưa kết nối", MonitorTone.NEUTRAL)
        }
        val rx = when {
            ready == null -> MonitorRow("Nhận dữ liệu", "Không (chưa kết nối)", MonitorTone.NEUTRAL)
            receiving -> MonitorRow("Nhận dữ liệu", String.format(Locale.US, "Đang nhận %.1f gói/giây", c.ratePerSec), MonitorTone.GOOD)
            c.received == 0L -> MonitorRow("Nhận dữ liệu", "Đã kết nối nhưng xe chưa gửi gói nào" + (if (i.hint == LinkHint.WAITING_IGNITION) " (có thể tắt khóa điện)" else ""), MonitorTone.WARN)
            else -> MonitorRow("Nhận dữ liệu", "Ngừng nhận ${((lastAgo ?: 0) / 1000)} giây trước", MonitorTone.WARN)
        }
        val rec = when {
            c.writer == WriterHealth.DEGRADED -> MonitorRow("Ghi dữ liệu", "LỖI GHI: ${c.writerError ?: "không rõ"} — đang giữ ${c.retryBuffered} gói trong bộ đệm", MonitorTone.BAD)
            c.lostByApp > 0 -> MonitorRow("Ghi dữ liệu", "Đang ghi nhưng app đã làm mất ${c.lostByApp} gói", MonitorTone.WARN)
            c.persisted > 0 -> MonitorRow("Ghi dữ liệu", "Đã ghi ${c.persisted} gói xuống bộ nhớ", MonitorTone.GOOD)
            else -> MonitorRow("Ghi dữ liệu", "Chưa ghi gói nào", MonitorTone.NEUTRAL)
        }
        val ver = when {
            i.verifiedFields > 0 -> MonitorRow("Dữ liệu đã xác minh", "${i.verifiedFields} trường đã xác minh", MonitorTone.GOOD)
            i.hypothesisFields > 0 -> MonitorRow("Dữ liệu đã xác minh", "Chưa có trường nào được xác minh (${i.hypothesisFields} giả thuyết giải mã)", MonitorTone.WARN)
            else -> MonitorRow("Dữ liệu đã xác minh", "Chưa có trường nào được xác minh", MonitorTone.NEUTRAL)
        }

        val link = buildList {
            add(MonitorRow("Xe / thiết bị", listOfNotNull(i.deviceName, i.deviceLabel).joinToString(" · ").ifBlank { NONE }))
            add(MonitorRow("Kết nối liên tục", if (i.readySinceNs != null && ready != null) duration(maxOf(0, (i.nowNs - i.readySinceNs) / 1_000_000)) else NONE))
            add(MonitorRow("RSSI", i.rssi?.let { "$it dBm" } ?: NONE))
            add(MonitorRow("MTU", if (ready != null) i.mtu.toString() else NONE))
            add(MonitorRow("Phiên GATT thứ", i.diag.epoch.toString()))
            i.backgroundService?.let { add(MonitorRow("Chạy nền", it, if (it.startsWith("Bị từ chối")) MonitorTone.BAD else MonitorTone.NEUTRAL)) }
        }
        val gatt = buildList {
            if (ready == null) add(MonitorRow("Dịch vụ", NONE))
            else {
                add(MonitorRow("Dịch vụ/characteristic", "${ready.services.size} dịch vụ, ${ready.services.sumOf { it.characteristics.size }} characteristic"))
                for ((u, s) in i.subscriptions) add(MonitorRow("Đăng ký nghe ${shortUuid(u)}", when (s) {
                    SubscriptionState.ACTIVE -> "Thiết bị đã xác nhận"; SubscriptionState.PENDING -> "Đang chờ xác nhận"; SubscriptionState.FAILED -> "THẤT BẠI" },
                    when (s) { SubscriptionState.ACTIVE -> MonitorTone.GOOD; SubscriptionState.PENDING -> MonitorTone.WARN; SubscriptionState.FAILED -> MonitorTone.BAD }))
                if (i.subscriptions.isEmpty()) add(MonitorRow("Đăng ký nghe", "Chưa đăng ký kênh nào", MonitorTone.WARN))
            }
        }
        val data = listOf(
            MonitorRow("Gói gần nhất", lastAgo?.let { "${maxOf(0, it) / 1000} giây trước" } ?: NONE),
            MonitorRow("Tốc độ nhận", if (c.received > 0) String.format(Locale.US, "%.1f gói/giây", c.ratePerSec) else NONE),
            MonitorRow("Tổng số gói / byte", "${c.received} / ${c.bytes}"),
            MonitorRow("Gói lỗi khung (sai checksum/độ dài)", i.invalidFrames.toString(), if (i.invalidFrames > 0) MonitorTone.WARN else MonitorTone.NEUTRAL),
            MonitorRow("Khoảng trống bộ đếm của xe", i.counterGaps.toString()),
            MonitorRow("Gói rỗng / quá lớn", "${c.emptyFrames} / ${c.oversizeFrames}", if (c.emptyFrames + c.oversizeFrames > 0) MonitorTone.WARN else MonitorTone.NEUTRAL),
            MonitorRow("Gói trùng nội dung (liền kề)", c.duplicatePayloads.toString()),
            MonitorRow("Khoảng cách lớn nhất giữa 2 gói", if (c.received > 1) "${c.maxGapMs} ms" else NONE),
            MonitorRow("Đồng hồ hệ thống nhảy lùi", c.wallClockJumps.toString(), if (c.wallClockJumps > 0) MonitorTone.WARN else MonitorTone.NEUTRAL),
            MonitorRow("Gói app làm mất", "${c.lostByApp} (hàng đợi ${c.droppedIngress}, ghi ${c.droppedStorage})", if (c.lostByApp > 0) MonitorTone.BAD else MonitorTone.GOOD),
            MonitorRow("Gói đến muộn/ngoài phiên bị loại", i.diag.lateEventsDropped.toString()),
            MonitorRow("Gói giao diện không theo kịp", "${i.diag.uiFramesDropped} (vẫn được ghi xuống bộ nhớ)")
        )
        val storage = listOf(
            MonitorRow("Hàng đợi", "${c.queueDepth}/${c.queueCapacity}" + (if (c.retryBuffered > 0) " + ${c.retryBuffered} chờ thử lại" else ""), if (c.queueDepth * 10 > c.queueCapacity * 8) MonitorTone.WARN else MonitorTone.NEUTRAL),
            MonitorRow("Đã ghi / dung lượng", "${c.persisted} gói / ${c.storageBytes / 1024} KB"),
            MonitorRow("Đoạn log đã xoay vòng xóa", c.evictedSegments.toString(), if (c.evictedSegments > 0) MonitorTone.WARN else MonitorTone.NEUTRAL)
        ) + c.recentDrops.takeLast(3).map { MonitorRow("Mất gói", "${it.count} gói (#${it.firstSeq}–#${it.lastSeq}) — ${it.reason.title}", MonitorTone.BAD) }

        val hist = i.history.takeLast(8).reversed().map { e ->
            val label = when (e.kind) {
                LinkEventKind.READY -> "Sẵn sàng"; LinkEventKind.LINK_LOST -> "Mất liên kết"; LinkEventKind.ATTEMPT_FAILED -> "Nối thất bại"
                LinkEventKind.USER_DISCONNECT -> "Người dùng ngắt"; LinkEventKind.BLUETOOTH_OFF -> "Bluetooth tắt"
                LinkEventKind.BLUETOOTH_RESUMED -> "Bluetooth bật lại"; LinkEventKind.AUTH_REQUIRED -> "Cần xác thực" }
            MonitorRow(label, e.reason + (e.status?.let { " [mã $it]" } ?: "") + (e.upMs?.let { " · sống ${duration(it)}" } ?: "") + " · #${e.epoch}",
                if (e.kind == LinkEventKind.READY || e.kind == LinkEventKind.BLUETOOTH_RESUMED) MonitorTone.GOOD else if (e.kind == LinkEventKind.USER_DISCONNECT) MonitorTone.NEUTRAL else MonitorTone.WARN)
        }
        // Có gói thuộc PHIÊN GATT HIỆN TẠI khi gói gần nhất đến sau thời điểm Ready (đổi từ mốc đơn điệu sang giờ hệ thống).
        val readyWallMs = i.readySinceNs?.let { i.nowMs - (i.nowNs - it) / 1_000_000 }
        val framesThisEpoch = if (readyWallMs != null && c.lastFrameWallMs != null && c.lastFrameWallMs >= readyWallMs) 1L else 0L
        val stageRes = LinkStages.evaluate(LinkStageInput(i.state, i.subscriptions, framesThisEpoch, i.adapterVerified, i.verifiedFields))
        val stageRow = MonitorRow("Mức liên kết", stageRes.stage.title + (stageRes.missingForNext?.let { " — còn thiếu: $it" } ?: ""),
            when (stageRes.stage) { LinkStage.PROTOCOL_VERIFIED -> MonitorTone.GOOD; LinkStage.DISCONNECTED -> MonitorTone.NEUTRAL; else -> MonitorTone.WARN })
        return MonitorSnapshot(MonitorLevels(bt, rx, rec, ver, stageRow), listOf(
            MonitorSection("Liên kết", link), MonitorSection("Dịch vụ GATT", gatt), MonitorSection("Dữ liệu nhận", data),
            MonitorSection("Ghi dữ liệu", storage), MonitorSection("Lịch sử kết nối", hist.ifEmpty { listOf(MonitorRow("—", NONE)) })))
    }

    fun shortUuid(u: String): String = u.lowercase().let { if (it.length >= 8) it.take(8) else it }
}
