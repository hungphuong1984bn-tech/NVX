package vn.nvxconnect.helpcenter

/**
 * Đích điều hướng của nút hành động trong bài hướng dẫn. CHỈ gồm các màn hình hiện có trong app;
 * thêm giá trị mới ở đây chỉ khi MainActivity đã có màn hình tương ứng.
 */
enum class HelpAction(val label: String) {
    OPEN_CONNECT("Mở màn hình Kết nối"),
    OPEN_AUTO_CONNECT("Mở Tự tìm xe"),
    OPEN_ANALYSIS("Mở Phân tích giao thức / Chẩn đoán"),
    OPEN_CAPABILITIES("Mở Danh mục chức năng"),
    OPEN_TFT("Mở Hiển thị trên màn hình xe"),
    OPEN_SETTINGS("Mở Cài đặt"),
    OPEN_MAP("Mở Bản đồ"),
    OPEN_TRIPS("Mở Hành trình"),
    OPEN_VEHICLE("Mở mục Xe (hồ sơ, bảo dưỡng)"),
    OPEN_SYSTEM_BLUETOOTH("Mở cài đặt Bluetooth của Android"),
    OPEN_APP_SETTINGS("Mở cài đặt quyền của ứng dụng")
}

enum class HelpCategory(val title: String) {
    START("Bắt đầu"), CONNECT("Kết nối"), DATA("Dữ liệu xe"), NAVIGATION("GPS & dẫn đường"),
    TROUBLE("Xử lý sự cố"), PRIVACY("Dữ liệu & riêng tư")
}

data class HelpGuide(
    val id: String,
    val order: Int,
    val title: String,
    val category: HelpCategory,
    val purpose: String,
    val prerequisites: List<String>,
    val steps: List<String>,
    val expected: String,
    val commonErrors: List<String>,
    val fixes: List<String>,
    /** Từ khóa tìm kiếm bổ sung (không dấu cũng tìm được). */
    val keywords: List<String> = emptyList(),
    /** Nút hành động; null nếu bài không có màn hình đích tương ứng. */
    val action: HelpAction? = null,
    /**
     * true: người dùng tự đánh dấu "đã làm xong" được (bài thủ tục). false: bài có kiểm tra tự động, hoặc chỉ là tham khảo;
     * trạng thái hoàn thành KHÔNG được tự đặt khi kiểm tra chưa đạt (xem [ConnectionChecklist]).
     */
    val manualComplete: Boolean = true,
    /** Bài có thao tác cấu hình/chẩn đoán: bắt buộc nhắc "chỉ thực hiện khi xe đã dừng ở nơi an toàn". */
    val requiresStationary: Boolean = true
)

object HelpSafety {
    const val STATIONARY_NOTICE =
        "An toàn: chỉ cấu hình, kết nối hoặc chẩn đoán khi xe đã dừng ở nơi an toàn và tắt máy/chống chân. " +
            "Không thao tác điện thoại khi đang lái xe."
}
