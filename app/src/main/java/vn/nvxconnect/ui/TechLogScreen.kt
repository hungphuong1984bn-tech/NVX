package vn.nvxconnect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import vn.nvxconnect.telemetry.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun stampOf(ms: Long) = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("vi")).format(Date(ms))
private fun durOf(ms: Long): String { val s = ms / 1000; return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) }
private fun kb(b: Long) = if (b >= 1024 * 1024) String.format(Locale.US, "%.1f MB", b / 1048576.0) else "${b / 1024} KB"
private fun n(v: Double) = if (v == Math.rint(v) && Math.abs(v) < 1e9) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.2f", v)

/**
 * Tab NHẬT KÝ KỸ THUẬT: lịch sử phiên, xem phiên, xuất MỘT ZIP. Chỉ bố trí giao diện và gọi module sẵn có
 * ([DiagViewModel] → AdvancedLogger/DiagSessionStore/DiagExporter/DiagBundle); không có bộ ghi log hay logic thu thập riêng.
 * Đổi tab không dừng ghi log và không đóng phiên: logger sống ở NvxApp, màn hình này chỉ đọc.
 */
@Composable
fun TechLogScreen(onOpenDiagnostics: () -> Unit, vm: DiagViewModel = viewModel()) {
    val status by vm.logger.status.collectAsState()
    val recovered by vm.logger.recovered.collectAsState()
    val message by vm.message.collectAsState()
    val viewed by vm.viewed.collectAsState()
    val sessions by vm.sessions.collectAsState()
    LaunchedEffect(status.state, status.sessionId, status.lastFinishedId) { vm.refresh() }

    Page(null) {
        AppTopBar("Nhật ký kỹ thuật", "Lịch sử phiên · xem phiên · xuất một tệp ZIP")
        message?.let { Notice(it, Tone.Neutral, "Đóng") { vm.message.value = null } }
        if (status.state != DiagState.IDLE)
            Notice("Đang có phiên ghi log (${status.label}, ${status.samples} mẫu). Xuất hoặc đổi tab KHÔNG làm dừng phiên; điều khiển ghi log nằm ở màn hình Dữ liệu xe.", Tone.Good)
        if (recovered.isNotEmpty()) Notice("Đã khôi phục ${recovered.size} phiên bị đóng đột ngột (${recovered.sumOf { it.records }} bản ghi còn nguyên).", Tone.Warn, "Đã hiểu") { vm.dismissRecovered() }
        viewed?.let { SessionView(it) { vm.closeView() } }
        HistoryTab(vm, status, perFormatExport = false, onView = { vm.view(it) }) { opts -> ExportAllPanel(vm, opts, sessions.size) }
        OutlinedButton(onClick = onOpenDiagnostics, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Mở Chẩn đoán (kết nối, quyền, gói hỗ trợ)") }
    }
}

@Composable
private fun ExportAllPanel(vm: DiagViewModel, opts: DiagExportOptions, sessionCount: Int) {
    val ctx = LocalContext.current
    val busy by vm.busy.collectAsState()
    val last by vm.lastBundle.collectAsState()
    var shareError by remember { mutableStateOf<String?>(null) }
    val share = { f: java.io.File ->
        shareError = null
        if (!f.isFile) shareError = "Tệp ZIP không còn trong bộ nhớ tạm (tự dọn sau 1 giờ). Bấm XUẤT TOÀN BỘ DỮ LIỆU để tạo lại."
        else runCatching { ShareFile.share(ctx, f, "application/zip", "Chia sẻ gói dữ liệu NVX Connect") }
            .onFailure { shareError = "Không mở được khung chia sẻ: " + (it.message ?: it.javaClass.simpleName) }
        Unit
    }
    Panel {
        Text("Xuất toàn bộ dữ liệu", style = MaterialTheme.typography.titleSmall)
        Text("Tạo MỘT tệp ZIP (NVX-Connect-v<phiên bản>-<ngày>-<giờ>.zip) gồm báo cáo HTML, telemetry CSV, events CSV, metadata JSON, biểu đồ (khi đủ mẫu thật) và README cho $sessionCount phiên. " +
            "Tệp được mở lại và kiểm tra trước khi báo thành công. Không có dữ liệu giả; nhật ký gốc không bị xóa; phiên đang ghi vẫn tiếp tục.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { vm.exportAll(opts) { f -> share(f) } }, enabled = !busy && sessionCount > 0, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(if (busy) "ĐANG TẠO VÀ KIỂM TRA ZIP…" else "XUẤT TOÀN BỘ DỮ LIỆU")
        }
        if (sessionCount == 0) Text("Chưa có phiên nào để xuất. Bắt đầu ghi log ở màn hình Dữ liệu xe.", style = MaterialTheme.typography.labelSmall)
        last?.let { b ->
            Text("Gói gần nhất: ${b.file.name} · ${b.sessions} phiên · ${b.entries} tệp · ${kb(b.file.length())}", style = MaterialTheme.typography.labelSmall)
            OutlinedButton(onClick = { share(b.file) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Chia sẻ lại gói này") }
        }
        shareError?.let { Notice(it, Tone.Warn) }
    }
}

/** Tóm tắt một phiên (số liệu thật từ DiagSummary; không nội suy). */
@Composable
private fun SessionView(s: DiagSummary, onClose: () -> Unit) {
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(s.meta.label.ifBlank { s.meta.id }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            StatusChip(s.state.title, if (s.state == DiagSessionState.RECORDING) Tone.Good else Tone.Neutral)
        }
        Text(s.verificationText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Bắt đầu ${stampOf(s.startedWallMs)} · ghi ${durOf(s.activeMs)} (tạm dừng ${durOf(s.pausedMs)})", style = MaterialTheme.typography.bodySmall)
        Text("${s.totalRecords} bản ghi · ${s.sampleCount} mẫu · mất liên kết ${s.linkLost} · sẵn sàng ${s.readyCount} · nối lại ${s.reconnectAttempts}", style = MaterialTheme.typography.bodySmall)
        if (s.errorCounts.isNotEmpty()) Text("Lỗi: " + s.errorCounts.entries.joinToString { "${it.key}×${it.value}" }, style = MaterialTheme.typography.bodySmall)
        if (s.droppedLimit + s.droppedLowStorage + s.droppedError > 0) Notice("Có mẫu bị loại (giới hạn ${s.droppedLimit}, thiếu bộ nhớ ${s.droppedLowStorage}, lỗi ghi ${s.droppedError}).", Tone.Warn)
        if (s.series.isEmpty()) Text("Chưa có mẫu số liệu nào.", style = MaterialTheme.typography.bodySmall)
        s.series.forEach { x ->
            Text("• ${x.title} — ${x.cls.title}: ${x.count} mẫu, ${n(x.min)}…${n(x.max)} ${x.unit} (TB ${n(x.mean)})" +
                if (x.gaps.isNotEmpty()) " · thiếu dữ liệu ${x.gaps.size} khoảng" else "", style = MaterialTheme.typography.labelSmall)
        }
        if (s.notes.isNotEmpty()) Text("Ghi chú: " + s.notes.size + " (xem đầy đủ trong ZIP)", style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = onClose) { Text("Đóng") }
    }
}
