package vn.nvxconnect.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.nvxconnect.auto.AutoState
import vn.nvxconnect.auto.EvidenceLevel
import vn.nvxconnect.bluetooth.ErrorKind
import vn.nvxconnect.bluetooth.KnownUuids
import vn.nvxconnect.bluetooth.DeviceQuery
import vn.nvxconnect.helpcenter.HelpContent
import vn.nvxconnect.helpcenter.HelpErrorMapper
import vn.nvxconnect.pro.*
import vn.nvxconnect.protocol.ByteViews
import vn.nvxconnect.protocol.CalQuality
import vn.nvxconnect.protocol.ConnState
import vn.nvxconnect.protocol.SubscriptionState
import vn.nvxconnect.protocol.YccCandidateHub
import vn.nvxconnect.protocol.YccCandidates
import vn.nvxconnect.telemetry.PayloadFlag
import vn.nvxconnect.telemetry.PayloadRecord
import vn.nvxconnect.telemetry.PayloadSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LAB_TABS = listOf("BLE Scanner", "GATT Explorer", "Live Packets", "HEX Inspector", "Frame Analyzer", "Hypotheses", "Evidence", "Session Logs", "Export")
private fun ts(ms: Long) = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(ms))

/**
 * Protocol Lab — công cụ khảo sát THỤ ĐỘNG. Không có đường ghi dữ liệu vào xe.
 * Nhãn trạng thái chỉ lấy từ engine hiện có (LabStatusMapper); giao diện không có nút nào gán VERIFIED.
 */
@Composable
fun ProtocolLabScreen(ble: BleViewModel, onBack: () -> Unit, onOpenCapabilities: () -> Unit = {}, onOpenHelp: (String?) -> Unit = {}, onOpenConnect: () -> Unit = {}) {
    val status by ble.autoStatus.collectAsState()
    val state by ble.connState.collectAsState()
    val link by ble.linkStatus.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Khung được chọn từ Live Packets để so sánh/giải nghĩa ở HEX Inspector.
    var hexA by rememberSaveable { mutableStateOf("") }
    var hexB by rememberSaveable { mutableStateOf("") }
    var timeA by rememberSaveable { mutableLongStateOf(0L) }
    var timeB by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(state) { ble.refreshReport() }

    val step = when (status.state) {
        AutoState.IDLE, AutoState.SCANNING, AutoState.FAILED, AutoState.CONNECTION_BUSY -> 0
        AutoState.CANDIDATE_FOUND -> 1
        AutoState.GATT_CONNECTED -> 2
        AutoState.BONDED, AutoState.AUTHENTICATED -> 4
        AutoState.PROTOCOL_VERIFIED, AutoState.DATA_AVAILABLE -> 5
    }
    val exploring = state is ConnState.Connecting || state is ConnState.Connected || state is ConnState.DiscoveringServices

    Page(null) {
        AppTopBar("Protocol Lab", "Khảo sát thụ động · chỉ đọc/nghe", onBack = onBack, actions = { HelpButton({ onOpenHelp(HelpContent.Id.EXPORT_DIAG) }) })
        HelpErrorMapper.forState(state, link)?.let { id -> Notice("Phát hiện vấn đề với kết nối hiện tại.", Tone.Warn, "Tìm cách khắc phục") { onOpenHelp(id) } }
        StepRow(listOf("Khảo sát", "GATT", "Phân tích", "Xác thực", "Kết nối"), step)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ScanRadarAnimation(active = exploring, Modifier.size(96.dp)) }
        Text(status.headline, style = MaterialTheme.typography.titleMedium)
        status.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) { LabStatus.values().forEach { LabBadge(it) } }
        Text("Sáu nhãn bằng chứng. Chỉ VERIFIED mới là dữ liệu đã xác minh độc lập; mọi nhãn khác là chưa xác minh.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipRow(LAB_TABS, tab) { tab = it }

        when (tab) {
            0 -> ScannerTab(ble, onOpenConnect)
            1 -> GattTab(ble)
            2 -> PacketsTab(ble, onPick = { rec, slot -> if (slot == 0) { hexA = rec.hex; timeA = rec.timeMs } else { hexB = rec.hex; timeB = rec.timeMs }; tab = 3 })
            3 -> HexTab(hexA, hexB, timeA, timeB, { hexA = it; timeA = 0 }, { hexB = it; timeB = 0 })
            4 -> FrameTab(ble)
            5 -> HypothesisTab(ble)
            6 -> EvidenceTab(ble)
            7 -> SessionTab(ble)
            else -> ExportTab(ble)
        }
        OutlinedButton(onClick = onOpenCapabilities, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Danh mục chức năng xe/app · đối chiếu") }
        if (state.isActive) OutlinedButton(onClick = { ble.disconnect() }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Ngắt kết nối") }
    }
}

// ------------------------------------------------------------------ 1. BLE Scanner
@Composable
private fun ColumnScope.ScannerTab(ble: BleViewModel, onOpenConnect: () -> Unit) {
    val scanning by ble.scanning.collectAsState()
    val rows by ble.visible.collectAsState()
    val total by ble.totalSeen.collectAsState()
    val q by ble.query.collectAsState()
    val problem by ble.problem.collectAsState()
    val state by ble.connState.collectAsState()
    val scan = rememberBleGate(onGranted = { ble.startScan() }, onDenied = { ble.reportPermissionDenied() })
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (scanning) Button(onClick = { ble.stopScan() }, Modifier.heightIn(min = 48.dp)) { Text("Dừng quét") }
            else Button(onClick = scan, enabled = !state.isActive, modifier = Modifier.heightIn(min = 48.dp)) { Text("Bắt đầu quét") }
            OutlinedButton(onClick = { ble.clearDevices() }, Modifier.heightIn(min = 48.dp)) { Text("Xóa danh sách") }
        }
        if (state.isActive) Text("Đang có phiên GATT: không quét song song.", style = MaterialTheme.typography.labelSmall, color = NvxColors.Warning)
        OutlinedTextField(q.text, { ble.setQuery(q.copy(text = it)) }, label = { Text("Tìm theo tên hoặc UUID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NvxChip("Có tên", q.namedOnly) { ble.setQuery(q.copy(namedOnly = !q.namedOnly)) }
            NvxChip("Kết nối được", q.connectableOnly) { ble.setQuery(q.copy(connectableOnly = !q.connectableOnly)) }
            NvxChip("Ẩn thiết bị cũ", q.hideStale) { ble.setQuery(q.copy(hideStale = !q.hideStale)) }
            NvxChip("Đã lưu", q.savedOnly) { ble.setQuery(q.copy(savedOnly = !q.savedOnly)) }
        }
        Text("Thấy $total thiết bị · hiển thị ${rows.size}. Quảng bá không chứng minh thiết bị là xe Yamaha.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    problem?.let { (kind, msg) ->
        if (kind == ErrorKind.PERMISSION_DENIED) StateBanner(UiState.PermissionDenied, "Chưa đủ quyền Bluetooth/vị trí", msg, "Quét lại", scan)
        else StateBanner(UiState.Error, kind.title, msg)
    }
    if (scanning && rows.isEmpty()) StateBanner(UiState.Loading, "Đang quét…", "Giữ điện thoại gần thiết bị.")
    else if (rows.isEmpty()) StateBanner(UiState.Empty, if (total > 0) "Không thiết bị nào khớp bộ lọc" else "Chưa quét thiết bị nào", "Bấm \"Bắt đầu quét\". Android ≤ 11 cần bật Vị trí.")
    rows.take(50).forEach { r ->
        val rec = r.entry.record
        Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(rec.name ?: "Không tên", style = MaterialTheme.typography.titleSmall)
                    Text("…${rec.address.takeLast(5)} · ${rec.rssi} dBm · thấy ${r.entry.count} lần · ${ts(r.entry.lastSeenMs)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SignalBars(r.entry.smoothedRssi, dim = r.stale)
            }
            if (rec.advertisedServiceUuids.isNotEmpty()) Text("UUID: " + rec.advertisedServiceUuids.joinToString(", ") { it.take(8) }, style = NvxMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (r.stale) StatusChip("DỮ LIỆU CŨ", Tone.Warn)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ble.connect(rec.address) }, enabled = rec.connectable != false && !state.isActive, modifier = Modifier.heightIn(min = 48.dp)) { Text("Kết nối thử") }
                OutlinedButton(onClick = { ble.save(rec) }, Modifier.heightIn(min = 48.dp)) { Text(if (ble.isSaved(rec)) "Đã lưu" else "Lưu") }
            }
        }
    }
    TextButton(onClick = onOpenConnect) { Text("Mở trang kết nối thủ công đầy đủ") }
}

// ------------------------------------------------------------------ 2. GATT Explorer
@Composable
private fun ColumnScope.GattTab(ble: BleViewModel) {
    val ins by ble.inspector.collectAsState()
    val subs by ble.subscriptions.collectAsState()
    var filter by rememberSaveable { mutableStateOf("") }
    val i = ins
    if (i == null) { StateBanner(UiState.Disconnected, "Chưa có phiên GATT", "Kết nối một thiết bị (tab BLE Scanner) để thấy service/characteristic."); return }
    Panel {
        Text("${i.services} service · ${i.characteristics} characteristic · đọc ${i.readable} · nghe ${i.subscribable} · có thuộc tính ghi ${i.writable}", style = MaterialTheme.typography.bodySmall)
        Text("App không ghi vào characteristic có thuộc tính ghi khi chưa có giao thức được xác minh. \"Nghe\" chỉ bật notify.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(filter, { filter = it }, label = { Text("Lọc UUID hoặc tên") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    val f = filter.trim().lowercase()
    val shown = i.rows.filter { f.isEmpty() || it.charUuid.lowercase().contains(f) || it.serviceUuid.lowercase().contains(f) || (it.label ?: "").lowercase().contains(f) }
    if (shown.isEmpty()) StateBanner(UiState.Empty, "Không characteristic nào khớp \"$filter\"")
    shown.groupBy { it.serviceUuid }.forEach { (svc, rs) ->
        Section(KnownUuids.pretty(svc))
        rs.forEach { r ->
            val st = subs[r.charUuid] ?: subs[r.charUuid.lowercase()]
            Panel {
                Text(r.label ?: KnownUuids.pretty(r.charUuid), style = MaterialTheme.typography.titleSmall)
                Text(r.charUuid, style = NvxMono)
                Text(r.properties.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (r.hasWrite) Text("Có thuộc tính ghi — app không ghi.", style = MaterialTheme.typography.labelSmall, color = NvxColors.Warning)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (r.canSubscribe) {
                        if (st == SubscriptionState.ACTIVE || st == SubscriptionState.PENDING) OutlinedButton(onClick = { ble.unsubscribe(r.charUuid) }, Modifier.heightIn(min = 48.dp)) { Text("Ngừng nghe") }
                        else OutlinedButton(onClick = { ble.subscribe(r.charUuid) }, Modifier.heightIn(min = 48.dp)) { Text("Nghe (notify)") }
                        if (st != null) StatusChip(st.name, when (st) { SubscriptionState.ACTIVE -> Tone.Good; SubscriptionState.PENDING -> Tone.Warn; SubscriptionState.FAILED -> Tone.Bad })
                    }
                    if (r.standardInfo) OutlinedButton(onClick = { ble.readInfo(r.charUuid) }, Modifier.heightIn(min = 48.dp)) { Text("Đọc") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 3. Live Packet Monitor
@Composable
private fun ColumnScope.PacketsTab(ble: BleViewModel, onPick: (PayloadRecord, Int) -> Unit) {
    val recording by ble.recording.collectAsState()
    val rec by ble.recStats.collectAsState()
    val nvx = LocalContext.current.applicationContext as vn.nvxconnect.NvxApp
    val pro by nvx.proPrefs.state.collectAsState()
    val now = rememberNowEvery(pro.uiRefreshMs.toLong())
    var q by rememberSaveable { mutableStateOf("") }
    var uuid by rememberSaveable { mutableStateOf("") }
    var minLen by rememberSaveable { mutableStateOf("") }
    var maxLen by rememberSaveable { mutableStateOf("") }
    var srcIdx by rememberSaveable { mutableIntStateOf(0) }
    var flagIdx by rememberSaveable { mutableIntStateOf(0) }
    var sortIdx by rememberSaveable { mutableIntStateOf(0) }
    var paused by rememberSaveable { mutableStateOf(false) }
    val sources = listOf<PayloadSource?>(null) + PayloadSource.values().toList()
    val flags = listOf<PayloadFlag?>(null) + PayloadFlag.values().toList()
    val query = PacketQuery(q, uuid, minLen.toIntOrNull(), maxLen.toIntOrNull(), sources[srcIdx], flags[flagIdx], null, PacketSort.values()[sortIdx])
    // Lọc/sắp xếp ngoài main thread; tạm dừng thì giữ nguyên danh sách để đọc.
    val tick = if (paused) 0L else now
    val result by produceState(Pair(emptyList<PayloadRecord>(), 0), query, tick) {
        value = withContext(Dispatchers.Default) { val all = ble.payloadSnapshot(); PacketFilter.apply(all, query, 150) to all.size }
    }
    val hist by produceState(emptyList<Pair<Int, Int>>(), tick) { value = withContext(Dispatchers.Default) { PacketFilter.lengthHistogram(ble.payloadSnapshot()) } }
    val age = rec.lastMs?.let { now - it }
    Panel {
        Text(DiagnosticsModel.collectionState(recording, age), style = MaterialTheme.typography.bodyMedium, color = if (age != null && age > 5000) NvxColors.Warning else MaterialTheme.colorScheme.onSurface)
        Text("Đã giữ ${result.second} payload · tổng nhận ${rec.payloads}" + if (rec.dropped > 0) " · đã cắt ${rec.dropped} bản ghi cũ nhất" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { paused = !paused }, Modifier.heightIn(min = 48.dp)) { Text(if (paused) "Tiếp tục cập nhật" else "Tạm dừng hiển thị") }
            if (recording) OutlinedButton(onClick = { ble.stopRecording() }, Modifier.heightIn(min = 48.dp)) { Text("Dừng ghi") } else Button(onClick = { ble.startRecording("thủ công") }, Modifier.heightIn(min = 48.dp)) { Text("Bắt đầu ghi") }
        }
    }
    OutlinedTextField(q, { q = it }, label = { Text("Tìm HEX (vd 6B 01) hoặc UUID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(uuid, { uuid = it }, label = { Text("Lọc UUID characteristic") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(minLen, { minLen = it.filter(Char::isDigit).take(3) }, label = { Text("Dài ≥ (byte)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        OutlinedTextField(maxLen, { maxLen = it.filter(Char::isDigit).take(3) }, label = { Text("Dài ≤ (byte)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
    }
    if (hist.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        hist.take(12).forEach { (len, n) -> NvxChip("$len byte ×$n", minLen == len.toString() && maxLen == len.toString()) { if (minLen == len.toString() && maxLen == len.toString()) { minLen = ""; maxLen = "" } else { minLen = len.toString(); maxLen = len.toString() } } }
    }
    Text("Nguồn", style = MaterialTheme.typography.labelSmall); ChipRow(listOf("Tất cả") + PayloadSource.values().map { it.name }, srcIdx) { srcIdx = it }
    Text("Trạng thái", style = MaterialTheme.typography.labelSmall); ChipRow(listOf("Tất cả") + PayloadFlag.values().map { it.name }, flagIdx) { flagIdx = it }
    Text("Sắp xếp", style = MaterialTheme.typography.labelSmall); ChipRow(PacketSort.values().map { it.title }, sortIdx) { sortIdx = it }
    if (result.second == 0) StateBanner(UiState.Empty, "Chưa nhận payload nào", "Kết nối và \"Nghe\" một characteristic để thấy gói. Đăng ký notify thành công chưa phải là đã có gói.")
    else if (result.first.isEmpty()) StateBanner(UiState.Empty, "Không gói nào khớp bộ lọc")
    result.first.forEach { r ->
        Panel {
            Text("#${r.seq} · ${ts(r.timeMs)} · ${r.length} byte · ${r.source.name} · ${r.charUuid?.take(8) ?: "—"}" + if (r.flags.isNotEmpty()) " · " + r.flags.joinToString("/") { it.name } else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MonoBox(r.hex.ifBlank { "(rỗng)" })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onPick(r, 0) }, Modifier.heightIn(min = 44.dp)) { Text("Chọn làm A") }
                OutlinedButton(onClick = { onPick(r, 1) }, Modifier.heightIn(min = 44.dp)) { Text("Chọn làm B") }
            }
        }
    }
    if (result.first.size == 150) Text("Chỉ hiện 150 gói đầu theo bộ lọc; thu hẹp bộ lọc để xem gói khác.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ------------------------------------------------------------------ 4. HEX Inspector
@Composable
private fun ColumnScope.HexTab(a: String, b: String, ta: Long, tb: Long, onA: (String) -> Unit, onB: (String) -> Unit) {
    var offset by rememberSaveable { mutableStateOf("0") }
    val pa = HexDiff.parse(a); val pb = HexDiff.parse(b)
    Panel {
        Text("So sánh hai khung HEX", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(a, onA, label = { Text("Khung A" + if (ta > 0) " · ${ts(ta)}" else "") }, isError = a.isNotBlank() && pa == null, modifier = Modifier.fillMaxWidth(), textStyle = NvxMono)
        OutlinedTextField(b, onB, label = { Text("Khung B" + if (tb > 0) " · ${ts(tb)}" else "") }, isError = b.isNotBlank() && pb == null, modifier = Modifier.fillMaxWidth(), textStyle = NvxMono)
        if ((a.isNotBlank() && pa == null) || (b.isNotBlank() && pb == null)) Text("HEX không hợp lệ: chỉ nhận 0-9 A-F, mỗi byte 1–2 ký tự.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Text("Dán HEX hoặc chọn gói ở tab Live Packets. Byte khác nhau được tô sáng.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (pa == null || pb == null || pa.isEmpty() && pb.isEmpty()) { StateBanner(UiState.Empty, "Cần hai khung HEX hợp lệ để so sánh"); }
    else {
        val d = HexDiff.diff(pa, pb)
        val hl = SpanStyle(background = NvxColors.Warning.copy(alpha = 0.35f), color = MaterialTheme.colorScheme.onSurface)
        fun render(bytes: List<Int>) = buildAnnotatedString {
            bytes.forEachIndexed { i, v -> if (i in d.diffIndices) withStyle(hl) { append("%02X".format(v)) } else append("%02X".format(v)); append(" ") }
        }
        Panel {
            Text(if (d.identical) "Hai khung GIỐNG HỆT (${pa.size} byte)." else "Khác ${d.diffIndices.size} vị trí" + if (d.lengthDiffers) " · độ dài khác (A ${pa.size}, B ${pb.size})" else "", style = MaterialTheme.typography.titleSmall,
                color = if (d.identical) NvxColors.Success else NvxColors.Warning)
            Text("A", style = MaterialTheme.typography.labelSmall); Text(render(pa), style = NvxMono, modifier = Modifier.horizontalScroll(rememberScrollState()))
            Text("B", style = MaterialTheme.typography.labelSmall); Text(render(pb), style = NvxMono, modifier = Modifier.horizontalScroll(rememberScrollState()))
            if (d.diffIndices.isNotEmpty()) Text("Vị trí (0-based): " + d.diffIndices.take(40).joinToString(", "), style = MaterialTheme.typography.labelSmall)
            if (ta > 0 && tb > 0) Text("Cách nhau ${kotlin.math.abs(tb - ta)} ms", style = MaterialTheme.typography.labelSmall)
        }
    }
    val bytes = pa?.takeIf { it.isNotEmpty() }?.let { l -> ByteArray(l.size) { l[it].toByte() } }
    if (bytes != null) Panel {
        Text("Giải nghĩa khung A theo vị trí", style = MaterialTheme.typography.titleSmall)
        MonoBox("HEX  ${ByteViews.hex(bytes)}\nDEC  ${ByteViews.decimal(bytes)}\nBIN  ${ByteViews.binary(bytes)}")
        OutlinedTextField(offset, { offset = it.filter(Char::isDigit).take(3) }, label = { Text("Vị trí byte (0–${bytes.size - 1})") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        val off = offset.toIntOrNull()
        if (off == null || off !in bytes.indices) Text("Vị trí ngoài khung.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        else ByteViews.interpretations(bytes, off).forEach { Text("${it.label} @${it.offset}: ${it.hex} = ${it.unsigned} (có dấu ${it.signed})", style = NvxMono) }
        Text("Các cách đọc này chỉ là phép biến đổi số học, KHÔNG phải ý nghĩa đã xác minh của trường.", style = MaterialTheme.typography.labelSmall, color = NvxColors.Warning)
    }
}

// ------------------------------------------------------------------ 5. Frame Analyzer
@Composable
private fun ColumnScope.FrameTab(ble: BleViewModel) {
    val ycc by ble.yccStats.collectAsState()
    val count by ble.frameCount.collectAsState()
    var text by remember { mutableStateOf<String?>(null) }
    var corr by remember { mutableStateOf<String?>(null) }
    Panel {
        Text("Phân tích khung notify đã nhận ($count khung)", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { text = ble.analysisText() }, enabled = count > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("Phân tích") }
            OutlinedButton(onClick = { corr = ble.correlationText() }, enabled = count > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("Tương quan hành động") }
        }
        if (count == 0) Text("Chưa có khung nào để phân tích.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (ycc.valid + ycc.invalid > 0) Panel {
        Text("Kênh YCC (định dạng khung)", style = MaterialTheme.typography.titleSmall)
        Text("Khung hợp lệ ${ycc.valid} · lỗi ${ycc.invalid} · đứt bộ đếm ${ycc.counterGaps}", style = MaterialTheme.typography.bodyMedium)
        Text("Đã xác thực ĐỊNH DẠNG (tổng kiểm, bộ đếm). Ý nghĩa các trường CHƯA được xác minh.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ycc.lastInvalid?.let { Text("Lỗi gần nhất: $it", style = MaterialTheme.typography.labelSmall) }
    }
    text?.let { Section("Kết quả phân tích"); MonoBox(it) }
    corr?.let { Section("Gợi ý tương quan (chưa phải xác minh)"); MonoBox(it) }
}

// ------------------------------------------------------------------ 6. Decoder Hypotheses
@Composable
private fun ColumnScope.HypothesisTab(ble: BleViewModel) {
    val verdicts by ble.fieldVerdicts.collectAsState()
    val cands by ble.yccCandidates.collectAsState()
    val ycc by ble.yccStats.collectAsState()
    val calSt by ble.calStatus.collectAsState()
    val calMsg by ble.calMessage.collectAsState()
    LaunchedEffect(Unit) { ble.evaluateFields() }
    Notice("Giả thuyết giải mã chỉ là ĐỀ XUẤT từ mẫu dữ liệu. Có công thức ở đây không có nghĩa là đúng.", Tone.Warn)
    if (verdicts.isEmpty()) StateBanner(UiState.Empty, "Chưa có trường nào được đánh giá", "Cần payload đã ghi.")
    verdicts.forEach { v ->
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(v.field.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)); LabBadge(LabStatusMapper.fromVerification(v.status, v.grade))
            }
            v.hypothesis?.let { MonoBox("khung loại ${it.frameType} · dài ${it.expectedLength} byte · byte ${it.byteOffset}..${it.byteOffset + it.byteCount - 1} · ${it.order.name}${if (it.signed) " · có dấu" else ""}\nGiá trị = thô × ${it.scale} + ${it.offsetValue} ${it.unit}"); Text("Cơ sở: ${it.basis}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } ?: Text("Chưa có giả thuyết giải mã cho trường này.", style = MaterialTheme.typography.bodySmall)
            Text(v.grade.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(v.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            v.unmet.take(4).forEach { Text("• chưa đạt: $it", style = MaterialTheme.typography.labelSmall) }
        }
    }
    if (ycc.valid + ycc.invalid > 0) {
        Section("Ứng viên kênh YCC")
        val now = System.currentTimeMillis()
        cands.values.sortedBy { it.id }.forEach { c ->
            val fresh = YccCandidateHub.fresh(cands, c.id, now) != null
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(c.param.name, style = MaterialTheme.typography.titleSmall); LabBadge(LabStatus.HYPOTHESIS) }
                Text(if (fresh) "${"%.1f".format(c.value)} ${c.unit} · độ tin cậy ${c.confidence.name}" else "Chưa có dữ liệu mới", style = MaterialTheme.typography.bodyMedium)
                if (fresh) Text(c.basis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (ycc.valid > 0) Panel {
        Text("Hiệu chỉnh bằng số đo thật", style = MaterialTheme.typography.titleSmall)
        Text("Giữ ổn định rồi nhập số đọc từ đồng hồ vòng tua của xe / đồng hồ vạn năng. Cần ≥4 điểm, phủ rộng, rồi ≥3 điểm kiểm chứng độc lập.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(YccCandidates.RPM_ID to "Vòng tua (rpm)", YccCandidates.BATT_ID to "Điện áp (V)").forEach { (id, title) ->
            var ref by remember(id) { mutableStateOf("") }
            val st = calSt[id]
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text("${st?.quality?.title ?: "Chưa hiệu chỉnh"} · ${st?.points ?: 0} điểm" + (st?.fit?.let { " · sai số ±${"%.2f".format(it.rmse)} · R² ${"%.4f".format(it.r2)}" } ?: "") +
                (if (st?.promotionReady == true) " · ĐỦ ĐIỀU KIỆN đề nghị xác minh" else if (st != null && st.validations.isNotEmpty()) " · kiểm chứng ${st.validations.size}/3" else ""), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(ref, { ref = it.replace(',', '.') }, label = { Text("Số đọc thực tế") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Button(onClick = { ref.toDoubleOrNull()?.let { ble.recordCalibration(id, it, false) } }, Modifier.heightIn(min = 48.dp)) { Text("Ghi điểm") }
                OutlinedButton(onClick = { ref.toDoubleOrNull()?.let { ble.recordCalibration(id, it, true) } }, enabled = st?.quality == CalQuality.GOOD, modifier = Modifier.heightIn(min = 48.dp)) { Text("Kiểm chứng") }
                TextButton(onClick = { ble.undoCalibration(id) }) { Text("Hoàn tác") }
                TextButton(onClick = { ble.resetCalibration(id) }) { Text("Xóa") }
            }
        }
        calMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

// ------------------------------------------------------------------ 7. Evidence & Verification
@Composable
private fun ColumnScope.EvidenceTab(ble: BleViewModel) {
    val findings by ble.findings.collectAsState()
    val verified = findings.count { it.level == EvidenceLevel.VERIFIED }
    Panel {
        Text("Bằng chứng xác thực", style = MaterialTheme.typography.titleSmall)
        LinearProgressIndicator(progress = { if (findings.isEmpty()) 0f else verified.toFloat() / findings.size }, modifier = Modifier.fillMaxWidth())
        Text(if (findings.isEmpty()) "Chưa có kết quả phân tích." else "$verified/${findings.size} mục đã VERIFIED. Không phải phần trăm độ tin cậy.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (findings.isEmpty()) StateBanner(UiState.Empty, "Chưa có báo cáo", "Kết nối và nhận gói để có kết quả.")
    findings.forEach { f -> LogEntryCard(f.title, f.detail) { LabBadge(LabStatusMapper.fromEvidence(f.level)) } }
    VerificationTab(ble)
}

// ------------------------------------------------------------------ 8. Session Logs
@Composable
private fun ColumnScope.SessionTab(ble: BleViewModel) {
    val recording by ble.recording.collectAsState()
    val rec by ble.recStats.collectAsState()
    val events by ble.events.collectAsState()
    val frames by ble.frameLines.collectAsState()
    var q by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val conn by produceState(emptyList<vn.nvxconnect.telemetry.ConnEvent>(), events.size) { value = withContext(Dispatchers.Default) { ble.connEventsSnapshot().takeLast(300) } }
    val streams by produceState(emptyList<Pair<String, vn.nvxconnect.telemetry.StreamStats>>(), rec.payloads) {
        value = withContext(Dispatchers.Default) { ble.streamStatsSnapshot().map { (k, v) -> ((k.charUuid?.take(8) ?: "—") + " · " + k.source.name) to v } }
    }
    Panel {
        Text("Phiên ghi ${rec.session.ifBlank { ble.recorderSessionId() }} · ${ble.recorderSessionLabel().ifBlank { "tự động" }}", style = MaterialTheme.typography.titleSmall)
        Text(if (recording) "ĐANG GHI" else "ĐÃ DỪNG", style = MaterialTheme.typography.labelLarge, color = if (recording) NvxColors.Success else NvxColors.Warning)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (recording) OutlinedButton(onClick = { ble.stopRecording() }, Modifier.heightIn(min = 48.dp)) { Text("Dừng ghi") } else Button(onClick = { ble.startRecording("thủ công") }, Modifier.heightIn(min = 48.dp)) { Text("Phiên mới") }
            OutlinedButton(onClick = { confirm = true }, Modifier.heightIn(min = 48.dp)) { Text("Xóa log") }
        }
    }
    if (streams.isNotEmpty()) Panel {
        Text("Luồng dữ liệu", style = MaterialTheme.typography.titleSmall)
        streams.forEach { (name, s) -> Text("$name: ${s.count} gói · ${s.bytes} byte · trùng ${s.duplicates} · bất thường ${s.abnormal} · ${ts(s.firstMs)}–${ts(s.lastMs)}", style = MaterialTheme.typography.labelSmall) }
    }
    OutlinedTextField(q, { q = it }, label = { Text("Tìm trong log") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val shown = events.filter { q.isBlank() || it.contains(q, ignoreCase = true) }.takeLast(100).reversed()
    if (shown.isEmpty()) StateBanner(UiState.Empty, "Chưa có log" + if (q.isNotBlank()) " khớp bộ lọc" else "")
    shown.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    val ce = conn.filter { q.isBlank() || (it.type.name + " " + (it.detail ?: "")).contains(q, ignoreCase = true) }.takeLast(60).reversed()
    if (ce.isNotEmpty()) { Section("Sự kiện kết nối có cấu trúc"); Panel { ce.forEach { Text("${ts(it.timeMs)} ${it.type.name}" + (it.detail?.let { d -> " · $d" } ?: ""), style = NvxMono) } } }
    if (frames.isNotEmpty()) { Section("Gói gần nhất (đã che)"); Panel { frames.takeLast(30).reversed().forEach { Text(it, style = NvxMono) } } }
    if (confirm) ConfirmDialog("Xóa toàn bộ log phiên?", "Payload đã ghi, nhật ký, kết quả phân tích và nhãn sẽ bị xóa khỏi bộ nhớ. Không ảnh hưởng hành trình.", "Xóa", { confirm = false; ble.clearLog() }, { confirm = false })
}

// ------------------------------------------------------------------ 9. Export Reports
@Composable
private fun ColumnScope.ExportTab(ble: BleViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    /** [heavy] = tạo nội dung ngoài main thread (chỉ cho hàm chỉ đọc bộ ghi đã đồng bộ). Hàm động vào danh sách khung giữ trên main như trước. */
    fun export(name: String, mime: String, heavy: Boolean, make: () -> String) {
        if (busy) return
        busy = true; msg = null
        scope.launch {
            try { val t = if (heavy) withContext(Dispatchers.Default) { make() } else make(); ShareFiles.share(ctx, name, mime, t); msg = "Đã tạo $name" }
            catch (e: Exception) { msg = "Xuất thất bại: ${e.message ?: e.javaClass.simpleName}" }
            busy = false
        }
    }
    if (busy) StateBanner(UiState.Loading, "Đang tạo báo cáo…")
    msg?.let { StateBanner(if (it.startsWith("Xuất thất bại")) UiState.Error else UiState.Success, it) }
    Panel {
        Text("Báo cáo khảo sát", style = MaterialTheme.typography.titleSmall)
        Button(onClick = { export("nvx-auto-report-$stamp.txt", "text/plain", false) { ble.reportText() } }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Báo cáo tổng hợp (TXT)") }
        OutlinedButton(onClick = { export("nvx-ble-log-$stamp.json", "application/json", false) { ble.exportJson() } }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Log BLE (JSON)") }
        OutlinedButton(onClick = { export("nvx-ble-log-$stamp.csv", "text/csv", false) { ble.exportCsv() } }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Log BLE (CSV)") }
    }
    Panel {
        Text("Payload và bằng chứng", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { export("nvx-telemetry-$stamp.json", "application/json", true) { ble.telemetryJson() } }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Payload (JSON)") }
        OutlinedButton(onClick = { export("nvx-telemetry-$stamp.csv", "text/csv", true) { ble.telemetryCsv() } }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Payload (CSV)") }
        OutlinedButton(onClick = { export("nvx-field-evidence-$stamp.md", "text/markdown", true) { ble.evidenceMarkdown() } }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Bảng bằng chứng (Markdown)") }
        OutlinedButton(onClick = { export("nvx-field-evidence-$stamp.csv", "text/csv", true) { ble.evidenceCsv() } }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Bảng bằng chứng (CSV)") }
    }
    Panel {
        Text("Danh mục chức năng", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { export("nvx-capabilities-$stamp.json", "application/json", false) { ble.capabilityJson() } }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Chức năng (JSON)") }
        OutlinedButton(onClick = { export("nvx-capabilities-$stamp.txt", "text/plain", false) { ble.capabilityText() } }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Chức năng (TXT)") }
    }
    Text("Mọi tệp đã che địa chỉ MAC và tên thiết bị. Gói hỗ trợ kỹ thuật nằm ở Công cụ → Chẩn đoán.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
