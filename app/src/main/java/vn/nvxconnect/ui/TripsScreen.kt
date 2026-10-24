package vn.nvxconnect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.nvxconnect.domain.DataSource
import vn.nvxconnect.location.TripPhase
import vn.nvxconnect.navigation.LatLon

@Composable
fun TripsScreen(app: AppViewModel) {
    val replay by app.replay.collectAsState()
    val r = replay
    if (r != null) { ReplayScreen(app, r); return }

    val trips by app.trips.collectAsState()
    val settings by app.settings.collectAsState()
    val phase by app.engine.phase.collectAsState()
    val live by app.engine.live.collectAsState()
    val gps by app.engine.gps.collectAsState()
    val msg by app.message.collectAsState()
    val shown = trips.filter { it.isDemo == settings.demo }

    Page("Hành trình") {
        msg?.let { Notice(it, Tone.Neutral, "Đóng") { app.message.value = null } }
        Panel {
            Section("Ghi hành trình")
            Text(when (phase) { TripPhase.IDLE -> "Chưa ghi"; TripPhase.RECORDING -> "Đang ghi · ${gps.label}"; TripPhase.PAUSED -> "Đang tạm dừng" })
            live?.let {
                Text("%.2f km".format(it.distanceM / 1000), style = MaterialTheme.typography.headlineMedium)
                Text("${fmtDur(it.durationMs)} · TB %.0f km/h · tối đa %.0f km/h".format(it.avgKmh, it.maxKmh))
            }
            SourceTag(DataSource.GPS_ESTIMATE)
            Text("Tốc độ và quãng đường lấy từ GPS điện thoại, không phải đồng hồ hay ECU của xe. Dừng GPS lâu hơn 30 giây coi là đứt đoạn, không nối thẳng.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TripControls(app)
        }
        Section("Lịch sử")
        if (shown.isEmpty()) Notice(if (settings.demo) "Chưa có hành trình DEMO." else "Chưa có hành trình nào. Bấm \"Bắt đầu hành trình\" để ghi.")
        shown.forEach { t -> TripRow(t) { app.openTrip(t.id) } }
    }
}

@Composable
private fun ReplayScreen(app: AppViewModel, r: Replay) {
    var confirm by remember { mutableStateOf(false) }
    val pts = r.points.filter { it.accuracyM <= 50f }
    val avgAcc = if (pts.isEmpty()) 0.0 else pts.map { it.accuracyM.toDouble() }.average()
    Column(Modifier.fillMaxSize()) {
        OsmMap(MapContent(track = r.points, endpoints = r.points.takeIf { it.size >= 2 }?.let { listOf(LatLon(it.first().lat, it.first().lon), LatLon(it.last().lat, it.last().lon)) } ?: emptyList(), fitKey = r.trip.id.toInt() + 1),
            Modifier.fillMaxWidth().weight(1f))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(fmtTime(r.trip.startMs), style = MaterialTheme.typography.titleMedium)
            Text("%.2f km · %s · TB %.0f km/h · tối đa %.0f km/h".format(r.stats.distanceM / 1000, fmtDur(r.trip.endMs - r.trip.startMs), r.stats.avgKmh, r.stats.maxKmh))
            Text("${r.points.size} điểm GPS · độ chính xác trung bình ±%.0f m".format(avgAcc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SourceTag(sourceOf(r.trip.isDemo))
            if (r.points.size < 2) Text("Hành trình này không có điểm GPS lưu lại (dữ liệu DEMO hoặc đã bị xóa).", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { app.closeReplay() }, Modifier.weight(1f)) { Text("Quay lại") }
                OutlinedButton(onClick = { confirm = true }, Modifier.weight(1f)) { Text("Xóa") }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        confirmButton = { TextButton(onClick = { confirm = false; app.deleteTrip(r.trip.id) }) { Text("Xóa") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Hủy") } },
        title = { Text("Xóa hành trình?") }, text = { Text("Hành trình và toàn bộ điểm GPS sẽ bị xóa khỏi máy.") })
}
