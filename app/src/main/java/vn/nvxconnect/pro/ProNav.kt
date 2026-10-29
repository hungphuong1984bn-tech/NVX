package vn.nvxconnect.pro

/**
 * 4 tab ở thanh dưới của NVX Connect (đã gộp từ 5 tab cũ; mọi màn hình cũ vẫn còn, chuyển thành trang con):
 *  - OVERVIEW "Tổng quan": trạng thái app/kết nối/phiên + lối tắt tới mọi màn hình còn lại.
 *  - CONNECT  "Kết nối xe": NƠI DUY NHẤT có thao tác quét/ghép đôi/kết nối/ngắt (gốc = kết nối thủ công; trang con = tự tìm xe, Protocol Lab).
 *  - DATA     "Dữ liệu xe": gốc = module Dữ liệu xe chuyên sâu (không đổi logic); trang con = Cockpit, Telemetry.
 *  - LOG      "Nhật ký kỹ thuật": gốc = lịch sử phiên + xuất một ZIP; trang con = Chẩn đoán.
 */
enum class ProTab(val title: String) { OVERVIEW("Tổng quan"), CONNECT("Kết nối xe"), DATA("Dữ liệu xe"), LOG("Nhật ký kỹ thuật") }

/**
 * Trang con. Trang con thuộc về một tab (nằm "trên" trang gốc của tab đó) nên nút Back và bấm lại tab luôn có hành vi xác định.
 * Chỉ mô tả ĐIỀU HƯỚNG, không chứa dữ liệu BLE/xe.
 */
enum class ProPage(val tab: ProTab, val title: String) {
    // Tổng quan
    SETTINGS(ProTab.OVERVIEW, "Cài đặt"),
    HELP(ProTab.OVERVIEW, "Trung tâm hướng dẫn"),
    VEHICLE(ProTab.OVERVIEW, "Xe và bảo dưỡng"),
    TRIPS(ProTab.OVERVIEW, "Hành trình"),
    MAP(ProTab.OVERVIEW, "Bản đồ"),
    CAPABILITIES(ProTab.OVERVIEW, "Danh mục chức năng"),
    TFT(ProTab.OVERVIEW, "Hiển thị TFT"),
    // Kết nối xe
    AUTO(ProTab.CONNECT, "Tự tìm và kết nối xe"),
    LAB(ProTab.CONNECT, "Protocol Lab"),
    // Dữ liệu xe
    COCKPIT(ProTab.DATA, "Cockpit"),
    TELEMETRY(ProTab.DATA, "Telemetry"),
    // Nhật ký kỹ thuật
    DIAGNOSTICS(ProTab.LOG, "Chẩn đoán"),
}

/**
 * Trạng thái điều hướng bất biến. Mỗi tab nhớ trang con riêng (giữ trạng thái hợp lý khi đổi tab).
 * Cockpit toàn màn hình là lớp phủ riêng, luôn đóng trước khi làm gì khác (Back đóng cockpit trước).
 * Điều hướng KHÔNG sở hữu kết nối/ghi log: chuyển tab chỉ đổi màn hình hiển thị; phiên GATT, bộ thu và logger sống ở NvxApp.
 */
data class ProNavState(
    val tab: ProTab = ProTab.OVERVIEW,
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

    /** Chuyển sang tab và hiện TRANG GỐC của nó (bỏ các trang con của tab đó). Dùng cho nút "Mở màn hình kết nối" ở nơi khác. */
    fun showRoot(t: ProTab): ProNavState = copy(tab = t, pages = pages + (t to emptyList()), fullscreenCockpit = false)

    /** Cockpit toàn màn hình = trang con COCKPIT (tab Dữ liệu xe) + cờ lớp phủ. */
    fun enterCockpit(): ProNavState = open(ProPage.COCKPIT).copy(fullscreenCockpit = true)
    fun exitCockpit(): ProNavState = copy(fullscreenCockpit = false)

    /** Back có việc để làm không (nếu false, để hệ thống xử lý Back: thoát app). */
    val canGoBack: Boolean get() = fullscreenCockpit || stack.isNotEmpty() || tab != ProTab.OVERVIEW

    /** Thứ tự Back: đóng cockpit toàn màn hình → bỏ trang con → về Tổng quan. */
    fun back(): ProNavState = when {
        fullscreenCockpit -> copy(fullscreenCockpit = false)
        stack.isNotEmpty() -> copy(pages = pages + (tab to stack.dropLast(1)))
        tab != ProTab.OVERVIEW -> copy(tab = ProTab.OVERVIEW)
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
                ProNavState(tab, pages, full && pages[ProTab.DATA]?.lastOrNull() == ProPage.COCKPIT && tab == ProTab.DATA, p[3].ifEmpty { null })
            } catch (_: Exception) { ProNavState() }
        }
    }
}
