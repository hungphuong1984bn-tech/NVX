package vn.nvxconnect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.nvxconnect.NvxApp
import vn.nvxconnect.location.GpsStatus
import vn.nvxconnect.location.TripPhase
import vn.nvxconnect.protocol.LinkLevel
import vn.nvxconnect.pro.CockpitMetric
import vn.nvxconnect.pro.CockpitModel
import vn.nvxconnect.pro.Gauge
import vn.nvxconnect.pro.GaugeState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fmtGauge(g: Gauge): String {
    val v = g.value ?: return "—"
    return if (g.metric == CockpitMetric.VOLTAGE || v % 1.0 != 0.0 && g.metric != CockpitMetric.GPS_SPEED && g.metric != CockpitMetric.RPM) String.format(Locale.US, "%.1f", v) else String.format(Locale.US, "%.0f", v)
}

private fun stateLabel(g: Gauge) = when (g.state) {
    GaugeState.LIVE -> "TRỰC TIẾP"; GaugeState.STALE -> "DỮ LIỆU CŨ"; GaugeState.WAITING -> "ĐANG CHỜ"; GaugeState.NO_SOURCE -> "CHƯA CÓ NGUỒN"
}

/**
 * Cockpit: tốc độ GPS lớn + các ô số liệu từ xe CHỈ khi có giá trị đã xác minh và còn mới.
 *  - Tốc độ lớn là GPS điện thoại, luôn ghi chú "không phải tốc độ ECU"; không có tốc độ xe vì chưa có nguồn xác minh.
 *  - Ô nào dữ liệu cũ thì KHÔNG hiện số (không giữ số cũ giả làm trực tiếp), chỉ hiện tuổi dữ liệu.
 *  - Toàn màn hình: ẩn thanh hệ thống, giữ sáng, nút lớn; không cho chỉnh bố cục khi đang chạy.
 */
@Composable
fun CockpitScreen(app: AppViewModel, ble: BleViewModel, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit, onOpenConnect: () -> Unit, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val nvx = ctx.applicationContext as NvxApp
    val pro by nvx.proPrefs.state.collectAsState()
    val tele by nvx.telemetry.state.collectAsState()
    val link by ble.linkStatus.collectAsState()
    val fix by app.engine.lastFix.collectAsState()
    val gps by app.engine.gps.collectAsState()
    val live by app.engine.live.collectAsState()
    val phase by app.engine.phase.collectAsState()
    val layout = rememberLayoutInfo()
    val now = rememberNowEvery(pro.uiRefreshMs.toLong())

    var permOk by remember { mutableStateOf(Perms.hasPreciseLocation(ctx)) }
    var denied by remember { mutableStateOf<String?>(null) }
    val ask = rememberLocationGate(onGranted = { permOk = true; denied = null }, onDenied = { denied = it })
    DisposableEffect(permOk) {
        if (permOk) app.engine.acquirePreview()
        onDispose { if (permOk) app.engine.releasePreview() }
    }
    var lockLandscape by rememberSaveable { mutableStateOf(false) }
    var showCustomize by remember { mutableStateOf(false) }
    ImmersiveEffect(fullscreen, lockLandscape)

    val gauges = CockpitModel.gauges(pro.cockpitHidden, tele, link.protocolVerified, fix?.speedMps, fix?.accuracyM, fix?.timeMs, now)
    val speed = gauges.first { it.metric == CockpitMetric.GPS_SPEED }
    val others = gauges.filter { it.metric != CockpitMetric.GPS_SPEED }
    val moving = (fix?.speedMps ?: 0f) > 1.4f   // ≈ 5 km/h theo GPS
    val hc = pro.cockpitHighContrast
    val bg = if (hc) (if (NvxColors.dark) Color.Black else Color.White) else NvxColors.Background
    val fg = if (hc) (if (NvxColors.dark) Color.White else Color.Black) else MaterialTheme.colorScheme.onSurface
    val linkTone = when (link.level) { LinkLevel.VERIFIED -> Tone.Good; LinkLevel.RAW_DATA_ONLY -> Tone.Warn; LinkLevel.ERROR -> Tone.Bad; else -> Tone.Neutral }
    val updated = listOfNotNull(fix?.timeMs, tele.values.values.maxOfOrNull { it.timeMs }).maxOrNull()
    val updatedText = updated?.let { SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it)) + " (${((now - it).coerceAtLeast(0)) / 1000} giây trước)" } ?: "chưa có"

    @Composable fun SpeedBlock(modifier: Modifier) = Column(modifier.semantics(mergeDescendants = true) {
        contentDescription = "Tốc độ GPS ${if (speed.showsValue) fmtGauge(speed) + " ki-lô-mét một giờ" else "chưa có"}. ${speed.note}" }, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (speed.showsValue) fmtGauge(speed) else "—", fontSize = if (fullscreen && layout.landscape) 150.sp else 96.sp, fontWeight = FontWeight.Bold, maxLines = 1, color = fg)
        Text("km/h · GPS", style = MaterialTheme.typography.titleMedium, color = NvxColors.Primary)
        Text(speed.note, style = MaterialTheme.typography.bodySmall, color = if (speed.state == GaugeState.STALE) NvxColors.Warning else MaterialTheme.colorScheme.onSurfaceVariant)
    }

    @Composable fun GaugeGrid(columns: Int) {
        if (others.isEmpty()) { Text("Bạn đã ẩn mọi ô số liệu của xe.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); return }
        others.chunked(columns).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowItems.forEach { g ->
                    Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.medium, color = if (hc) bg else NvxColors.Surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (hc) fg else NvxColors.Outline)) {
                        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            RingGauge(g.fraction, if (g.showsValue) fmtGauge(g) else "—", if (g.showsValue) g.unit else "", g.metric.title, "${stateLabel(g)} · ${g.note}",
                                if (g.state == GaugeState.LIVE) NvxColors.Success else NvxColors.Outline, Modifier.fillMaxWidth(0.8f), valueSize = 26.sp)
                        }
                    }
                }
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    if (fullscreen) {
        BackgroundBox(bg) {
            if (layout.landscape) Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    FullscreenStatus(link.headline, linkTone, CockpitModel.quality(gauges), updatedText)
                    SpeedBlock(Modifier.fillMaxWidth())
                    FullscreenControls(hc, lockLandscape, { onFullscreen(false) }, { nvx.proPrefs.update { it.copy(cockpitHighContrast = !it.cockpitHighContrast) } }, { lockLandscape = !lockLandscape })
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { GaugeGrid(2) }
            } else Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FullscreenStatus(link.headline, linkTone, CockpitModel.quality(gauges), updatedText)
                SpeedBlock(Modifier.fillMaxWidth())
                GaugeGrid(2)
                FullscreenControls(hc, lockLandscape, { onFullscreen(false) }, { nvx.proPrefs.update { it.copy(cockpitHighContrast = !it.cockpitHighContrast) } }, { lockLandscape = !lockLandscape })
            }
        }
        return
    }

    Page(null) {
        AppTopBar("Cockpit", "Đồng hồ xe · chỉ hiện số liệu có nguồn hợp lệ", actions = {
            TextButton(onClick = { onFullscreen(true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Toàn màn hình") }
        })
        if (!permOk) StateBanner(UiState.PermissionDenied, "Cần quyền vị trí chính xác để hiện tốc độ GPS", denied, "Cấp quyền vị trí", ask)
        else if (gps == GpsStatus.OFF) StateBanner(UiState.Error, "GPS đang tắt", "Bật Vị trí của điện thoại để có tốc độ.", "Mở cài đặt vị trí") { Perms.openLocationSettings(ctx) }
        else if (fix == null) StateBanner(UiState.Loading, "Đang tìm tín hiệu GPS…", "Ra nơi thoáng để có vị trí.")
        if (link.level == LinkLevel.NOT_CONNECTED) StateBanner(UiState.Disconnected, "Chưa kết nối xe", "Các ô số liệu xe sẽ hiện khi có giao thức xe đã xác minh và dữ liệu mới. Tốc độ GPS vẫn dùng được.", "Kết nối xe", onOpenConnect)
        else if (!link.protocolVerified) StateBanner(UiState.Empty, link.headline, link.detail ?: "Chưa có giao thức xe được xác minh nên không có số liệu xe để hiển thị.")
        if (others.any { it.state == GaugeState.STALE }) StateBanner(UiState.Stale, "Có số liệu xe đã cũ", "Ô bị cũ không hiện số để tránh hiểu nhầm là dữ liệu trực tiếp.")

        HeroCard { SpeedBlock(Modifier.fillMaxWidth()) }
        Panel {
            StatusRow("Kết nối xe", link.headline, linkTone)
            StatusRow("Chất lượng dữ liệu", CockpitModel.quality(gauges), if (others.any { it.state == GaugeState.LIVE }) Tone.Good else Tone.Neutral)
            StatusRow("Cập nhật gần nhất", updatedText, Tone.Neutral)
            StatusRow("GPS", gps.label, when (gps) { GpsStatus.GOOD -> Tone.Good; GpsStatus.WEAK, GpsStatus.SEARCHING -> Tone.Warn; GpsStatus.OFF -> Tone.Bad; GpsStatus.INACTIVE -> Tone.Neutral })
        }
        GaugeGrid(layout.columns.coerceAtLeast(2).coerceAtMost(3))
        Panel {
            Section("Hành trình hiện tại")
            val l = live
            if (l == null || phase == TripPhase.IDLE) Text("Chưa ghi hành trình. Quãng đường và thời gian chạy lấy từ GPS điện thoại.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                StatusRow("Quãng đường (GPS)", "%.2f km".format(l.distanceM / 1000), Tone.Neutral)
                StatusRow("Thời gian chạy", fmtDur(l.durationMs), Tone.Neutral)
                StatusRow("Trung bình / tối đa (GPS)", "%.0f / %.0f km/h".format(l.avgKmh, l.maxKmh), Tone.Neutral)
            }
            TripControls(app)
        }
        Panel {
            Section("Bố cục")
            Text("Ẩn hoặc hiện các ô số liệu của xe. Tốc độ GPS luôn hiện.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (moving) Text("Đang di chuyển: tạm khóa chỉnh bố cục để an toàn. Hãy dừng xe hẳn rồi chỉnh.", style = MaterialTheme.typography.bodySmall, color = NvxColors.Warning)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showCustomize = true }, enabled = !moving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Tùy chỉnh ô số liệu") }
                OutlinedButton(onClick = { nvx.proPrefs.update { it.copy(cockpitHighContrast = !it.cockpitHighContrast) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (hc) "Tắt tương phản cao" else "Tương phản cao") }
            }
            TextButton(onClick = onOpenSettings) { Text("Chu kỳ cập nhật và thêm tùy chọn trong Cài đặt") }
        }
        Text("Công suất và pin: NVX 155 chạy xăng, app không có nguồn dữ liệu xác minh cho các số này nên không hiển thị.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (showCustomize) AlertDialog(onDismissRequest = { showCustomize = false },
        confirmButton = { TextButton(onClick = { showCustomize = false }) { Text("Xong") } },
        title = { Text("Ô số liệu hiển thị") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CockpitMetric.values().filter { it != CockpitMetric.GPS_SPEED }.forEach { m ->
                    LabeledSwitch(m.title, m.key !in pro.cockpitHidden) { on -> nvx.proPrefs.update { s -> s.copy(cockpitHidden = if (on) s.cockpitHidden - m.key else s.cockpitHidden + m.key) } }
                }
                Text("Ô vẫn chỉ hiện số khi có dữ liệu xe đã xác minh và còn mới.", style = MaterialTheme.typography.labelSmall)
            }
        })
}

@Composable private fun BackgroundBox(bg: Color, content: @Composable BoxScope.() -> Unit) =
    Box(Modifier.fillMaxSize().background(bg).safeDrawingPadding(), content = content)

@Composable private fun FullscreenStatus(headline: String, tone: Tone, quality: String, updated: String) = Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { StatusDot(toneColor(tone), 12); Text(headline, style = MaterialTheme.typography.titleMedium, maxLines = 2) }
    Text("$quality · cập nhật $updated", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun FullscreenControls(hc: Boolean, landscape: Boolean, onExit: () -> Unit, onContrast: () -> Unit, onLandscape: () -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BigButton("Thoát", onExit, Modifier.weight(1f))
        BigButton(if (hc) "Tương phản: bật" else "Tương phản", onContrast, Modifier.weight(1f), primary = false)
        BigButton(if (landscape) "Ngang: khóa" else "Khóa ngang", onLandscape, Modifier.weight(1f), primary = false)
    }
