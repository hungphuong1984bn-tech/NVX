package vn.nvxconnect.helpcenter

import vn.nvxconnect.auto.AutoFailure
import vn.nvxconnect.bluetooth.ErrorKind
import vn.nvxconnect.helpcenter.HelpContent.Id
import vn.nvxconnect.protocol.ConnState
import vn.nvxconnect.protocol.LinkLevel
import vn.nvxconnect.protocol.VehicleLinkStatus

/**
 * Ánh xạ LỖI THỰC TẾ → bài hướng dẫn. Dùng `when` đầy đủ (không có nhánh else) nên thêm giá trị enum mới
 * mà quên ánh xạ sẽ lỗi biên dịch. Test bảo đảm mọi id trả về đều tồn tại trong [HelpContent].
 */
object HelpErrorMapper {
    fun forError(kind: ErrorKind): String = when (kind) {
        ErrorKind.BLUETOOTH_UNSUPPORTED -> Id.PREPARE_PHONE
        ErrorKind.BLUETOOTH_OFF -> Id.PREPARE_PHONE
        ErrorKind.LOCATION_SERVICES_OFF -> Id.PREPARE_PHONE
        ErrorKind.PERMISSION_DENIED -> Id.PERMISSIONS
        ErrorKind.INVALID_ADDRESS -> Id.SCAN_SELECT
        ErrorKind.SCAN_FAILED -> Id.NOT_FOUND
        ErrorKind.OUT_OF_RANGE -> Id.NOT_FOUND
        ErrorKind.TIMEOUT -> Id.CONNECT_STATES
        ErrorKind.AUTH_REQUIRED -> Id.CONNECT_STATES
        ErrorKind.SERVICE_DISCOVERY_FAILED -> Id.GATT_SERVICES
        ErrorKind.GATT_ERROR -> Id.LOST_NO_DATA
    }

    fun forAutoFailure(f: AutoFailure): String = when (f) {
        AutoFailure.PERMISSION -> Id.PERMISSIONS
        AutoFailure.BLUETOOTH_OFF -> Id.PREPARE_PHONE
        AutoFailure.AUTH_REQUIRED -> Id.CONNECT_STATES
        AutoFailure.EXCLUSIVE_HOLDER_SUSPECTED -> Id.YCONNECT
        AutoFailure.NOT_FOUND -> Id.NOT_FOUND
        AutoFailure.GATT_ERROR -> Id.LOST_NO_DATA
        AutoFailure.TIMEOUT -> Id.CONNECT_STATES
        AutoFailure.RETRIES_EXHAUSTED -> Id.LOST_NO_DATA
    }

    /**
     * Gợi ý bài cho trạng thái hiện tại, hoặc null nếu KHÔNG có lỗi để khắc phục (không bịa nút "khắc phục" khi mọi thứ bình thường).
     * RAW_DATA_ONLY không phải lỗi kết nối nhưng là nguyên nhân phổ biến của "không có dữ liệu" nên có bài riêng.
     */
    fun forState(conn: ConnState, link: VehicleLinkStatus): String? = when {
        conn is ConnState.Error -> forError(conn.kind)
        conn is ConnState.Reconnecting -> Id.LOST_NO_DATA
        link.level == LinkLevel.RAW_DATA_ONLY -> Id.VEHICLE_DATA
        else -> null
    }
}
