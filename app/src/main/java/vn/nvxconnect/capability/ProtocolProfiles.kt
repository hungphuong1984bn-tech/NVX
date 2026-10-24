package vn.nvxconnect.capability

/** Kết luận về tương thích của một hồ sơ với xe MỤC TIÊU (Yamaha NVX 155 V3 SP 2025). */
enum class NvxCompatibility(val label: String) {
    /** Chưa có bằng chứng nào cho NVX. Mặc định của mọi hồ sơ tham chiếu. */
    UNKNOWN("Chưa có bằng chứng cho NVX"),
    /** Có bằng chứng thật rằng hồ sơ KHÔNG áp dụng (ví dụ khác kênh truyền). */
    NOT_APPLICABLE("Không áp dụng"),
    /** Đã thử lặp lại trên NVX 155 V3 SP 2025 thật (kèm biên bản). Hiện không hồ sơ nào đạt. */
    VERIFIED("Đã xác minh trên NVX")
}

/**
 * Một nguồn giao thức/tài liệu CÔNG KHAI về xe Yamaha. Đây là HỒ SƠ THAM CHIẾU để người khảo sát đối chiếu, không phải adapter:
 *  - không có mã giải mã nào được chạy hay sao chép từ nguồn;
 *  - [nvxCompatibility] = UNKNOWN cho tới khi có biên bản thử trên đúng mẫu xe;
 *  - giao thức/ mẫu xe khác (Cygnus, RayZR, MT, XMAX...) KHÔNG được coi là tương thích mặc định.
 */
data class ProtocolProfile(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val sourceLicense: String,
    /** Kênh truyền theo nguồn nêu: "Bluetooth Classic RFCOMM (SPP)", "BLE GATT"... "Nguồn không nêu" nếu không rõ. */
    val transport: String,
    /** Mẫu xe/đời xe mà NGUỒN nêu (không phải suy đoán của app). */
    val vehiclesStatedBySource: List<String>,
    val controllerStatedBySource: String?,
    val softwareVersionStatedBySource: String?,
    val nvxCompatibility: NvxCompatibility,
    /** Bằng chứng/ghi chú về tương thích với NVX, kể cả việc KHÔNG có bằng chứng. */
    val compatibilityEvidence: String,
    /** Điều được phép làm với nguồn này trong dự án (license). */
    val usageNote: String,
    /** Ngày tra cứu (YYYY-MM-DD). */
    val retrievedOn: String
) {
    val verifiedForNvx: Boolean get() = nvxCompatibility == NvxCompatibility.VERIFIED
}

object ProtocolProfileCatalog {
    const val TARGET_VEHICLE = "Yamaha NVX 155 V3 SP 2025"

    /**
     * Các nguồn công khai đã tra cứu (2026-10-23). KHÔNG nguồn nào nêu NVX; vì thế không mục nào VERIFIED.
     * Tìm thêm nguồn: thêm mục mới KÈM URL, license, mẫu xe do nguồn nêu và bằng chứng; đừng đổi [NvxCompatibility] nếu chưa có biên bản thử.
     */
    val builtIn: List<ProtocolProfile> = listOf(
        ProtocolProfile(
            id = "ref.pillion.navilite",
            title = "Pillion — triển khai NaviLite (kênh dẫn đường lên TFT của CCU Garmin)",
            sourceUrl = "https://github.com/alexandrevega/pillion",
            sourceLicense = "PolyForm Noncommercial 1.0.0 (không phải giấy phép mã nguồn mở OSI; cấm dùng thương mại)",
            transport = "Bluetooth Classic RFCOMM (SPP) theo tài liệu giao thức của nguồn — KHÔNG phải BLE GATT",
            vehiclesStatedBySource = listOf("MT-07 (2025)", "MT-09 (2024, 2025)", "MT-09 SP (2026)", "R9 (2026)", "XSR900 (2025)", "XMAX (2025)",
                "NMAX (nguồn ghi 'rất có thể', chưa xác nhận)"),
            controllerStatedBySource = "Garmin \"Communication Control Unit\" (CCU); tương thích Garmin StreetCross",
            softwareVersionStatedBySource = "CCU scooter có mã phần mềm 006-B3952-xx (XMAX/NMAX) khác CCU dòng MT",
            nvxCompatibility = NvxCompatibility.UNKNOWN,
            compatibilityEvidence = "Nguồn không nêu NVX. Chưa biết CCU của NVX 155 V3 SP 2025 (thị trường Việt Nam) có cùng nền tảng Garmin hay không. " +
                "Kênh của nguồn là RFCOMM, trong khi app này chỉ quét/kết nối BLE: nếu NVX dùng cùng nền tảng, kênh TFT có thể không hiện ra ở khảo sát BLE.",
            usageNote = "Chỉ đọc tài liệu để đối chiếu. Không sao chép mã (giấy phép phi thương mại), không gửi khung lên xe, không phát lại gói.",
            retrievedOn = "2026-10-23"
        ),
        ProtocolProfile(
            id = "ref.yammic.ccu",
            title = "YammiC — ứng dụng mã nguồn mở kết nối Yamaha CCU qua BLE (dữ liệu CAN, TFT)",
            sourceUrl = "https://github.com/C0Newb/YammiC",
            sourceLicense = "GPL-3.0",
            transport = "BLE (theo mô tả của nguồn)",
            vehiclesStatedBySource = listOf("README nêu 'xe thể thao' của tác giả, không ghi mẫu/đời xe cụ thể"),
            controllerStatedBySource = "Yamaha CCU trên 'xe đời mới'",
            softwareVersionStatedBySource = null,
            nvxCompatibility = NvxCompatibility.UNKNOWN,
            compatibilityEvidence = "Nguồn tự ghi: RPM, nhiệt độ, số, ga đã giải mã nhưng chỉ ga và số được xác nhận chạy thật; mức xăng KHÔNG tìm thấy tín hiệu CAN; " +
                "ghép đôi CCU (lấy passkey qua API Yamaha cần tài khoản) còn là việc chưa làm. Không nêu NVX hay xe tay ga.",
            usageNote = "GPL-3.0: không đưa mã vào app khi chưa xem xét license. Dùng làm tài liệu tham khảo cấu trúc dữ liệu; phải kiểm chứng độc lập trên NVX.",
            retrievedOn = "2026-10-23"
        ),
        ProtocolProfile(
            id = "ref.yamaha.yconnect.official",
            title = "Tài liệu chính thức Yamaha về Y-Connect/CCU (không có đặc tả giao thức)",
            sourceUrl = "https://global.yamaha-motor.com/business/mc/connectivity/yconnect/lc.html",
            sourceLicense = "Nội dung của Yamaha (chỉ tham khảo)",
            transport = "Bluetooth BLE 4.1 trở lên (phía điện thoại, theo yêu cầu của ứng dụng Y-Connect)",
            vehiclesStatedBySource = listOf("Chỉ xe có CCU; 'khả năng tương thích thay đổi theo mẫu xe'"),
            controllerStatedBySource = "Communication Control Unit (CCU)",
            softwareVersionStatedBySource = null,
            nvxCompatibility = NvxCompatibility.UNKNOWN,
            compatibilityEvidence = "Yamaha mô tả chức năng (thông báo, chỉ đường, bảo dưỡng, nhật ký hành trình) và yêu cầu tài khoản Yamaha Motor ID; " +
                "không công bố UUID, định dạng gói hay cơ chế xác thực. Tin ra mắt NVX 2025 tại Việt Nam (VOH, 2025-06-26) nói có Google Maps qua Y-Connect, " +
                "theo dõi nhiên liệu và lịch bảo dưỡng, nhưng đó là mô tả tính năng, không phải giao thức.",
            usageNote = "Chỉ để biết xe/ứng dụng chính thức làm được gì. Không suy ra giao thức từ mô tả tính năng.",
            retrievedOn = "2026-10-23"
        )
    )

    /** Hồ sơ đã xác minh cho đúng xe mục tiêu. Mặc định rỗng; một hồ sơ chỉ vào đây khi có biên bản thử trên NVX thật. */
    fun verifiedForNvx(profiles: List<ProtocolProfile> = builtIn): List<ProtocolProfile> = profiles.filter { it.verifiedForNvx }
}
