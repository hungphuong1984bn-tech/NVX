package vn.nvxconnect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.nvxconnect.NvxApp
import vn.nvxconnect.bluetooth.BleBlocker
import vn.nvxconnect.bluetooth.BlePreflight
import vn.nvxconnect.helpcenter.CheckStatus
import vn.nvxconnect.helpcenter.ConnectionChecklist
import vn.nvxconnect.pro.DiagnosticsModel
import vn.nvxconnect.pro.LabStatus
import vn.nvxconnect.pro.LabStatusMapper
import vn.nvxconnect.protocol.ConnState
import vn.nvxconnect.protocol.VehicleParam
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostics Center: chẩn đoán KẾT NỐI điện thoại ↔ thiết bị. Không kết luận lỗi xe, không tạo mã lỗi ECU:
 * mã DTC chỉ hiện khi có giá trị DTC thật đã xác minh (hiện chưa có giao thức nào cung cấp).
 */
@Composable
fun DiagnosticsScreen(ble: BleViewModel, onBack: () -> Unit, onOpenHelp: (String?) -> Unit, onOpenLab: () -> Unit) {
    val ctx = LocalContext.current
    val nvx = ctx.applicationContext as NvxApp
    val scope = rememberCoroutineScope()
    val conn by ble.connState.collectAsState()
    val link by ble.linkStatus.collectAsState()
    val auth by ble.auth.collectAsState()
    val rec by ble.recStats.collectAsState()
    val recording by ble.recording.collectAsState()
    val verdicts by ble.fieldVerdicts.collectAsState()
    val tele by nvx.telemetry.state.collectAsState()
    val now = rememberNowEvery(1000)
    LaunchedEffect(Unit) { ble.evaluateFields() }
    val blockers = remember(now / 2000) { runCatching { BlePreflight.check(ctx) }.getOrNull() }
    val checklist = remember(now / 2000, conn, link) { ble.diagnosticsChecklist() }
    val issues by produceState(emptyList<vn.nvxconnect.pro.DiagIssue>(), now / 3000) { value = withContext(Dispatchers.Default) { DiagnosticsModel.issues(ble.logEntriesSnapshot()) } }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    fun export(name: String, mime: String, make: () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try { val t = withContext(Dispatchers.Default) { make() }; ShareFiles.share(ctx, name, mime, t); msg = "Đã tạo $name" } catch (e: Exception) { msg = "Xuất thất bại: ${e.message ?: e.javaClass.simpleName}" }
            busy = false
        }
    }

    Page(null) {
        AppTopBar("Chẩn đoán", "Kết nối · quyền · GATT · thu thập", onBack = onBack)
        Section("Tổng quan")
        Panel {
            if (blockers == null) StatusRow("Bluetooth và quyền", "Chưa kiểm tra được", Tone.Warn)
            else {
                StatusRow("Bluetooth", if (BleBlocker.BLUETOOTH_OFF in blockers || BleBlocker.NO_BLE_HARDWARE in blockers) "Không khả dụng" else "Bật, hỗ trợ BLE", if (BleBlocker.BLUETOOTH_OFF in blockers || BleBlocker.NO_BLE_HARDWARE in blockers) Tone.Bad else Tone.Good)
                StatusRow("Quyền Bluetooth", if (BleBlocker.PERMISSION_MISSING in blockers) "Thiếu quyền" else "Đã cấp", if (BleBlocker.PERMISSION_MISSING in blockers) Tone.Bad else Tone.Good)
                StatusRow("Dịch vụ vị trí (Android ≤ 11)", if (BleBlocker.LOCATION_SERVICES_OFF in blockers) "Đang tắt" else "Không bị chặn", if (BleBlocker.LOCATION_SERVICES_OFF in blockers) Tone.Bad else Tone.Good)
            }
            StatusRow("Quyền vị trí chính xác (GPS)", if (Perms.hasPreciseLocation(ctx)) "Đã cấp" else "Chưa cấp", if (Perms.hasPreciseLocation(ctx)) Tone.Good else Tone.Warn)
            StatusRow("Trạng thái GATT", if (conn is ConnState.Ready) "Sẵn sàng" else conn.code, if (conn is ConnState.Ready) Tone.Good else if (conn is ConnState.Error) Tone.Bad else if (conn.isActive) Tone.Warn else Tone.Neutral)
            StatusRow("Xác thực thiết bị", auth.headline, Tone.Neutral)
            StatusRow("Giao thức xe", link.headline, if (link.protocolVerified) Tone.Good else Tone.Warn)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Perms.openBluetoothSettings(ctx) }, Modifier.heightIn(min = 48.dp)) { Text("Cài đặt Bluetooth") }
                OutlinedButton(onClick = { Perms.openAppSettings(ctx) }, Modifier.heightIn(min = 48.dp)) { Text("Quyền ứng dụng") }
            }
        }

        Section("Chẩn đoán từng bước")
        Panel {
            val first = ConnectionChecklist.firstProblem(checklist)
            Text("Đạt ${ConnectionChecklist.passedCount(checklist)} / ${checklist.size}. Mỗi bước chỉ ĐẠT khi có bằng chứng thật.", style = MaterialTheme.typography.bodySmall)
            checklist.forEach { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(r.step.title, style = MaterialTheme.typography.bodyMedium)
                        Text(r.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusChip(when (r.status) { CheckStatus.PASSED -> "ĐẠT"; CheckStatus.FAILED -> "LỖI"; CheckStatus.PENDING -> "CHƯA"; CheckStatus.UNSUPPORTED -> "CHƯA HỖ TRỢ" },
                        when (r.status) { CheckStatus.PASSED -> Tone.Good; CheckStatus.FAILED -> Tone.Bad; CheckStatus.PENDING -> Tone.Neutral; CheckStatus.UNSUPPORTED -> Tone.Warn })
                }
            }
            if (first != null) OutlinedButton(onClick = { onOpenHelp(first.guideId) }, Modifier.heightIn(min = 48.dp)) { Text("Khắc phục: ${first.step.title}") }
            else Text("Bảy bước kết nối đầu đều đạt.", style = MaterialTheme.typography.bodySmall, color = NvxColors.Success)
        }

        Section("Nhật ký lỗi")
        if (issues.isEmpty()) StateBanner(UiState.Success, "Không có lỗi nào trong nhật ký phiên này", "Chỉ lỗi kết nối/quét/đọc do app ghi nhận. Không có nghĩa là xe không có lỗi.")
        issues.take(30).forEach { i ->
            Panel {
                Text(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(i.timeMs)) + (i.state?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Nguyên nhân: ${i.cause}", style = MaterialTheme.typography.bodyMedium)
                Text("Gợi ý: ${i.hint}", style = MaterialTheme.typography.bodySmall, color = NvxColors.Primary)
            }
        }

        Section("Báo cáo thu thập và xác minh")
        Panel {
            StatusRow("Thu thập", DiagnosticsModel.collectionState(recording, rec.lastMs?.let { now - it }), if (recording && rec.lastMs != null && now - rec.lastMs!! <= 5000) Tone.Good else Tone.Warn)
            StatusRow("Payload đã ghi", "${rec.payloads}" + if (rec.dropped > 0) " (cắt ${rec.dropped})" else "", Tone.Neutral)
            if (verdicts.isEmpty()) Text("Chưa đánh giá trường telemetry nào.", style = MaterialTheme.typography.bodySmall)
            else LabStatus.values().forEach { s ->
                val n = verdicts.count { LabStatusMapper.fromVerification(it.status, it.grade) == s }
                if (n > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { LabBadge(s); Text("$n trường") }
            }
            val dtc = tele.values[VehicleParam.DTC]
            StatusRow("Mã lỗi xe (DTC)", if (dtc == null) "Chưa có dữ liệu DTC thật" else "Đã nhận giá trị DTC (xem Telemetry)", if (dtc == null) Tone.Neutral else Tone.Warn)
            Text("App không tự tạo mã lỗi ECU và không kết luận lỗi xe khi chưa có dữ liệu DTC thật đã xác minh.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onOpenLab, Modifier.heightIn(min = 48.dp)) { Text("Mở Protocol Lab") }
        }

        Section("Gói hỗ trợ kỹ thuật")
        if (busy) StateBanner(UiState.Loading, "Đang tạo gói…")
        msg?.let { StateBanner(if (it.startsWith("Xuất thất bại")) UiState.Error else UiState.Success, it) }
        Panel {
            Text("Gồm: các bước kiểm tra, trạng thái từng giá trị xe, mức tích hợp TFT, dòng thời gian lỗi/trạng thái. Địa chỉ MAC đã che; không có tọa độ GPS, VIN hay dữ liệu hành trình.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { export("nvx-diagnostics-$stamp.json", "application/json") { ble.diagnosticsJson() } }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Xuất gói hỗ trợ (JSON)") }
            OutlinedButton(onClick = { export("nvx-diagnostics-$stamp.csv", "text/csv") { ble.diagnosticsCsv() } }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Xuất gói hỗ trợ (CSV)") }
        }
    }
}
