package vn.nvxconnect.protocol

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import vn.nvxconnect.bluetooth.*
import java.util.UUID

/**
 * Cổng Android cho GattSessionController. CHỈ NGHE: không ghi characteristic dữ liệu, không createBond, không gửi lệnh.
 * Thao tác ghi duy nhất là ghi descriptor CCCD để bật/tắt notify theo yêu cầu của người dùng.
 *  - Mỗi lần mở phiên có một token; callback của phiên cũ bị bỏ qua và đóng.
 *  - Ngắt: disconnect() rồi close() khi hệ thống báo xong (hoặc sau 600 ms) để giải phóng tài nguyên.
 *  - Quan sát (không khởi tạo) trạng thái ghép nối và trạng thái Bluetooth bật/tắt.
 * CHƯA thử trên điện thoại/xe thật.
 */
@SuppressLint("MissingPermission")
class AndroidGattPort(private val context: Context) : GattPort {
    override var events: GattEvents? = null
    private val lock = Any()
    private var gatt: BluetoothGatt? = null
    private var token = 0
    private var services: List<BluetoothGattService> = emptyList()
    private var receiversOn = false
    private val handler = Handler(Looper.getMainLooper())

    private fun adapter(): BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override fun open(address: String): PortOpen {
        val a = adapter() ?: return PortOpen.Failed(ErrorKind.BLUETOOTH_UNSUPPORTED, "Thiết bị không hỗ trợ Bluetooth")
        if (!a.isEnabled) return PortOpen.Failed(ErrorKind.BLUETOOTH_OFF, "Bluetooth đang tắt. Hãy bật Bluetooth rồi thử lại.")
        if (!BlePermissions.hasConnect(context)) return PortOpen.Failed(ErrorKind.PERMISSION_DENIED, "Chưa cấp quyền Thiết bị ở gần (BLUETOOTH_CONNECT).")
        val dev = try { a.getRemoteDevice(address) } catch (e: IllegalArgumentException) {
            return PortOpen.Failed(ErrorKind.INVALID_ADDRESS, "Địa chỉ thiết bị không hợp lệ")
        }
        registerReceivers()
        val my: Int
        synchronized(lock) { releaseLocked(); my = ++token }
        val g = try { dev.connectGatt(context, false, callbackFor(my), BluetoothDevice.TRANSPORT_LE) }
        catch (e: SecurityException) { return PortOpen.Failed(ErrorKind.PERMISSION_DENIED, "Thiếu quyền kết nối Bluetooth: ${e.message}") }
        if (g == null) return PortOpen.Failed(ErrorKind.GATT_ERROR, "Android không tạo được phiên GATT")
        synchronized(lock) { if (token == my) { if (gatt == null) gatt = g } else safeClose(g) }
        return PortOpen.Started
    }

    private fun callbackFor(my: Int) = object : BluetoothGattCallback() {
        private fun live(g: BluetoothGatt): Boolean = synchronized(lock) {
            if (token != my) false else { if (gatt == null) gatt = g; true }
        }
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (!live(g)) { if (newState == BluetoothProfile.STATE_DISCONNECTED) safeClose(g); return }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> events?.onLinkState(true, status)
                BluetoothProfile.STATE_DISCONNECTED -> events?.onLinkState(false, status)
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { if (live(g)) events?.onMtu(mtu, status) }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (!live(g)) return
            val list = if (status == BluetoothGatt.GATT_SUCCESS) g.services.toList() else emptyList()
            synchronized(lock) { services = list }
            events?.onServices(GattSurveyor.snapshot(list), status)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (live(g)) events?.onDescriptorWrite(d.characteristic.uuid.toString(), status)
        }
        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) { if (live(g)) events?.onRssi(rssi, status) }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (live(g)) events?.onRead(c.uuid.toString(), value, status)
        }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33 && live(g)) events?.onRead(c.uuid.toString(), c.value ?: ByteArray(0), status)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (live(g)) events?.onNotification(c.uuid.toString(), value)
        }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && live(g)) events?.onNotification(c.uuid.toString(), c.value ?: ByteArray(0))
        }
    }

    private fun current(): BluetoothGatt? = synchronized(lock) { gatt }
    private fun findChar(uuid: String): BluetoothGattCharacteristic? {
        val id = runCatching { UUID.fromString(uuid) }.getOrNull() ?: return null
        return synchronized(lock) { services }.flatMap { it.characteristics }.firstOrNull { it.uuid == id }
    }

    override fun requestMtu(mtu: Int) = runCatching { current()?.requestMtu(mtu) == true }.getOrDefault(false)
    override fun discoverServices() = runCatching { current()?.discoverServices() == true }.getOrDefault(false)
    override fun readRssi() = runCatching { current()?.readRemoteRssi() == true }.getOrDefault(false)

    override fun readCharacteristic(charUuid: String): Boolean = runCatching {
        val g = current() ?: return false
        val c = findChar(charUuid) ?: return false
        g.readCharacteristic(c)
    }.getOrDefault(false)

    override fun setNotification(charUuid: String, enable: Boolean): Boolean = runCatching {
        val g = current() ?: return false
        val c = findChar(charUuid) ?: return false
        val d = c.getDescriptor(CCCD) ?: return false
        if (!g.setCharacteristicNotification(c, enable)) return false
        val canNotify = c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val v = when {
            !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            canNotify -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        else { @Suppress("DEPRECATION") run { d.value = v; g.writeDescriptor(d) } }
    }.getOrDefault(false)

    override fun close() { synchronized(lock) { token++; releaseLocked() } }

    private fun releaseLocked() {
        val g = gatt; gatt = null; services = emptyList()
        if (g != null) {
            runCatching { g.disconnect() }
            handler.postDelayed({ safeClose(g) }, 600)
        }
    }
    private fun safeClose(g: BluetoothGatt) { runCatching { g.close() } }

    // ---- quan sát ghép nối và trạng thái Bluetooth (không bao giờ gọi createBond) ----
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_OFF -> events?.onBluetoothState(false)
                    BluetoothAdapter.STATE_ON -> events?.onBluetoothState(true)
                }
            }
        }
    }
    private fun registerReceivers() {
        if (receiversOn) return
        val f = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(receiver, f)
            receiversOn = true
        }
    }
    fun dispose() {
        close()
        if (receiversOn) { runCatching { context.unregisterReceiver(receiver) }; receiversOn = false }
    }

    private companion object { val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") }
}

/** Kết nối xe qua BLE cho ứng dụng: GattSessionController + AndroidGattPort. Gọi dispose() khi không dùng nữa. */
class BleVehicleConnection private constructor(
    private val port: AndroidGattPort, private val c: GattSessionController
) : VehicleConnection by c {
    fun dispose() { c.disconnect(); port.dispose() }

    companion object {
        fun create(context: Context, scope: CoroutineScope): BleVehicleConnection {
            val port = AndroidGattPort(context.applicationContext)
            return BleVehicleConnection(port, GattSessionController(port, scope))
        }
    }
}
