package vn.nvxconnect.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.nvxconnect.pro.CockpitModel
import vn.nvxconnect.pro.ProSettings
import vn.nvxconnect.pro.ThemeMode

/**
 * Cấu hình giao diện Pro (giao diện, cockpit, chu kỳ vẽ, dung lượng log, giá nhiên liệu).
 * Dùng SharedPreferences đồng bộ để chủ đề áp dụng ngay khi mở app (DataStore là bất đồng bộ nên sẽ nhấp nháy sai chủ đề).
 * Không chứa dữ liệu xe/hành trình. "Xóa toàn bộ dữ liệu" gọi [clear].
 */
class ProPrefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("nvx_pro", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<ProSettings> = _state.asStateFlow()
    val value: ProSettings get() = _state.value

    private fun load() = ProSettings.sanitize(ProSettings(
        theme = ThemeMode.parse(sp.getString("theme", null)),
        cockpitHidden = CockpitModel.decodeHidden(sp.getString("cockpit_hidden", null)),
        cockpitHighContrast = sp.getBoolean("cockpit_hc", false),
        uiRefreshMs = sp.getInt("ui_refresh_ms", 500),
        logMaxRecords = sp.getInt("log_max_records", 20_000),
        fuelPriceVnd = sp.getInt("fuel_price_vnd", 0),
        consumptionL100 = sp.getFloat("consumption_l100", 0f).toDouble()))

    fun update(f: (ProSettings) -> ProSettings) {
        val n = ProSettings.sanitize(f(_state.value))
        sp.edit()
            .putString("theme", n.theme.key).putString("cockpit_hidden", CockpitModel.encodeHidden(n.cockpitHidden))
            .putBoolean("cockpit_hc", n.cockpitHighContrast).putInt("ui_refresh_ms", n.uiRefreshMs).putInt("log_max_records", n.logMaxRecords)
            .putInt("fuel_price_vnd", n.fuelPriceVnd).putFloat("consumption_l100", n.consumptionL100.toFloat()).apply()
        _state.value = n
    }

    fun clear() { sp.edit().clear().apply(); _state.value = load() }
}
