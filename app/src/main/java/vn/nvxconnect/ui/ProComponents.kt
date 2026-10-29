package vn.nvxconnect.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.nvxconnect.pro.LabStatus

// Thành phần dùng chung của giao diện Pro. Không chứa logic BLE/xe: chỉ nhận dữ liệu đã được lớp trên quyết định.

/** 7 trạng thái giao diện bắt buộc cho mọi màn hình quan trọng. */
enum class UiState(val label: String, val tone: Tone) {
    Loading("ĐANG TẢI", Tone.Neutral), Empty("CHƯA CÓ DỮ LIỆU", Tone.Neutral), Error("LỖI", Tone.Bad), PermissionDenied("THIẾU QUYỀN", Tone.Warn),
    Disconnected("CHƯA KẾT NỐI", Tone.Neutral), Stale("DỮ LIỆU CŨ", Tone.Warn), Success("THÀNH CÔNG", Tone.Good)
}

@Composable internal fun StateBanner(kind: UiState, title: String, body: String? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null) = Panel {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (kind == UiState.Loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        StatusChip(kind.label, kind.tone)
    }
    Text(title, style = MaterialTheme.typography.titleSmall)
    if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (actionLabel != null && onAction != null) OutlinedButton(onClick = onAction, Modifier.heightIn(min = 48.dp)) { Text(actionLabel) }
}

/** Huy hiệu 6 trạng thái bằng chứng của Protocol Lab. Màu xanh lá CHỈ dành cho VERIFIED. */
@Composable internal fun LabBadge(s: LabStatus) = StatusChip(s.title, when (s) {
    LabStatus.VERIFIED -> Tone.Good; LabStatus.CORRELATED, LabStatus.HYPOTHESIS -> Tone.Warn; LabStatus.OBSERVED -> Tone.Neutral
    LabStatus.UNAVAILABLE, LabStatus.INACCESSIBLE -> Tone.Bad })

/** Chip chọn (một lựa chọn). Cao tối thiểu 40dp, có vai trò Tab cho trình đọc màn hình. */
@Composable internal fun NvxChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.heightIn(min = 40.dp).clip(shape)
        .background(if (selected) NvxColors.PrimaryDeep else NvxColors.SurfaceHigh)
        .border(1.dp, if (selected) NvxColors.Primary else NvxColors.Outline, shape)
        .clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, maxLines = 1, style = MaterialTheme.typography.labelLarge, letterSpacing = 0.sp,
            color = if (selected) NvxColors.OnPrimaryDeep else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable internal fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) =
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, t -> NvxChip(t, i == selected) { onSelect(i) } }
    }

@Composable internal fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
    title = { Text(title) }, text = { Text(text) })

/**
 * Vòng đo 270°. [fraction] = null khi KHÔNG có số hợp lệ: chỉ vẽ nền vòng, không vẽ cung giá trị (không bao giờ vẽ cung 0 như thể đo được 0).
 * Chỉ vẽ lại khi giá trị đổi; không có animation vô hạn nên không tốn pin.
 */
@Composable internal fun RingGauge(fraction: Float?, value: String, unit: String, label: String, caption: String, arcColor: androidx.compose.ui.graphics.Color,
                                   modifier: Modifier = Modifier, valueSize: TextUnit = 28.sp) {
    val track = NvxColors.Outline
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $value $unit. $caption" }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.09f
                val d = Size(size.minDimension - stroke, size.minDimension - stroke)
                val tl = Offset((size.width - d.width) / 2f, (size.height - d.height) / 2f)
                drawArc(track, 135f, 270f, false, topLeft = tl, size = d, style = Stroke(stroke, cap = StrokeCap.Round))
                if (fraction != null && fraction > 0f) drawArc(arcColor, 135f, 270f * fraction.coerceIn(0f, 1f), false, topLeft = tl, size = d, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, fontSize = valueSize, fontWeight = FontWeight.Bold, maxLines = 1, color = if (fraction != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                if (unit.isNotBlank()) Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

/** Nút lớn cho cockpit (cao ≥ 64dp) và thao tác khi đang di chuyển. */
@Composable internal fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true) =
    if (primary) Button(onClick = onClick, modifier = modifier.heightIn(min = 64.dp), shape = RoundedCornerShape(NvxRadius.lg)) { Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1) }
    else OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 64.dp), shape = RoundedCornerShape(NvxRadius.lg)) { Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1) }

/** Hộp chữ đơn cách có thể chọn/cuộn ngang: cho HEX/UUID. */
@Composable internal fun MonoBox(text: String, modifier: Modifier = Modifier) = Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(NvxRadius.sm), color = NvxColors.SurfaceHigh) {
    SelectionContainer { Text(text, Modifier.padding(10.dp).horizontalScroll(rememberScrollState()), style = NvxMono, color = MaterialTheme.colorScheme.onSurface) }
}

/** Thẻ chọn một mục trong lối tắt ở tab Tổng quan. Mọi thẻ đều dẫn tới màn hình CÓ THẬT. */
@Composable internal fun ShortcutCard(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    Surface(modifier.heightIn(min = 72.dp).clip(RoundedCornerShape(NvxRadius.md)).clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(NvxRadius.md), color = NvxColors.SurfaceHigh, border = androidx.compose.foundation.BorderStroke(1.dp, NvxColors.OutlineSoft)) {
        Column(Modifier.padding(NvxSpacing.md).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }

/** Đồng hồ thời gian cập nhật theo chu kỳ [intervalMs] (cấu hình trong Cài đặt), dùng để dữ liệu quá hạn tự chuyển sang trạng thái "cũ". */
@Composable internal fun rememberNowEvery(intervalMs: Long): Long {
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(intervalMs) { while (true) { kotlinx.coroutines.delay(intervalMs); now = System.currentTimeMillis() } }
    return now
}
