package vn.nvxconnect.pro

/** 5 mục ở thanh dưới của NVX Connect Pro. */
enum class ProTab(val title: String) { HOME("Trang chủ"), COCKPIT("Cockpit"), TELEMETRY("Telemetry"), RIDES("Hành trình"), TOOLS("Công cụ") }

/**
 * Trang con. Trang con thuộc về một tab (nằm "trên" trang gốc của tab đó) nên nút Back và bấm lại tab luôn có hành vi xác định.
 * Chỉ mô tả ĐIỀU HƯỚNG, không chứa dữ liệu BLE/xe.
 */
enum class ProPage(val tab: ProTab, val title: String) {
    LAB(ProTab.TOOLS, "Protocol Lab"),
    DIAGNOSTICS(ProTab.TOOLS, "Chẩn đoán"),
    SETTINGS(ProTab.TOOLS, "Cài đặt"),
    CONNECT(ProTab.TOOLS, "Kết nối thủ công"),
    AUTO(ProTab.TOOLS, "Tự tìm và kết nối xe"),
    CAPABILITIES(ProTab.TOOLS, "Danh mục chức năng"),
    TFT(ProTab.TOOLS, "Hiển thị TFT"),
    HELP(ProTab.TOOLS, "Trung tâm hướng dẫn"),
    MAP(ProTab.RIDES, "Bản đồ"),
    VEHICLE(ProTab.TOOLS, "Xe và bảo dưỡng"),
}

/**
 * Trạng thái điều hướng bất biến. Mỗi tab nhớ trang con riêng (giữ trạng thái hợp lý khi đổi tab).
 * Cockpit toàn màn hình là lớp phủ riêng, luôn đóng trước khi làm gì khác (Back đóng cockpit trước).
 */
data class ProNavState(
    val tab: ProTab = ProTab.HOME,
    val pages: Map<ProTab, List<ProPage>> = emptyMap(),
    val fullscreenCockpit: Boolean = false,
    val helpTopic: String? = null
) {
    val stack: List<ProPage> get() = pages[tab] ?: emptyList()
    val page: ProPage? get() = stack.lastOrNull()

    /** Chọn tab. Chọn lại tab đang mở sẽ về trang gốc của tab đó. Luôn thoát cockpit toàn màn hình. */
    fun selectTab(t: ProTab): ProNavState =
        if (t == tab) copy(pages = pages + (t to emptyList()), fullscreenCockpit = false)
        else copy(tab = t, fullscreenCockpit = false)

    /** Mở trang con: chuyển sang tab sở hữu trang đó. Mở lại đúng trang đang ở đỉnh thì không chồng thêm. */
    fun open(p: ProPage): ProNavState {
        val cur = pages[p.tab] ?: emptyList()
        val next = if (cur.lastOrNull() == p) cur else cur + p
        return copy(tab = p.tab, pages = pages + (p.tab to next), fullscreenCockpit = false)
    }

    fun openHelp(topic: String?): ProNavState = open(ProPage.HELP).copy(helpTopic = topic)

    fun enterCockpit(): ProNavState = copy(tab = ProTab.COCKPIT, fullscreenCockpit = true)
    fun exitCockpit(): ProNavState = copy(fullscreenCockpit = false)

    /** Back có việc để làm không (nếu false, để hệ thống xử lý Back: thoát app). */
    val canGoBack: Boolean get() = fullscreenCockpit || stack.isNotEmpty() || tab != ProTab.HOME

    /** Thứ tự Back: đóng cockpit toàn màn hình → bỏ trang con → về Trang chủ. */
    fun back(): ProNavState = when {
        fullscreenCockpit -> copy(fullscreenCockpit = false)
        stack.isNotEmpty() -> copy(pages = pages + (tab to stack.dropLast(1)))
        tab != ProTab.HOME -> copy(tab = ProTab.HOME)
        else -> this
    }

    /** Mã hóa để khôi phục sau khi tiến trình bị hệ thống thu hồi/xoay màn hình (dùng với rememberSaveable). */
    fun encode(): String {
        val pg = ProTab.values().joinToString(";") { t -> (pages[t] ?: emptyList()).joinToString(",") { it.name } }
        return listOf(tab.name, if (fullscreenCockpit) "1" else "0", pg, helpTopic ?: "").joinToString("|")
    }

    companion object {
        /** Giải mã chịu lỗi: dữ liệu hỏng/không nhận ra → trạng thái mặc định, không bao giờ ném lỗi. */
        fun decode(s: String?): ProNavState {
            if (s == null) return ProNavState()
            return try {
                val p = s.split("|", limit = 4); if (p.size < 4) return ProNavState()
                val tab = ProTab.valueOf(p[0]); val full = p[1] == "1"
                val groups = p[2].split(";")
                val pages = HashMap<ProTab, List<ProPage>>()
                ProTab.values().forEachIndexed { i, t ->
                    val g = groups.getOrNull(i).orEmpty()
                    val list = if (g.isEmpty()) emptyList() else g.split(",").mapNotNull { n -> ProPage.values().firstOrNull { it.name == n }?.takeIf { pg -> pg.tab == t } }
                    if (list.isNotEmpty()) pages[t] = list
                }
                ProNavState(tab, pages, full && tab == ProTab.COCKPIT, p[3].ifEmpty { null })
            } catch (_: Exception) { ProNavState() }
        }
    }
}
