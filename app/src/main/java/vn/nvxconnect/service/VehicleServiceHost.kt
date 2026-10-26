package vn.nvxconnect.service

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import vn.nvxconnect.protocol.ConnState
import vn.nvxconnect.protocol.VehicleConnection

enum class ServiceState { STOPPED, STARTING, RUNNING, DENIED }
data class ServiceStatus(val state: ServiceState = ServiceState.STOPPED, val reason: String? = null)

/** Ghi nhớ ý định kết nối để nhận biết "phiên trước bị dừng đột ngột" (tiến trình bị tắt khi còn muốn kết nối). */
class SessionIntentStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("nvx_vehicle_session", Context.MODE_PRIVATE)
    /** true nếu lần chạy trước kết thúc khi vẫn đang muốn kết nối. Đọc MỘT lần lúc khởi động. */
    val previousSessionUnclean: Boolean = sp.getBoolean("wanted", false)
    fun setWanted(w: Boolean) { sp.edit().putBoolean("wanted", w).apply() }
}

/**
 * Nối trạng thái kết nối với dịch vụ foreground: bật khi bắt đầu kết nối, tắt khi ngắt/lỗi không tự khắc phục.
 * Chạy trong phạm vi tiến trình nên hoạt động cả khi không có Activity. Nếu hệ thống từ chối bật dịch vụ,
 * trạng thái là DENIED (giao diện hiển thị cảnh báo "có thể bị dừng khi tắt màn hình") — KHÔNG im lặng.
 */
class VehicleServiceHost(private val context: Context, private val conn: VehicleConnection, private val scope: CoroutineScope, private val intent: SessionIntentStore) {
    private val _status = MutableStateFlow(ServiceStatus())
    val status: StateFlow<ServiceStatus> = _status

    fun start() {
        scope.launch {
            conn.state.distinctUntilChangedBy { VehicleServicePolicy.shouldRun(it) }.collect { s -> apply(s) }
        }
    }

    private fun apply(s: ConnState) {
        val run = VehicleServicePolicy.shouldRun(s)
        intent.setWanted(run)
        if (run) {
            if (_status.value.state == ServiceState.RUNNING || _status.value.state == ServiceState.STARTING) return
            _status.value = ServiceStatus(ServiceState.STARTING)
            try { context.startForegroundService(Intent(context, VehicleConnectionService::class.java)) }
            catch (e: Exception) { _status.value = ServiceStatus(ServiceState.DENIED, e.message ?: e.javaClass.simpleName) }
        } else if (_status.value.state != ServiceState.STOPPED) {
            runCatching { context.stopService(Intent(context, VehicleConnectionService::class.java)) }
            _status.value = ServiceStatus(ServiceState.STOPPED)
        }
    }

    fun reportRunning() { _status.value = ServiceStatus(ServiceState.RUNNING) }
    fun reportStopped() { if (_status.value.state != ServiceState.DENIED) _status.value = ServiceStatus(ServiceState.STOPPED) }
    fun reportFailure(reason: String) { _status.value = ServiceStatus(ServiceState.DENIED, reason) }
}
