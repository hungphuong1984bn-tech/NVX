package vn.nvxconnect.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.nvxconnect.AppInfo
import vn.nvxconnect.NvxApp
import vn.nvxconnect.data.*
import vn.nvxconnect.domain.*
import vn.nvxconnect.location.EngineEvent
import vn.nvxconnect.location.TripService
import vn.nvxconnect.navigation.UrlPolicy

data class Replay(val trip: Trip, val points: List<GeoPoint>, val stats: TripStatsResult)

/** Cài đặt, dữ liệu người dùng (hành trình, xăng, bảo dưỡng) và điều khiển hành trình. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val nvx = getApplication<NvxApp>()
    private val dao = nvx.db.dao()
    private val repo = nvx.settingsRepo
    val engine = nvx.tripEngine

    private fun <T> Flow<T>.state(init: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), init)
    val settings = repo.settings.state(AppSettings())
    val trips = dao.trips().state(emptyList())
    val fuel = dao.fuel().state(emptyList())
    val maintenance = dao.maintenance().state(emptyList())

    val message = MutableStateFlow<String?>(null)
    val replay = MutableStateFlow<Replay?>(null)
    /** Chuỗi nạp từ tệp CSV của Telemetry Center để PHÁT LẠI. Tách khỏi dữ liệu trực tiếp; không bao giờ ghi vào lịch sử trực tiếp. */
    val replaySeries = MutableStateFlow<Map<String, List<vn.nvxconnect.pro.Sample>>>(emptyMap())

    init {
        viewModelScope.launch {
            engine.events.collect { e ->
                when (e) {
                    is EngineEvent.Message -> message.value = e.text
                    is EngineEvent.TripSaved -> message.value = "Đã lưu hành trình %.2f km".format(e.distanceM / 1000)
                    is EngineEvent.SpeedOver -> message.value = "Vượt ngưỡng ${e.limitKmh} km/h (GPS: %.0f)".format(e.kmh)
                    else -> {}
                }
            }
        }
    }

    // ---------------------------------------------------------------- điều khiển hành trình (gọi sau khi có quyền vị trí)
    fun startTrip() {
        if (!engine.start()) return
        if (!TripService.start(getApplication())) message.value = "Không bật được dịch vụ nền: hành trình chỉ ghi khi ứng dụng đang mở."
    }
    fun pauseTrip() { engine.pause() }
    fun resumeTrip() { engine.resume() }
    fun stopTrip() { nvx.navSession.end(); engine.stop() }

    // ---------------------------------------------------------------- hành trình đã lưu
    fun openTrip(id: Long) = viewModelScope.launch {
        val t = dao.trip(id) ?: return@launch
        val pts = dao.points(id).map { GeoPoint(it.lat, it.lon, it.speedMps, it.accuracyM, it.timeMs) }
        replay.value = Replay(t, pts, TripStats.compute(pts))
    }
    fun closeReplay() { replay.value = null }
    /** Xóa nhiều chuyến (đã được người dùng xác nhận ở giao diện). Chuyến đang xem lại cũng bị đóng nếu nằm trong danh sách. */
    fun deleteTrips(ids: List<Long>) = viewModelScope.launch {
        ids.forEach { dao.deletePoints(it); dao.deleteTrip(it) }
        if (replay.value?.trip?.id in ids) replay.value = null
        message.value = "Đã xóa ${ids.size} hành trình"
    }
    fun deleteTrip(id: Long) = viewModelScope.launch { dao.deletePoints(id); dao.deleteTrip(id); if (replay.value?.trip?.id == id) replay.value = null }

    // ---------------------------------------------------------------- cài đặt
    fun setSpeedLimit(k: Int) = viewModelScope.launch { repo.setSpeedLimit(k) }
    fun setOdo(km: Int) = viewModelScope.launch { repo.setOdo(km) }
    fun setAlertSound(v: Boolean) = viewModelScope.launch { repo.setAlertSound(v) }
    fun setVoice(v: Boolean) = viewModelScope.launch { repo.setVoice(v) }
    fun setAutoConnect(v: Boolean) = viewModelScope.launch { repo.setAutoConnect(v) }
    fun setTftMapOnly(v: Boolean) = viewModelScope.launch { repo.setTftMapOnly(v) }
    fun setTftFlag(key: String, v: Boolean) = viewModelScope.launch { repo.setTftFlag(key, v) }
    fun setDemo(on: Boolean) = viewModelScope.launch { repo.setDemo(on); if (on) DemoData.seed(dao) else DemoData.clear(dao) }

    /** Trả về lỗi tiếng Việt hoặc null nếu đã lưu. */
    fun saveVehicle(p: VehicleProfile): String? {
        val err = ProfileValidator.validate(p); if (err != null) return err
        viewModelScope.launch { repo.setVehicle(p) }; return null
    }
    fun saveRoutingUrl(raw: String): String? {
        val u = UrlPolicy.normalize(raw) ?: return "Cần địa chỉ https:// (http chỉ cho máy nội bộ)"
        viewModelScope.launch { repo.setRoutingUrl(u) }; return null
    }
    fun saveGeocodeUrl(raw: String): String? {
        val u = UrlPolicy.normalize(raw) ?: return "Cần địa chỉ https:// (http chỉ cho máy nội bộ)"
        viewModelScope.launch { repo.setGeocodeUrl(u) }; return null
    }
    fun resetServers() = viewModelScope.launch { repo.setRoutingUrl(vn.nvxconnect.navigation.NetDefaults.OSRM_BASE); repo.setGeocodeUrl(vn.nvxconnect.navigation.NetDefaults.NOMINATIM_BASE) }

    // ---------------------------------------------------------------- nhiên liệu
    /** Trả về lỗi hoặc null. totalVnd để trống thì tính = lít × giá. */
    fun addFuel(odo: Int?, liters: Double?, price: Int?, totalVnd: Long?, full: Boolean): String? {
        val err = FuelValidator.validate(odo, liters, price, totalVnd, fuel.value.filter { !it.isDemo }); if (err != null) return err
        viewModelScope.launch {
            dao.upsertFuel(FuelEntry(odometerKm = odo!!, liters = liters!!, pricePerLiter = price!!,
                totalVnd = totalVnd ?: Math.round(liters * price), fullTank = full, dateMs = System.currentTimeMillis()))
        }
        return null
    }
    fun deleteFuel(id: Long) = viewModelScope.launch { dao.deleteFuel(id) }

    // ---------------------------------------------------------------- bảo dưỡng
    fun addMaint(name: String, km: Int?, days: Int?) = viewModelScope.launch {
        dao.upsertMaintenance(MaintenanceItem(name = name, intervalKm = km, intervalDays = days, lastDoneOdoKm = settings.value.odoKm,
            lastDoneMs = if (km == null && days == null) 0 else System.currentTimeMillis()))
    }
    fun markDone(i: MaintenanceItem) = viewModelScope.launch { dao.upsertMaintenance(i.copy(lastDoneOdoKm = settings.value.odoKm, lastDoneMs = System.currentTimeMillis())) }
    fun deleteMaint(id: Long) = viewModelScope.launch { dao.deleteMaintenance(id) }
    /** Gợi ý tham khảo của app, KHÔNG phải thông số chính thức của Yamaha: hãy đối chiếu sổ bảo dưỡng. */
    fun addPresets() = viewModelScope.launch {
        listOf("Nhớt máy" to 3000, "Nhớt láp (hộp số)" to 9000, "Lọc gió" to 12000, "Bugi" to 12000, "Dây curoa" to 24000).forEach {
            dao.upsertMaintenance(MaintenanceItem(name = it.first, intervalKm = it.second, intervalDays = null, lastDoneOdoKm = 0, lastDoneMs = 0))
        }
    }

    // ---------------------------------------------------------------- quyền riêng tư
    fun exportData(onReady: (String) -> Unit) = viewModelScope.launch {
        val json = withContext(Dispatchers.IO) {
            DataExporter.toJson(settings.value.vehicle, settings.value.odoKm, dao.allTrips(), dao.allPoints(), dao.allFuel(), dao.allMaintenance(), System.currentTimeMillis(), AppInfo.VERSION)
        }
        onReady(json)
    }
    fun deleteAllData() = viewModelScope.launch {
        nvx.navSession.end(); engine.stop()
        dao.deleteEverything(); repo.clearAll(); replay.value = null
        nvx.helpProgress.reset(); nvx.vehicleData.clear(); nvx.history.clear(); nvx.proPrefs.clear()
        message.value = "Đã xóa toàn bộ dữ liệu trong ứng dụng"
    }
}
