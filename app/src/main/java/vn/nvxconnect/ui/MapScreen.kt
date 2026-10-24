package vn.nvxconnect.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import vn.nvxconnect.location.GpsStatus
import vn.nvxconnect.location.TripPhase
import vn.nvxconnect.navigation.*

/**
 * Bản đồ + tìm kiếm + dẫn đường trên ĐIỆN THOẠI. Khi đang dẫn đường và xe chạy (>~5 km/h theo GPS) giao diện chỉ còn
 * thông tin lớn và nút Tạm dừng/Kết thúc, không hiện ô tìm kiếm.
 */
@Composable
fun MapScreen(app: AppViewModel, nav: NavViewModel, onOpenTft: () -> Unit = {}) {
    val ctx = LocalContext.current
    val fix by nav.engine.lastFix.collectAsState()
    val track by nav.engine.track.collectAsState()
    val navSnap by nav.engine.nav.collectAsState()
    val gps by nav.engine.gps.collectAsState()
    val phase by nav.engine.phase.collectAsState()
    val preview by nav.preview.collectAsState()
    val results by nav.results.collectAsState()
    val busy by nav.busy.collectAsState()
    val message by nav.message.collectAsState()
    val online by nav.online.collectAsState()
    val voice by nav.voiceStatus.collectAsState()
    val sessionStatus by nav.session.status.collectAsState()
    val settings by app.settings.collectAsState()

    var permOk by remember { mutableStateOf(Perms.hasPreciseLocation(ctx)) }
    var denied by remember { mutableStateOf<String?>(null) }
    val ask = rememberLocationGate(onGranted = { permOk = true; denied = null }, onDenied = { denied = it })
    DisposableEffect(permOk) {
        if (permOk) nav.acquirePreview()
        onDispose { if (permOk) nav.releasePreview() }
    }

    var query by remember { mutableStateOf("") }
    var follow by remember { mutableStateOf(true) }
    var showSteps by remember { mutableStateOf(false) }
    val navigating = navSnap != null
    val driving = navigating && (fix?.speedMps ?: 0f) > 1.5f

    val shownRoute = navSnap?.route ?: preview?.route
    val content = MapContent(
        track = track, route = shownRoute?.points ?: emptyList(), me = fix,
        endpoints = shownRoute?.let { listOf(it.points.first(), it.points.last()) } ?: emptyList(),
        follow = follow && preview == null, fitKey = preview?.route?.hashCode() ?: 0)

    Box(Modifier.fillMaxSize()) {
        OsmMap(content, Modifier.fillMaxSize())

        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!permOk) Notice(denied ?: "Cần quyền vị trí chính xác để hiện vị trí của bạn trên bản đồ.", Tone.Warn, "Cấp quyền vị trí", ask)
            if (!online) Notice("Đang offline: bản đồ chỉ hiện các ô đã lưu; không tìm kiếm/định tuyến được.", Tone.Warn)
            if (permOk && gps == GpsStatus.OFF) Notice("GPS đang tắt. Hãy bật Vị trí của điện thoại.", Tone.Bad, "Mở cài đặt vị trí") { Perms.openLocationSettings(ctx) }
            else if (permOk && (gps == GpsStatus.SEARCHING || gps == GpsStatus.WEAK)) Notice(gps.label + (if (fix == null) ". Hãy ra nơi thoáng." else ""), Tone.Warn)
            if (!driving && !navigating) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true,
                                label = { Text("Địa điểm hoặc \"vĩ độ, kinh độ\"") },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { nav.search(query) }))
                            Button(onClick = { nav.search(query) }, enabled = !busy && query.isNotBlank()) { Text("Tìm") }
                        }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (results.isNotEmpty()) LazyColumn(Modifier.heightIn(max = 220.dp)) {
                            items(results) { p ->
                                Column(Modifier.fillMaxWidth().clickable { nav.choose(p) }.padding(vertical = 8.dp)) {
                                    Text(p.name, style = MaterialTheme.typography.titleSmall)
                                    Text(p.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                }
                                HorizontalDivider()
                            }
                        }
                        if (query.isNotBlank()) TextButton(onClick = {
                            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            catch (_: ActivityNotFoundException) { nav.message.value = "Không có ứng dụng bản đồ nào để mở." }
                        }) { Text("Mở trong ứng dụng bản đồ khác") }
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val snap = navSnap
            val pv = preview
            if (snap != null) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)) {
                    Column(Modifier.padding(14.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val p = snap.progress
                        val ns = p?.nextStep
                        when {
                            snap.phase == NavPhase.ARRIVED -> Text("Bạn đã đến nơi", style = MaterialTheme.typography.headlineSmall)
                            snap.phase == NavPhase.OFF_ROUTE -> Text("Đã lệch lộ trình", style = MaterialTheme.typography.headlineSmall, color = NvxColors.Warning)
                            p != null && ns != null -> {
                                Text(TurnText.instruction(ns), style = MaterialTheme.typography.headlineSmall)
                                Text("sau " + Fmt.distance(p.distanceToNextM), style = MaterialTheme.typography.titleMedium)
                            }
                            else -> Text("Đang chờ tín hiệu GPS để bắt đầu dẫn đường…", style = MaterialTheme.typography.titleMedium)
                        }
                        sessionStatus?.let { Text(it, color = NvxColors.Warning, style = MaterialTheme.typography.bodySmall) }
                        if (p != null && snap.phase != NavPhase.ARRIVED) {
                            Text(Fmt.duration(p.remainingS), style = MaterialTheme.typography.headlineSmall)
                            Text(Fmt.distance(p.remainingM) + " · đến khoảng " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(System.currentTimeMillis() + (p.remainingS * 1000).toLong())), style = MaterialTheme.typography.bodyMedium)
                            Text("Thời gian là ước tính theo dữ liệu định tuyến, chưa tính ùn tắc, đèn đỏ.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (phase == TripPhase.PAUSED) Button(onClick = { app.resumeTrip() }, Modifier.weight(1f).height(56.dp)) { Text("Tiếp tục") }
                            else OutlinedButton(onClick = { app.pauseTrip() }, Modifier.weight(1f).height(56.dp)) { Text("Tạm dừng") }
                            Button(onClick = { app.stopTrip() }, Modifier.weight(1f).height(56.dp)) { Text("Kết thúc") }
                        }
                        if (!driving) {
                            OutlinedButton(onClick = { follow = !follow }) { Text(if (follow) "Đang bám theo vị trí" else "Bấm để bám theo vị trí") }
                            if (voice != vn.nvxconnect.navigation.VoiceStatus.READY) Text(voice.label, style = MaterialTheme.typography.labelSmall, color = NvxColors.Warning)
                            else if (!settings.voiceGuidance) Text("Giọng nói đang tắt trong Cài đặt.", style = MaterialTheme.typography.labelSmall)
                            Text("Hiển thị trên màn hình xe", style = MaterialTheme.typography.labelLarge)
                            Segmented(listOf("Chỉ bản đồ", "Đầy đủ thông tin"), if (settings.tftMapOnly) 0 else 1) { app.setTftMapOnly(it == 0) }
                            TextButton(onClick = onOpenTft) { Text("Cài đặt hiển thị TFT") }
                            Text("Chỉ dẫn hiển thị trên điện thoại. " + nav.tft.limitation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else if (pv != null) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)) {
                    Column(Modifier.padding(14.dp).heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(pv.destinationName, style = MaterialTheme.typography.titleMedium)
                        Text(Fmt.distance(pv.route.distanceM) + " · khoảng " + Fmt.duration(pv.route.durationS) + " (ước tính, chưa tính ùn tắc)")
                        Text("Tuyến tính theo hồ sơ ô tô của ${pv.route.engine}; có thể đi qua đường mô tô bị cấm. Luôn tuân theo biển báo.", style = MaterialTheme.typography.labelSmall, color = NvxColors.Warning)
                        TextButton(onClick = { showSteps = !showSteps }) { Text(if (showSteps) "Ẩn các bước" else "Xem ${pv.route.steps.size} bước") }
                        if (showSteps) pv.route.steps.forEach { Text("• " + TurnText.instruction(it) + if (it.distanceM > 0) " (${Fmt.distance(it.distanceM)})" else "", style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { nav.clearPreview() }, Modifier.weight(1f).height(52.dp)) { Text("Hủy") }
                            Button(onClick = { follow = true; nav.startNavigation() }, Modifier.weight(1f).height(52.dp)) { Text("Bắt đầu") }
                        }
                    }
                }
            } else if (phase != TripPhase.IDLE) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)) {
                    Column(Modifier.padding(12.dp)) { Text(if (phase == TripPhase.PAUSED) "Đang tạm dừng ghi hành trình" else "Đang ghi hành trình"); TripControls(app) }
                }
            }
            Text("© OpenStreetMap contributors · định tuyến OSRM · tìm kiếm Nominatim", style = MaterialTheme.typography.labelSmall,
                color = Color.White, modifier = Modifier.align(Alignment.End))
        }
    }
}
