package vn.nvxconnect.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens của NVX Connect (Premium Smart Cockpit, dark).
 * Quy ước màu: xanh điện = hành động/đang quét; xanh lá CHỈ cho trạng thái đã xác minh/đã kết nối thật;
 * vàng = chờ xác minh/cảnh báo; đỏ = lỗi thật.
 */
object NvxColors {
    val Background = Color(0xFF050B1A)
    val Surface = Color(0xFF0B1730)
    val SurfaceHigh = Color(0xFF10213F)
    val Outline = Color(0xFF22395F)
    val OutlineSoft = Color(0xFF1A2D4D)
    val Primary = Color(0xFF3B8EFF)
    val PrimaryDeep = Color(0xFF1F5FD6)
    val Success = Color(0xFF34D399)
    val Warning = Color(0xFFFFB020)
    val Danger = Color(0xFFFF5C6C)
    val Neutral = Color(0xFF93A4C3)
    val TextPrimary = Color(0xFFF2F6FF)
    val TextSecondary = Color(0xFF9FB0CF)
    val HeroTop = Color(0xFF123A7A)
}

object NvxSpacing { val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val lg = 16.dp; val xl = 24.dp }
object NvxRadius { val sm = 12.dp; val md = 16.dp; val lg = 20.dp; val xl = 28.dp }
object NvxElevation { val flat = 0.dp; val raised = 4.dp }
object NvxMotion { const val FAST = 150; const val NORMAL = 300; const val RADAR = 2400 }

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = NvxColors.Primary, onPrimary = Color.White,
        primaryContainer = NvxColors.PrimaryDeep, onPrimaryContainer = Color.White,
        secondary = NvxColors.Neutral,
        background = NvxColors.Background, onBackground = NvxColors.TextPrimary,
        surface = NvxColors.Surface, onSurface = NvxColors.TextPrimary,
        surfaceVariant = NvxColors.SurfaceHigh, onSurfaceVariant = NvxColors.TextSecondary,
        outline = NvxColors.Outline, outlineVariant = NvxColors.OutlineSoft,
        error = NvxColors.Danger, onError = Color.White),
    typography = Typography(
        displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = (-0.5).sp),
        headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp),
        headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
        titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp),
        titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
        labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 1.2.sp)),
    shapes = Shapes(small = RoundedCornerShape(NvxRadius.sm), medium = RoundedCornerShape(NvxRadius.md), large = RoundedCornerShape(NvxRadius.lg)),
    content = content)
