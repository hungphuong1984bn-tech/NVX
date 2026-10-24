package vn.nvxconnect.bluetooth

/**
 * Nhãn CHUNG cho UUID thường gặp, chỉ để dễ đọc log. Nhãn không xác nhận hãng hay chức năng của xe:
 * ví dụ "Nordic UART" chỉ nói thiết bị dùng khung dịch vụ nối tiếp phổ biến, nội dung gói vẫn chưa biết.
 */
object KnownUuids {
    private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"
    private val short16 = mapOf(
        "1800" to "Generic Access", "1801" to "Generic Attribute", "180a" to "Device Information", "180f" to "Battery",
        "2a00" to "Device Name", "2a01" to "Appearance", "2a04" to "Peripheral Preferred Connection Parameters",
        "2aa6" to "Central Address Resolution",
        "1812" to "Human Interface Device (HID, bàn phím/chuột/tay cầm)",
        "2a05" to "Service Changed", "2a33" to "HID Boot Mouse Input", "2a4a" to "HID Information",
        "2a4b" to "HID Report Map", "2a4c" to "HID Control Point", "2a4d" to "HID Report",
        "2a23" to "System ID", "2a24" to "Model Number", "2a25" to "Serial Number (app KHÔNG đọc)",
        "2a26" to "Firmware Revision", "2a27" to "Hardware Revision", "2a28" to "Software Revision",
        "2a29" to "Manufacturer Name", "2a2a" to "IEEE Regulatory Certification", "2a50" to "PnP ID",
        "2b29" to "Client Supported Features", "2b2a" to "Database Hash",
        "fee0" to "UUID 16-bit đăng ký cho Anhui Huami (thường gặp ở vòng đeo tay)",
        "fef5" to "UUID 16-bit đăng ký cho Dialog Semiconductor",
        "ffb0" to "UUID 16-bit tự đặt (không đăng ký), thường là module serial BLE giá rẻ",
        "ffb1" to "ffb0: kênh ghi (module serial)", "ffb2" to "ffb0: kênh nhận (module serial)"
    )
    const val NUS_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val NUS_RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e" // điện thoại GHI vào đây
    const val NUS_TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e" // thiết bị gửi NOTIFY ra đây

    /** Khung serial của Texas Instruments (f000ffc0-0451-4000-b000-000000000000), phổ biến ở module BLE dùng chip TI. */
    const val TI_SERIAL_SERVICE = "f000ffc0-0451-4000-b000-000000000000"

    fun label(uuid: String): String? {
        val u = uuid.lowercase()
        when (u) {
            TI_SERIAL_SERVICE -> return "Khung serial Texas Instruments (nội dung gói chưa biết)"
            "f000ffc1-0451-4000-b000-000000000000", "f000ffc2-0451-4000-b000-000000000000" -> return "TI serial: kênh ghi/notify (nội dung chưa biết)"
            NUS_SERVICE -> return "Nordic UART Service (khung serial BLE phổ biến)"
            NUS_RX -> return "NUS RX: kênh điện thoại ghi vào thiết bị (app KHÔNG ghi)"
            NUS_TX -> return "NUS TX: kênh thiết bị gửi notify ra điện thoại"
        }
        if (u.length == 36 && u.startsWith("0000") && u.endsWith(BASE_SUFFIX)) return short16[u.substring(4, 8)]
        return null
    }

    fun pretty(uuid: String): String {
        val l = label(uuid) ?: return uuid
        return "$uuid\n  ↳ $l"
    }
}
