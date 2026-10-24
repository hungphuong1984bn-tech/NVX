package vn.nvxconnect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import vn.nvxconnect.NvxApp
import vn.nvxconnect.capability.VehicleCapabilityRegistry
import vn.nvxconnect.helpcenter.HelpContent
import vn.nvxconnect.protocol.ConnState
import vn.nvxconnect.protocol.formatLive
import vn.nvxconnect.protocol.LiveValue
import vn.nvxconnect.vehicledata.DataStatus
import vn.nvxconnect.data.FuelEntry
import vn.nvxconnect.domain.*

@Composable
fun VehicleScreen(app: AppViewModel, ble: BleViewModel, onOpenHelp: (String?) -> Unit = {}) {
    var sub by remember { mutableIntStateOf(0) }
    Page("Xe") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HelpButton({ onOpenHelp(HelpContent.Id.VEHICLE_DATA) }, "Hướng dẫn dữ liệu xe")
            HelpButton({ onOpenHelp(HelpContent.Id.GPS_TRIPS) }, "Hướng dẫn hành trình")
        }
        Segmented(listOf("Hồ sơ", "Thông số", "Bảo dưỡng", "Xăng"), sub) { sub = it }
        when (sub) { 0 -> ProfileSection(app, ble); 1 -> SpecsSection(app); 2 -> MaintenanceSection(app); else -> FuelSection(app) }
    }
}

@Composable
private fun ProfileSection(app: AppViewModel, ble: BleViewModel) {
    val s by app.settings.collectAsState()
    var model by remember(s.vehicle) { mutableStateOf(s.vehicle.model) }
    var variant by remember(s.vehicle) { mutableStateOf(s.vehicle.variant) }
    var cc by remember(s.vehicle) { mutableStateOf(s.vehicle.engineCc.toString()) }
    var date by remember(s.vehicle) { mutableStateOf(s.vehicle.purchaseDate) }
    var odo by remember(s.odoKm) { mutableStateOf(if (s.odoKm > 0) s.odoKm.toString() else "") }
    var err by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    Panel {
        Section("Hồ sơ xe (bạn nhập)"); SourceTag(DataSource.USER_INPUT)
        OutlinedTextField(model, { model = it }, label = { Text("Tên xe") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(variant, { variant = it }, label = { Text("Phiên bản") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(cc, { cc = it.filter(Char::isDigit).take(4) }, label = { Text("Dung tích động cơ (cc)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(date, { date = it }, label = { Text("Ngày mua (yyyy-MM-dd)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(odo, { odo = it.filter(Char::isDigit).take(7) }, label = { Text("ODO hiện tại (km)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (saved) Text("Đã lưu", color = MaterialTheme.colorScheme.primary)
        Button(onClick = {
            saved = false
            err = app.saveVehicle(VehicleProfile(model.trim(), variant.trim(), cc.toIntOrNull() ?: 0, date.trim()))
            if (err == null) { app.setOdo(odo.toIntOrNull() ?: 0); saved = true }
        }) { Text("Lưu hồ sơ") }
        Text("ODO là số bạn tự nhập, app không đọc ODO từ xe.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    VehicleDataPanel(ble)
}

/**
 * Dữ liệu từ xe với trạng thái từng giá trị (VERIFIED/STALE/UNVERIFIED/UNAVAILABLE/UNSUPPORTED), nguồn và tuổi dữ liệu.
 * Chỉ VERIFIED hiển thị số; mọi trạng thái khác hiển thị "Chưa có dữ liệu" kèm lý do. Hiện chưa có adapter đã xác minh nên mọi dòng là UNAVAILABLE.
 */
@Composable
private fun VehicleDataPanel(ble: BleViewModel) {
    val nvx = LocalContext.current.applicationContext as NvxApp
    val link by ble.linkStatus.collectAsState()
    val conn by ble.connState.collectAsState()
    val tele by nvx.telemetry.state.collectAsState()   // đọc để vẽ lại khi có giá trị mới
    val now = rememberNow()
    val supported = (conn as? ConnState.Ready)?.let { nvx.adapters.find(it.services)?.supportedParams }
    val rows = remember(tele, now, link.protocolVerified, supported) { nvx.vehicleData.snapshot(link.protocolVerified, supported, now) }
    Panel {
        Section("Dữ liệu từ xe (đã xác minh)")
        Text(link.headline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        rows.forEach { r ->
            val name = VehicleCapabilityRegistry.defs.firstOrNull { it.param == r.param }?.name ?: r.param.name
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text(if (r.displayable) formatLive(LiveValue(r.value!!, r.unit ?: "", r.updatedAtMs ?: 0L, r.evidenceId ?: "")) else VehicleReadings.NO_DATA,
                        style = MaterialTheme.typography.titleSmall)
                    Text(r.note + (r.ageMs?.let { " · cách đây ${it / 1000} giây" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusChip(r.status.name, when (r.status) { DataStatus.VERIFIED -> Tone.Good; DataStatus.UNAVAILABLE -> Tone.Neutral; else -> Tone.Warn })
            }
        }
        Text("Tốc độ GPS của điện thoại không bao giờ được dùng thay tốc độ xe. Khoảng hợp lệ là giới hạn hợp lý để loại giá trị rác, không phải thông số Yamaha.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SourceTag(DataSource.UNAVAILABLE)
    }
}

@Composable
private fun MaintenanceSection(app: AppViewModel) {
    val items by app.maintenance.collectAsState(); val s by app.settings.collectAsState()
    val shown = items.filter { it.isDemo == s.demo }
    var name by remember { mutableStateOf("") }; var km by remember { mutableStateOf("") }; var days by remember { mutableStateOf("") }
    val now = System.currentTimeMillis()
    if (s.odoKm == 0) Notice("Hãy nhập ODO ở mục Hồ sơ để tính hạn theo km.", Tone.Warn)
    shown.forEach { i ->
        val d = Maintenance.due(i, s.odoKm, now)
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(i.name, style = MaterialTheme.typography.titleMedium)
                when (d.status) {
                    MaintStatus.OK -> StatusChip("CÒN HẠN", Tone.Good); MaintStatus.SOON -> StatusChip("SẮP ĐẾN HẠN", Tone.Warn)
                    MaintStatus.OVERDUE -> StatusChip("QUÁ HẠN", Tone.Bad); MaintStatus.COMPLETED -> StatusChip("ĐÃ HOÀN THÀNH", Tone.Good)
                    MaintStatus.UNRECORDED -> StatusChip("CHƯA GHI", Tone.Neutral)
                }
            }
            val rule = listOfNotNull(i.intervalKm?.let { "mỗi $it km" }, i.intervalDays?.let { "mỗi $it ngày" }).joinToString(" hoặc ").ifEmpty { "việc một lần" }
            Text(rule, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            d.remainingKm?.let { Text(if (it >= 0) "Còn $it km" else "Quá ${-it} km") }
            d.remainingDays?.let { Text(if (it >= 0) "Còn $it ngày" else "Quá ${-it} ngày") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { app.markDone(i) }) { Text("Vừa làm xong") }
                OutlinedButton(onClick = { app.deleteMaint(i.id) }) { Text("Xóa") }
            }
        }
    }
    if (shown.isEmpty()) Notice("Chưa có hạng mục bảo dưỡng.")
    if (!s.demo) {
        Panel {
            Section("Thêm hạng mục")
            OutlinedTextField(name, { name = it }, label = { Text("Tên hạng mục") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(km, { km = it.filter(Char::isDigit) }, label = { Text("Chu kỳ km") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(days, { days = it.filter(Char::isDigit) }, label = { Text("Chu kỳ ngày") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Text("Bỏ trống cả hai = việc làm một lần.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { app.addMaint(name.trim(), km.toIntOrNull(), days.toIntOrNull()); name = ""; km = ""; days = "" }, enabled = name.isNotBlank()) { Text("Thêm") }
            OutlinedButton(onClick = { app.addPresets() }) { Text("Thêm gợi ý tham khảo") }
            Text("Chu kỳ gợi ý là số tham khảo của app, KHÔNG phải thông số chính thức của Yamaha. Hãy đối chiếu sổ bảo dưỡng của xe.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FuelSection(app: AppViewModel) {
    val fuel by app.fuel.collectAsState(); val s by app.settings.collectAsState()
    val shown = fuel.filter { it.isDemo == s.demo }
    var odo by remember { mutableStateOf(if (s.odoKm > 0) s.odoKm.toString() else "") }
    var liters by remember { mutableStateOf("") }; var price by remember { mutableStateOf("") }; var total by remember { mutableStateOf("") }
    var full by remember { mutableStateOf(true) }; var err by remember { mutableStateOf<String?>(null) }
    val stats = FuelCalc.compute(shown)
    Panel {
        Section("Tiêu hao (tính từ số bạn nhập)"); SourceTag(DataSource.CALCULATED)
        if (stats == null) Text("Cần ít nhất 2 lần đổ đầy bình, có ODO khác nhau, để tính L/100km.")
        else { Text("%.2f L/100 km".format(stats.lPer100Km), style = MaterialTheme.typography.headlineMedium); Text("%d km · %.1f L · %,.0f VND/km".format(stats.distanceKm, stats.liters, stats.vndPerKm)) }
        Text("Không phải số liệu ECU. Phương pháp đổ đầy đến đổ đầy.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!s.demo) Panel {
        Section("Ghi lần đổ xăng"); SourceTag(DataSource.USER_INPUT)
        val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        OutlinedTextField(odo, { odo = it.filter(Char::isDigit) }, label = { Text("ODO (km)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(liters, { liters = it.replace(',', '.') }, label = { Text("Số lít") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = num)
            OutlinedTextField(price, { price = it.filter(Char::isDigit) }, label = { Text("Giá/lít (VND)") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        OutlinedTextField(total, { total = it.filter(Char::isDigit) }, label = { Text("Tổng tiền (VND, bỏ trống = lít × giá)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        LabeledSwitch("Đổ đầy bình", full) { full = it }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            err = app.addFuel(odo.toIntOrNull(), liters.toDoubleOrNull(), price.toIntOrNull(), total.toLongOrNull(), full)
            if (err == null) { liters = ""; total = "" }
        }) { Text("Lưu") }
    }
    Section("Lịch sử đổ xăng")
    if (shown.isEmpty()) Notice("Chưa có lần đổ xăng nào.")
    shown.sortedByDescending { it.odometerKm }.forEach { f: FuelEntry ->
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${f.odometerKm} km · %.2f L".format(f.liters), style = MaterialTheme.typography.titleSmall)
                if (f.fullTank) StatusChip("ĐỔ ĐẦY", Tone.Good)
            }
            Text("%,d VND/L · tổng %,d VND · ${fmtTime(f.dateMs)}".format(f.pricePerLiter, f.totalVnd), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { app.deleteFuel(f.id) }) { Text("Xóa") }
        }
    }
}

@Composable
private fun SpecsSection(app: AppViewModel) {
    val fuel by app.fuel.collectAsState(); val s by app.settings.collectAsState()
    val stats = FuelCalc.compute(fuel.filter { it.isDemo == s.demo })
    Panel {
        Section("Thông số ${s.vehicle.model} (${VehicleSpecs.REFERENCE_LABEL})")
        VehicleSpecs.nvx155.forEach { i ->
            Text(i.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(i.value)
            i.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(VehicleSpecs.DISCLAIMER, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Panel {
        Section("Tầm chạy ước tính (đầy bình)"); SourceTag(DataSource.CALCULATED)
        val est = RangeCalc.estimate(VehicleSpecs.tankOrDefault(null), stats?.lPer100Km)
        if (est == null) Text("Cần ít nhất 2 lần đổ đầy bình ở mục Xăng để tính.")
        else {
            Text("~${est.rangeKm} km", style = MaterialTheme.typography.headlineMedium)
            Text("Bình %.1f L (tham khảo) ÷ %.2f L/100 km (từ số bạn nhập)".format(est.tankL, est.lPer100Km), style = MaterialTheme.typography.bodySmall)
        }
        Text("Không phải số liệu xe/ECU. Mức xăng thực tế app không đọc được.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
