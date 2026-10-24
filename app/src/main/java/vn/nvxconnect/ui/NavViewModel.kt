package vn.nvxconnect.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import vn.nvxconnect.NvxApp
import vn.nvxconnect.location.NetworkMonitor
import vn.nvxconnect.location.TripService
import vn.nvxconnect.navigation.*
import vn.nvxconnect.protocol.UnsupportedTftBridge

data class RoutePreview(val route: Route, val destinationName: String)

/** Tìm địa điểm, tính tuyến, bắt đầu/kết thúc dẫn đường. Dẫn đường chạy trong TripEngine nên vẫn chạy khi tắt màn hình. */
class NavViewModel(app: Application) : AndroidViewModel(app) {
    private val nvx = getApplication<NvxApp>()
    val engine = nvx.tripEngine
    val session = nvx.navSession
    val tft = UnsupportedTftBridge
    val voiceStatus = nvx.voice.status
    val online: StateFlow<Boolean> = NetworkMonitor.online(app).stateIn(viewModelScope, SharingStarted.Eagerly, NetworkMonitor.isOnline(app))

    val results = MutableStateFlow<List<Place>>(emptyList())
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val preview = MutableStateFlow<RoutePreview?>(null)

    fun acquirePreview() = engine.acquirePreview()
    fun releasePreview() = engine.releasePreview()

    /** Tìm theo từ khóa hoặc nhập thẳng tọa độ "vĩ độ, kinh độ" (dùng được khi không có máy chủ tìm kiếm). */
    fun search(q: String) {
        message.value = null; results.value = emptyList()
        CoordinateParser.parse(q)?.let { ll -> choose(Place("Tọa độ đã nhập", "%.5f, %.5f".format(ll.lat, ll.lon), ll, "coordinate")); return }
        busy.value = true
        viewModelScope.launch {
            val near = engine.lastFix.value?.let { LatLon(it.lat, it.lon) }
            when (val r = nvx.geocoder().search(q, near)) {
                is NetResult.Ok -> results.value = r.value
                is NetResult.Fail -> message.value = r.message
            }
            busy.value = false
        }
    }

    fun choose(p: Place) {
        val from = engine.lastFix.value?.let { LatLon(it.lat, it.lon) }
        if (from == null) { message.value = "Chưa có vị trí GPS hiện tại để tính đường. Hãy chờ tín hiệu GPS."; return }
        busy.value = true; message.value = null
        viewModelScope.launch {
            when (val r = nvx.planner().route(from, p.location)) {
                is NetResult.Ok -> { preview.value = RoutePreview(r.value, p.name); results.value = emptyList() }
                is NetResult.Fail -> message.value = r.message
            }
            busy.value = false
        }
    }

    /** Gọi sau khi đã có quyền vị trí. Bắt đầu ghi hành trình + dẫn đường. */
    fun startNavigation() {
        val p = preview.value ?: return
        session.begin(p.route)
        if (!TripService.start(getApplication())) message.value = "Không bật được dịch vụ nền: hành trình chỉ ghi khi ứng dụng đang mở."
        preview.value = null
    }
    fun endNavigation() { session.end() }
    fun clearPreview() { preview.value = null; results.value = emptyList(); message.value = null }
}
