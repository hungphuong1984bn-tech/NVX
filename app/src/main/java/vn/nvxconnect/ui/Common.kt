package vn.nvxconnect.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import vn.nvxconnect.domain.DataSource
import vn.nvxconnect.location.TripPhase
import java.io.File

/** Trang cuộn dọc. Trên màn hình rộng, nội dung được giới hạn bề rộng và căn giữa (không kéo giãn chữ ra hai mép). */
@Composable
internal fun Page(title: String?, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val layout = rememberLayoutInfo()
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
        Column(Modifier.widthIn(max = layout.contentMaxWidth).fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/** Chia sẻ văn bản dạng tệp qua FileProvider (không cắt cụt như gửi chuỗi trực tiếp). */
object ShareFiles {
    fun share(context: Context, fileName: String, mime: String, text: String) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
        val f = File(dir, fileName).apply { writeText(text) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Chia sẻ").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Nút điều khiển hành trình: bắt đầu / tạm dừng / tiếp tục / kết thúc. Bắt đầu cần quyền vị trí chính xác. */
@Composable
internal fun TripControls(app: AppViewModel) {
    val phase by app.engine.phase.collectAsState()
    var denied by remember { mutableStateOf<String?>(null) }
    val start = rememberLocationGate(onGranted = { denied = null; app.startTrip() }, onDenied = { denied = it })
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (phase) {
            TripPhase.IDLE -> Button(onClick = start, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Bắt đầu hành trình") }
            TripPhase.RECORDING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { app.pauseTrip() }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Tạm dừng") }
                Button(onClick = { app.stopTrip() }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Kết thúc") }
            }
            TripPhase.PAUSED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { app.resumeTrip() }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Tiếp tục") }
                OutlinedButton(onClick = { app.stopTrip() }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Kết thúc") }
            }
        }
        denied?.let { Notice(it, Tone.Warn, "Mở cài đặt ứng dụng") { Perms.openAppSettings(ctx) } }
    }
}

internal fun sourceOf(demo: Boolean) = if (demo) DataSource.DEMO else DataSource.GPS_ESTIMATE
