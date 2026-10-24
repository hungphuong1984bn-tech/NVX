package vn.nvxconnect.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import vn.nvxconnect.data.Trip
import vn.nvxconnect.domain.DataSource
import java.text.SimpleDateFormat
import java.util.*

enum class Tone { Neutral, Good, Warn, Bad }

internal fun fmtTime(ms: Long): String = SimpleDateFormat("dd/MM HH:mm", Locale("vi")).format(Date(ms))
internal fun fmtDur(ms: Long): String = "${ms / 3_600_000}h ${ms / 60_000 % 60}ph"

internal fun toneColor(t: Tone): Color = when (t) {
    Tone.Neutral -> NvxColors.Neutral; Tone.Good -> NvxColors.Success; Tone.Warn -> NvxColors.Warning; Tone.Bad -> NvxColors.Danger
}

@Composable internal fun Section(t: String) =
    Text(t.uppercase(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = NvxSpacing.xs))

@Composable internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(NvxRadius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, NvxColors.Outline)) {
        Column(Modifier.padding(NvxSpacing.lg), verticalArrangement = Arrangement.spacedBy(NvxSpacing.sm), content = content)
    }

@Composable internal fun HeroCard(content: @Composable ColumnScope.() -> Unit) =
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(NvxRadius.xl))
        .background(Brush.verticalGradient(listOf(NvxColors.HeroTop, NvxColors.Surface)))
        .border(1.dp, NvxColors.Outline, RoundedCornerShape(NvxRadius.xl)).padding(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(NvxSpacing.sm), content = content)
    }

@Composable internal fun StatusChip(text: String, tone: Tone) {
    val c = toneColor(tone)
    Surface(shape = RoundedCornerShape(50), color = c.copy(alpha = 0.16f), border = BorderStroke(1.dp, c.copy(alpha = 0.35f))) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = c, style = MaterialTheme.typography.labelMedium)
    }
}

/** Nhãn nguồn dữ liệu. Mọi số liệu phải gắn nhãn để không nhầm GPS/ người nhập/ DEMO với dữ liệu xe. */
@Composable internal fun SourceTag(s: DataSource) = StatusChip(s.label, when (s) {
    DataSource.LIVE_VERIFIED -> Tone.Good; DataSource.DEMO -> Tone.Warn; DataSource.UNAVAILABLE -> Tone.Bad; else -> Tone.Neutral })

@Composable internal fun StatTile(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier, source: DataSource? = null) =
    Surface(modifier, shape = RoundedCornerShape(NvxRadius.md), color = NvxColors.SurfaceHigh, border = BorderStroke(1.dp, NvxColors.OutlineSoft)) {
        Column(Modifier.padding(NvxSpacing.md), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            androidx.compose.material3.Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (source != null) Text(source.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }

@Composable internal fun TripRow(t: Trip, onClick: () -> Unit) = Panel(Modifier.clickable { onClick() }) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(fmtTime(t.startMs), style = MaterialTheme.typography.titleSmall)
            Text("%.1f km · %s · tối đa %.0f km/h".format(t.distanceM / 1000, fmtDur(t.endMs - t.startMs), t.maxSpeedKmh),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SourceTag(if (t.isDemo) DataSource.DEMO else DataSource.GPS_ESTIMATE)
    }
}

/** Thông báo ngắn có thể kèm nút. Màu chữ theo tone. */
@Composable internal fun Notice(text: String, tone: Tone = Tone.Neutral, actionLabel: String? = null, onAction: (() -> Unit)? = null) = Panel {
    val c = when (tone) { Tone.Bad -> MaterialTheme.colorScheme.error; Tone.Warn -> NvxColors.Warning; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Text(text, color = c, style = MaterialTheme.typography.bodyMedium)
    if (actionLabel != null && onAction != null) OutlinedButton(onClick = onAction) { Text(actionLabel) }
}

/** Nhóm nút chọn một mục (thay cho tab, chỉ dùng Button/OutlinedButton ổn định). */
@Composable internal fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, t ->
            if (i == selected) Button(onClick = { onSelect(i) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text(t, maxLines = 1) }
            else OutlinedButton(onClick = { onSelect(i) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text(t, maxLines = 1) }
        }
    }

@Composable internal fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(checked, onChange)
    }

/** Chấm trạng thái tròn nhỏ cạnh tiêu đề. */
@Composable internal fun StatusDot(color: Color, size: Int = 10) =
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))

/** Vạch sóng 4 mức theo RSSI (dBm đã làm mượt). Chỉ là ước lượng độ gần, không phải chất lượng liên kết. */
@Composable internal fun SignalBars(rssiDbm: Double, dim: Boolean = false) {
    val level = when { rssiDbm >= -60 -> 4; rssiDbm >= -70 -> 3; rssiDbm >= -82 -> 2; else -> 1 }
    val on = if (dim) NvxColors.Neutral else NvxColors.Primary
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) Box(Modifier.width(4.dp).height((4 + i * 4).dp).clip(RoundedCornerShape(1.dp))
            .background(if (i <= level) on else Color(0x33FFFFFF)))
    }
}

/** Thanh các bước: bước < current là đã xong, bước = current là đang làm, current = số bước nghĩa là xong hết. */
@Composable internal fun StepRow(steps: List<String>, current: Int) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        steps.forEachIndexed { i, label ->
            val done = i < current
            val active = i == current
            val c = if (done || active) MaterialTheme.colorScheme.primary else NvxColors.Outline
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(if (done) c else Color.Transparent).border(2.dp, c, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text(if (done) "\u2713" else "${i + 1}", color = if (done) Color.White else c, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(4.dp))
                Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall,
                    color = if (done || active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

/** Tiêu đề mục có thể mở/đóng; nội dung do người gọi vẽ ngay bên dưới khi open = true. */
@Composable internal fun DisclosureHeader(title: String, subtitle: String?, open: Boolean, onToggle: () -> Unit) =
    Panel(Modifier.clickable { onToggle() }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (open) "\u25B2" else "\u25BC", color = MaterialTheme.colorScheme.primary)
        }
    }
