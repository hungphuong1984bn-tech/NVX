package vn.nvxconnect.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

private tailrec fun Context.findActivity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.findActivity(); else -> null }

/**
 * Chế độ toàn màn hình cho Cockpit: ẩn thanh hệ thống (vuốt từ mép để hiện tạm), giữ màn hình sáng, tùy chọn khóa ngang.
 * Khi rời (tắt chế độ, đổi tab, Back) mọi thứ được khôi phục. Không cần quyền mới.
 */
@Composable
internal fun ImmersiveEffect(enabled: Boolean, lockLandscape: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled, lockLandscape) {
        val activity = view.context.findActivity()
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        if (enabled) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            view.keepScreenOn = true
            if (lockLandscape) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            if (enabled) {
                controller?.show(WindowInsetsCompat.Type.systemBars())
                view.keepScreenOn = false
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}
