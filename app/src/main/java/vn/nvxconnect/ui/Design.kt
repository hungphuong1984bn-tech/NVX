package vn.nvxconnect.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.nvxconnect.pro.ThemeMode

/**
 * Hệ thống thiết kế NVX Connect Pro: premium automotive, nền than chì, điểm nhấn cyan.
 * Quy ước màu: cyan = hành động/đang hoạt động; xanh lá CHỈ cho trạng thái đã xác minh/kết nối thật;
 * vàng = chờ xác minh/cảnh báo; đỏ = lỗi thật. Màu cảnh báo không dùng để trang trí.
 */
private class Palette(
    val background: Color, val surface: Color, val surfaceHigh: Color, val outline: Color, val outlineSoft: Color,
    val primary: Color, val primaryDeep: Color, val onPrimary: Color, val success: Color, val warning: Color, val danger: Color,
    val neutral: Color, val textPrimary: Color, val textSecondary: Color, val heroTop: Color, val onDanger: Color
)

private val DarkPalette = Palette(
    background = Color(0xFF0A0D12), surface = Color(0xFF12171E), surfaceHigh = Color(0xFF1A212A), outline = Color(0xFF2B3440), outlineSoft = Color(0xFF1F2832),
    primary = Color(0xFF22D3EE), primaryDeep = Color(0xFF0E7490), onPrimary = Color(0xFF00232B), success = Color(0xFF34D399), warning = Color(0xFFFFB020),
    danger = Color(0xFFFF6B7A), neutral = Color(0xFF9AA7B6), textPrimary = Color(0xFFF1F5F9), textSecondary = Color(0xFFA3B1C2), heroTop = Color(0xFF0F3A46), onDanger = Color(0xFF2A0008))

// Light theme thiết kế riêng (không đảo màu): nền xám lạnh, thẻ trắng, cyan đậm để đạt tương phản chữ ≥ 4.5:1.
private val LightPalette = Palette(
    background = Color(0xFFF2F5F8), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFE9EEF3), outline = Color(0xFFC3CCD6), outlineSoft = Color(0xFFDCE3EA),
    primary = Color(0xFF0B7285), primaryDeep = Color(0xFF0A5565), onPrimary = Color(0xFFFFFFFF), success = Color(0xFF0B8457), warning = Color(0xFF9A5B00),
    danger = Color(0xFFC62839), neutral = Color(0xFF516070), textPrimary = Color(0xFF0F1720), textSecondary = Color(0xFF445260), heroTop = Color(0xFFCDEAF0), onDanger = Color(0xFFFFFFFF))

object NvxColors {
    /** Chủ đề đang áp dụng. Do [AppTheme] đặt; các thành phần đọc qua getter nên tự vẽ lại khi đổi chủ đề. */
    internal var dark by mutableStateOf(true)
    private val p get() = if (dark) DarkPalette else LightPalette
    val Background get() = p.background
    val Surface get() = p.surface
    val SurfaceHigh get() = p.surfaceHigh
    val Outline get() = p.outline
    val OutlineSoft get() = p.outlineSoft
    val Primary get() = p.primary
    val PrimaryDeep get() = p.primaryDeep
    val OnPrimary get() = p.onPrimary
    val Success get() = p.success
    val Warning get() = p.warning
    val Danger get() = p.danger
    val OnDanger get() = p.onDanger
    val Neutral get() = p.neutral
    val TextPrimary get() = p.textPrimary
    val TextSecondary get() = p.textSecondary
    val HeroTop get() = p.heroTop
    /** Chữ trên nền PrimaryDeep (chỉ báo thanh điều hướng, nút chọn). */
    val OnPrimaryDeep: Color get() = Color.White
}

object NvxSpacing { val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val lg = 16.dp; val xl = 24.dp }
object NvxRadius { val sm = 12.dp; val md = 16.dp; val lg = 20.dp; val xl = 28.dp }
object NvxElevation { val flat = 0.dp; val raised = 4.dp }
object NvxMotion { const val FAST = 150; const val NORMAL = 300; const val RADAR = 2400 }

/** Thông tin bố cục theo kích thước màn hình: dùng để giới hạn bề rộng nội dung và chia cột trên màn hình lớn/xoay ngang. */
data class LayoutInfo(val widthDp: Int, val heightDp: Int) {
    val landscape get() = widthDp > heightDp
    val wide get() = widthDp >= 600
    /** Số cột cho lưới thẻ nhỏ. */
    val columns get() = when { widthDp >= 840 -> 3; widthDp >= 560 || landscape && widthDp >= 480 -> 2; else -> 1 }
    val contentMaxWidth: Dp get() = if (widthDp >= 840) 960.dp else if (widthDp >= 600) 720.dp else Dp.Unspecified
}

@Composable
fun rememberLayoutInfo(): LayoutInfo { val c = LocalConfiguration.current; return LayoutInfo(c.screenWidthDp, c.screenHeightDp) }

@Composable
fun AppTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    if (NvxColors.dark != dark) NvxColors.dark = dark
    val p = if (dark) DarkPalette else LightPalette
    val base = if (dark) darkColorScheme(
        primary = p.primary, onPrimary = p.onPrimary, primaryContainer = p.primaryDeep, onPrimaryContainer = Color.White,
        secondary = p.neutral, background = p.background, onBackground = p.textPrimary, surface = p.surface, onSurface = p.textPrimary,
        surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textSecondary, outline = p.outline, outlineVariant = p.outlineSoft, error = p.danger, onError = p.onDanger)
    else lightColorScheme(
        primary = p.primary, onPrimary = p.onPrimary, primaryContainer = p.primaryDeep, onPrimaryContainer = Color.White,
        secondary = p.neutral, background = p.background, onBackground = p.textPrimary, surface = p.surface, onSurface = p.textPrimary,
        surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textSecondary, outline = p.outline, outlineVariant = p.outlineSoft, error = p.danger, onError = p.onDanger)
    MaterialTheme(
        colorScheme = base,
        typography = Typography(
            displayLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 88.sp, letterSpacing = (-1.5).sp),
            displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = (-0.5).sp),
            headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp),
            headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
            titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 1.2.sp)),
        shapes = Shapes(small = RoundedCornerShape(NvxRadius.sm), medium = RoundedCornerShape(NvxRadius.md), large = RoundedCornerShape(NvxRadius.lg)),
        content = content)
}

/** Kiểu chữ đơn cách cho HEX/UUID/log (không cần tải font). */
val NvxMono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
