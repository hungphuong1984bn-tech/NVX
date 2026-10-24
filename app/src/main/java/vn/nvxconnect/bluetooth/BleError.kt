package vn.nvxconnect.bluetooth

/** Phân loại lỗi để giao diện hiển thị đúng hướng xử lý. */
enum class ErrorKind(val title: String) {
    BLUETOOTH_UNSUPPORTED("Thiết bị không hỗ trợ BLE"),
    BLUETOOTH_OFF("Bluetooth đang tắt"),
    PERMISSION_DENIED("Chưa cấp quyền"),
    LOCATION_SERVICES_OFF("Dịch vụ vị trí đang tắt"),
    INVALID_ADDRESS("Địa chỉ không hợp lệ"),
    TIMEOUT("Hết thời gian chờ"),
    OUT_OF_RANGE("Thiết bị ngoài phạm vi hoặc không phản hồi"),
    SERVICE_DISCOVERY_FAILED("Khám phá dịch vụ thất bại"),
    AUTH_REQUIRED("Thiết bị yêu cầu ghép nối/xác thực"),
    SCAN_FAILED("Quét thất bại"),
    GATT_ERROR("Lỗi GATT")
}

class BleException(val kind: ErrorKind, message: String) : Exception(message)
