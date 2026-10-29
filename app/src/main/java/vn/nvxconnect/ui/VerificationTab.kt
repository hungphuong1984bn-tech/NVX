package vn.nvxconnect.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import vn.nvxconnect.telemetry.FieldVerdict
import vn.nvxconnect.telemetry.VerificationStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_IMPORT_BYTES = 8 * 1024 * 1024

private fun readLimited(ctx: android.content.Context, uri: android.net.Uri): String? = try {
    ctx.contentResolver.openInputStream(uri)?.use { ins ->
        val buf = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192); var total = 0
        while (true) { val n = ins.read(chunk); if (n < 0) break; total += n; if (total > MAX_IMPORT_BYTES) return@use null; buf.write(chunk, 0, n) }
        buf.toString("UTF-8")
    }
} catch (_: Exception) { null }

/**
 * Trung tâm kiểm tra/xác minh: trạng thái ghi, số gói, độ cũ, raw gần nhất, từng trường telemetry với trạng thái xác minh,
 * xuất JSON/CSV, nhập log và dữ liệu tham chiếu. Không hiển thị số 0 thay cho dữ liệu chưa có.
 */
@Composable
fun VerificationTab(ble: BleViewModel) {
    val ctx = LocalContext.current
    val recording by ble.recording.collectAsState()
    val rec by ble.recStats.collectAsState()
    val verdicts by ble.fieldVerdicts.collectAsState()
    val msg by ble.toolMessage.collectAsState()
    val analysis by ble.importedAnalysis.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { ble.evaluateFields(); while (true) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }
    val pickLog = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readLimited(ctx, uri)?.let { ble.importLog(it) } ?: run { ble.toolMessage.value = "Không đọc được tệp (quá 8 MB hoặc lỗi đọc)" }
    }
    val pickRef = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readLimited(ctx, uri)?.let { ble.importReference(it) } ?: run { ble.toolMessage.value = "Không đọc được tệp (quá 8 MB hoặc lỗi đọc)" }
    }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    Panel {
        Text("Thu thập payload", style = MaterialTheme.typography.titleSmall)
        Text(if (recording) "ĐANG GHI · phiên ${rec.session}" else "ĐÃ DỪNG GHI (gói đến lúc này bị bỏ, đếm: ${rec.ignored})", style = MaterialTheme.typography.bodyMedium)
        Text("Số payload: ${rec.payloads}" + if (rec.dropped > 0) " · đã cắt ${rec.dropped} bản ghi cũ nhất do giới hạn dung lượng" else "", style = MaterialTheme.typography.bodySmall)
        val age = rec.lastMs?.let { (now - it) / 1000 }
        Text(if (age == null) "Chưa nhận payload nào." else "Gói gần nhất cách đây $age s" + if (age > 5) " — DỮ LIỆU CŨ / có thể mất kết nối" else "", style = MaterialTheme.typography.bodySmall)
        Text(if (rec.lastHex == null) "Raw gần nhất: Chưa có dữ liệu" else "Raw gần nhất (${rec.lastChar?.take(8)}): ${rec.lastHex}", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (recording) OutlinedButton(onClick = { ble.stopRecording() }) { Text("Dừng ghi") } else Button(onClick = { ble.startRecording("thủ công") }) { Text("Bắt đầu ghi") }
            OutlinedButton(onClick = { ShareFiles.share(ctx, "nvx-telemetry-$stamp.json", "application/json", ble.telemetryJson()) }) { Text("Xuất JSON") }
            OutlinedButton(onClick = { ShareFiles.share(ctx, "nvx-telemetry-$stamp.csv", "text/csv", ble.telemetryCsv()) }) { Text("Xuất CSV") }
        }
        Text("Log chỉ gồm gói thu động; thiết bị đã ẩn danh. Khung 0x6B/0x6C là khung ĐANG NGHIÊN CỨU, chưa phải dữ liệu động cơ đã xác minh.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    SectionHeader("Trường telemetry")
    verdicts.forEach { v -> FieldCard(v, now, ble.lifecycleOf(v)) }

    Panel {
        Text("Phân tích ngoại tuyến & đối chiếu", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickLog.launch(arrayOf("application/json", "text/csv", "text/comma-separated-values", "text/plain", "*/*")) }) { Text("Nhập log") }
            OutlinedButton(onClick = { pickRef.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) }) { Text("Nhập tham chiếu") }
        }
        Text("Tham chiếu CSV: time_ms,field,value,unit,source,grade(official|independent|cluster|inference),method,session. Xem SAMPLE_LOG_GUIDE.md.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ShareFiles.share(ctx, "nvx-field-evidence-$stamp.md", "text/markdown", ble.evidenceMarkdown()) }) { Text("Xuất bảng bằng chứng") }
            OutlinedButton(onClick = { ShareFiles.share(ctx, "nvx-field-evidence-$stamp.csv", "text/csv", ble.evidenceCsv()) }) { Text("CSV bằng chứng") }
        }
        analysis?.let { a -> OutlinedButton(onClick = { ShareFiles.share(ctx, "nvx-analysis-$stamp.md", "text/markdown", a) }) { Text("Xuất kết quả phân tích log đã nhập") } }
    }
}

@Composable
private fun FieldCard(v: FieldVerdict, now: Long, life: vn.nvxconnect.telemetry.FieldLifecycleState) {
    Panel {
        val tone = when (v.status) { VerificationStatus.VERIFIED -> "ĐÃ XÁC MINH"; VerificationStatus.CORRELATED -> "ĐÃ ĐỐI CHIẾU — CHƯA ĐỦ TIÊU CHÍ"; VerificationStatus.DECODED_HYPOTHESIS -> "GIẢ THUYẾT — CHƯA XÁC MINH"
            VerificationStatus.RAW_UNVERIFIED -> "DỮ LIỆU THÔ — CHƯA XÁC MINH"; VerificationStatus.STALE -> "DỮ LIỆU QUÁ HẠN"; VerificationStatus.UNAVAILABLE -> "CHƯA CÓ NGUỒN DỮ LIỆU" }
        Text("${v.field.title} · $tone", style = MaterialTheme.typography.titleSmall)
        Text("Vòng đời: ${life.lifecycle.title}${if (life.stale) " · QUÁ HẠN" else ""}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        val last = v.lastSample
        val fresh = last != null && v.status != VerificationStatus.STALE && now - last.timeMs <= 5000
        // Giá trị giải mã chỉ hiện khi còn mới; luôn kèm nhãn trạng thái. Không bao giờ hiện 0 thay cho "chưa có".
        Text(if (fresh) "${"%.1f".format(last!!.value)} ${v.field.unit} (${if (v.status == VerificationStatus.VERIFIED) "đã xác minh" else "chưa xác minh"})" else if (v.status == VerificationStatus.UNAVAILABLE) "Chưa có dữ liệu" else "Chưa xác minh / không có mẫu mới",
            style = MaterialTheme.typography.bodyMedium)
        last?.let { Text("Mẫu cuối: ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it.timeMs))} · ${v.sampleCount} mẫu", style = MaterialTheme.typography.labelSmall) }
        v.metrics?.let { m -> Text("Đối chiếu ${m.pairs} cặp · r=${"%.4f".format(m.pearson)} · sai số TB ${"%.2f".format(m.mae)} / max ${"%.2f".format(m.maxAbs)} ${v.field.unit}", style = MaterialTheme.typography.bodySmall) }
        Text(v.grade.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(v.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        v.hypothesis?.let { Text(it.formula, style = MaterialTheme.typography.labelSmall) }
    }
}
