package vn.nvxconnect.bluetooth

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build

/** Quyền runtime cần cho BLE theo từng phiên bản Android. Tên quyền là chuỗi hằng để phần logic test được trên JVM. */
object BlePermissions {
    const val SCAN = "android.permission.BLUETOOTH_SCAN"
    const val CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"

    /**
     * Android 12+ (API 31): BLUETOOTH_SCAN (đã khai báo neverForLocation nên KHÔNG cần quyền vị trí) + BLUETOOTH_CONNECT.
     * Android 8-11: quét BLE cần quyền vị trí; BLUETOOTH/BLUETOOTH_ADMIN là quyền cài đặt, không hỏi lúc chạy.
     */
    fun forScan(sdk: Int): List<String> =
        if (sdk >= 31) listOf(SCAN, CONNECT) else listOf(FINE_LOCATION)

    fun forConnect(sdk: Int): List<String> = if (sdk >= 31) listOf(CONNECT) else emptyList()

    fun missing(required: List<String>, isGranted: (String) -> Boolean): List<String> = required.filterNot(isGranted)

    fun granted(context: Context, p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun missingForScan(context: Context): List<String> = missing(forScan(Build.VERSION.SDK_INT)) { granted(context, it) }
    fun hasConnect(context: Context): Boolean = missing(forConnect(Build.VERSION.SDK_INT)) { granted(context, it) }.isEmpty()
}

enum class BleBlocker(val kind: ErrorKind, val message: String) {
    NO_BLE_HARDWARE(ErrorKind.BLUETOOTH_UNSUPPORTED, "Điện thoại không hỗ trợ Bluetooth Low Energy."),
    BLUETOOTH_OFF(ErrorKind.BLUETOOTH_OFF, "Bluetooth đang tắt. Hãy bật Bluetooth rồi thử lại."),
    PERMISSION_MISSING(ErrorKind.PERMISSION_DENIED, "Chưa cấp quyền cần thiết. Hãy cấp quyền (Thiết bị ở gần / Vị trí) trong hộp thoại hoặc Cài đặt."),
    LOCATION_SERVICES_OFF(ErrorKind.LOCATION_SERVICES_OFF, "Android 11 trở xuống cần bật Vị trí (GPS) của hệ thống thì mới quét được BLE.")
}

/** Kiểm tra điều kiện trước khi quét/kết nối. Phần quyết định là hàm thuần để unit test. */
object BlePreflight {
    fun evaluate(sdk: Int, hasBleHardware: Boolean, bluetoothOn: Boolean, missingPermissions: List<String>, locationServicesOn: Boolean): List<BleBlocker> =
        buildList {
            if (!hasBleHardware) { add(BleBlocker.NO_BLE_HARDWARE); return@buildList }
            if (missingPermissions.isNotEmpty()) add(BleBlocker.PERMISSION_MISSING)
            if (!bluetoothOn) add(BleBlocker.BLUETOOTH_OFF)
            if (sdk < 31 && !locationServicesOn) add(BleBlocker.LOCATION_SERVICES_OFF)
        }

    fun check(context: Context): List<BleBlocker> {
        val hasBle = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val btOn = try { adapter?.isEnabled == true } catch (_: SecurityException) { false }
        val locOn = (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.let {
            if (Build.VERSION.SDK_INT >= 28) it.isLocationEnabled else true
        } ?: true
        return evaluate(Build.VERSION.SDK_INT, hasBle && adapter != null, btOn, BlePermissions.missingForScan(context), locOn)
    }
}
