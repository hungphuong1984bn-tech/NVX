package vn.nvxconnect.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.nvxconnect.auto.EvidenceLevel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

// Bộ thành phần dùng chung của giao diện "Smart Cockpit". Không chứa logic BLE: chỉ nhận dữ liệu đã có từ ViewModel.

@Composable internal fun NvxWordmark() = Row(verticalAlignment = Alignment.Bottom) {
    Text("NVX", fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontSize = 24.sp, color = NvxColors.TextPrimary)
    Spacer(Modifier.width(6.dp))
    Text("CONNECT", fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 3.sp, color = NvxColors.Primary, modifier = Modifier.padding(bottom = 3.dp))
}

/** Thanh tiêu đề: nút quay lại (tùy chọn), tiêu đề/wordmark, vùng hành động bên phải. */
@Composable internal fun AppTopBar(title: String?, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) =
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Quay lại" }) { Text("←", fontSize = 20.sp) }
        Column(Modifier.weight(1f)) {
            if (title == null) NvxWordmark() else Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }

@Composable internal fun SectionHeader(title: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) =
    Row(Modifier.fillMaxWidth().padding(top = NvxSpacing.sm), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Section(title)
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }

/** Nút chính: cao ≥ 56dp, có trạng thái đang xử lý. */
@Composable internal fun PrimaryActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) =
    Button(onClick = onClick, enabled = enabled && !loading, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(NvxRadius.lg)) {
        if (loading) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }

/** Thẻ trạng thái kết nối. tone phải do trạng thái THẬT quyết định (Good chỉ khi đã xác minh). */
@Composable internal fun ConnectionStatusCard(headline: String, detail: String?, tone: Tone, badges: List<Pair<String, Tone>> = emptyList(),
                                               content: @Composable ColumnScope.() -> Unit = {}) = HeroCard {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NvxSpacing.sm)) {
        StatusDot(toneColor(tone), 12)
        Text(headline, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
    }
    if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (badges.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { badges.forEach { (t, tn) -> StatusChip(t, tn) } }
    content()
}

/** Tóm tắt xe: dữ liệu do người dùng nhập (hồ sơ), không phải từ xe. */
@Composable internal fun VehicleSummaryCard(model: String, variant: String, footer: String? = null, trailing: @Composable () -> Unit = {}) = Panel {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("YAMAHA", style = MaterialTheme.typography.labelSmall, color = NvxColors.Primary, letterSpacing = 2.sp)
            Text("$model $variant".trim(), style = MaterialTheme.typography.titleMedium)
            if (footer != null) Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

/** Huy hiệu mức bằng chứng giao thức. Không hiển thị phần trăm tin cậy. */
@Composable internal fun ProtocolStatusBadge(level: EvidenceLevel) = StatusChip(level.name, when (level) {
    EvidenceLevel.OBSERVED -> Tone.Neutral; EvidenceLevel.HYPOTHESIS -> Tone.Warn; EvidenceLevel.VERIFIED -> Tone.Good; EvidenceLevel.UNKNOWN -> Tone.Bad
    EvidenceLevel.ENCRYPTED_OR_AUTHENTICATED -> Tone.Warn })

/** Thẻ thiết bị ứng viên: tên, RSSI, huy hiệu xếp hạng, bằng chứng và nút do người gọi cung cấp. */
@Composable internal fun DeviceCandidateCard(name: String, subtitle: String, rssiDbm: Int, scoreText: String?, badge: Pair<String, Tone>,
                                             evidence: List<String>, nextStep: String?, actions: @Composable RowScope.() -> Unit) = Panel {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NvxSpacing.md), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SignalBars(rssiDbm.toDouble())
        if (scoreText != null) Text(scoreText, style = MaterialTheme.typography.titleMedium)
    }
    StatusChip(badge.first, badge.second)
    evidence.forEach { Text("• $it", style = MaterialTheme.typography.labelSmall) }
    if (nextStep != null) Text("→ $nextStep", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), content = actions)
}

/** Một dòng kết quả/log: huy hiệu + tiêu đề + chi tiết. */
@Composable internal fun LogEntryCard(title: String, detail: String?, badge: @Composable () -> Unit) = Panel {
    badge()
    Text(title, style = MaterialTheme.typography.bodyMedium)
    if (detail != null) Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Radar quét BLE. Chỉ chuyển động khi active = true (đúng với trạng thái quét thật); dừng quét thì đứng yên, không tốn pin. */
@Composable internal fun ScanRadarAnimation(active: Boolean, modifier: Modifier = Modifier.size(160.dp)) {
    if (active) {
        val t = rememberInfiniteTransition(label = "radar")
        val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(NvxMotion.RADAR, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
        RadarCanvas(p, true, modifier)
    } else RadarCanvas(0f, false, modifier)
}

@Composable private fun RadarCanvas(progress: Float, active: Boolean, modifier: Modifier) {
    val desc = if (active) "Đang quét Bluetooth" else "Radar quét Bluetooth, đang dừng"
    Canvas(modifier.semantics { contentDescription = desc }) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f
        for (i in 1..3) drawCircle(NvxColors.Outline, radius = r * i / 3f, center = c, style = Stroke(1.dp.toPx()))
        if (active) for (k in 0..1) {
            val q = (progress + k * 0.5f) % 1f
            drawCircle(NvxColors.Primary.copy(alpha = (1f - q) * 0.55f), radius = r * q, center = c, style = Stroke(2.dp.toPx()))
        }
        drawCircle(if (active) NvxColors.Primary else NvxColors.Neutral, radius = r * 0.1f, center = c)
    }
}

@Composable internal fun EmptyState(title: String, body: String? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null) = Panel {
    Text(title, style = MaterialTheme.typography.titleSmall)
    if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (actionLabel != null && onAction != null) OutlinedButton(onClick = onAction) { Text(actionLabel) }
}

@Composable internal fun ErrorState(title: String, body: String? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null) = Panel {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
    if (body != null) Text(body, style = MaterialTheme.typography.bodySmall)
    if (actionLabel != null && onAction != null) OutlinedButton(onClick = onAction) { Text(actionLabel) }
}

@Composable internal fun LoadingState(text: String) = Panel {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NvxSpacing.md)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Thanh điều hướng dưới, theo token màu. */
@Composable internal fun NvxBottomBar(labels: List<String>, icons: List<ImageVector>, selected: Int, onSelect: (Int) -> Unit) =
    NavigationBar(containerColor = NvxColors.Surface, contentColor = NvxColors.TextPrimary) {
        labels.forEachIndexed { i, t ->
            NavigationBarItem(selected = selected == i, onClick = { onSelect(i) },
                icon = { Icon(icons[i], contentDescription = t) },
                label = { Text(t, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = NvxColors.OnPrimaryDeep, selectedTextColor = NvxColors.Primary,
                    indicatorColor = NvxColors.PrimaryDeep, unselectedIconColor = NvxColors.TextSecondary, unselectedTextColor = NvxColors.TextSecondary))
        }
    }

/** Ô số liệu lớn (vd: Thiết bị tìm thấy / Ứng viên / Đã kết nối). */
@Composable internal fun CountTile(value: String, label: String, modifier: Modifier = Modifier, accent: Color = NvxColors.TextPrimary, valueSize: androidx.compose.ui.unit.TextUnit = 28.sp) =
    Surface(modifier, shape = RoundedCornerShape(NvxRadius.md), color = NvxColors.SurfaceHigh, border = androidx.compose.foundation.BorderStroke(1.dp, NvxColors.OutlineSoft)) {
        Column(Modifier.padding(NvxSpacing.md).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = valueSize, fontWeight = FontWeight.Bold, color = accent, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }

/** Một dòng "nhãn — giá trị" có màu theo tone (dùng cho Trạng thái kết nối). */
@Composable internal fun StatusRow(label: String, value: String, tone: Tone) =
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = toneColor(tone), maxLines = 2, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }

/** Dòng menu có mũi tên (màn Cài đặt). */
@Composable internal fun MenuRow(title: String, subtitle: String? = null, trailing: String? = null, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(vertical = NvxSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("  ›", style = MaterialTheme.typography.titleMedium, color = NvxColors.TextSecondary)
    }

/** Ảnh xe NVX (res/drawable-nodpi/bike_hero.webp, nền trong suốt) trên nền ánh xanh nhẹ. */
@Composable internal fun BikeHero(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().clip(RoundedCornerShape(NvxRadius.lg))
    .background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(NvxColors.Primary.copy(alpha = 0.28f), Color.Transparent)))
    .padding(horizontal = NvxSpacing.lg, vertical = NvxSpacing.sm)) {
    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(vn.nvxconnect.R.drawable.bike_hero), contentDescription = "Yamaha NVX 155",
        modifier = Modifier.fillMaxWidth(), contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
}

/** Thời gian hiện tại cập nhật mỗi giây, để dữ liệu xe quá cũ tự chuyển về "Chưa khả dụng". */
@Composable internal fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    return now
}
