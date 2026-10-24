package vn.nvxconnect.location

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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import vn.nvxconnect.NvxApp
import vn.nvxconnect.domain.TripStatsResult
import vn.nvxconnect.ui.MainActivity

/**
 * Dịch vụ foreground (loại "location") giữ tiến trình sống để TripEngine tiếp tục ghi GPS/dẫn đường khi tắt màn hình.
 * Dịch vụ không ghi GPS: TripEngine (phạm vi ứng dụng) làm việc đó. Dịch vụ tự dừng khi hành trình kết thúc.
 * Chỉ khởi động từ giao diện đang mở (Android 12+ không cho bật dịch vụ foreground từ nền) sau khi đã có quyền vị trí.
 */
class TripService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as NvxApp
        val engine = app.tripEngine
        ensureChannel()
        val first = notification(engine.phase.value, engine.live.value)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIF_ID, first)
        if (intent?.action == ACTION_STOP) {
            app.navSession.end(); engine.stop()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        job?.cancel()
        job = scope.launch {
            combine(engine.phase, engine.live) { p, l -> p to l }.collect { (p, l) ->
                if (p == TripPhase.IDLE) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                else (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notification(p, l))
            }
        }
        return START_NOT_STICKY   // tiến trình bị hệ thống tắt: chuyến dở dang được khôi phục khi mở app lần sau
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ghi hành trình", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(phase: TripPhase, live: TripStatsResult?): Notification {
        val km = (live?.distanceM ?: 0.0) / 1000.0
        val title = if (phase == TripPhase.PAUSED) "Hành trình đang tạm dừng" else "Đang ghi hành trình (GPS điện thoại)"
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TripService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle(title)
            .setContentText("%.2f km".format(km)).setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Kết thúc", stop).build()).build()
    }

    companion object {
        private const val CHANNEL = "trip"; private const val NOTIF_ID = 42; const val ACTION_STOP = "vn.nvxconnect.STOP_TRIP"
        /** false nếu hệ thống từ chối (ví dụ app đang ở nền): khi đó hành trình chỉ ghi lúc app mở. */
        fun start(context: Context): Boolean = try { context.startForegroundService(Intent(context, TripService::class.java)); true } catch (e: Exception) { false }
    }
}
