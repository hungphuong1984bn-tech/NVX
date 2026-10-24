package vn.nvxconnect.auto

import vn.nvxconnect.bluetooth.ErrorKind
import vn.nvxconnect.protocol.ConnState
import vn.nvxconnect.protocol.VehicleLinkStatus

/**
 * Trạng thái hiển thị của luồng tự tìm và kết nối. Mỗi trạng thái "cao" chỉ xuất hiện khi có sự kiện xác nhận tương ứng:
 *  - GATT_CONNECTED: phiên GATT đã Ready (đã nối VÀ khám phá xong dịch vụ). Chưa nói gì về xác thực hay giao thức.
 *  - BONDED: Android báo đã ghép đôi (BOND_BONDED) với đúng thiết bị đó VÀ GATT đã Ready. Chỉ là ghép đôi mức liên kết BLE;
 *    KHÔNG phải bằng chứng xe đã xác thực ứng dụng ở lớp giao thức.
 *  - AUTHENTICATED: có bằng chứng xác thực Ở LỚP GIAO THỨC (do adapter đã xác minh báo qua [AutoInputs.authenticated]).
 *    Hiện chưa có adapter nào nên trạng thái này chưa thể đạt được trên thực tế.
 *  - PROTOCOL_VERIFIED: có adapter giao thức ĐÃ XÁC MINH khớp thiết bị (AdapterRegistry yêu cầu bằng chứng trên xe thật).
 *  - DATA_AVAILABLE: đã giải mã được ít nhất một giá trị xe bằng adapter đã xác minh.
 *  - CONNECTION_BUSY: nghi ứng dụng khác (Y-Connect) đang giữ kết nối độc quyền. Không phải lỗi của app; app dừng và không chiếm kết nối.
 */
enum class AutoState { IDLE, SCANNING, CANDIDATE_FOUND, GATT_CONNECTED, BONDED, AUTHENTICATED, PROTOCOL_VERIFIED, DATA_AVAILABLE, CONNECTION_BUSY, FAILED }

enum class AutoFailure(val title: String) {
    PERMISSION("Thiếu quyền Bluetooth"),
    BLUETOOTH_OFF("Bluetooth đang tắt"),
    AUTH_REQUIRED("Thiết bị yêu cầu ghép đôi/xác thực"),
    EXCLUSIVE_HOLDER_SUSPECTED("Nghi có ứng dụng khác (Y-Connect) đang giữ kết nối"),
    NOT_FOUND("Không thấy thiết bị đã lưu"),
    GATT_ERROR("Lỗi GATT"),
    TIMEOUT("Hết thời gian chờ"),
    RETRIES_EXHAUSTED("Đã thử lại hết số lần cho phép")
}

enum class BondState { NONE, BONDING, BONDED }

data class AutoStatus(val state: AutoState, val headline: String, val detail: String? = null, val failure: AutoFailure? = null)

data class AutoInputs(
    val scanning: Boolean,
    /** Đã thấy thiết bị khớp hồ sơ hoặc người dùng đã chọn một ứng viên để kết nối. */
    val candidateFound: Boolean,
    val conn: ConnState,
    val bond: BondState,
    val link: VehicleLinkStatus,
    val verifiedValueSeen: Boolean,
    val runFailure: AutoFailure? = null,
    val runMessage: String? = null,
    /** Bằng chứng xác thực ở LỚP GIAO THỨC do adapter đã xác minh cung cấp. Mặc định false; BONDED không bao giờ làm nó thành true. */
    val authenticated: Boolean = false
)

object AutoStatusResolver {
    fun resolve(i: AutoInputs): AutoStatus {
        val c = i.conn
        if (c is ConnState.Ready) {
            return when {
                i.link.protocolVerified && i.verifiedValueSeen ->
                    AutoStatus(AutoState.DATA_AVAILABLE, "CÓ DỮ LIỆU XE", "Đã giải mã bằng adapter đã xác minh: ${i.link.headline}")
                i.link.protocolVerified ->
                    AutoStatus(AutoState.PROTOCOL_VERIFIED, "ĐÃ XÁC MINH GIAO THỨC", "Chưa nhận giá trị xe nào")
                i.authenticated ->
                    AutoStatus(AutoState.AUTHENTICATED, "ĐÃ XÁC THỰC (LỚP GIAO THỨC)",
                        "${VehicleLinkStatus.NOT_VERIFIED}: chưa có số liệu xe.")
                i.bond == BondState.BONDED ->
                    AutoStatus(AutoState.BONDED, "ĐÃ GHÉP ĐÔI (BONDED)",
                        "Chỉ là ghép đôi BLE do Android xác nhận, KHÔNG chứng minh xe đã xác thực ứng dụng. ${VehicleLinkStatus.NOT_VERIFIED}: chưa có số liệu xe.")
                else ->
                    AutoStatus(AutoState.GATT_CONNECTED, "ĐÃ NỐI GATT",
                        "${VehicleLinkStatus.RAW_ONLY} · ${VehicleLinkStatus.NOT_VERIFIED} · chưa xác thực")
            }
        }
        if (i.runFailure == AutoFailure.EXCLUSIVE_HOLDER_SUSPECTED)
            return AutoStatus(AutoState.CONNECTION_BUSY, "XE ĐANG BẬN VỚI ỨNG DỤNG KHÁC",
                i.runMessage ?: "Nghi Y-Connect đang giữ kết nối. App không chiếm kết nối: hãy đóng Y-Connect/tắt Bluetooth của nó rồi thử lại.",
                AutoFailure.EXCLUSIVE_HOLDER_SUSPECTED)
        if (c is ConnState.Error) return AutoStatus(AutoState.FAILED, "THẤT BẠI", c.reason, c.kind.toFailure())
        if (i.runFailure != null) return AutoStatus(AutoState.FAILED, i.runFailure.title.uppercase(), i.runMessage, i.runFailure)
        if (c.isActive) return AutoStatus(AutoState.CANDIDATE_FOUND, "ĐANG KẾT NỐI TỚI ỨNG VIÊN…", "${c.code} — chưa phải đã kết nối")
        if (i.scanning) return if (i.candidateFound) AutoStatus(AutoState.CANDIDATE_FOUND, "ĐÃ THẤY ỨNG VIÊN", i.runMessage)
        else AutoStatus(AutoState.SCANNING, "ĐANG QUÉT…", i.runMessage)
        if (i.candidateFound) return AutoStatus(AutoState.CANDIDATE_FOUND, "ĐÃ THẤY ỨNG VIÊN", i.runMessage)
        return AutoStatus(AutoState.IDLE, "CHƯA BẮT ĐẦU", i.runMessage)
    }

    private fun ErrorKind.toFailure() = when (this) {
        ErrorKind.PERMISSION_DENIED -> AutoFailure.PERMISSION
        ErrorKind.BLUETOOTH_OFF, ErrorKind.BLUETOOTH_UNSUPPORTED -> AutoFailure.BLUETOOTH_OFF
        ErrorKind.AUTH_REQUIRED -> AutoFailure.AUTH_REQUIRED
        ErrorKind.TIMEOUT, ErrorKind.OUT_OF_RANGE -> AutoFailure.TIMEOUT
        else -> AutoFailure.GATT_ERROR
    }
}

enum class AuthLevel { UNKNOWN, NOT_REQUIRED_OBSERVED, REQUIRED, PAIRING, BONDED, REJECTED }

data class AuthAssessment(val level: AuthLevel, val headline: String, val guidance: List<String>) {
    /** true chỉ khi Android xác nhận đã ghép đôi. KHÔNG có nghĩa giao thức Yamaha đã được xác thực. */
    val linkAuthenticated get() = level == AuthLevel.BONDED
}

/** Sự kiện ghép đôi do hệ điều hành báo. reason là mã hệ thống nếu có (không giải thích được thì để nguyên số). */
data class BondEvent(val address: String, val previous: BondState, val now: BondState, val reason: Int? = null, val timeMs: Long = 0L)

/**
 * Đánh giá xác thực chỉ từ BẰNG CHỨNG QUAN SÁT ĐƯỢC. Không đoán PIN, không lưu bí mật, không tự ghép đôi:
 * ghép đôi chỉ bắt đầu khi người dùng bấm nút, và mã xác nhận/PIN luôn do hộp thoại hệ thống Android xử lý.
 */
object AuthAssessor {
    fun assess(conn: ConnState, bond: BondState, lastBondEvent: BondEvent?): AuthAssessment {
        val pairingGuide = listOf(
            "Ghép đôi theo quy trình Android: bấm \"Ghép đôi\" ở đây (Android sẽ hiện hộp thoại) hoặc vào Cài đặt Bluetooth.",
            "Nếu xe chỉ chấp nhận Y-Connect, cần phương thức tích hợp chính thức hoặc tài liệu giao thức của Yamaha. App không đoán PIN và không vượt xác thực."
        )
        if (lastBondEvent != null && lastBondEvent.previous == BondState.BONDING && lastBondEvent.now == BondState.NONE)
            return AuthAssessment(AuthLevel.REJECTED, "Ghép đôi KHÔNG thành công" + (lastBondEvent.reason?.let { " (mã hệ thống $it)" } ?: ""),
                listOf("Thiết bị hoặc bạn đã từ chối/hủy, hoặc mã xác nhận không khớp.") + pairingGuide)
        if (bond == BondState.BONDING) return AuthAssessment(AuthLevel.PAIRING, "Đang ghép đôi: hãy xác nhận trên hộp thoại Android", emptyList())
        if (bond == BondState.BONDED) return AuthAssessment(AuthLevel.BONDED, "Đã ghép đôi (Android xác nhận)",
            listOf("Đây là ghép đôi mức liên kết BLE. Nó KHÔNG chứng minh giao thức Yamaha đã được xác thực."))
        if (conn is ConnState.Error && conn.kind == ErrorKind.AUTH_REQUIRED)
            return AuthAssessment(AuthLevel.REQUIRED, "Thiết bị yêu cầu ghép đôi/xác thực", pairingGuide)
        if (conn is ConnState.Ready) return AuthAssessment(AuthLevel.NOT_REQUIRED_OBSERVED, "Chưa thấy yêu cầu xác thực ở mức GATT",
            listOf("Kết nối GATT mở được không cần ghép đôi. Điều đó không có nghĩa dữ liệu xe không cần xác thực ở lớp giao thức."))
        return AuthAssessment(AuthLevel.UNKNOWN, "Chưa có thông tin xác thực", emptyList())
    }
}
