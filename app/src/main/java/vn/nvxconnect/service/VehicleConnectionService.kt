package vn.nvxconnect.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import vn.nvxconnect.NvxApp
import vn.nvxconnect.ui.MainActivity

/**
 * Dịch vụ foreground loại connectedDevice: giữ TIẾN TRÌNH sống để phiên GATT (thuộc [NvxApp.vehicleConnection], KHÔNG thuộc dịch vụ)
 * và đường ghi dữ liệu tiếp tục chạy khi tắt màn hình. Dịch vụ không giữ GATT nên không thể tạo phiên thứ hai.
 * Chỉ được bật từ [VehicleServiceHost] khi giao diện đang ở tiền cảnh (Android 12+ cấm bật dịch vụ foreground từ nền).
 * KHÔNG dùng quyền vị trí nền.
 */
class VehicleConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var lastText = ""; private var lastAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as NvxApp
        ensureChannel()
        val conn = app.vehicleConnection; val capture = app.capture
        val first = VehicleServicePolicy.notification(conn.state.value, conn.hint.value, capture.stats.value, System.currentTimeMillis())
        // startForeground PHẢI gọi trong vài giây sau startForegroundService, kể cả khi sắp dừng.
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, build(first), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIF_ID, build(first))
        } catch (e: Exception) {   // ví dụ thiếu quyền BLUETOOTH_CONNECT (Android 14+) hoặc hệ thống từ chối
            app.vehicleServiceHost.reportFailure(e.message ?: e.javaClass.simpleName)
            stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            conn.disconnect()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        app.vehicleServiceHost.reportRunning()
        job?.cancel()
        job = scope.launch {
            combine(conn.state, conn.hint, capture.stats) { s, h, st -> Triple(s, h, st) }.collect { (s, h, st) ->
                if (!VehicleServicePolicy.shouldRun(s)) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return@collect }
                val now = System.currentTimeMillis()
                val c = VehicleServicePolicy.notification(s, h, st, now)
                val text = c.title + "|" + c.text
                // Chỉ cập nhật khi nội dung đổi và cách lần trước ≥ 1,5 s (Android giới hạn tần suất cập nhật thông báo).
                if (text != lastText && now - lastAt >= 1_500) {
                    lastText = text; lastAt = now
                    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, build(c))
                }
            }
        }
        // Không tự khởi động lại bằng START_STICKY: Android 12+ có thể từ chối dịch vụ foreground bật từ nền.
        // Khi tiến trình bị hệ điều hành tắt, lần mở app sau sẽ báo "phiên trước bị dừng đột ngột" và người dùng bấm kết nối lại.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel(); scope.cancel()
        (application as? NvxApp)?.vehicleServiceHost?.reportStopped()
        // Ghi nốt sự kiện còn trong hàng đợi (luồng riêng, không chặn luồng chính).
        (application as? NvxApp)?.let { a -> Thread { try { a.eventLog.flushBlocking() } catch (_: Exception) {} }.start() }
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Kết nối xe", NotificationManager.IMPORTANCE_LOW))
    }

    private fun build(c: NotificationContent): Notification {
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 11, Intent(this, VehicleConnectionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle(c.title).setContentText(c.text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Ngắt kết nối", stop).build()).build()
    }

    companion object {
        private const val CHANNEL = "vehicle_link"; private const val NOTIF_ID = 43
        const val ACTION_STOP = "vn.nvxconnect.STOP_VEHICLE_LINK"
    }
}
