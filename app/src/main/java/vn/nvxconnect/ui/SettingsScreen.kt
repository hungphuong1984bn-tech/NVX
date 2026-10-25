package vn.nvxconnect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import vn.nvxconnect.AppInfo
import vn.nvxconnect.navigation.VoiceStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(app: AppViewModel, ble: BleViewModel, nav: NavViewModel, onBack: () -> Unit, onOpenTft: () -> Unit = {}, onOpenHelp: (String?) -> Unit = {}, onOpenLab: () -> Unit = {}) {
    val nvx = LocalContext.current.applicationContext as vn.nvxconnect.NvxApp
    val pro by nvx.proPrefs.state.collectAsState()
    val recording by ble.recording.collectAsState()
    var fuelPrice by remember(pro.fuelPriceVnd) { mutableStateOf(if (pro.fuelPriceVnd > 0) pro.fuelPriceVnd.toString() else "") }
    var consumption by remember(pro.consumptionL100) { mutableStateOf(if (pro.consumptionL100 > 0) pro.consumptionL100.toString() else "") }
    var costSaved by remember { mutableStateOf(false) }
    var confirmClearLog by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<vn.nvxconnect.bluetooth.SavedDevice?>(null) }
    val ctx = LocalContext.current
    val s by app.settings.collectAsState()
    val voice by nav.voiceStatus.collectAsState()
    val saved by ble.saved.collectAsState()
    var limit by remember(s.speedLimitKmh) { mutableStateOf(if (s.speedLimitKmh > 0) s.speedLimitKmh.toString() else "") }
    var routeUrl by remember(s.routingBaseUrl) { mutableStateOf(s.routingBaseUrl) }
    var geoUrl by remember(s.geocodeBaseUrl) { mutableStateOf(s.geocodeBaseUrl) }
    var urlErr by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    Page(null) {
        AppTopBar("Cài đặt", onBack = onBack)
        VehicleSummaryCard(s.vehicle.model, s.vehicle.variant, if (saved.isEmpty()) "Chưa lưu xe nào" else "${saved.size} thiết bị đã lưu")
        Panel {
            Section("Giao diện")
            Text("Chủ đề", style = MaterialTheme.typography.titleSmall)
            ChipRow(vn.nvxconnect.pro.ThemeMode.values().map { it.title }, vn.nvxconnect.pro.ThemeMode.values().indexOf(pro.theme)) { i -> nvx.proPrefs.update { it.copy(theme = vn.nvxconnect.pro.ThemeMode.values()[i]) } }
            Text("Tối là mặc định. \"Theo hệ thống\" đổi theo chế độ tối/sáng của điện thoại.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Panel {
            Section("Cockpit và biểu đồ")
            Text("Chu kỳ cập nhật giao diện", style = MaterialTheme.typography.titleSmall)
            ChipRow(vn.nvxconnect.pro.ProSettings.REFRESH_CHOICES.map { if (it >= 1000) "${it / 1000} giây" else "$it ms" }, vn.nvxconnect.pro.ProSettings.REFRESH_CHOICES.indexOf(pro.uiRefreshMs)) { i -> nvx.proPrefs.update { it.copy(uiRefreshMs = vn.nvxconnect.pro.ProSettings.REFRESH_CHOICES[i]) } }
            Text("Chỉ ảnh hưởng tần suất VẼ LẠI. Gói dữ liệu vẫn được ghi theo tần suất xe gửi. Dữ liệu xe cập nhật theo chu kỳ của xe (thường vài lần mỗi giây); 500 ms là đủ cho dữ liệu này, chu kỳ ngắn hơn tốn pin hơn.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LabeledSwitch("Cockpit tương phản cao", pro.cockpitHighContrast) { v -> nvx.proPrefs.update { it.copy(cockpitHighContrast = v) } }
            Text("Ô số liệu hiển thị trên Cockpit", style = MaterialTheme.typography.titleSmall)
            vn.nvxconnect.pro.CockpitMetric.values().filter { it != vn.nvxconnect.pro.CockpitMetric.GPS_SPEED }.forEach { m ->
                LabeledSwitch(m.title, m.key !in pro.cockpitHidden) { on -> nvx.proPrefs.update { st -> st.copy(cockpitHidden = if (on) st.cockpitHidden - m.key else st.cockpitHidden + m.key) } }
            }
        }
        Panel {
            Section("Chi phí hành trình")
            Text("Số do bạn nhập, dùng để ƯỚC TÍNH chi phí từ quãng đường GPS. Không phải số đo của xe.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(fuelPrice, { fuelPrice = it.filter(Char::isDigit).take(7); costSaved = false }, label = { Text("Giá xăng (đ/lít)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(consumption, { consumption = it.replace(',', '.').filter { c -> c.isDigit() || c == '.' }.take(5); costSaved = false }, label = { Text("Mức tiêu thụ (lít/100 km)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { nvx.proPrefs.update { it.copy(fuelPriceVnd = fuelPrice.toIntOrNull() ?: 0, consumptionL100 = consumption.toDoubleOrNull() ?: 0.0) }; costSaved = true }, Modifier.heightIn(min = 48.dp)) { Text("Lưu") }
                if (costSaved) Text("Đã lưu", color = MaterialTheme.colorScheme.primary)
            }
        }
        Panel {
            Section("Log và phiên")
            Text("Số payload tối đa giữ trong bộ nhớ", style = MaterialTheme.typography.titleSmall)
            ChipRow(vn.nvxconnect.pro.ProSettings.LOG_CHOICES.map { "%,d".format(it) }, vn.nvxconnect.pro.ProSettings.LOG_CHOICES.indexOf(pro.logMaxRecords)) { i -> nvx.proPrefs.update { it.copy(logMaxRecords = vn.nvxconnect.pro.ProSettings.LOG_CHOICES[i]) } }
            Text("Áp dụng khi mở lại ứng dụng. Khi đầy, bản ghi cũ nhất bị cắt và được báo trong Protocol Lab (không cắt âm thầm).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (recording) "Đang ghi phiên ${ble.recorderSessionId()}" else "Đã dừng ghi", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recording) OutlinedButton(onClick = { ble.stopRecording() }, Modifier.heightIn(min = 48.dp)) { Text("Dừng ghi") } else Button(onClick = { ble.startRecording("thủ công") }, Modifier.heightIn(min = 48.dp)) { Text("Phiên mới") }
                OutlinedButton(onClick = { confirmClearLog = true }, Modifier.heightIn(min = 48.dp)) { Text("Xóa log phiên") }
            }
            OutlinedButton(onClick = onOpenLab, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Nhập log / xuất báo cáo (Protocol Lab)") }
        }
        Panel {
            Section("Thiết bị đã lưu")
            if (saved.isEmpty()) Text("Chưa lưu thiết bị nào. Lưu từ Protocol Lab → BLE Scanner hoặc Tự tìm và kết nối xe.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            saved.forEach { d ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { Text(d.title, style = MaterialTheme.typography.bodyMedium); if (d.preferred) Text("Thiết bị ưu tiên (tự kết nối)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                    OutlinedButton(onClick = { ble.togglePreferred(d) }, Modifier.heightIn(min = 44.dp)) { Text(if (d.preferred) "Bỏ ưu tiên" else "Ưu tiên") }
                    TextButton(onClick = { removeTarget = d }) { Text("Xóa") }
                }
            }
        }
        Panel {
            Section("Phiên bản và hồ sơ")
            Text("Ứng dụng ${AppInfo.VERSION}", style = MaterialTheme.typography.bodyMedium)
            val evs = nvx.adapters.evidences()
            Text(if (evs.isEmpty()) "Decoder giao thức xe: chưa có (không có adapter nào được xác minh)." else "Decoder: " + evs.joinToString { "${it.id} (${it.protocolVersion})" }, style = MaterialTheme.typography.bodySmall)
            Text("Hồ sơ xe: ${s.vehicle.model} ${s.vehicle.variant} · ${s.vehicle.engineCc} cc (do bạn nhập)", style = MaterialTheme.typography.bodySmall)
        }
        Panel {
            LabeledSwitch("Kết nối tự động", s.autoConnect) { app.setAutoConnect(it) }
            Text("Tự động tìm và kết nối xe đã lưu khi ở gần.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MenuRow("Chế độ hiển thị TFT", if (s.tftMapOnly) "Chỉ bản đồ" else "Đầy đủ thông tin", onClick = onOpenTft)
            MenuRow("Thông báo", "Mở cài đặt thông báo của hệ thống") { Perms.openAppSettings(ctx) }
            MenuRow("Trung tâm hướng dẫn", "14 bài tiếng Việt · tìm kiếm · kiểm tra kết nối từng bước") { onOpenHelp(null) }
            MenuRow("Quyền riêng tư và xóa dữ liệu", "Biết app lưu gì và cách xóa") { onOpenHelp(vn.nvxconnect.helpcenter.HelpContent.Id.PRIVACY) }
        }

        Panel {
            Section("Đơn vị")
            Text("Khoảng cách km · tốc độ km/h · nhiên liệu lít · tiền VND (cố định).", style = MaterialTheme.typography.bodyMedium)
        }
        Panel {
            Section("Cảnh báo tốc độ (theo GPS điện thoại)")
            OutlinedTextField(limit, { limit = it.filter(Char::isDigit).take(3) }, label = { Text("Ngưỡng km/h (trống = tắt)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Button(onClick = { app.setSpeedLimit(limit.toIntOrNull() ?: 0) }) { Text("Lưu ngưỡng") }
            LabeledSwitch("Âm cảnh báo khi vượt ngưỡng", s.alertSound) { app.setAlertSound(it) }
            Text("Ngưỡng này là do bạn đặt và dựa vào GPS, không phải tốc độ ECU và không thay thế biển báo giao thông.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Panel {
            Section("Dẫn đường")
            LabeledSwitch("Chỉ dẫn bằng giọng nói", s.voiceGuidance) { app.setVoice(it) }
            Text(voice.label, style = MaterialTheme.typography.bodySmall, color = if (voice == VoiceStatus.READY) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
        Panel {
            Section("Kết nối BLE")
            LabeledSwitch("Tự kết nối thiết bị ưu tiên khi mở app", s.autoConnect) { app.setAutoConnect(it) }
            Text(if (saved.any { it.preferred }) "Thiết bị ưu tiên: " + saved.first { it.preferred }.title else "Chưa chọn thiết bị ưu tiên (đánh dấu ★ trong tab Kết nối).", style = MaterialTheme.typography.bodySmall)
            Text("Chỉ kết nối khi thấy thiết bị trong vòng ~12 giây và đã có quyền; không tự xin quyền, không tự ghép nối.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Panel {
            Section("Máy chủ bản đồ (nâng cao)")
            OutlinedTextField(routeUrl, { routeUrl = it }, label = { Text("Máy chủ định tuyến OSRM") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(geoUrl, { geoUrl = it }, label = { Text("Máy chủ tìm kiếm Nominatim") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            urlErr?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { urlErr = app.saveRoutingUrl(routeUrl) ?: app.saveGeocodeUrl(geoUrl) }) { Text("Lưu") }
                OutlinedButton(onClick = { urlErr = null; app.resetServers() }) { Text("Mặc định") }
            }
            Text("Mặc định dùng máy chủ DEMO công cộng của OSRM (hồ sơ ô tô, không cam kết dịch vụ) và Nominatim của OpenStreetMap (tối đa 1 yêu cầu/giây, chỉ tìm khi bạn bấm Tìm). " +
                "Nếu dùng nhiều, hãy tự dựng máy chủ.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Panel {
            Section("Chế độ DEMO")
            LabeledSwitch("Hiện dữ liệu minh họa", s.demo) { app.setDemo(it) }
            Text("Bật: nạp hành trình/xăng/bảo dưỡng mẫu, luôn gắn nhãn DEMO và tách khỏi dữ liệu thật. Tắt: xóa dữ liệu DEMO. Không bao giờ có RPM/nhiệt độ/điện áp giả.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Panel {
            Section("Quyền riêng tư")
            Text("Dữ liệu nằm trên máy. App chỉ gọi mạng khi bạn tìm địa điểm/tính đường (gửi từ khóa và tọa độ tới máy chủ ở trên) và khi tải ô bản đồ.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { app.exportData { json -> ShareFiles.share(ctx, "nvx-du-lieu-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json", "application/json", json) } }) { Text("Xuất dữ liệu của tôi (JSON)") }
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Xóa toàn bộ dữ liệu") }
        }
        Panel {
            Section("Hiển thị lên màn hình TFT của xe")
            StatusChip("KHÔNG HỖ TRỢ", Tone.Warn)
            Text(nav.tft.limitation, style = MaterialTheme.typography.bodySmall)
        }
        Panel {
            Section("Giới thiệu")
            Text("NVX Connect ${AppInfo.VERSION}", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { showAbout = !showAbout }) { Text(if (showAbout) "Ẩn giấy phép" else "Nguồn dữ liệu và giấy phép") }
            if (showAbout) Text(
                "Dữ liệu bản đồ © những người đóng góp OpenStreetMap, giấy phép ODbL (openstreetmap.org/copyright).\n" +
                    "Ô bản đồ: máy chủ ô OpenStreetMap, dùng theo chính sách tile usage của OSM (có định danh ứng dụng, có cache).\n" +
                    "Tìm kiếm: Nominatim. Định tuyến: OSRM (Open Source Routing Machine).\n" +
                    "Thư viện: osmdroid (Apache 2.0), AndroidX/Jetpack Compose (Apache 2.0), Google Play Services Location.\n" +
                    "Ứng dụng độc lập, không liên kết với Yamaha. Không dùng thay biển báo giao thông.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirmClearLog) ConfirmDialog("Xóa log phiên?", "Payload đã ghi, nhật ký và kết quả phân tích sẽ bị xóa khỏi bộ nhớ.", "Xóa", { confirmClearLog = false; ble.clearLog() }, { confirmClearLog = false })
    removeTarget?.let { d -> ConfirmDialog("Xóa thiết bị đã lưu?", "${d.title} sẽ bị bỏ khỏi danh sách đã lưu (không ảnh hưởng ghép đôi của hệ thống).", "Xóa", { removeTarget = null; ble.removeSaved(d.address) }, { removeTarget = null }) }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        confirmButton = { TextButton(onClick = { confirmDelete = false; app.deleteAllData() }) { Text("Xóa hết") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Hủy") } },
        title = { Text("Xóa toàn bộ dữ liệu?") },
        text = { Text("Hành trình, điểm GPS, xăng, bảo dưỡng, hồ sơ xe, cài đặt và thiết bị BLE đã lưu sẽ bị xóa khỏi máy. Không thể khôi phục.") })
}
