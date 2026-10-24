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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.nvxconnect.domain.*
import vn.nvxconnect.location.GpsStatus
import vn.nvxconnect.location.TripPhase
import vn.nvxconnect.protocol.LinkLevel
import vn.nvxconnect.protocol.ConnState
import vn.nvxconnect.protocol.VehicleParam
import vn.nvxconnect.protocol.formatLive
import androidx.compose.ui.platform.LocalContext
import vn.nvxconnect.protocol.VehicleLinkStatus
import vn.nvxconnect.auto.AuthLevel
import java.util.Calendar

@Composable
fun HomeScreen(app: AppViewModel, ble: BleViewModel, onOpenTab: (Int) -> Unit, onSettings: () -> Unit, onOpenAuto: () -> Unit = {}, onOpenAnalysis: () -> Unit = {}) {
    val settings by app.settings.collectAsState()
    val trips by app.trips.collectAsState()
    val maint by app.maintenance.collectAsState()
    val link by ble.linkStatus.collectAsState()
    val gps by app.engine.gps.collectAsState()
    val fix by app.engine.lastFix.collectAsState()
    val phase by app.engine.phase.collectAsState()
    val live by app.engine.live.collectAsState()
    val msg by app.message.collectAsState()
    val tele by (LocalContext.current.applicationContext as vn.nvxconnect.NvxApp).telemetry.state.collectAsState()
    val now = rememberNow()
    val gatt by ble.connState.collectAsState()
    val auth by ble.auth.collectAsState()
    val rssi by ble.rssi.collectAsState()

    val startOfDay = remember { Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis }
    val todayTrips = trips.filter { it.startMs >= startOfDay && it.isDemo == settings.demo }
    val todayKm = (todayTrips.sumOf { it.distanceM } + (live?.distanceM ?: 0.0)) / 1000.0
    val due = maint.filter { it.isDemo == settings.demo }.map { it to Maintenance.due(it, settings.odoKm, System.currentTimeMillis()) }
        .filter { it.second.status == MaintStatus.SOON || it.second.status == MaintStatus.OVERDUE }.take(3)

    Page(null) {
        AppTopBar(title = null, actions = { IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Cài đặt") } })
        VehicleSummaryCard(settings.vehicle.model, settings.vehicle.variant, "Hồ sơ do bạn nhập") { SourceTag(DataSource.USER_INPUT) }
        if (settings.demo) Notice("CHẾ ĐỘ DEMO đang bật: dữ liệu minh họa, không phải dữ liệu thật.", Tone.Warn)
        msg?.let { Notice(it, Tone.Neutral, "Đóng") { app.message.value = null } }

        val tone = when (link.level) { LinkLevel.VERIFIED -> Tone.Good; LinkLevel.RAW_DATA_ONLY -> Tone.Warn; LinkLevel.ERROR -> Tone.Bad; else -> Tone.Neutral }
        ConnectionStatusCard(link.headline, link.detail, tone) {
            BikeHero()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val fuel = if (link.protocolVerified) tele.fresh(VehicleParam.FUEL_LEVEL, now) else null
                CountTile(fuel?.let { formatLive(it) } ?: "Chưa khả dụng", if (fuel != null) "Xăng xe · ${DataSource.LIVE_VERIFIED.label}" else "Xăng xe (chưa có nguồn từ xe)",
                    Modifier.weight(1f), valueSize = if (fuel != null) 22.sp else 15.sp)
                CountTile(rssi?.let { "$it dBm" } ?: "—", "Tín hiệu BLE", Modifier.weight(1f))
            }
            if (link.level == LinkLevel.RAW_DATA_ONLY) Text("Kết nối GATT tới một thiết bị không chứng minh đó là bộ điều khiển của xe.", style = MaterialTheme.typography.bodySmall)
        }
        Panel {
            Section("Trạng thái kết nối")
            StatusRow("Kết nối GATT", if (gatt is ConnState.Ready) "Đã kết nối" else gatt.code,
                if (gatt is ConnState.Ready) Tone.Good else if (gatt is ConnState.Error) Tone.Bad else if (gatt.isActive) Tone.Warn else Tone.Neutral)
            StatusRow("Xác thực thiết bị", auth.headline,
                when (auth.level) { AuthLevel.BONDED -> Tone.Good; AuthLevel.REQUIRED, AuthLevel.REJECTED -> Tone.Bad; AuthLevel.PAIRING -> Tone.Warn; else -> Tone.Neutral })
            StatusRow("Giao thức Yamaha", if (link.protocolVerified) "Đã xác minh" else VehicleLinkStatus.NOT_VERIFIED, if (link.protocolVerified) Tone.Good else Tone.Warn)
        }
        PrimaryActionButton("Tự tìm và kết nối xe", onOpenAuto)
        OutlinedButton(onClick = onOpenAnalysis, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Xem chi tiết giao thức") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onOpenTab(1) }, Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Bản đồ", maxLines = 1) }
            OutlinedButton(onClick = { onOpenTab(2) }, Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Hành trình", maxLines = 1) }
            OutlinedButton(onClick = { onOpenTab(3) }, Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Xe", maxLines = 1) }
        }
        TextButton(onClick = { onOpenTab(4) }) { Text("Mở trang kết nối thủ công") }

        Panel {
            Section("GPS điện thoại")
            StatusChip(gps.label, when (gps) { GpsStatus.GOOD -> Tone.Good; GpsStatus.WEAK, GpsStatus.SEARCHING -> Tone.Warn; GpsStatus.OFF -> Tone.Bad; GpsStatus.INACTIVE -> Tone.Neutral })
            Text(fix?.let { "Độ chính xác ±%.0f m · tốc độ GPS %.0f km/h".format(it.accuracyM, it.speedMps * 3.6) } ?: "Chưa có vị trí. Mở tab Bản đồ hoặc bắt đầu hành trình để nghe GPS.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (gps == GpsStatus.OFF) Text("Hãy bật Vị trí (GPS) của điện thoại.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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

        // --- Dữ liệu xe: không bao giờ hiển thị số giả ---
        Panel {
            Section("Dữ liệu từ xe")
            val verified = link.protocolVerified
            val rowsDef = listOf("Tốc độ xe" to VehicleParam.SPEED_KMH, "Xăng xe" to VehicleParam.FUEL_LEVEL, "Vòng tua (RPM)" to VehicleParam.RPM,
                "Nhiệt độ động cơ" to VehicleParam.COOLANT_TEMP_C, "Điện áp" to VehicleParam.BATTERY_V, "Mã lỗi" to VehicleParam.DTC, "ODO xe" to VehicleParam.ODOMETER_KM)
            var anyLive = false
            rowsDef.forEach { (label, param) ->
                val v = if (verified) tele.fresh(param, now) else null
                if (v != null) anyLive = true
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label); Text(v?.let { formatLive(it) } ?: if (verified) "Đang chờ giải mã" else VehicleReadings.NO_DATA,
                        color = if (v != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SourceTag(if (anyLive) DataSource.LIVE_VERIFIED else DataSource.UNAVAILABLE)
            if (!verified) Text("Chưa có giao thức xe được xác minh nên app không giải mã tốc độ, xăng, RPM, nhiệt độ, điện áp hay mã lỗi.", style = MaterialTheme.typography.bodySmall)
        }

        Panel {
            Section("Hành trình đang ghi")
            Text(when (phase) { TripPhase.IDLE -> "Chưa ghi"; TripPhase.RECORDING -> "Đang ghi"; TripPhase.PAUSED -> "Đang tạm dừng" })
            live?.let { Text("%.2f km · %s · TB %.0f km/h · tối đa %.0f km/h (GPS)".format(it.distanceM / 1000, fmtDur(it.durationMs), it.avgKmh, it.maxKmh), style = MaterialTheme.typography.bodySmall) }
            TripControls(app)
        }

        Panel {
            Section("Bảo dưỡng cần chú ý")
            if (due.isEmpty()) Text("Không có hạng mục sắp/quá hạn" + if (maint.isEmpty()) " (chưa thêm hạng mục nào)." else ".", color = MaterialTheme.colorScheme.onSurfaceVariant)
            due.forEach { (item, d) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item.name); StatusChip(if (d.status == MaintStatus.OVERDUE) "QUÁ HẠN" else "SẮP ĐẾN HẠN", if (d.status == MaintStatus.OVERDUE) Tone.Bad else Tone.Warn)
                }
            }
            OutlinedButton(onClick = { onOpenTab(3) }) { Icon(Icons.Filled.Create, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Mở bảo dưỡng") }
        }
    }
}
