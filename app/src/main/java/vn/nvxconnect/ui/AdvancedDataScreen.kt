package vn.nvxconnect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import vn.nvxconnect.telemetry.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun classTone(c: DiagClass): Tone = when (c) {
    DiagClass.REAL -> Tone.Good; DiagClass.DERIVED -> Tone.Neutral; DiagClass.UNSUPPORTED -> Tone.Neutral; DiagClass.INVALID -> Tone.Bad; DiagClass.NO_DATA -> Tone.Warn
}

private fun num(v: Double, unit: String): String {
    val t = if (v == Math.rint(v) && Math.abs(v) < 1e9) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.2f", v)
    return if (unit.isBlank()) t else "$t $unit"
}

private fun clock(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
private fun stamp(ms: Long) = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("vi")).format(Date(ms))
private fun dur(ms: Long): String { val s = ms / 1000; return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) }
private fun sizeText(b: Long) = if (b >= 1024 * 1024) String.format(Locale.US, "%.1f MB", b / 1048576.0) else "${b / 1024} KB"

/** Một dòng số liệu: tên, giá trị (chỉ khi dùng được), nhãn phân loại; số cũ/không hợp lệ KHÔNG hiện như số hiện tại. */
@Composable
private fun DiagRowView(r: DiagRow) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(r.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(if (r.usable) num(r.value!!, r.unit) else "—", style = MaterialTheme.typography.titleSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(r.cls.title, classTone(r.cls))
            r.source?.let { Text(it.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        val extra = if (r.cls == DiagClass.INVALID && r.lastUnusableValue != null) "Giá trị cuối ${num(r.lastUnusableValue, r.unit)} — KHÔNG dùng như số hiện tại. " else ""
        Text(extra + r.note + (r.updatedWallMs?.let { " · cập nhật ${clock(it)}" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Dữ liệu xe chuyên sâu (Advanced Vehicle Data Logger). CHỈ ĐỌC: không có nút nào gửi lệnh tới xe. Hai tab: trực tiếp + lịch sử/xuất.
 * Số liệu được phân loại thực / suy tính / chưa hỗ trợ / không hợp lệ; chưa có bằng chứng xe thật thì luôn hiện "Chưa xác minh trên xe thật".
 */
@Composable
fun AdvancedDataScreen(onBack: (() -> Unit)? = null, onOpenConnect: () -> Unit, vm: DiagViewModel = viewModel(),
                       showHistory: Boolean = true, header: @Composable ColumnScope.() -> Unit = {}) {
    val ctx = LocalContext.current
    val status by vm.logger.status.collectAsState()
    val recovered by vm.logger.recovered.collectAsState()
    val message by vm.message.collectAsState()
    val now = rememberNowEvery(1000L)
    val snap = remember(now) { vm.collector.snapshot(System.currentTimeMillis()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(status.state, status.sessionId, status.lastFinishedId) { vm.refresh() }

    Page(null) {
        AppTopBar("Dữ liệu xe chuyên sâu", "Chỉ đọc · không gửi lệnh tới xe · không tải lên máy chủ", onBack = onBack)
        header()
        if (!snap.link.verifiedOnVehicle) Notice(DiagVerification.NOT_VERIFIED_TEXT + ". Chưa có giao thức nào được kiểm chứng trên xe thật nên mọi số liệu từ xe có thể là \"Chưa hỗ trợ\"; app không đoán UUID, PID hay ý nghĩa byte.", Tone.Warn)
        message?.let { Notice(it, Tone.Neutral, "Đóng") { vm.message.value = null } }
        if (recovered.isNotEmpty()) Notice("Đã khôi phục ${recovered.size} phiên bị đóng đột ngột (${recovered.sumOf { it.records }} bản ghi còn nguyên). Xem ở " + (if (showHistory) "tab Lịch sử" else "tab Nhật ký kỹ thuật") + "; bạn có thể xuất hoặc tiếp tục phiên.", Tone.Warn, "Đã hiểu") { vm.dismissRecovered() }
        if (showHistory) Segmented(listOf("Trực tiếp", "Lịch sử & xuất"), tab) { tab = it }
        if (!showHistory || tab == 0) LiveTab(vm, snap, status, onOpenConnect) else HistoryTab(vm, status)
    }
}

@Composable
private fun LiveTab(vm: DiagViewModel, snap: DiagSnapshot, st: DiagStatus, onOpenConnect: () -> Unit) {
    val l = snap.link
    Section("Trạng thái liên kết BLE")
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(l.stateText, if (l.ready) Tone.Good else Tone.Neutral)
            StatusChip(if (l.protocolVerified) "Giao thức đã xác minh" else "Giao thức CHƯA xác minh", if (l.protocolVerified) Tone.Good else Tone.Warn)
        }
        if (!l.ready) OutlinedButton(onClick = onOpenConnect, Modifier.heightIn(min = 48.dp)) { Text("Mở màn hình kết nối") }
        Text("Gói gần nhất: " + (l.lastFrameWallMs?.let { "${clock(it)} (${(snap.wallMs - it) / 1000} giây trước)" } ?: "chưa có"), style = MaterialTheme.typography.bodySmall)
        Text("Số mẫu đã ghi: ${st.samples} · cập nhật ghi cuối: ${st.lastWriteWallMs?.let { clock(it) } ?: "—"}", style = MaterialTheme.typography.bodySmall)
    }
    snap.rows.groupBy { it.group }.forEach { (group, rows) ->
        Section(group)
        Panel { rows.forEachIndexed { i, r -> if (i > 0) HorizontalDivider(); DiagRowView(r) } }
    }
    Section("Ghi log")
    LoggerControls(vm, st)
}

@Composable
private fun LoggerControls(vm: DiagViewModel, st: DiagStatus) {
    var label by rememberSaveable { mutableStateOf("") }
    var intervalIdx by rememberSaveable { mutableIntStateOf(1) }
    var note by rememberSaveable { mutableStateOf("") }
    var askStop by remember { mutableStateOf(false) }
    val intervals = listOf(500L, 1000L, 2000L, 5000L)
    Panel {
        when (st.state) {
            DiagState.IDLE -> {
                OutlinedTextField(label, { label = it }, label = { Text("Nhãn phiên (vd: Chạy thử ga, xe giật)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Chu kỳ lấy mẫu (nhanh hơn = tốn pin và dung lượng hơn)", style = MaterialTheme.typography.labelSmall)
                ChipRow(listOf("0,5 giây", "1 giây", "2 giây", "5 giây"), intervalIdx) { intervalIdx = it }
                Button(onClick = { vm.start(label, intervals[intervalIdx]) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Bắt đầu ghi log") }
            }
            DiagState.RECORDING, DiagState.PAUSED -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(if (st.state == DiagState.RECORDING) "ĐANG GHI" else "TẠM DỪNG", if (st.state == DiagState.RECORDING) Tone.Good else Tone.Warn)
                    Text("${st.label} · ${dur(st.activeMs)}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Mẫu ${st.samples} · sự kiện BLE ${st.bleEvents} · lỗi ${st.errors} · ghi chú ${st.notes} · dấu thiếu dữ liệu ${st.missingMarks} · ${sizeText(st.sessionBytes)}", style = MaterialTheme.typography.labelSmall)
                if (st.health != DiagHealth.OK) Notice(st.health.title + " — đã loại ${st.counters.dropped} bản ghi (có đếm). Nhật ký cũ không bị xóa; hãy xuất rồi xóa thủ công phiên cũ.", Tone.Bad)
                if (st.state == DiagState.RECORDING) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.pause() }, Modifier.weight(1f).height(56.dp)) { Text("Tạm dừng") }
                    Button(onClick = { askStop = true }, Modifier.weight(1f).height(56.dp)) { Text("Kết thúc") }
                } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.resume() }, Modifier.weight(1f).height(56.dp)) { Text("Tiếp tục") }
                    OutlinedButton(onClick = { askStop = true }, Modifier.weight(1f).height(56.dp)) { Text("Kết thúc") }
                }
                OutlinedTextField(note, { note = it }, label = { Text("Ghi chú sự cố (vd: giật khi ga 40 km/h)") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { if (vm.note(note)) note = "" }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Lưu ghi chú vào phiên") }
                Text("Khi tạm dừng, mẫu và sự kiện BLE không được ghi (có đếm số bị bỏ); ghi chú vẫn lưu được.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (askStop) ConfirmDialog("Kết thúc phiên?", "Phiên sẽ được đóng và lưu. Bạn xuất hoặc xem lại ở tab Lịch sử.", "Kết thúc", { askStop = false; vm.stop() }, { askStop = false })
}

private data class ExportTarget(val id: String, val format: DiagFormat)

/**
 * Lịch sử phiên + tùy chọn xuất. [perFormatExport] = false ẩn các nút xuất từng định dạng (tab Nhật ký kỹ thuật chỉ xuất MỘT ZIP);
 * [onView] thêm nút "Xem"; [extra] vẽ thêm nội dung ngay dưới tùy chọn xuất (nhận đúng các tùy chọn đang chọn).
 */
@Composable
internal fun HistoryTab(vm: DiagViewModel, st: DiagStatus, perFormatExport: Boolean = true, onView: ((String) -> Unit)? = null,
                        extra: @Composable (DiagExportOptions) -> Unit = {}) {
    val ctx = LocalContext.current
    val sessions by vm.sessions.collectAsState()
    val usage by vm.usage.collectAsState()
    val busy by vm.busy.collectAsState()
    var redact by rememberSaveable { mutableStateOf(true) }
    var notes by rememberSaveable { mutableStateOf(true) }
    var relative by rememberSaveable { mutableStateOf(false) }
    var delId by remember { mutableStateOf<String?>(null) }

    Section("Tùy chọn xuất")
    Panel {
        LabeledSwitch("Ẩn thông tin nhận dạng (mã thiết bị, MAC, VIN, email trong ghi chú)", redact) { redact = it }
        LabeledSwitch("Kèm ghi chú sự cố", notes) { notes = it }
        LabeledSwitch("Chỉ dùng thời gian tương đối (ẩn ngày giờ thật)", relative) { relative = it }
        Text("Xuất = tạo tệp rồi mở khung chia sẻ của Android; chỉ gửi đi nơi bạn chọn. App không tự tải gì lên máy chủ.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    extra(DiagExportOptions(redact, notes, relative))
    usage?.let { u -> Text("Đang dùng ${sizeText(u.totalBytes)} / ${sizeText(u.maxTotalBytes)} cho ${u.sessions} phiên" + (u.freeBytes?.let { " · bộ nhớ trống ${sizeText(it)}" } ?: "") + ". App không tự xóa nhật ký.", style = MaterialTheme.typography.labelSmall) }
    Section("Lịch sử phiên")
    if (sessions.isEmpty()) StateBanner(UiState.Empty, "Chưa có phiên nào", "Bắt đầu ghi ở màn hình Dữ liệu xe.")
    sessions.forEach { s ->
        val live = s.id == st.sessionId && st.state != DiagState.IDLE
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.meta.label.ifBlank { s.id }, style = MaterialTheme.typography.titleSmall)
                    Text("${stamp(s.meta.startedWallMs)} · ${sizeText(s.sizeBytes)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusChip(s.state.title, when (s.state) { DiagSessionState.RECORDING -> Tone.Good; DiagSessionState.STOPPED -> Tone.Neutral; else -> Tone.Warn })
            }
            Text(if (s.meta.verifiedOnVehicle) DiagVerification.VERIFIED_TEXT else DiagVerification.NOT_VERIFIED_TEXT, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val opts = DiagExportOptions(redact, notes, relative)
            if (perFormatExport) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DiagFormat.values().forEach { f ->
                    OutlinedButton(onClick = { vm.export(s.id, f, opts) { file, mime -> ShareFile.share(ctx, file, mime) } }, Modifier.weight(1f), enabled = !busy, contentPadding = PaddingValues(horizontal = 2.dp)) { Text(f.ext.uppercase(), maxLines = 1) }
                }
            }
            if (s.state == DiagSessionState.INTERRUPTED && st.state == DiagState.IDLE) OutlinedButton(onClick = { vm.continueInterrupted(s.id) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Tiếp tục phiên này") }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onView != null) TextButton(onClick = { onView(s.id) }) { Text("Xem phiên") }
                if (!live) TextButton(onClick = { delId = s.id }) { Text("Xóa phiên") }
            }
        }
    }
    delId?.let { id -> ConfirmDialog("Xóa phiên $id?", "Tệp nhật ký sẽ bị xóa khỏi điện thoại và không khôi phục được. Hãy xuất trước nếu cần.", "Xóa", { vm.delete(id); delId = null }, { delId = null }) }
}
