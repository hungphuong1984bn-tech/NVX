package vn.nvxconnect.service

import vn.nvxconnect.bluetooth.ErrorKind
import vn.nvxconnect.protocol.ConnState
import vn.nvxconnect.protocol.LinkHint
import vn.nvxconnect.telemetry.CaptureStats
import vn.nvxconnect.telemetry.WriterHealth
import java.util.Locale

data class NotificationContent(val title: String, val text: String)

/** Quy tắc thuần (test được) cho dịch vụ foreground giữ kết nối xe. Không phụ thuộc Android. */
object VehicleServicePolicy {
    /**
     * Dịch vụ chạy khi người dùng còn MUỐN kết nối: đang kết nối/chờ xe, hoặc Bluetooth đang tắt (sẽ tự nối lại khi bật).
     * Dừng khi: đã ngắt, hoặc lỗi không thể tự khắc phục (cần xác thực, thiếu quyền, địa chỉ sai, không hỗ trợ).
     */
    fun shouldRun(s: ConnState): Boolean = when (s) {
        is ConnState.Disconnected -> false
        is ConnState.Error -> s.kind == ErrorKind.BLUETOOTH_OFF
        else -> true
    }

    /** Địa chỉ cần nhớ để nhắc "phiên trước bị dừng đột ngột": chỉ khi còn muốn kết nối. */
    fun wantsSession(s: ConnState) = shouldRun(s)

    const val NO_DATA_AFTER_MS = 5_000L

    fun notification(state: ConnState, hint: LinkHint, stats: CaptureStats, nowMs: Long): NotificationContent {
        val warn = buildString {
            if (stats.writer == WriterHealth.DEGRADED) append(" · LỖI GHI DỮ LIỆU")
            if (stats.lostByApp > 0) append(" · app mất ${stats.lostByApp} gói")
        }
        return when (state) {
            is ConnState.Ready -> {
                val last = stats.lastFrameWallMs
                val fresh = last != null && nowMs - last in 0..NO_DATA_AFTER_MS
                if (fresh) NotificationContent("NVX Connect · đang nhận dữ liệu xe", String.format(Locale.US, "%.0f gói/giây · đã nhận %d gói", stats.ratePerSec, stats.received) + warn)
                else NotificationContent("NVX Connect · đã kết nối Bluetooth", "Chưa có dữ liệu từ xe (xe có thể đang tắt khóa điện)" + warn)
            }
            is ConnState.Connecting, is ConnState.Connected, is ConnState.DiscoveringServices -> NotificationContent("NVX Connect", "Đang kết nối xe…" + warn)
            is ConnState.Reconnecting -> NotificationContent("NVX Connect · đang chờ xe",
                (if (hint == LinkHint.WAITING_IGNITION) "Xe vừa ngắt kết nối (có thể tắt khóa điện). " else "") + "Thử nối lại lần ${state.attempt}; tự nối khi xe hoạt động trở lại" + warn)
            is ConnState.Error -> NotificationContent("NVX Connect · Bluetooth đang tắt", "Sẽ tự nối lại khi bạn bật Bluetooth" + warn)
            is ConnState.Disconnected -> NotificationContent("NVX Connect", "Đã ngắt kết nối")
        }
    }
}
