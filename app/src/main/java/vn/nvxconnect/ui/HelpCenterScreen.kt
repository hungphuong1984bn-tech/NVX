package vn.nvxconnect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import vn.nvxconnect.NvxApp
import vn.nvxconnect.bluetooth.BlePreflight
import vn.nvxconnect.helpcenter.*

/**
 * Trung tâm hướng dẫn (tiếng Việt, tìm kiếm được). Nội dung lấy từ [HelpContent]; logic tìm kiếm/tiến độ/kiểm tra nằm ở gói helpcenter
 * và có unit test trên JVM. LỚP COMPOSE NÀY CHƯA được trình biên dịch Compose kiểm tra và chưa thử trên máy thật (xem docs/BUILD_LOG.md).
 *
 * @param initialGuideId mở thẳng bài này (nút "Hướng dẫn"/"Tìm cách khắc phục" theo ngữ cảnh); null = danh sách.
 * @param onNavigate điều hướng tới màn hình đích của nút hành động (chỉ các màn hình tồn tại trong app).
 */
@Composable
fun HelpCenterScreen(ble: BleViewModel, initialGuideId: String?, onBack: () -> Unit, onNavigate: (HelpAction) -> Unit) {
    val ctx = LocalContext.current
    val nvx = ctx.applicationContext as NvxApp
    val tracker = nvx.helpProgress
    var openId by remember(initialGuideId) { mutableStateOf(initialGuideId?.takeIf { HelpContent.find(it) != null }) }
    var query by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }   // tăng khi tiến độ đổi để vẽ lại
    var message by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    // Quy trình kiểm tra: dựng từ trạng thái THỰC TẾ, làm mới mỗi giây (người dùng có thể vừa bật Bluetooth ở cài đặt hệ thống).
    val now = rememberNow()
    val conn by ble.connState.collectAsState()
    val link by ble.linkStatus.collectAsState()
    val total by ble.totalSeen.collectAsState()
    val seen by ble.verifiedValueSeen.collectAsState()
    val blockers = remember(now / 2000) { runCatching { BlePreflight.check(ctx) }.getOrNull() }
    val checklist = ConnectionChecklist.evaluate(ChecklistInputs(
        blockers = blockers, deviceFound = total > 0, conn = conn, link = link, verifiedValueSeen = seen, tftStatus = nvx.navAdapters.resolve().status))

    fun act(a: HelpAction) = when (a) {
        HelpAction.OPEN_SYSTEM_BLUETOOTH -> Perms.openBluetoothSettings(ctx)
        HelpAction.OPEN_APP_SETTINGS -> Perms.openAppSettings(ctx)
        else -> onNavigate(a)
    }

    val guide = openId?.let { HelpContent.find(it) }
    Page(null) {
        AppTopBar("Trung tâm hướng dẫn", "Tiếng Việt · tìm kiếm được", onBack = { if (guide != null && initialGuideId == null) openId = null else onBack() })
        Notice(HelpSafety.STATIONARY_NOTICE, Tone.Warn)

        if (guide != null) GuideDetail(guide, checklist, tracker.isCompleted(guide.id), version, message,
            onBackToList = { openId = null; message = null },
            onAction = { act(it) },
            onToggleDone = {
                if (tracker.isCompleted(guide.id)) { tracker.unmark(guide.id); message = null }
                else message = when (val r = tracker.markComplete(guide.id, checklist)) { is CompleteResult.Done -> "Đã đánh dấu hoàn thành."; is CompleteResult.NotAllowed -> r.reason }
                version++
            })
        else {
            ProgressPanel(tracker, version) { confirmReset = true }
            ChecklistPanel(checklist) { openId = it; message = null }
            OutlinedTextField(query, { query = it }, label = { Text("Tìm hướng dẫn (ví dụ: quyền, quét, TFT)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val found = HelpSearch.search(query)
            if (found.isEmpty()) Notice("Không có bài nào khớp \"$query\". Thử từ khóa ngắn hơn, ví dụ \"kết nối\" hoặc \"quyền\".")
            found.forEach { g -> GuideRow(g, tracker.isCompleted(g.id)) { openId = g.id; message = null } }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        confirmButton = { TextButton(onClick = { confirmReset = false; tracker.reset(); version++ }) { Text("Đặt lại") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Hủy") } },
        title = { Text("Đặt lại tiến độ hướng dẫn?") },
        text = { Text("Chỉ xóa dấu \"đã hoàn thành\" trong Trung tâm hướng dẫn. Dữ liệu hành trình, bảo dưỡng không bị ảnh hưởng.") })
}

private fun statusTone(s: CheckStatus) = when (s) { CheckStatus.PASSED -> Tone.Good; CheckStatus.FAILED -> Tone.Bad; CheckStatus.UNSUPPORTED -> Tone.Warn; CheckStatus.PENDING -> Tone.Neutral }
private fun statusText(s: CheckStatus) = when (s) { CheckStatus.PASSED -> "ĐẠT"; CheckStatus.FAILED -> "CHƯA ĐẠT"; CheckStatus.UNSUPPORTED -> "KHÔNG HỖ TRỢ"; CheckStatus.PENDING -> "CHƯA KIỂM TRA" }

@Composable
private fun ProgressPanel(tracker: HelpProgressTracker, @Suppress("UNUSED_PARAMETER") version: Int, onReset: () -> Unit) = Panel {
    Section("Tiến độ")
    Text("Đã hoàn thành ${tracker.completedCount()} / ${tracker.trackableCount()} bài thủ tục", style = MaterialTheme.typography.titleSmall)
    LinearProgressIndicator(progress = { tracker.ratio().toFloat() }, modifier = Modifier.fillMaxWidth())
    Text("Bài tham khảo (dữ liệu xe, Y-Connect, TFT, xử lý sự cố) không có dấu hoàn thành. Bài có kiểm tra tự động chỉ đánh dấu được khi bước kiểm tra đạt.",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = onReset) { Text("Đặt lại tiến độ") }
}

@Composable
private fun ChecklistPanel(results: List<StepResult>, onOpenGuide: (String) -> Unit) = Panel {
    Section("Kiểm tra kết nối từng bước")
    Text("Đạt ${ConnectionChecklist.passedCount(results)} / ${results.size}. Mỗi bước chỉ ĐẠT khi có bằng chứng thật.", style = MaterialTheme.typography.bodySmall)
    val first = ConnectionChecklist.firstProblem(results)
    results.forEach { r ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(r.step.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                StatusChip(statusText(r.status), statusTone(r.status))
            }
            Text(r.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (r == first || r.status == CheckStatus.FAILED) TextButton(onClick = { onOpenGuide(r.guideId) }) { Text("Tìm cách khắc phục") }
        }
    }
}

@Composable
private fun GuideRow(g: HelpGuide, done: Boolean, onClick: () -> Unit) = Panel(Modifier) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("${g.order}. ${g.title}", style = MaterialTheme.typography.titleSmall)
            Text(g.purpose, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        if (done) StatusChip("Đã xong", Tone.Good) else StatusChip(g.category.title, Tone.Neutral)
    }
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("Mở hướng dẫn") }
}

@Composable
private fun GuideDetail(g: HelpGuide, checklist: List<StepResult>, done: Boolean, @Suppress("UNUSED_PARAMETER") version: Int, message: String?,
                        onBackToList: () -> Unit, onAction: (HelpAction) -> Unit, onToggleDone: () -> Unit) {
    OutlinedButton(onClick = onBackToList) { Text("← Danh sách hướng dẫn") }
    Panel {
        Text("${g.order}. ${g.title}", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusChip(g.category.title, Tone.Neutral)
            if (done) StatusChip("Đã hoàn thành", Tone.Good)
            ConnectionChecklist.stepForGuide[g.id]?.let { s ->
                val r = checklist.first { it.step == s }
                StatusChip("Kiểm tra: " + statusText(r.status), statusTone(r.status))
            }
        }
    }
    if (g.requiresStationary) Notice(HelpSafety.STATIONARY_NOTICE, Tone.Warn)
    Panel { Section("Mục đích"); Text(g.purpose) }
    Panel { Section("Điều kiện trước khi thực hiện"); g.prerequisites.forEach { Text("• $it") } }
    Panel { Section("Các bước"); g.steps.forEachIndexed { i, s -> Text("${i + 1}. $s") } }
    Panel { Section("Kết quả mong đợi"); Text(g.expected) }
    Panel { Section("Lỗi thường gặp"); g.commonErrors.forEach { Text("• $it") } }
    Panel { Section("Cách khắc phục"); g.fixes.forEach { Text("• $it") } }
    g.action?.let { a -> Button(onClick = { onAction(a) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(a.label) } }
    if (g.manualComplete) {
        OutlinedButton(onClick = onToggleDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (done) "Bỏ đánh dấu hoàn thành" else "Đánh dấu đã hoàn thành") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else {
        Text("Bài tham khảo: không có trạng thái hoàn thành. Hãy xem \"Kiểm tra kết nối từng bước\" ở danh sách để biết bước nào đã đạt thật.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- nút theo ngữ cảnh dùng ở các màn hình khác

/** Nút "Hướng dẫn" nhỏ gọn đặt ở màn hình kết nối, dữ liệu, TFT, cài đặt. [topic] = mở thẳng bài tương ứng. */
@Composable
internal fun HelpButton(onClick: () -> Unit, label: String = "Hướng dẫn") = OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 40.dp)) { Text(label) }

/** Nút "Tìm cách khắc phục" cho lỗi thực tế; chỉ hiển thị khi có bài tương ứng. */
@Composable
internal fun FixButton(guideId: String?, onOpenHelp: (String?) -> Unit) {
    if (guideId != null) Button(onClick = { onOpenHelp(guideId) }) { Text("Tìm cách khắc phục") }
}
