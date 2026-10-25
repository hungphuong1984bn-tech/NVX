package vn.nvxconnect.pro

enum class ThemeMode(val key: String, val title: String) {
    SYSTEM("system", "Theo hệ thống"), DARK("dark", "Tối"), LIGHT("light", "Sáng");
    companion object { fun parse(k: String?) = values().firstOrNull { it.key == k } ?: DARK }   // mặc định: tối
}

/** Cấu hình giao diện Pro: lưu cục bộ, đồng bộ (SharedPreferences) để áp dụng ngay khi mở app, không nhấp nháy sai giao diện. */
data class ProSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val cockpitHidden: Set<String> = emptySet(),
    val cockpitHighContrast: Boolean = false,
    /** Chu kỳ vẽ lại biểu đồ/cockpit (ms). Độc lập với tần suất ghi log gói. */
    val uiRefreshMs: Int = 500,
    /** Số payload tối đa giữ trong bộ ghi (RawFrameRecorder). */
    val logMaxRecords: Int = 20_000,
    /** Giá nhiên liệu người dùng nhập (đ/lít); 0 = chưa đặt. */
    val fuelPriceVnd: Int = 0,
    /** Mức tiêu thụ người dùng nhập (lít/100 km); 0 = chưa đặt. */
    val consumptionL100: Double = 0.0
) {
    companion object {
        val REFRESH_CHOICES = listOf(250, 500, 1000, 2000)
        val LOG_CHOICES = listOf(5_000, 20_000, 50_000)
        fun sanitize(s: ProSettings) = s.copy(
            uiRefreshMs = if (s.uiRefreshMs in REFRESH_CHOICES) s.uiRefreshMs else 500,
            logMaxRecords = if (s.logMaxRecords in LOG_CHOICES) s.logMaxRecords else 20_000,
            fuelPriceVnd = s.fuelPriceVnd.coerceIn(0, 1_000_000),
            consumptionL100 = if (s.consumptionL100.isFinite()) s.consumptionL100.coerceIn(0.0, 30.0) else 0.0,
            cockpitHidden = CockpitModel.decodeHidden(CockpitModel.encodeHidden(s.cockpitHidden)))
    }
}
