package vn.nvxconnect.capability

/** Loại chức năng của APP (tách hẳn khỏi chức năng của XE: xem [VehicleCapability]). */
enum class AppKind(val label: String) {
    /** Công cụ nối/khảo sát thiết bị BLE. Không đối chiếu với chức năng xe cụ thể. */
    VEHICLE_LINK_TOOL("Công cụ kết nối/khảo sát"),
    /** Chỉ cần điện thoại (GPS, bản đồ, dữ liệu người dùng nhập). Phải chạy độc lập khi không có xe. */
    PHONE_ONLY("Chỉ cần điện thoại"),
    /** Dùng dữ liệu/khả năng của xe: chỉ hoạt động thật khi chức năng xe tương ứng đã được xác minh. */
    VEHICLE_DATA_CONSUMER("Dùng dữ liệu/khả năng của xe")
}

/**
 * Mức kiểm thử THẬT của chức năng:
 *  - NONE: chưa có test nào.
 *  - PARTIAL_JVM: phần logic có unit test JVM nhưng phần nền tảng/giao diện (Android API, Compose, osmdroid) chưa được kiểm.
 *  - JVM_UNIT: toàn bộ logic của chức năng có unit test JVM (vẫn chưa chạy trên điện thoại).
 *  - ON_DEVICE: đã thử trên điện thoại/xe thật. HIỆN CHƯA CÓ MỤC NÀO đạt mức này; chỉ nâng khi có biên bản thử thật (docs/TEST_CHECKLIST.md).
 */
enum class TestLevel { NONE, PARTIAL_JVM, JVM_UNIT, ON_DEVICE }

/** Bản chất dữ liệu mà chức năng tạo ra/hiển thị. */
enum class DataKind(val label: String) {
    LIVE_VEHICLE("Dữ liệu thật từ xe (khi có adapter đã xác minh)"),
    LIVE_DEVICE_BLE("Dữ liệu thật từ thiết bị BLE (quảng bá/GATT)"),
    LIVE_PHONE("Dữ liệu thật từ điện thoại (GPS, cảm biến)"),
    COMPUTED("Dữ liệu tính toán"),
    USER_INPUT("Dữ liệu người dùng nhập"),
    SAMPLE("Dữ liệu mẫu (DEMO)"),
    NONE("Không tạo dữ liệu")
}

data class AppCapability(
    val id: String,
    val name: String,
    val kind: AppKind,
    /** true = đã có mã chạy được cho chức năng này. false = chưa làm (kể cả khi đã có khung chặn an toàn). */
    val implemented: Boolean,
    val testLevel: TestLevel,
    /** Tên lớp test JVM (trong package vn.nvxconnect) chứng minh mức kiểm thử; test bảo vệ kiểm tra các lớp này có thật. */
    val testRefs: List<String>,
    /** Phần nào được/chưa được kiểm thử, viết ngắn gọn. */
    val testScope: String,
    val permissions: List<String>,
    val needsNetwork: Boolean,
    val needsGps: Boolean,
    val needsExternalDevice: Boolean,
    val dataKind: DataKind,
    /** Có dùng được khi KHÔNG có phiên kết nối tới xe hay không. */
    val worksWithoutVehicle: Boolean,
    /** Id chức năng xe mà chức năng app này cần ([VehicleCapability.id]); null nếu không phụ thuộc chức năng xe cụ thể. */
    val consumes: String? = null,
    val note: String? = null
)

/**
 * Danh mục chức năng của NVX Connect, viết tay và kiểm tra bằng test: mỗi mục phải khớp thực tế mã nguồn.
 * Khi thêm/xóa chức năng thật, cập nhật ở đây (test bảo vệ chặn id trùng, test tham chiếu không tồn tại, và chức năng
 * "chỉ cần điện thoại" mà lại đòi có xe).
 */
object AppCapabilityRegistry {
    /** Tăng khi danh mục đổi. Ghi vào báo cáo. */
    const val CATALOG_VERSION = "app-caps-1"

    private const val FINE = "ACCESS_FINE_LOCATION"
    private const val BT_SCAN = "BLUETOOTH_SCAN (Android 12+) / ACCESS_FINE_LOCATION (Android ≤ 11)"
    private const val BT_CONNECT = "BLUETOOTH_CONNECT (Android 12+)"
    private const val ON_VEHICLE_NOT_TESTED = "Chưa thử trên điện thoại/xe thật."

    private fun consumer(id: String, name: String, vehicleCap: String, implemented: Boolean, level: TestLevel, scope: String, note: String? = null) =
        AppCapability(id, name, AppKind.VEHICLE_DATA_CONSUMER, implemented, level, if (level == TestLevel.NONE) emptyList() else listOf("TelemetryTftTest"),
            scope, emptyList(), false, false, true, DataKind.LIVE_VEHICLE, false, vehicleCap, note)

    val all: List<AppCapability> = listOf(
        // ------------------------------------------------------------------ công cụ kết nối/khảo sát
        AppCapability("app.ble_scan", "Quét BLE (tên, RSSI, connectable, service UUID, manufacturer data)", AppKind.VEHICLE_LINK_TOOL, true,
            TestLevel.PARTIAL_JVM, listOf("ScanAndLogTest", "BleUpgradeTest"), "Bảng thiết bị, lọc, tìm kiếm, địa chỉ xoay vòng được test; BleScanner (API Android) chưa. $ON_VEHICLE_NOT_TESTED",
            listOf(BT_SCAN), false, false, false, DataKind.LIVE_DEVICE_BLE, true),
        AppCapability("app.auto_discovery", "Khảo sát tự động: so sánh xe tắt/bật, xếp hạng ứng viên", AppKind.VEHICLE_LINK_TOOL, true,
            TestLevel.JVM_UNIT, listOf("AutoDiscoveryTest"), "Logic xếp hạng/gom địa chỉ xoay vòng. Không có mức nào khẳng định là Yamaha. $ON_VEHICLE_NOT_TESTED",
            listOf(BT_SCAN), false, false, false, DataKind.COMPUTED, true, note = "Chỉ có ý nghĩa khi xe tắt/bật trong tầm quét."),
        AppCapability("app.gatt_connect", "Kết nối/ngắt kết nối GATT, timeout, kết nối lại có giới hạn", AppKind.VEHICLE_LINK_TOOL, true,
            TestLevel.JVM_UNIT, listOf("GattSessionTest"), "Máy trạng thái bằng cổng GATT giả; hành vi stack BLE thật (status 133, MTU) chưa kiểm.",
            listOf(BT_CONNECT), false, false, true, DataKind.LIVE_DEVICE_BLE, false),
        AppCapability("app.save_reconnect", "Lưu xe và tự kết nối lại", AppKind.VEHICLE_LINK_TOOL, true,
            TestLevel.JVM_UNIT, listOf("AutoConnectTest", "AutoDiscoveryTest"), "Bộ điều khiển với kết nối/nguồn quét giả. $ON_VEHICLE_NOT_TESTED",
            listOf(BT_SCAN, BT_CONNECT), false, false, true, DataKind.LIVE_DEVICE_BLE, false, note = "Lưu hồ sơ dùng được không cần xe; tự kết nối lại thì cần thiết bị trong tầm."),
        AppCapability("app.gatt_explorer", "GATT Explorer: service/characteristic/descriptor, thuộc tính, nghe notify", AppKind.VEHICLE_LINK_TOOL, true,
            TestLevel.JVM_UNIT, listOf("GattSessionTest", "ProtocolReportTest"), "Khám phá, đăng ký notify (xác nhận CCCD), đọc thông tin chuẩn theo danh sách cho phép, bảng kiểm tra; cổng GATT giả.",
            listOf(BT_CONNECT), false, false, true, DataKind.LIVE_DEVICE_BLE, false, note = "Không có thao tác ghi dữ liệu vào xe."),
        AppCapability("app.protocol_analysis", "Phân tích giao thức: cấu trúc gói, so sánh sự kiện/phiên, mức bằng chứng", AppKind.VEHICLE_LINK_TOOL, true,
            TestLevel.JVM_UNIT, listOf("ProtocolReportTest", "SpeedCrossCheckTest", "BleUpgradeTest"), "Dữ liệu TỔNG HỢP, không phải gói của xe. Không bao giờ sinh VERIFIED.",
            emptyList(), false, false, true, DataKind.COMPUTED, false, note = "Cần gói đã thu từ phiên đang nối; chưa nhập được log cũ từ tệp."),
        AppCapability("app.log_export", "Thu thập và xuất log JSON/CSV đã che thông tin nhạy cảm", AppKind.VEHICLE_LINK_TOOL, true,
            TestLevel.JVM_UNIT, listOf("ScanAndLogTest", "SurveyTest", "DataExportTest"), "Che MAC/ASCII dài, escape JSON/CSV, bộ đệm có giới hạn.",
            emptyList(), false, false, false, DataKind.LIVE_DEVICE_BLE, true, note = "Log chỉ giữ trong bộ nhớ của phiên (có giới hạn); không lưu bền vững cho tới khi bạn bấm xuất tệp."),
        AppCapability("app.capability_registry", "Danh mục chức năng xe/app, đối chiếu và báo cáo chưa hỗ trợ", AppKind.VEHICLE_LINK_TOOL, true,
            TestLevel.PARTIAL_JVM, listOf("CapabilityTest"), "Logic danh mục/đối chiếu/xuất báo cáo được test; màn hình Compose chưa được trình biên dịch kiểm.",
            emptyList(), false, false, false, DataKind.COMPUTED, true),
        AppCapability("app.connection_lost_alert", "Cảnh báo mất kết nối (thông báo/âm thanh)", AppKind.VEHICLE_LINK_TOOL, false,
            TestLevel.NONE, emptyList(), "Chưa có.",
            emptyList(), false, false, true, DataKind.NONE, false,
            note = "Hiện chỉ đổi trạng thái trong tab Kết nối, ghi log và tự kết nối lại có giới hạn; chưa có cảnh báo chủ động cho người lái."),

        // ------------------------------------------------------------------ chỉ cần điện thoại
        AppCapability("app.map_gps", "Bản đồ OpenStreetMap + vị trí GPS + hướng", AppKind.PHONE_ONLY, true,
            TestLevel.PARTIAL_JVM, listOf("NavigationTest"), "Hình học tuyến được test; hiển thị osmdroid/Compose chưa được biên dịch/chạy.",
            listOf(FINE), true, true, false, DataKind.LIVE_PHONE, true, note = "Cần mạng để tải ô bản đồ."),
        AppCapability("app.phone_navigation", "Dẫn đường trên điện thoại: tìm địa điểm, tuyến, giọng nói, tính lại khi lệch", AppKind.PHONE_ONLY, true,
            TestLevel.JVM_UNIT, listOf("NavigationTest", "NavigationSessionTest"), "Bộ phân tích OSRM/Nominatim, động cơ dẫn đường, phiên với client giả; giọng nói TTS và mạng thật chưa kiểm.",
            listOf(FINE), true, true, false, DataKind.COMPUTED, true, note = "OSRM demo dùng hồ sơ ô tô, có thể chọn đường mô tô bị cấm; thời gian đến không tính ùn tắc."),
        AppCapability("app.trip_recording", "Ghi và tính toán hành trình bằng GPS (quãng đường, tốc độ TB/tối đa)", AppKind.PHONE_ONLY, true,
            TestLevel.JVM_UNIT, listOf("TripEngineTest", "CalcTest"), "Động cơ hành trình với bộ ghi giả; dịch vụ foreground và GPS thật chưa kiểm.",
            listOf(FINE, "FOREGROUND_SERVICE_LOCATION", "POST_NOTIFICATIONS (Android 13+)"), false, true, false, DataKind.COMPUTED, true,
            note = "Tốc độ/quãng đường là GPS ước tính, không phải của xe."),
        AppCapability("app.fuel_log", "Nhật ký nhiên liệu (L/100 km từ số bạn nhập)", AppKind.PHONE_ONLY, true,
            TestLevel.JVM_UNIT, listOf("CalcTest", "VehicleSpecsTest"), "Kiểm tra nhập liệu, tính đổ đầy→đổ đầy, tầm chạy ước tính.",
            emptyList(), false, false, false, DataKind.USER_INPUT, true, note = "Không phải mức xăng đọc từ xe."),
        AppCapability("app.maintenance_log", "Bảo dưỡng do bạn nhập (còn hạn/sắp/quá hạn)", AppKind.PHONE_ONLY, true,
            TestLevel.JVM_UNIT, listOf("CalcTest"), "Tính trạng thái theo km/ngày.",
            emptyList(), false, false, false, DataKind.USER_INPUT, true, note = "Chu kỳ gợi ý là số tham khảo của app, không phải của Yamaha. Khác với trạng thái bảo dưỡng do xe báo."),
        AppCapability("app.display_modes", "Chế độ hiển thị đầy đủ / chỉ bản đồ (sở thích)", AppKind.PHONE_ONLY, true,
            TestLevel.PARTIAL_JVM, listOf("TelemetryTftTest"), "Dựng khung theo sở thích được test; không có gì được gửi lên TFT.",
            emptyList(), false, false, false, DataKind.NONE, true, note = "Chỉ lưu sở thích và dựng khung nội bộ; không hiển thị lên màn hình xe."),
        AppCapability("app.demo_data", "Chế độ DEMO (dữ liệu mẫu có nhãn riêng)", AppKind.PHONE_ONLY, true,
            TestLevel.PARTIAL_JVM, listOf("CalcTest"), "Kiểm tra nguồn dữ liệu tách biệt; không dùng cho số liệu thật.",
            emptyList(), false, false, false, DataKind.SAMPLE, true),

        // ------------------------------------------------------------------ dùng dữ liệu/khả năng của xe
        consumer("app.show_speed", "Hiển thị tốc độ do xe báo (Trang chủ + khung TFT)", "veh.speed", true, TestLevel.JVM_UNIT,
            "Hub chỉ nhận dữ liệu đã xác minh; giá trị cũ bị coi là chưa khả dụng. Chưa có adapter nên chưa từng hiển thị số thật."),
        consumer("app.show_rpm", "Hiển thị vòng tua do xe báo", "veh.rpm", true, TestLevel.PARTIAL_JVM, "Chỉ dòng hiển thị chung; cổng dữ liệu được test."),
        consumer("app.show_fuel", "Hiển thị mức xăng do xe báo (Trang chủ + khung TFT)", "veh.fuel_level", true, TestLevel.JVM_UNIT,
            "Như hiển thị tốc độ. Đơn vị (%, vạch, lít) phải lấy từ bằng chứng của adapter."),
        consumer("app.show_odometer", "Hiển thị ODO do xe báo", "veh.odometer", true, TestLevel.PARTIAL_JVM, "Chỉ dòng hiển thị chung.",
            note = "ODO người dùng nhập là chức năng khác (USER_INPUT)."),
        consumer("app.show_battery", "Hiển thị điện áp ắc quy do xe báo", "veh.battery_voltage", true, TestLevel.PARTIAL_JVM, "Chỉ dòng hiển thị chung."),
        consumer("app.show_dtc", "Hiển thị cảnh báo/mã lỗi do xe báo", "veh.dtc", true, TestLevel.PARTIAL_JVM, "Chỉ dòng hiển thị chung."),
        consumer("app.show_vehicle_trip", "Hiển thị quãng đường hành trình do xe báo", "veh.trip_distance", false, TestLevel.NONE, "Chưa có.",
            note = "Quãng đường hành trình của app là GPS ước tính (app.trip_recording)."),
        consumer("app.show_ignition", "Hiển thị trạng thái khóa điện/động cơ do xe báo", "veh.ignition_state", false, TestLevel.NONE, "Chưa có."),
        consumer("app.show_vehicle_maintenance", "Hiển thị trạng thái bảo dưỡng do xe báo", "veh.maintenance", false, TestLevel.NONE, "Chưa có."),
        consumer("app.tft_navigation", "Gửi chỉ đường/khung hiển thị lên TFT", "veh.navigation_data", false, TestLevel.JVM_UNIT,
            "Chỉ kiểm thử rằng cầu nối từ chối mọi khung và cổng chặn đúng điều kiện. Việc gửi thật CHƯA triển khai.",
            note = "Cầu nối mặc định UNSUPPORTED; cần giao thức có tài liệu/cho phép và thử trên xe thật (docs/TFT_NAVIGATION.md)."),
        consumer("app.tft_control", "Đọc trạng thái/điều khiển màn hình TFT", "veh.tft_control", false, TestLevel.NONE, "Chưa có.",
            note = "App không gửi lệnh điều khiển nào lên xe.")
    )

    fun byId(id: String): AppCapability? = all.firstOrNull { it.id == id }
}
