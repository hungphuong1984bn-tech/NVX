package vn.nvxconnect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.nvxconnect.AppInfo
import vn.nvxconnect.NvxApp
import vn.nvxconnect.auto.AuthLevel
import vn.nvxconnect.bluetooth.BleBlocker
import vn.nvxconnect.bluetooth.BlePreflight
import vn.nvxconnect.domain.*
import vn.nvxconnect.helpcenter.HelpErrorMapper
import vn.nvxconnect.location.GpsStatus
import vn.nvxconnect.location.TripPhase
import vn.nvxconnect.pro.LabStatus
import vn.nvxconnect.pro.LabStatusMapper
import vn.nvxconnect.pro.ProPage
import vn.nvxconnect.pro.ProTab
import vn.nvxconnect.protocol.ConnState
import vn.nvxconnect.protocol.LinkLevel
import vn.nvxconnect.protocol.VehicleLinkStatus
import vn.nvxconnect.protocol.VehicleParam
import vn.nvxconnect.protocol.formatLive
import java.util.Calendar

private data class Shortcut(val title: String, val subtitle: String, val go: () -> Unit)
private data class Alert(val text: String, val tone: Tone, val action: String? = null, val onAction: (() -> Unit)? = null)

/** Trang chủ Pro: trạng thái xe/BLE, lối tắt, tóm tắt gần nhất và cảnh báo phát sinh từ dữ liệu thật. Không có số liệu xe nào được dựng sẵn. */
@Composable
fun HomeScreen(app: AppViewModel, ble: BleViewModel, onOpenTab: (ProTab) -> Unit, onOpenPage: (ProPage) -> Unit, onOpenHelp: (String?) -> Unit = {}) {
    val ctx = LocalContext.current
    val nvx = ctx.applicationContext as NvxApp
    val settings by app.settings.collectAsState()
    val trips by app.trips.collectAsState()
    val maint by app.maintenance.collectAsState()
    val link by ble.linkStatus.collectAsState()
    val diag by nvx.diagLogger.status.collectAsState()
    val gps by app.engine.gps.collectAsState()
    val fix by app.engine.lastFix.collectAsState()
    val phase by app.engine.phase.collectAsState()
    val live by app.engine.live.collectAsState()
    val msg by app.message.collectAsState()
    val tele by nvx.telemetry.state.collectAsState()
    val gatt by ble.connState.collectAsState()
    val auth by ble.auth.collectAsState()
    val rssi by ble.rssi.collectAsState()
    val saved by ble.saved.collectAsState()
    val rec by ble.recStats.collectAsState()
    val verdicts by ble.fieldVerdicts.collectAsState()
    val layout = rememberLayoutInfo()
    val now = rememberNow()
    LaunchedEffect(Unit) { ble.evaluateFields() }

    val startOfDay = remember { Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis }
    val real = trips.filter { it.isDemo == settings.demo }
    val todayTrips = real.filter { it.startMs >= startOfDay }
    val todayKm = (todayTrips.sumOf { it.distanceM } + (live?.distanceM ?: 0.0)) / 1000.0
    val due = maint.filter { it.isDemo == settings.demo }.map { it to Maintenance.due(it, settings.odoKm, System.currentTimeMillis()) }
        .filter { it.second.status == MaintStatus.SOON || it.second.status == MaintStatus.OVERDUE }.take(3)

    // Trạng thái dữ liệu xe: chỉ TRỰC TIẾP khi có giá trị đã xác minh còn mới.
    val anyFresh = link.protocolVerified && tele.values.keys.any { tele.fresh(it, now) != null }
    val anyStale = link.protocolVerified && tele.values.isNotEmpty() && !anyFresh
    val dataChip = when { !link.protocolVerified -> "CHƯA XÁC MINH" to Tone.Warn; anyFresh -> "TRỰC TIẾP" to Tone.Good; anyStale -> "DỮ LIỆU CŨ" to Tone.Warn; else -> "ĐANG CHỜ DỮ LIỆU" to Tone.Neutral }
    val linkTone = when (link.level) { LinkLevel.VERIFIED -> Tone.Good; LinkLevel.RAW_DATA_ONLY -> Tone.Warn; LinkLevel.ERROR -> Tone.Bad; else -> Tone.Neutral }

    // Cảnh báo CHỈ từ trạng thái thật.
    val blockers = remember(now / 5000) { runCatching { BlePreflight.check(ctx) }.getOrNull() }
    val alerts = buildList {
        HelpErrorMapper.forState(gatt, link)?.let { id -> add(Alert(if (gatt is ConnState.Error) "Kết nối: ${(gatt as ConnState.Error).reason}" else link.headline, if (gatt is ConnState.Error) Tone.Bad else Tone.Warn, "Cách khắc phục") { onOpenHelp(id) }) }
        if (blockers != null && BleBlocker.PERMISSION_MISSING in blockers) add(Alert("Thiếu quyền Bluetooth/vị trí cho ứng dụng.", Tone.Warn, "Mở chẩn đoán") { onOpenPage(ProPage.DIAGNOSTICS) })
        if (blockers != null && BleBlocker.BLUETOOTH_OFF in blockers) add(Alert("Bluetooth của điện thoại đang tắt.", Tone.Warn, "Cài đặt Bluetooth") { Perms.openBluetoothSettings(ctx) })
        if (anyStale) add(Alert("Dữ liệu xe đã cũ (không nhận được giá trị mới trong 5 giây).", Tone.Warn))
        if (phase != TripPhase.IDLE && gps == GpsStatus.OFF) add(Alert("Đang ghi hành trình nhưng GPS tắt.", Tone.Bad, "Mở cài đặt vị trí") { Perms.openLocationSettings(ctx) })
        due.forEach { (item, d) -> add(Alert("Bảo dưỡng ${item.name}: ${if (d.status == MaintStatus.OVERDUE) "QUÁ HẠN" else "sắp đến hạn"}", if (d.status == MaintStatus.OVERDUE) Tone.Bad else Tone.Warn, "Mở bảo dưỡng") { onOpenPage(ProPage.VEHICLE) }) }
    }

    Page(null) {
        AppTopBar(title = null, actions = { IconButton(onClick = { onOpenPage(ProPage.SETTINGS) }) { Icon(Icons.Filled.Settings, contentDescription = "Cài đặt") } })
        if (settings.demo) Notice("CHẾ ĐỘ DEMO đang bật: dữ liệu minh họa, không phải dữ liệu thật.", Tone.Warn)
        msg?.let { Notice(it, Tone.Neutral, "Đóng") { app.message.value = null } }

        ConnectionStatusCard(link.headline, link.detail, linkTone, badges = listOf(dataChip)) {
            BikeHero()
            Text("${settings.vehicle.model} ${settings.vehicle.variant}".trim() + " · hồ sơ do bạn nhập", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StatusRow("Kết nối GATT", if (gatt is ConnState.Ready) "Đã kết nối" else gatt.code, if (gatt is ConnState.Ready) Tone.Good else if (gatt is ConnState.Error) Tone.Bad else if (gatt.isActive) Tone.Warn else Tone.Neutral)
            StatusRow("Xác thực thiết bị", auth.headline, when (auth.level) { AuthLevel.BONDED -> Tone.Good; AuthLevel.REQUIRED, AuthLevel.REJECTED -> Tone.Bad; AuthLevel.PAIRING -> Tone.Warn; else -> Tone.Neutral })
            StatusRow("Giao thức Yamaha", if (link.protocolVerified) "Đã xác minh" else VehicleLinkStatus.NOT_VERIFIED, if (link.protocolVerified) Tone.Good else Tone.Warn)
            StatusRow("Tín hiệu BLE", rssi?.let { "$it dBm" } ?: "—", Tone.Neutral)
        }
        if (link.level == LinkLevel.NOT_CONNECTED) StateBanner(UiState.Disconnected,
            if (saved.isEmpty()) "Chưa có xe nào được lưu" else "Chưa kết nối xe",
            "1) Bật Bluetooth và đứng gần xe.  2) Bấm \"Tự tìm và kết nối xe\".  3) Chọn thiết bị ứng viên rồi lưu làm hồ sơ xe. Tốc độ GPS và hành trình dùng được ngay cả khi chưa kết nối.",
            "Tự tìm và kết nối xe") { onOpenPage(ProPage.AUTO) }

        SectionHeader("Lối tắt")
        val shortcuts = listOf(
            Shortcut("Kết nối xe", "Quét, ghép đôi, kết nối") { onOpenTab(ProTab.CONNECT) },
            Shortcut("Dữ liệu xe", "Thu thập dữ liệu chuyên sâu") { onOpenTab(ProTab.DATA) },
            Shortcut("Nhật ký kỹ thuật", "Lịch sử phiên, xuất ZIP") { onOpenTab(ProTab.LOG) },
            Shortcut("Cockpit", "Đồng hồ, tốc độ GPS") { onOpenPage(ProPage.COCKPIT) },
            Shortcut("Telemetry", "Biểu đồ dữ liệu thật") { onOpenPage(ProPage.TELEMETRY) },
            Shortcut("Hành trình", "Chuyến đi và thống kê") { onOpenPage(ProPage.TRIPS) },
            Shortcut("Bản đồ", "Tìm đường, dẫn đường") { onOpenPage(ProPage.MAP) },
            Shortcut("Chẩn đoán", "Kết nối, lỗi, hỗ trợ") { onOpenPage(ProPage.DIAGNOSTICS) },
            Shortcut("Protocol Lab", "Quét, GATT, gói, HEX") { onOpenPage(ProPage.LAB) })
        shortcuts.chunked(layout.columns.coerceAtLeast(2)).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { sc -> ShortcutCard(sc.title, sc.subtitle, sc.go, Modifier.weight(1f)) }
                repeat(layout.columns.coerceAtLeast(2) - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        SectionHeader("Cảnh báo")
        if (alerts.isEmpty()) StateBanner(UiState.Success, "Không có cảnh báo", "Cảnh báo chỉ xuất hiện từ trạng thái thật: kết nối, quyền, dữ liệu cũ, GPS, bảo dưỡng.")
        alerts.forEach { Notice(it.text, it.tone, it.action, it.onAction) }

        SectionHeader("Gần nhất")
        Panel {
            Text("Phiên kết nối", style = MaterialTheme.typography.titleSmall)
            Text(if (rec.payloads == 0L) "Chưa nhận payload nào trong phiên này." else "Phiên ${rec.session}: ${rec.payloads} payload" + (rec.lastMs?.let { " · gói cuối ${((now - it) / 1000).coerceAtLeast(0)} giây trước" } ?: ""), style = MaterialTheme.typography.bodySmall)
            Text("Ứng dụng và phiên ghi log", style = MaterialTheme.typography.titleSmall)
            Text("NVX Connect ${AppInfo.DISPLAY}", style = MaterialTheme.typography.bodySmall)
            Text(if (diag.state == vn.nvxconnect.telemetry.DiagState.IDLE) "Chưa ghi log chẩn đoán." else "Đang ${if (diag.state == vn.nvxconnect.telemetry.DiagState.PAUSED) "tạm dừng" else "ghi"} log: ${diag.label} · ${diag.samples} mẫu (chuyển tab không làm dừng).", style = MaterialTheme.typography.bodySmall)
            Text("Hành trình gần nhất", style = MaterialTheme.typography.titleSmall)
            val last = real.firstOrNull()
            Text(if (last == null) "Chưa có hành trình." else "${fmtTime(last.startMs)} · %.1f km · ${fmtDur(last.endMs - last.startMs)} (GPS)".format(last.distanceM / 1000), style = MaterialTheme.typography.bodySmall)
            Text("Kết quả xác minh mới nhất", style = MaterialTheme.typography.titleSmall)
            if (verdicts.isEmpty()) Text("Chưa đánh giá trường nào.", style = MaterialTheme.typography.bodySmall)
            else {
                val v = verdicts.count { LabStatusMapper.fromVerification(it.status, it.grade) == LabStatus.VERIFIED }
                Text("$v/${verdicts.size} trường telemetry đã VERIFIED" + if (v == 0) " — chưa trường nào được xác minh." else ".", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { onOpenPage(ProPage.LAB) }) { Text("Mở Protocol Lab") }
        }

        HeroCard {
            Text("QUÃNG ĐƯỜNG HÔM NAY", style = MaterialTheme.typography.labelLarge)
            Text("%.1f km".format(todayKm), style = MaterialTheme.typography.displaySmall)
            SourceTag(if (settings.demo) DataSource.DEMO else DataSource.GPS_ESTIMATE)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(Icons.Filled.Info, "ODO", if (settings.odoKm > 0) "${settings.odoKm} km" else "Chưa nhập", Modifier.weight(1f), DataSource.USER_INPUT)
            StatTile(Icons.Filled.Place, "Tốc độ tối đa hôm nay", "%.0f km/h".format(todayTrips.maxOfOrNull { it.maxSpeedKmh } ?: 0.0), Modifier.weight(1f), DataSource.GPS_ESTIMATE)
        }

        Panel {
            Section("Dữ liệu từ xe")
            val rowsDef = listOf("Tốc độ xe" to VehicleParam.SPEED_KMH, "Xăng xe" to VehicleParam.FUEL_LEVEL, "Vòng tua (RPM)" to VehicleParam.RPM,
                "Nhiệt độ động cơ" to VehicleParam.COOLANT_TEMP_C, "Điện áp" to VehicleParam.BATTERY_V, "Mã lỗi" to VehicleParam.DTC, "ODO xe" to VehicleParam.ODOMETER_KM)
            rowsDef.forEach { (label, param) ->
                val v = if (link.protocolVerified) tele.fresh(param, now) else null
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label); Text(v?.let { formatLive(it) } ?: if (link.protocolVerified) "Đang chờ giải mã" else VehicleReadings.NO_DATA,
                        color = if (v != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SourceTag(if (anyFresh) DataSource.LIVE_VERIFIED else DataSource.UNAVAILABLE)
            if (!link.protocolVerified) Text("Chưa có giao thức xe được xác minh nên app không giải mã tốc độ, xăng, RPM, nhiệt độ, điện áp hay mã lỗi.", style = MaterialTheme.typography.bodySmall)
        }
        Panel {
            Section("Hành trình đang ghi")
            Text(when (phase) { TripPhase.IDLE -> "Chưa ghi"; TripPhase.RECORDING -> "Đang ghi · ${gps.label}"; TripPhase.PAUSED -> "Đang tạm dừng" })
            live?.let { Text("%.2f km · %s · TB %.0f km/h · tối đa %.0f km/h (GPS)".format(it.distanceM / 1000, fmtDur(it.durationMs), it.avgKmh, it.maxKmh), style = MaterialTheme.typography.bodySmall) }
            fix?.let { Text("GPS ±%.0f m".format(it.accuracyM), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TripControls(app)
        }
        Panel {
            Section("Bảo dưỡng")
            if (due.isEmpty()) Text("Không có hạng mục sắp/quá hạn" + if (maint.isEmpty()) " (chưa thêm hạng mục nào)." else ".", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { onOpenPage(ProPage.VEHICLE) }, Modifier.heightIn(min = 48.dp)) { Icon(Icons.Filled.Create, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Mở xe và bảo dưỡng") }
        }
    }
}
