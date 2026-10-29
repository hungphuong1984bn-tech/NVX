package vn.nvxconnect.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import vn.nvxconnect.telemetry.MonitorRow
import vn.nvxconnect.telemetry.MonitorTone
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun uiTone(t: MonitorTone): Tone = when (t) {
    MonitorTone.GOOD -> Tone.Good; MonitorTone.WARN -> Tone.Warn; MonitorTone.BAD -> Tone.Bad; MonitorTone.NEUTRAL -> Tone.Neutral
}

@Composable
private fun MonitorRowView(r: MonitorRow) {
    val color: Color = if (r.tone == MonitorTone.NEUTRAL) MaterialTheme.colorScheme.onSurface else toneColor(uiTone(r.tone))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(r.label, Modifier.weight(0.42f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(r.value, Modifier.weight(0.58f), style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/**
 * Bảng giám sát kết nối xe theo thời gian thực. Bốn mức "đã kết nối Bluetooth / đang nhận dữ liệu / đang ghi dữ liệu / dữ liệu đã xác minh"
 * được hiển thị riêng, không mức nào suy ra mức khác. Thiếu dữ liệu thì hiện "Chưa có dữ liệu", không có số giả.
 */
@Composable
fun LinkMonitorPanel(ble: BleViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snap by ble.monitor.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var askClear by remember { mutableStateOf(false) }

    fun export(csv: Boolean) {
        scope.launch {
            try {
                val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val f = File(dir, "nvx-capture-$stamp." + (if (csv) "csv" else "jsonl"))
                val summary = f.outputStream().use { ble.exportCapture(csv, it) }
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                val send = Intent(Intent.ACTION_SEND).setType(if (csv) "text/csv" else "text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(Intent.createChooser(send, "Chia sẻ").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                message = summary
            } catch (e: Exception) {
                message = "Xuất thất bại: " + (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    if (ble.previousSessionUnclean) Notice("Phiên kết nối lần trước bị dừng đột ngột (ứng dụng/hệ điều hành bị tắt). Dữ liệu đã ghi xong vẫn còn; gói xe gửi trong lúc ứng dụng không chạy thì không khôi phục được. Hãy kết nối lại.", Tone.Warn)

    Section("Giám sát kết nối")
    Panel {
        MonitorRowView(snap.levels.stage)
        MonitorRowView(snap.levels.bluetooth)
        MonitorRowView(snap.levels.receiving)
        MonitorRowView(snap.levels.recording)
        MonitorRowView(snap.levels.verified)
    }
    for (s in snap.sections) {
        Panel {
            Text(s.title, style = MaterialTheme.typography.titleSmall)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { for (r in s.rows) MonitorRowView(r) }
        }
    }
    Panel {
        Text("Xuất / xóa dữ liệu thu thập", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { export(true) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Xuất toàn bộ gói (CSV)") }
        OutlinedButton(onClick = { export(false) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Xuất toàn bộ gói (JSON Lines)") }
        OutlinedButton(onClick = { askClear = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Xóa dữ liệu thu thập") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (askClear) ConfirmDialog("Xóa dữ liệu thu thập?", "Toàn bộ gói đã ghi trên điện thoại sẽ bị xóa và không khôi phục được.", "Xóa",
        onConfirm = { ble.clearLog(); askClear = false; message = "Đã xóa dữ liệu thu thập" }, onDismiss = { askClear = false })
}
