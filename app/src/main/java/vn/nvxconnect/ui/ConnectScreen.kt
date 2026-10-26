package vn.nvxconnect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import vn.nvxconnect.bluetooth.*
import vn.nvxconnect.helpcenter.HelpContent
import vn.nvxconnect.helpcenter.HelpErrorMapper
import vn.nvxconnect.protocol.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kết nối xe qua BLE theo từng bước: cấp quyền/Bluetooth, quét, kết nối, dữ liệu.
 * Mặc định chỉ hiện luồng kết nối gọn; công cụ khảo sát giao thức (cây GATT, gói dữ liệu, log) nằm ở mục "Nâng cao".
 * KHÔNG có nút nào ghi dữ liệu vào xe. "Nghe" chỉ bật notify bằng cách ghi descriptor CCCD, trên characteristic bạn chọn.
 * Chưa có giao thức xe đã xác minh nên bước "Dữ liệu" không bao giờ hoàn tất: app chỉ nhận RAW.
 */
@Composable
fun ConnectScreen(ble: BleViewModel, onOpenAuto: () -> Unit = {}, onOpenHelp: (String?) -> Unit = {}) {
    val ctx = LocalContext.current
    val state by ble.connState.collectAsState()
    val link by ble.linkStatus.collectAsState()
    val rssi by ble.rssi.collectAsState()
    val mtu by ble.mtu.collectAsState()
    val subs by ble.subscriptions.collectAsState()
    val info by ble.deviceInfo.collectAsState()
    val scanning by ble.scanning.collectAsState()
    val problem by ble.problem.collectAsState()
    val rows by ble.visible.collectAsState()
    val total by ble.totalSeen.collectAsState()
    val q by ble.query.collectAsState()
    val saved by ble.saved.collectAsState()
    val events by ble.events.collectAsState()
    val frames by ble.frameLines.collectAsState()
    val frameCount by ble.frameCount.collectAsState()
    var analysis by remember { mutableStateOf<String?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    val tagCounts by ble.tagCounts.collectAsState()
    var correlation by remember { mutableStateOf<String?>(null) }
    var customTag by remember { mutableStateOf("") }

    val scan = rememberBleGate(onGranted = { ble.startScan() }, onDenied = { ble.reportPermissionDenied() })

    val step = when {
        state is ConnState.Ready -> if (link.level == LinkLevel.VERIFIED) 4 else 3
        state.isActive -> 2
        scanning -> 1
        else -> 0
    }
    val tone = when (link.level) { LinkLevel.VERIFIED -> Tone.Good; LinkLevel.RAW_DATA_ONLY -> Tone.Warn; LinkLevel.ERROR -> Tone.Bad; else -> Tone.Neutral }
    val preferred = saved.firstOrNull { it.preferred } ?: saved.firstOrNull()

    Page("Kết nối xe", "Bluetooth Low Energy · chỉ đọc/nghe") {
        LinkMonitorPanel(ble)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HelpButton({ onOpenHelp(HelpContent.Id.CONNECT_STATES) }, "Hướng dẫn")
            HelpButton({ onOpenHelp(null) }, "Kiểm tra từng bước")
            // Chỉ hiện khi có lỗi/cảnh báo thật (ConnState.Error, đang kết nối lại, hoặc chỉ nhận RAW).
            FixButton(HelpErrorMapper.forState(state, link), onOpenHelp)
        }
        OutlinedButton(onClick = onOpenAuto, modifier = Modifier.fillMaxWidth()) { Text("Tự tìm và kết nối xe (khảo sát tự động)") }
        HeroCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(toneColor(tone), 12)
                Text(link.headline, style = MaterialTheme.typography.titleMedium)
            }
            link.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(4.dp))
            StepRow(listOf("Bluetooth", "Quét", "Kết nối", "Dữ liệu"), step)
            Spacer(Modifier.height(4.dp))
            Text("Phiên GATT: ${state.code}" + (rssi?.let { " · RSSI $it dBm" } ?: "") + if (state is ConnState.Ready) " · MTU $mtu" else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                state is ConnState.Error -> Button(onClick = { ble.retry() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Thử lại") }
                state.isActive -> OutlinedButton(onClick = { ble.disconnect() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Ngắt kết nối") }
                scanning -> OutlinedButton(onClick = { ble.stopScan() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Dừng quét") }
                else -> {
                    Button(onClick = scan, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Quét tìm xe") }
                    if (preferred != null) OutlinedButton(onClick = { ble.connect(preferred.address) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("Kết nối lại: " + preferred.title, maxLines = 1)
                    }
                }
            }
            if (link.level == LinkLevel.RAW_DATA_ONLY)
                Text("Đã kết nối nhưng chưa có giao thức xe được xác minh, nên chưa có số liệu xe. App chỉ nhận dữ liệu thô (RAW).",
                    style = MaterialTheme.typography.labelSmall, color = WarnColor)
            Text("App không gửi lệnh vào xe và không ghép nối thay Y-Connect.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        problem?.let { (kind, reason) ->
            Panel {
                Text(kind.title, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                Text(reason, style = MaterialTheme.typography.bodySmall)
                when (kind) {
                    ErrorKind.PERMISSION_DENIED -> { Button(onClick = scan) { Text("Cấp quyền") }; OutlinedButton(onClick = { Perms.openAppSettings(ctx) }) { Text("Mở cài đặt ứng dụng") } }
                    ErrorKind.BLUETOOTH_OFF -> OutlinedButton(onClick = { Perms.openBluetoothSettings(ctx) }) { Text("Mở cài đặt Bluetooth") }
                    ErrorKind.LOCATION_SERVICES_OFF -> OutlinedButton(onClick = { Perms.openLocationSettings(ctx) }) { Text("Mở cài đặt vị trí") }
                    ErrorKind.SCAN_FAILED -> Button(onClick = scan) { Text("Quét lại") }
                    else -> {}
                }
                FixButton(HelpErrorMapper.forError(kind), onOpenHelp)
            }
        }

        if (scanning) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ScanRadarAnimation(active = true, modifier = Modifier.size(120.dp)) }
            Text("Đang quét, tự dừng sau 60 giây. Bật khóa điện xe và đứng gần xe.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (saved.isNotEmpty()) {
            Section("Thiết bị đã lưu")
            saved.forEach { d ->
                Panel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.title, style = MaterialTheme.typography.titleSmall)
                            Text(d.address + " · " + AddressKind.classify(d.address).label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (d.preferred) StatusChip("Tự kết nối", Tone.Good)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { ble.connect(d.address) }, enabled = !state.isActive) { Text("Kết nối") }
                        OutlinedButton(onClick = { ble.togglePreferred(d) }) { Text(if (d.preferred) "Bỏ ưu tiên" else "Ưu tiên") }
                        OutlinedButton(onClick = { ble.removeSaved(d.address) }) { Text("Xóa") }
                    }
                }
            }
        }

        Section("Thiết bị gần đây (${rows.size}/$total)")
        OutlinedTextField(q.text, { ble.setQuery(q.copy(text = it)) }, label = { Text("Tìm theo tên, địa chỉ, UUID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        DisclosureHeader("Bộ lọc", "Đã lưu, kết nối được, có tên, ẩn mất tín hiệu", showFilters) { showFilters = !showFilters }
        if (showFilters) Panel {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterToggle("Đã lưu", q.savedOnly, Modifier.weight(1f)) { ble.setQuery(q.copy(savedOnly = !q.savedOnly)) }
                FilterToggle("Kết nối được", q.connectableOnly, Modifier.weight(1f)) { ble.setQuery(q.copy(connectableOnly = !q.connectableOnly)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterToggle("Có tên", q.namedOnly, Modifier.weight(1f)) { ble.setQuery(q.copy(namedOnly = !q.namedOnly)) }
                FilterToggle("Ẩn mất tín hiệu", q.hideStale, Modifier.weight(1f)) { ble.setQuery(q.copy(hideStale = !q.hideStale)) }
            }
            OutlinedButton(onClick = { ble.clearDevices() }) { Text("Xóa danh sách thiết bị") }
        }
        if (rows.isEmpty()) Notice(if (total == 0) "Chưa thấy thiết bị nào. Bấm \"Quét tìm xe\"; nếu NVX đang tắt máy, bật khóa điện rồi quét lại." else "Không có thiết bị khớp bộ lọc.")
        rows.sortedByDescending { looksLikeYamaha(it.entry.record.name) }.take(60).forEach { row ->
            val r = row.entry.record
            val likely = looksLikeYamaha(r.name)
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name ?: "(không tên)", style = MaterialTheme.typography.titleSmall)
                        Text(r.address + " · " + r.addressKind.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        SignalBars(row.entry.smoothedRssi, dim = row.stale)
                        Text("%.0f dBm".format(row.entry.smoothedRssi) + if (row.stale) " · mất" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (likely) StatusChip("Tên giống thiết bị Yamaha (chỉ đoán theo tên)", Tone.Good)
                if (r.connectable == false) StatusChip("Không kết nối được", Tone.Bad)
                if (r.addressKind.mayChange) Text("Địa chỉ có thể xoay vòng: thiết bị đã lưu được nhận lại theo tên.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (showAdvanced) {
                    Text("Kết nối được: " + (r.connectable?.let { if (it) "có" else "không" } ?: "không rõ") + (r.txPowerDbm?.let { " · TX $it dBm" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    if (r.advertisedServiceUuids.isNotEmpty()) Text(r.advertisedServiceUuids.joinToString("\n") { KnownUuids.pretty(it) }, style = MaterialTheme.typography.labelSmall)
                    if (r.manufacturerData.isNotEmpty()) Text("Mã hãng: " + r.manufacturerData.keys.joinToString { "0x%04X".format(it) }, style = MaterialTheme.typography.labelSmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { ble.connect(r.address) }, enabled = r.connectable != false && !state.isActive) { Text("Kết nối") }
                    OutlinedButton(onClick = { ble.save(r) }, enabled = !ble.isSaved(r)) { Text(if (ble.isSaved(r)) "Đã lưu" else "Lưu") }
                }
            }
        }

        DisclosureHeader("Nâng cao: khảo sát giao thức", "Cây GATT, gói dữ liệu RAW, nhật ký, xuất log", showAdvanced) { showAdvanced = !showAdvanced }

        if (showAdvanced) {
            (state as? ConnState.Ready)?.let { ready ->
                Section("Cây GATT (${ready.services.size} service)")
                if (ready.services.isEmpty()) Notice("Thiết bị không công bố service nào.")
                if (info.isNotEmpty()) Panel { Section("Thông tin chuẩn đã đọc"); info.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodySmall) } }
                ready.services.forEach { s ->
                    Panel {
                        Text(KnownUuids.pretty(s.uuid), style = MaterialTheme.typography.titleSmall)
                        s.characteristics.forEach { c ->
                            HorizontalDivider()
                            Text(KnownUuids.pretty(c.uuid), style = MaterialTheme.typography.bodyMedium)
                            Text(c.properties.joinToString(" · ").ifEmpty { "(không có thuộc tính)" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            if (c.descriptors.isNotEmpty()) Text("Descriptor: " + c.descriptors.joinToString { it.uuid.substring(4, 8) }, style = MaterialTheme.typography.labelSmall)
                            val st = subs[c.uuid.lowercase()]
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (c.canSubscribe) {
                                    if (st == SubscriptionState.ACTIVE || st == SubscriptionState.PENDING) OutlinedButton(onClick = { ble.unsubscribe(c.uuid) }) { Text("Ngừng nghe") }
                                    else Button(onClick = { ble.subscribe(c.uuid) }) { Text(if (st == SubscriptionState.FAILED) "Nghe lại" else "Nghe") }
                                }
                                if (c.canRead && StandardInfo.isAllowed(c.uuid)) OutlinedButton(onClick = { ble.readInfo(c.uuid) }) { Text("Đọc " + (StandardInfo.label(c.uuid) ?: "")) }
                            }
                            when (st) {
                                SubscriptionState.ACTIVE -> StatusChip("Đang nghe (thiết bị đã xác nhận)", Tone.Good)
                                SubscriptionState.PENDING -> StatusChip("Chờ thiết bị xác nhận…", Tone.Warn)
                                SubscriptionState.FAILED -> StatusChip("Đăng ký thất bại, xem nhật ký", Tone.Bad)
                                null -> {}
                            }
                            if (c.canWrite) Text("Có thuộc tính ghi: app KHÔNG ghi vào đây.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Panel {
                Section("Gói dữ liệu nhận được ($frameCount)")
                if (frames.isEmpty()) Text("Chưa có. Kết nối rồi bấm \"Nghe\" ở characteristic NOTIFY/INDICATE.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                frames.takeLast(25).forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
                Text("Dữ liệu hiển thị là RAW/UNKNOWN; chuỗi giống ASCII bị che.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { analysis = ble.analysisText() }, enabled = frameCount > 0) { Text("Phân tích sơ bộ") }
                analysis?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }

            Panel {
                Section("Khảo sát có hướng dẫn")
                Text("Đang \"Nghe\" ở characteristic NOTIFY thì mỗi khi bạn vừa làm một hành động trên xe thật, bấm nút tương ứng ngay lúc đó. " +
                    "Lặp lại mỗi hành động 3-5 lần. App sẽ gợi ý vị trí byte đổi trùng với hành động — chỉ là gợi ý, bạn tự đối chiếu với đồng hồ xe.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val presets = listOf("Ga nhẹ", "Ga mạnh", "Nhả ga", "Phanh trước", "Phanh sau", "Xi-nhan trái", "Xi-nhan phải", "Tắt xi-nhan", "Còi", "Đèn pha", "Nổ máy", "Tắt máy", "Chống nghiêng")
                FlowButtons(presets) { ble.tagAction(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(customTag, { customTag = it }, label = { Text("Hành động khác") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = { ble.tagAction(customTag); customTag = "" }, enabled = customTag.isNotBlank()) { Text("Gắn nhãn") }
                }
                if (tagCounts.isNotEmpty()) {
                    Text("Đã gắn nhãn: " + tagCounts.entries.joinToString { "${it.key} ×${it.value}" }, style = MaterialTheme.typography.labelSmall)
                    OutlinedButton(onClick = { ble.clearTags() }) { Text("Xóa nhãn") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { correlation = ble.correlationText() }, enabled = tagCounts.isNotEmpty() && frameCount > 0) { Text("Tìm tương quan") }
                }
                correlation?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                Text("Không có kết quả nào ở đây tự động trở thành số liệu xe hay trạng thái \"đã xác minh\". " +
                    "Để dùng thật, điền docs/PROTOCOL_EVIDENCE_TEMPLATE.md rồi nhờ viết adapter thủ công.",
                    style = MaterialTheme.typography.labelSmall, color = WarnColor)
            }

            Panel {
                Section("Nhật ký sự kiện")
                if (events.isEmpty()) Text("Chưa có sự kiện.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                events.takeLast(30).forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
            }

            Panel {
                Section("Xuất log để phân tích giao thức")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { ShareFiles.share(ctx, "nvx-ble-log-${stamp()}.json", "application/json", ble.exportJson()) }) { Text("Xuất JSON") }
                    OutlinedButton(onClick = { ShareFiles.share(ctx, "nvx-ble-log-${stamp()}.csv", "text/csv", ble.exportCsv()) }) { Text("Xuất CSV") }
                    OutlinedButton(onClick = { ble.clearLog() }) { Text("Xóa log") }
                }
                Text("Địa chỉ thiết bị được băm, tên bị che, chuỗi ASCII dài trong gói được thay bằng '*'. Hướng dẫn gửi log: (1) quét và kết nối ở nơi gần xe, bật khóa điện; " +
                    "(2) bấm Nghe ở các characteristic NOTIFY, thao tác bình thường trên xe (không phải thao tác nguy hiểm); (3) Xuất JSON và gửi tệp cho người hỗ trợ kèm " +
                    "phiên bản xe/CCU/firmware nếu biết. Không gửi log lúc đang lái.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val WarnColor = NvxColors.Warning

/** Gợi ý theo tên quảng bá; KHÔNG phải xác minh thiết bị là xe Yamaha. */
private fun looksLikeYamaha(name: String?): Boolean {
    val n = name?.lowercase() ?: return false
    return "nvx" in n || "yamaha" in n || "y-connect" in n || "yconnect" in n
}

private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

@Composable
private fun FilterToggle(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (on) Button(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(label, maxLines = 1) }
    else OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(label, maxLines = 1) }
}

/** Xếp nút thành nhiều hàng, 3 nút/hàng (không dùng FlowRow để tránh API thử nghiệm). */
@Composable
private fun FlowButtons(labels: List<String>, onClick: (String) -> Unit) =
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { l -> OutlinedButton(onClick = { onClick(l) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text(l, maxLines = 1, style = MaterialTheme.typography.labelSmall) } }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
