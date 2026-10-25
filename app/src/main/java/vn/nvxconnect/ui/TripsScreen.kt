package vn.nvxconnect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.nvxconnect.NvxApp
import vn.nvxconnect.data.Trip
import vn.nvxconnect.domain.DataSource
import vn.nvxconnect.location.TripPhase
import vn.nvxconnect.navigation.LatLon
import vn.nvxconnect.pro.RideRange
import vn.nvxconnect.pro.RideSort
import vn.nvxconnect.pro.RideSummary
import vn.nvxconnect.pro.RideTools

/** Ride Center: ghi hành trình, danh sách có tìm kiếm/lọc/sắp xếp, thống kê, chi phí theo số bạn nhập, xóa có xác nhận. Số liệu là GPS điện thoại. */
@Composable
fun TripsScreen(app: AppViewModel, onOpenMap: () -> Unit = {}) {
    val replay by app.replay.collectAsState()
    val r = replay
    if (r != null) { ReplayScreen(app, r); return }

    val ctx = LocalContext.current
    val nvx = ctx.applicationContext as NvxApp
    val pro by nvx.proPrefs.state.collectAsState()
    val trips by app.trips.collectAsState()
    val settings by app.settings.collectAsState()
    val phase by app.engine.phase.collectAsState()
    val live by app.engine.live.collectAsState()
    val gps by app.engine.gps.collectAsState()
    val msg by app.message.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var rangeIdx by rememberSaveable { mutableIntStateOf(0) }
    var sortIdx by rememberSaveable { mutableIntStateOf(0) }
    var confirmAll by remember { mutableStateOf(false) }
    val now = rememberNow()

    val all = remember(trips) { trips.map { RideSummary(it.id, it.startMs, it.endMs, it.distanceM, it.maxSpeedKmh, it.isDemo) } }
    val shown = remember(all, query, rangeIdx, sortIdx, settings.demo, now / 60_000) {
        RideTools.filter(all, query, RideRange.values()[rangeIdx], RideSort.values()[sortIdx], System.currentTimeMillis(), settings.demo)
    }
    val totalKm = shown.sumOf { it.km }; val totalMs = shown.sumOf { it.durationMs }
    val cost = RideTools.cost(totalKm, pro.consumptionL100, pro.fuelPriceVnd)
    val byId = remember(trips) { trips.associateBy { it.id } }

    Page("Hành trình", "Ride Center · số liệu GPS điện thoại") {
        msg?.let { Notice(it, Tone.Neutral, "Đóng") { app.message.value = null } }
        Panel {
            Section("Ghi hành trình")
            Text(when (phase) { TripPhase.IDLE -> "Chưa ghi"; TripPhase.RECORDING -> "Đang ghi · ${gps.label}"; TripPhase.PAUSED -> "Đang tạm dừng" })
            live?.let {
                Text("%.2f km".format(it.distanceM / 1000), style = MaterialTheme.typography.headlineMedium)
                Text("${fmtDur(it.durationMs)} · TB %.0f km/h · tối đa %.0f km/h".format(it.avgKmh, it.maxKmh))
            }
            SourceTag(DataSource.GPS_ESTIMATE)
            Text("Tốc độ và quãng đường lấy từ GPS điện thoại, không phải đồng hồ hay ECU của xe. Dừng GPS lâu hơn 30 giây coi là đứt đoạn, không nối thẳng.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TripControls(app)
            OutlinedButton(onClick = onOpenMap, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Bản đồ và dẫn đường") }
        }

        Section("Thống kê (theo bộ lọc)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountTile("${shown.size}", "chuyến", Modifier.weight(1f), valueSize = 22.sp)
            CountTile("%.1f".format(totalKm), "km", Modifier.weight(1f), valueSize = 22.sp)
            CountTile(fmtDur(totalMs), "thời gian", Modifier.weight(1f), valueSize = 18.sp)
        }
        Panel {
            Section("Chi phí và năng lượng (ước tính)")
            if (cost == null) {
                Text("Chưa cấu hình giá nhiên liệu và mức tiêu thụ nên app không ước tính chi phí.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Nhập ở Công cụ → Cài đặt → Chi phí hành trình.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("≈ %.2f lít · ≈ %s".format(cost.liters, RideTools.vnd(cost.vnd)), style = MaterialTheme.typography.titleMedium)
                Text("Nguồn: ${cost.source}", style = MaterialTheme.typography.labelSmall)
                Text("Độ tin cậy: ${cost.confidence}", style = MaterialTheme.typography.labelSmall, color = NvxColors.Warning)
            }
        }

        Section("Lịch sử")
        OutlinedTextField(query, { query = it }, label = { Text("Tìm: ngày (vd 12/10) hoặc \">5\" = từ 5 km") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ChipRow(RideRange.values().map { it.title }, rangeIdx) { rangeIdx = it }
        ChipRow(RideSort.values().map { it.title }, sortIdx) { sortIdx = it }
        if (shown.isEmpty()) StateBanner(UiState.Empty,
            if (settings.demo) "Chưa có hành trình DEMO" else if (all.any { !it.isDemo }) "Không hành trình nào khớp bộ lọc" else "Chưa có hành trình nào",
            "Bấm \"Bắt đầu hành trình\" để ghi.")
        shown.forEach { s -> byId[s.id]?.let { t -> TripRow(t) { app.openTrip(t.id) } } }
        if (shown.isNotEmpty()) OutlinedButton(onClick = { confirmAll = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Xóa ${shown.size} chuyến đang hiển thị") }
    }
    if (confirmAll) ConfirmDialog("Xóa ${shown.size} hành trình?", "Các hành trình đang hiển thị theo bộ lọc và toàn bộ điểm GPS sẽ bị xóa khỏi máy. Không thể khôi phục.", "Xóa",
        { confirmAll = false; app.deleteTrips(shown.map { it.id }) }, { confirmAll = false })
}


@Composable
private fun ReplayScreen(app: AppViewModel, r: Replay) {
    val nvx = LocalContext.current.applicationContext as NvxApp
    val pro by nvx.proPrefs.state.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    val pts = r.points.filter { it.accuracyM <= 50f }
    val avgAcc = if (pts.isEmpty()) 0.0 else pts.map { it.accuracyM.toDouble() }.average()
    val stopMs = remember(r) { RideTools.stopTimeMs(r.points) }
    val totalMs = (r.trip.endMs - r.trip.startMs).coerceAtLeast(0)
    val cost = RideTools.cost(r.stats.distanceM / 1000, pro.consumptionL100, pro.fuelPriceVnd)
    Column(Modifier.fillMaxSize()) {
        OsmMap(MapContent(track = r.points, endpoints = r.points.takeIf { it.size >= 2 }?.let { listOf(LatLon(it.first().lat, it.first().lon), LatLon(it.last().lat, it.last().lon)) } ?: emptyList(), fitKey = r.trip.id.toInt() + 1),
            Modifier.fillMaxWidth().weight(1f))
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(fmtTime(r.trip.startMs), style = MaterialTheme.typography.titleMedium)
            Text("%.2f km · %s · TB %.0f km/h · tối đa %.0f km/h".format(r.stats.distanceM / 1000, fmtDur(totalMs), r.stats.avgKmh, r.stats.maxKmh))
            Text("Thời gian dừng ${fmtDur(stopMs)} · di chuyển ${fmtDur((totalMs - stopMs).coerceAtLeast(0))} (từ điểm GPS; khoảng mất GPS không tính là dừng)", style = MaterialTheme.typography.bodySmall)
            Text("${r.points.size} điểm GPS · độ chính xác trung bình ±%.0f m".format(avgAcc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (cost != null) {
                Text("Ước tính ≈ %.2f lít · ≈ %s".format(cost.liters, RideTools.vnd(cost.vnd)), style = MaterialTheme.typography.bodyMedium)
                Text("Nguồn: ${cost.source}. Độ tin cậy: ${cost.confidence}.", style = MaterialTheme.typography.labelSmall, color = NvxColors.Warning)
            } else Text("Chưa cấu hình giá/tiêu thụ trong Cài đặt nên không ước tính chi phí.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SourceTag(sourceOf(r.trip.isDemo))
            if (r.points.size < 2) Text("Hành trình này không có điểm GPS lưu lại (dữ liệu DEMO hoặc đã bị xóa).", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { app.closeReplay() }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Quay lại") }
                OutlinedButton(onClick = { confirm = true }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Xóa") }
            }
        }
    }
    if (confirm) ConfirmDialog("Xóa hành trình?", "Hành trình và toàn bộ điểm GPS sẽ bị xóa khỏi máy.", "Xóa", { confirm = false; app.deleteTrip(r.trip.id) }, { confirm = false })
}
