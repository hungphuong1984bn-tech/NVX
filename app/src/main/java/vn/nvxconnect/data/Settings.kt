package vn.nvxconnect.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import vn.nvxconnect.bluetooth.SavedDevice
import vn.nvxconnect.bluetooth.SavedDeviceCodec
import vn.nvxconnect.domain.VehicleProfile
import vn.nvxconnect.navigation.NetDefaults

private val Context.store by preferencesDataStore("settings")

/** Đơn vị cố định: km, km/h, lít, VND. */
data class AppSettings(
    val demo: Boolean = false,
    /** ODO do người dùng nhập (USER_INPUT), không đọc từ xe. */
    val odoKm: Int = 0,
    val speedLimitKmh: Int = 0,
    val alertSound: Boolean = true,
    val voiceGuidance: Boolean = true,
    val autoConnect: Boolean = false,
    val routingBaseUrl: String = NetDefaults.OSRM_BASE,
    val geocodeBaseUrl: String = NetDefaults.NOMINATIM_BASE,
    val vehicle: VehicleProfile = VehicleProfile(),
    /** Lựa chọn hiển thị lên TFT: chỉ là sở thích đã lưu, CHƯA có hiệu lực vì chưa có giao thức TFT xác minh. */
    val tftMapOnly: Boolean = false,
    val tftSpeed: Boolean = true, val tftRoute: Boolean = true, val tftTrip: Boolean = true, val tftVehicle: Boolean = true, val tftClock: Boolean = true
)

class SettingsRepository(private val context: Context) {
    private object K {
        val DEMO = booleanPreferencesKey("demo"); val ODO = intPreferencesKey("odo_km"); val LIMIT = intPreferencesKey("speed_limit")
        val SOUND = booleanPreferencesKey("alert_sound"); val VOICE = booleanPreferencesKey("voice"); val AUTO = booleanPreferencesKey("auto_connect")
        val ROUTE = stringPreferencesKey("routing_url"); val GEO = stringPreferencesKey("geocode_url")
        val V_MODEL = stringPreferencesKey("v_model"); val V_VARIANT = stringPreferencesKey("v_variant")
        val V_CC = intPreferencesKey("v_cc"); val V_DATE = stringPreferencesKey("v_date")
        val SAVED = stringPreferencesKey("saved_devices")
        val TFT_MAP = booleanPreferencesKey("tft_map_only")
    }
    private val d = VehicleProfile(); private val def = AppSettings()

    val settings: Flow<AppSettings> = context.store.data.map { p ->
        AppSettings(
            demo = p[K.DEMO] ?: false, odoKm = p[K.ODO] ?: 0, speedLimitKmh = p[K.LIMIT] ?: 0,
            alertSound = p[K.SOUND] ?: true, voiceGuidance = p[K.VOICE] ?: true, autoConnect = p[K.AUTO] ?: false,
            routingBaseUrl = p[K.ROUTE] ?: def.routingBaseUrl, geocodeBaseUrl = p[K.GEO] ?: def.geocodeBaseUrl,
            vehicle = VehicleProfile(p[K.V_MODEL] ?: d.model, p[K.V_VARIANT] ?: d.variant, p[K.V_CC] ?: d.engineCc, p[K.V_DATE] ?: d.purchaseDate),
            tftMapOnly = p[K.TFT_MAP] ?: false,
            tftSpeed = p[booleanPreferencesKey("tft_speed")] ?: true, tftRoute = p[booleanPreferencesKey("tft_route")] ?: true,
            tftTrip = p[booleanPreferencesKey("tft_trip")] ?: true, tftVehicle = p[booleanPreferencesKey("tft_vehicle")] ?: true,
            tftClock = p[booleanPreferencesKey("tft_clock")] ?: true)
    }
    val savedDevices: Flow<List<SavedDevice>> = context.store.data.map { SavedDeviceCodec.decode(it[K.SAVED] ?: "") }

    suspend fun setDemo(v: Boolean) { context.store.edit { it[K.DEMO] = v } }
    suspend fun setOdo(v: Int) { context.store.edit { it[K.ODO] = v.coerceAtLeast(0) } }
    suspend fun setSpeedLimit(v: Int) { context.store.edit { it[K.LIMIT] = v.coerceIn(0, 300) } }
    suspend fun setAlertSound(v: Boolean) { context.store.edit { it[K.SOUND] = v } }
    suspend fun setVoice(v: Boolean) { context.store.edit { it[K.VOICE] = v } }
    suspend fun setAutoConnect(v: Boolean) { context.store.edit { it[K.AUTO] = v } }
    suspend fun setRoutingUrl(v: String) { context.store.edit { it[K.ROUTE] = v } }
    suspend fun setGeocodeUrl(v: String) { context.store.edit { it[K.GEO] = v } }
    suspend fun setVehicle(p: VehicleProfile) {
        context.store.edit { it[K.V_MODEL] = p.model.trim(); it[K.V_VARIANT] = p.variant.trim(); it[K.V_CC] = p.engineCc; it[K.V_DATE] = p.purchaseDate.trim() }
    }
    suspend fun setTftMapOnly(v: Boolean) { context.store.edit { it[K.TFT_MAP] = v } }
    /** key ∈ tft_speed, tft_route, tft_trip, tft_vehicle, tft_clock. */
    suspend fun setTftFlag(key: String, v: Boolean) { context.store.edit { it[booleanPreferencesKey(key)] = v } }
    suspend fun saveDevices(list: List<SavedDevice>) { context.store.edit { it[K.SAVED] = SavedDeviceCodec.encode(list) } }
    /** Xóa mọi cài đặt và thiết bị đã lưu (quyền riêng tư). */
    suspend fun clearAll() { context.store.edit { it.clear() } }
}
