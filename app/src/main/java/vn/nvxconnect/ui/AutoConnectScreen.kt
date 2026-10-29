package vn.nvxconnect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import vn.nvxconnect.NvxApp
import vn.nvxconnect.auto.*
import vn.nvxconnect.protocol.ConnState
import vn.nvxconnect.protocol.SubscriptionState
import vn.nvxconnect.protocol.TftBridgeStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Tự tìm và kết nối xe": khảo sát quảng bá (xe tắt/bật) → xếp hạng ứng viên → kết nối thử do bạn cho phép →
 * khám phá GATT → trạng thái xác thực → báo cáo giao thức.
 *
 * Nguyên tắc hiển thị: trạng thái chỉ đi lên khi có sự kiện xác nhận (xem AutoStatusResolver); không ứng viên nào được gọi là
 * "xe Yamaha" chỉ vì quảng bá; không có nút nào ghi dữ liệu vào xe. Nút "Dừng khẩn cấp" luôn hiện.
 * CHƯA được trình biên dịch Compose kiểm tra và CHƯA thử trên điện thoại/xe thật.
 */
@Composable
fun AutoConnectScreen(app: AppViewModel, ble: BleViewModel, onBack: () -> Unit, onOpenAnalysis: () -> Unit = {}, onOpenHelp: (String?) -> Unit = {}) {
    val ctx = LocalContext.current
    val settings by app.settings.collectAsState()
    val status by ble.autoStatus.collectAsState()
    val run by ble.autoRun.collectAsState()
    val scanning by ble.scanning.collectAsState()
    val sessions by ble.scanSessions.collectAsState()
    val label by ble.surveyLabel.collectAsState()
    val cands by ble.candidates.collectAsState()
    val state by ble.connState.collectAsState()
    val rssi by ble.rssi.collectAsState()
    val mtu by ble.mtu.collectAsState()
    val inspector by ble.inspector.collectAsState()
    val subs by ble.subscriptions.collectAsState()
    val auth by ble.auth.collectAsState()
    val findings by ble.findings.collectAsState()
    val frameCount by ble.frameCount.collectAsState()
    val problem by ble.problem.collectAsState()
    val totalSeen by ble.totalSeen.collectAsState()
    val segNames by ble.segmentNames.collectAsState()
    val activeSeg by ble.activeSegment.collectAsState()
    var segName by remember { mutableStateOf("") }
    val navBridge = remember { (ctx.applicationContext as NvxApp).navAdapters.resolve() }

    val startSurvey = rememberBleGate(onGranted = { ble.startSurvey() }, onDenied = { ble.reportPermissionDenied() })
    val startAuto = rememberBleGate(onGranted = { ble.startAutoConnect() }, onDenied = { ble.reportPermissionDenied() })

    val step = when (status.state) {
        AutoState.IDLE, AutoState.SCANNING, AutoState.FAILED, AutoState.CONNECTION_BUSY -> 0
        AutoState.CANDIDATE_FOUND -> 2
        AutoState.GATT_CONNECTED -> 3
        AutoState.BONDED, AutoState.AUTHENTICATED -> 4
        AutoState.PROTOCOL_VERIFIED, AutoState.DATA_AVAILABLE -> 5
    }
    val tone = when (status.state) {
        AutoState.FAILED -> Tone.Bad
        AutoState.PROTOCOL_VERIFIED, AutoState.DATA_AVAILABLE -> Tone.Good
        AutoState.GATT_CONNECTED, AutoState.BONDED, AutoState.AUTHENTICATED, AutoState.CANDIDATE_FOUND, AutoState.CONNECTION_BUSY -> Tone.Warn
        else -> Tone.Neutral
    }
    val busy = scanning || state.isActive || run.phase == RunPhase.SCANNING || run.phase == RunPhase.CONNECTING

    Page(null) {
        AppTopBar("Tự tìm và kết nối xe", "Chỉ quét, kết nối và nghe. App không ghi dữ liệu vào xe.", onBack = onBack,
            actions = { HelpButton({ onOpenHelp(vn.nvxconnect.helpcenter.HelpContent.Id.SCAN_SELECT) }) })

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountTile("$totalSeen", "Thiết bị tìm thấy", Modifier.weight(1f))
            CountTile("${cands.size}", "Ứng viên tiềm năng", Modifier.weight(1f), accent = NvxColors.Primary)
            CountTile(if (state is ConnState.Ready) "1" else "0", "Đã kết nối", Modifier.weight(1f), accent = if (state is ConnState.Ready) NvxColors.Success else NvxColors.TextPrimary)
        }
        HeroCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(toneColor(tone), 12)
                Text(status.headline, style = MaterialTheme.typography.titleMedium)
            }
            status.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(4.dp))
            StepRow(listOf("Khảo sát", "Ứng viên", "GATT", "Xác thực", "Giao thức"), step)
            Text("GATT: ${state.code}" + (rssi?.let { " · RSSI $it dBm" } ?: "") + if (state is ConnState.Ready) " · MTU $mtu" else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ScanRadarAnimation(active = scanning) }
            if (busy && !scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Thiết bị thấy: ${cands.size} ứng viên · ${sessions.size} phiên quét", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("Phiên khảo sát này là:", style = MaterialTheme.typography.labelMedium)
            Segmented(listOf(SessionLabel.VEHICLE_OFF.title, SessionLabel.VEHICLE_ON.title), if (label == SessionLabel.VEHICLE_ON) 1 else 0) {
                ble.setSurveyLabel(if (it == 1) SessionLabel.VEHICLE_ON else SessionLabel.VEHICLE_OFF)
            }
            if (scanning) OutlinedButton(onClick = { ble.stopScan() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Dừng") }
            else PrimaryActionButton("Bắt đầu khảo sát (20 giây)", startSurvey, enabled = !busy)
            Button(onClick = { ble.emergencyStop() }, modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NvxColors.Danger, contentColor = NvxColors.OnDanger)) { Text("DỪNG KHẨN CẤP (đóng quét và GATT)") }
            Text("Cách dùng: đặt điện thoại gần xe, quét 1 phiên lúc khóa điện TẮT, rồi 1–2 phiên lúc khóa điện BẬT. Thiết bị chỉ xuất hiện khi xe bật và lặp lại sẽ được xếp cao hơn.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        problem?.let { (kind, reason) ->
            Notice("${kind.title}: $reason", Tone.Bad, "Tìm cách khắc phục") { onOpenHelp(vn.nvxconnect.helpcenter.HelpErrorMapper.forError(kind)) }
        }

        // ---------------------------------------------------------------- tự kết nối
        Section("Tự kết nối xe đã lưu")
        Panel {
            LabeledSwitch("Cho phép tự kết nối xe đã lưu khi mở app", settings.autoConnect) { app.setAutoConnect(it) }
            Text("Chỉ tự kết nối tới hồ sơ xe bạn đã lưu và đặt ưu tiên, tối đa 3 chu kỳ có giãn cách. Không bao giờ tự chọn thiết bị lạ.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            run.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            run.failure?.let { f ->
                StatusChip(f.title, Tone.Bad)
                FixButton(vn.nvxconnect.helpcenter.HelpErrorMapper.forAutoFailure(f), onOpenHelp)
                if (f == AutoFailure.EXCLUSIVE_HOLDER_SUSPECTED)
                    Text("Hãy đóng Y-Connect (và tắt Bluetooth của nó) hoặc dùng phương thức tích hợp chính thức, rồi bấm thử lại. App đã dừng để không chiếm kết nối của ứng dụng khác.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = startAuto, enabled = !busy && settings.autoConnect) { Text("Tự tìm xe đã lưu") }
                if (run.phase != RunPhase.IDLE && run.phase != RunPhase.CONNECTED) OutlinedButton(onClick = { ble.cancelAuto() }) { Text("Hủy") }
            }
            if (!settings.autoConnect) Text("Bật công tắc ở trên để dùng.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---------------------------------------------------------------- ứng viên
        Section("Ứng viên (${cands.size}) · ${sessions.size} phiên quét")
        Notice("Quảng bá BLE không chứng minh một thiết bị là xe Yamaha. Danh sách này chỉ xếp hạng bằng chứng gián tiếp.")
        if (cands.isEmpty()) Notice("Chưa có ứng viên. Cần thêm bằng chứng: bấm \"Bắt đầu khảo sát\" ở gần xe.")
        if (sessions.isNotEmpty()) OutlinedButton(onClick = { ble.clearSessions() }) { Text("Xóa các phiên quét") }
        cands.take(10).forEach { c ->
            DeviceCandidateCard(
                name = c.displayName,
                subtitle = c.latest.addressKind.label + " · …" + c.latest.address.takeLast(5) + " · %d dBm".format(c.latest.rssi),
                rssiDbm = c.latest.rssi, scoreText = "${c.score}",
                badge = c.confidence.title to when (c.confidence) {
                    Confidence.USER_CONFIRMED -> Tone.Good; Confidence.CIRCUMSTANTIAL, Confidence.WEAK -> Tone.Warn; Confidence.INSUFFICIENT -> Tone.Neutral },
                evidence = c.evidence.map { it.text }, nextStep = c.nextStep) {
                Button(onClick = { ble.connectCandidate(c) }, enabled = !busy && c.latest.connectable != false) { Text("Kết nối thử (GATT)") }
                OutlinedButton(onClick = { ble.saveCandidate(c) }) { Text("Lưu làm xe của tôi") }
            }
        }

        // ---------------------------------------------------------------- GATT inspector
        Section("Khám phá GATT")
        val ins = inspector
        if (ins == null) Notice(if (state.isActive) "Đang kết nối/khám phá: ${state.code}" else "Chưa có phiên GATT. Chọn \"Kết nối thử\" ở một ứng viên.")
        else Panel {
            Text("${ins.services} service · ${ins.characteristics} characteristic · đọc được ${ins.readable} · nghe được ${ins.subscribable} · có thuộc tính ghi ${ins.writable}",
                style = MaterialTheme.typography.bodySmall)
            if (ins.writable > 0) Text("App KHÔNG ghi vào các characteristic có thuộc tính ghi (chưa có giao thức xác minh).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ins.rows.filter { it.canSubscribe }.forEach { r ->
                val st = subs[r.charUuid.lowercase()]
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.charUuid.take(8), style = MaterialTheme.typography.bodyMedium)
                        Text(r.properties.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (st == SubscriptionState.ACTIVE || st == SubscriptionState.PENDING) OutlinedButton(onClick = { ble.unsubscribe(r.charUuid) }) { Text(if (st == SubscriptionState.ACTIVE) "Ngừng nghe" else "Chờ…") }
                    else Button(onClick = { ble.subscribe(r.charUuid) }) { Text(if (st == SubscriptionState.FAILED) "Nghe lại" else "Nghe") }
                }
            }
            Text("Đã nhận $frameCount gói.", style = MaterialTheme.typography.labelSmall)
            if (state.isActive) OutlinedButton(onClick = { ble.disconnect() }) { Text("Ngắt kết nối") }
        }

        // ---------------------------------------------------------------- xác thực
        Section("Xác thực")
        Panel {
            StatusChip(auth.headline, when (auth.level) {
                AuthLevel.BONDED -> Tone.Good; AuthLevel.REQUIRED, AuthLevel.REJECTED -> Tone.Bad; AuthLevel.PAIRING -> Tone.Warn; else -> Tone.Neutral })
            auth.guidance.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { ble.requestPairing() }, enabled = auth.level == AuthLevel.REQUIRED || auth.level == AuthLevel.REJECTED || state is ConnState.Ready) { Text("Ghép đôi (hộp thoại Android)") }
                OutlinedButton(onClick = { Perms.openBluetoothSettings(ctx) }) { Text("Cài đặt Bluetooth") }
            }
            Text("App không đoán PIN, không lưu mật khẩu/token/khóa, không giả danh thiết bị. Mã xác nhận do bạn nhập trên hộp thoại của Android.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        OutlinedButton(onClick = onOpenAnalysis, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Mở màn Phân tích giao thức") }
        // ---------------------------------------------------------------- báo cáo giao thức
        Section("Báo cáo giao thức")
        Panel {
            Text("Phiên thử nghiệm theo sự kiện (so sánh gói giữa các phiên):", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(segName, { segName = it }, label = { Text("Tên phiên (vd: Đèn pha bật)") }, singleLine = true, modifier = Modifier.weight(1f))
                if (activeSeg == null) Button(onClick = { ble.beginSegment(segName); segName = "" }, enabled = segName.isNotBlank()) { Text("Bắt đầu") }
                else OutlinedButton(onClick = { ble.endSegment() }) { Text("Kết thúc") }
            }
            activeSeg?.let { StatusChip("Đang ghi phiên: $it", Tone.Warn) }
            if (segNames.isNotEmpty()) Text("Đã có phiên: " + segNames.joinToString(), style = MaterialTheme.typography.labelSmall)
            Button(onClick = { ble.refreshReport() }) { Text("Cập nhật báo cáo") }
        }
        if (findings.isEmpty()) Notice("Chưa có báo cáo. Bấm \"Cập nhật báo cáo\".")
        findings.forEach { f -> LogEntryCard(f.title, f.detail) { ProtocolStatusBadge(f.level) } }

        // ---------------------------------------------------------------- dẫn đường lên TFT
        Section("Dẫn đường lên TFT")
        Panel {
            StatusChip(navBridge.status.name, if (navBridge.status == TftBridgeStatus.VERIFIED_READY) Tone.Good else Tone.Bad)
            Text(navBridge.limitation, style = MaterialTheme.typography.bodySmall)
            Text("Bản đồ và dẫn đường trên điện thoại (tab Bản đồ) vẫn dùng bình thường.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---------------------------------------------------------------- xuất
        Section("Xuất log đã loại thông tin nhạy cảm")
        Panel {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { ShareFiles.share(ctx, "nvx-auto-report-${stamp()}.txt", "text/plain", ble.reportText()) }) { Text("Xuất báo cáo") }
                OutlinedButton(onClick = { ShareFiles.share(ctx, "nvx-ble-log-${stamp()}.json", "application/json", ble.exportJson()) }) { Text("Xuất JSON") }
                OutlinedButton(onClick = { ShareFiles.share(ctx, "nvx-events-${stamp()}.jsonl", "application/json", ble.eventLogJsonl()) }) { Text("Xuất sự kiện") }
            }
            Text("Địa chỉ được băm, tên bị che, chuỗi ASCII dài trong gói được thay bằng '*'. Không gửi log lúc đang lái.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
