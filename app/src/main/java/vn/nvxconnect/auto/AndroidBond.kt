package vn.nvxconnect.auto

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.nvxconnect.bluetooth.BlePermissions

/**
 * Quan sát trạng thái ghép đôi do hệ điều hành báo, và chỉ bắt đầu ghép đôi khi người dùng bấm nút.
 *  - KHÔNG có PIN, mật khẩu, token hay khóa nào đi qua lớp này, không lưu gì xuống đĩa.
 *  - Không đoán/ép mã: createBond mở hộp thoại hệ thống, mã xác nhận do người dùng nhập/đối chiếu.
 *  - Không gọi hàm ẩn, không giả danh thiết bị.
 * CHƯA thử trên điện thoại thật.
 */
@SuppressLint("MissingPermission")
class AndroidBondObserver(private val context: Context) {
    private val _states = MutableStateFlow<Map<String, BondState>>(emptyMap())
    val states: StateFlow<Map<String, BondState>> = _states.asStateFlow()
    private val _events = MutableSharedFlow<BondEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<BondEvent> = _events.asSharedFlow()
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            val address = dev?.address ?: return
            val now = map(i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR))
            val prev = map(i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR))
            // Mã lý do là hằng ẩn của Android: đọc bằng tên chuỗi, không có thì để null.
            val reason = i.getIntExtra("android.bluetooth.device.extra.REASON", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
            _states.value = _states.value + (address.uppercase() to now)
            _events.tryEmit(BondEvent(address, prev, now, reason, System.currentTimeMillis()))
        }
    }

    private fun map(s: Int) = when (s) {
        BluetoothDevice.BOND_BONDED -> BondState.BONDED
        BluetoothDevice.BOND_BONDING -> BondState.BONDING
        else -> BondState.NONE
    }

    fun start() {
        if (started) return
        val f = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(receiver, f)
            started = true
        }
    }

    fun stop() { if (started) { runCatching { context.unregisterReceiver(receiver) }; started = false } }

    /** Trạng thái ghép đôi hiện tại do hệ thống báo (đọc, không thay đổi gì). */
    fun current(address: String): BondState {
        if (!BlePermissions.hasConnect(context)) return BondState.NONE
        val a = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return BondState.NONE
        return runCatching { map(a.getRemoteDevice(address).bondState) }.getOrDefault(BondState.NONE)
            .also { _states.value = _states.value + (address.uppercase() to it) }
    }

    /**
     * Bắt đầu ghép đôi theo quy trình hệ điều hành. CHỈ gọi từ nút do người dùng bấm, cho thiết bị người dùng đã chọn.
     * Trả về false nếu thiếu quyền, Bluetooth tắt, địa chỉ sai hoặc hệ thống từ chối bắt đầu.
     */
    fun requestPairing(address: String): Boolean {
        if (!BlePermissions.hasConnect(context)) return false
        val a: BluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return false
        if (!a.isEnabled) return false
        return runCatching { a.getRemoteDevice(address).createBond() }.getOrDefault(false)
    }
}
