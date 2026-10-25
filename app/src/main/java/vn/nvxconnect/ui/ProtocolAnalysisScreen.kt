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
import vn.nvxconnect.auto.AutoState
import vn.nvxconnect.auto.EvidenceLevel
import vn.nvxconnect.helpcenter.HelpContent
import vn.nvxconnect.helpcenter.HelpErrorMapper
import vn.nvxconnect.protocol.ConnState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phân tích giao thức: tiến trình thật (từ AutoStatus), kết quả theo mức bằng chứng, log, chi tiết GATT, xuất báo cáo.
 * Không có phần trăm tin cậy: chỉ đếm số mục đã VERIFIED trên tổng số mục đã phân tích.
 */
@Composable
fun ProtocolAnalysisScreen(ble: BleViewModel, onBack: () -> Unit, onOpenCapabilities: () -> Unit = {}, onOpenHelp: (String?) -> Unit = {}) {
    val ctx = LocalContext.current
    val status by ble.autoStatus.collectAsState()
    val state by ble.connState.collectAsState()
    val inspector by ble.inspector.collectAsState()
    val findings by ble.findings.collectAsState()
    val events by ble.events.collectAsState()
    val frames by ble.frameLines.collectAsState()
    val ycc by ble.yccStats.collectAsState()
    val yccCands by ble.yccCandidates.collectAsState()
    val calSt by ble.calStatus.collectAsState()
    val calMsg by ble.calMessage.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    var showHex by remember { mutableStateOf(false) }
    LaunchedEffect(state) { ble.refreshReport() }

    val step = when (status.state) {
        AutoState.IDLE, AutoState.SCANNING, AutoState.FAILED, AutoState.CONNECTION_BUSY -> 0
        AutoState.CANDIDATE_FOUND -> 1
        AutoState.GATT_CONNECTED -> 2
        AutoState.BONDED, AutoState.AUTHENTICATED -> 4
        AutoState.PROTOCOL_VERIFIED, AutoState.DATA_AVAILABLE -> 5
    }
    val exploring = state is ConnState.Connecting || state is ConnState.Connected || state is ConnState.DiscoveringServices
    val verified = findings.count { it.level == EvidenceLevel.VERIFIED }

    Page(null) {
        AppTopBar("Phân tích giao thức", onBack = onBack, actions = { HelpButton({ onOpenHelp(HelpContent.Id.EXPORT_DIAG) }) })
        // Lỗi thực tế → nút dẫn tới đúng bài khắc phục (chỉ hiện khi đang có lỗi/ cảnh báo, không bịa nút khi mọi thứ bình thường).
        HelpErrorMapper.forState(state, ble.linkStatus.collectAsState().value)?.let { id ->
            Notice("Phát hiện vấn đề với kết nối hiện tại.", Tone.Warn, "Tìm cách khắc phục") { onOpenHelp(id) }
        }
        StepRow(listOf("Khảo sát", "GATT", "Phân tích", "Xác thực", "Kết nối"), step)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ScanRadarAnimation(active = exploring) }
        Text(status.headline, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
        status.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        Segmented(listOf("Tổng quan", "Log", "Chi tiết", "Xác minh"), tab) { tab = it }
        when (tab) {
            0 -> {
                if (ycc.valid + ycc.invalid > 0) Panel {
                    Text("Kênh YCC (khung dữ liệu)", style = MaterialTheme.typography.titleSmall)
                    Text("Khung hợp lệ ${ycc.valid} · lỗi ${ycc.invalid} · đứt bộ đếm ${ycc.counterGaps}", style = MaterialTheme.typography.bodyMedium)
                    Text("Đã xác thực ĐỊNH DẠNG (tổng kiểm, bộ đếm). Ý nghĩa các trường CHƯA được xác minh.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ycc.lastInvalid?.let { Text("Lỗi gần nhất: $it", style = MaterialTheme.typography.labelSmall) }
                    val now = System.currentTimeMillis()
                    yccCands.values.sortedBy { it.id }.forEach { c ->
                        val fresh = vn.nvxconnect.protocol.YccCandidateHub.fresh(yccCands, c.id, now) != null
                        Text(if (fresh) "${c.param.name}: ${"%.1f".format(c.value)} ${c.unit} · ${c.label}" else "${c.param.name}: chưa có dữ liệu mới",
                            style = MaterialTheme.typography.bodyMedium)
                        if (fresh) Text(c.basis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (ycc.valid > 0) Panel {
                    Text("Hiệu chỉnh bằng số đo thật", style = MaterialTheme.typography.titleSmall)
                    Text("Giữ ổn định rồi nhập số đọc từ đồng hồ vòng tua của xe / đồng hồ vạn năng. Cần ≥4 điểm, phủ rộng, rồi ≥3 điểm kiểm chứng độc lập.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf(vn.nvxconnect.protocol.YccCandidates.RPM_ID to "Vòng tua (rpm)", vn.nvxconnect.protocol.YccCandidates.BATT_ID to "Điện áp (V)").forEach { (id, title) ->
                        var ref by remember(id) { mutableStateOf("") }
                        val st = calSt[id]
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text("${st?.quality?.title ?: "Chưa hiệu chỉnh"} · ${st?.points ?: 0} điểm" +
                            (st?.fit?.let { " · sai số ±${"%.2f".format(it.rmse)} · R² ${"%.4f".format(it.r2)}" } ?: "") +
                            (if (st?.promotionReady == true) " · ĐỦ ĐIỀU KIỆN đề nghị xác minh" else if (st != null && st.validations.isNotEmpty()) " · kiểm chứng ${st.validations.size}/3" else ""),
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = ref, onValueChange = { ref = it.replace(',', '.') }, label = { Text("Số đọc thực tế") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { ref.toDoubleOrNull()?.let { ble.recordCalibration(id, it, false) } }) { Text("Ghi điểm") }
                            OutlinedButton(onClick = { ref.toDoubleOrNull()?.let { ble.recordCalibration(id, it, true) } }, enabled = st?.quality == vn.nvxconnect.protocol.CalQuality.GOOD) { Text("Kiểm chứng") }
                            TextButton(onClick = { ble.undoCalibration(id) }) { Text("Hoàn tác") }
                            TextButton(onClick = { ble.resetCalibration(id) }) { Text("Xóa") }
                        }
                    }
                    calMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                Panel {
                    Text("Bằng chứng xác thực", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(progress = { if (findings.isEmpty()) 0f else verified.toFloat() / findings.size }, modifier = Modifier.fillMaxWidth())
                    Text(if (findings.isEmpty()) "Chưa có kết quả phân tích." else "$verified/${findings.size} mục đã VERIFIED. Không phải phần trăm độ tin cậy.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                inspector?.let { ins ->
                    Panel {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Service đã phát hiện", style = MaterialTheme.typography.titleSmall); Text("${ins.services}")
                        }
                        ins.rows.groupBy { it.serviceUuid }.forEach { (uuid, rows) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(uuid.take(8), style = MaterialTheme.typography.bodyMedium)
                                Text("${rows.size} characteristic", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } ?: EmptyState("Chưa có phiên GATT", "Kết nối thử một ứng viên ở màn Tự tìm và kết nối xe để thấy service/characteristic.")
                SectionHeader("Kết quả phân tích")
                if (findings.isEmpty()) EmptyState("Chưa có báo cáo")
                findings.forEach { f -> LogEntryCard(f.title, f.detail) { ProtocolStatusBadge(f.level) } }
            }
            1 -> {
                OutlinedTextField(q, { q = it }, label = { Text("Tìm trong log") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val shown = events.filter { q.isBlank() || it.contains(q, ignoreCase = true) }.takeLast(100).reversed()
                if (shown.isEmpty()) EmptyState("Chưa có log" + if (q.isNotBlank()) " khớp bộ lọc" else "")
                shown.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                DisclosureHeader("Gói dữ liệu (HEX)", "${frames.size} dòng gần nhất", showHex) { showHex = !showHex }
                if (showHex) Panel { frames.takeLast(50).reversed().forEach { Text(it, style = MaterialTheme.typography.labelSmall) } }
            }
            2 -> {
                val ins = inspector
                if (ins == null) EmptyState("Chưa có phiên GATT")
                else {
                    Panel {
                        Text("${ins.services} service · ${ins.characteristics} characteristic · đọc ${ins.readable} · nghe ${ins.subscribable} · có thuộc tính ghi ${ins.writable}", style = MaterialTheme.typography.bodySmall)
                        Text("App không ghi vào characteristic có thuộc tính ghi khi chưa có giao thức được xác minh.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ins.rows.forEach { r ->
                        Panel {
                            Text(r.charUuid, style = MaterialTheme.typography.bodySmall)
                            Text(r.properties.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            else -> VerificationTab(ble)
        }
        OutlinedButton(onClick = onOpenCapabilities, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Danh mục chức năng xe/app · đối chiếu") }
        if (state.isActive) OutlinedButton(onClick = { ble.disconnect() }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Ngắt kết nối") }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ShareFiles.share(ctx, "nvx-ble-log-$stamp.json", "application/json", ble.exportJson()) }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Xuất log") }
            Button(onClick = { ShareFiles.share(ctx, "nvx-auto-report-$stamp.txt", "text/plain", ble.reportText()) }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Lưu báo cáo") }
        }
        Text("Log đã được che thông tin nhạy cảm trước khi chia sẻ.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
