package vn.nvxconnect.protocol

import vn.nvxconnect.bluetooth.ErrorKind
import vn.nvxconnect.bluetooth.GattServiceInfo

sealed interface PortOpen {
    data object Started : PortOpen
    data class Failed(val kind: ErrorKind, val reason: String) : PortOpen
}

/**
 * Ranh giới giữa máy trạng thái (thuần Kotlin, test được) và Android BluetoothGatt.
 * Không có hàm ghi dữ liệu: chỉ có thao tác khám phá, đọc thông tin chuẩn, và bật/tắt notify (ghi CCCD).
 */
interface GattPort {
    var events: GattEvents?
    /** Mở phiên GATT mới; phiên cũ (nếu còn) phải được đóng. */
    fun open(address: String): PortOpen
    /**
     * [background] = true: kết nối nền (Android autoConnect) — hệ thống tự nối ngay khi xe phát quảng bá lại (bật khóa điện),
     * không bị giới hạn thời gian chờ. Mặc định rơi về [open] thường để các cổng giả không phải đổi.
     */
    fun open(address: String, background: Boolean): PortOpen = open(address)
    fun requestMtu(mtu: Int): Boolean
    fun discoverServices(): Boolean
    fun readRssi(): Boolean
    /** setCharacteristicNotification + ghi CCCD. true = đã bắt đầu; kết quả đến qua onDescriptorWrite. */
    fun setNotification(charUuid: String, enable: Boolean): Boolean
    fun readCharacteristic(charUuid: String): Boolean
    /** Ngắt và giải phóng phiên hiện tại; mọi callback muộn của phiên đó phải bị bỏ qua. */
    fun close()
}

interface GattEvents {
    fun onLinkState(connected: Boolean, status: Int)
    fun onMtu(mtu: Int, status: Int)
    fun onServices(services: List<GattServiceInfo>, status: Int)
    fun onDescriptorWrite(charUuid: String, status: Int)
    fun onRssi(rssi: Int, status: Int)
    fun onRead(charUuid: String, value: ByteArray, status: Int)
    fun onNotification(charUuid: String, value: ByteArray)
    fun onBluetoothState(enabled: Boolean)
}
