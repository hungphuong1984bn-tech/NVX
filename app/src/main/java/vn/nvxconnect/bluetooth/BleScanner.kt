package vn.nvxconnect.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Bộ lọc quét tùy chọn. Rỗng = quét tất cả (mặc định của khảo sát).
 * address chỉ nên dùng khi chắc chắn địa chỉ không xoay vòng; namePrefix lọc phía ứng dụng.
 */
data class ScanSpec(val address: String? = null, val serviceUuid: UUID? = null, val namePrefix: String? = null)

/**
 * Chỉ quét quảng bá, không kết nối. Lỗi được báo bằng BleException (có ErrorKind) qua Flow.
 * Tự dừng sau timeoutMs để tiết kiệm pin (0 = không tự dừng).
 */
class BleScanner(private val context: Context) {
    @SuppressLint("MissingPermission")
    fun scan(spec: ScanSpec = ScanSpec(), lowPower: Boolean = false, timeoutMs: Long = 60_000): Flow<AdvertRecord> = callbackFlow {
        val blockers = BlePreflight.check(context)
        if (blockers.isNotEmpty()) {
            val b = blockers.first()
            close(BleException(b.kind, b.message)); return@callbackFlow
        }
        val scanner = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner
        if (scanner == null) { close(BleException(ErrorKind.BLUETOOTH_OFF, "Bluetooth tắt hoặc không có bộ quét BLE")); return@callbackFlow }
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val rec = r.scanRecord
                val name = rec?.deviceName
                if (spec.namePrefix != null && name?.startsWith(spec.namePrefix, ignoreCase = true) != true) return
                val mfg = HashMap<Int, String>()
                rec?.manufacturerSpecificData?.let { sa ->
                    for (i in 0 until sa.size()) mfg[sa.keyAt(i)] = HexUtil.toHex(sa.valueAt(i) ?: ByteArray(0))
                }
                trySend(AdvertRecord(
                    address = r.device.address, name = name, rssi = r.rssi,
                    advertisedServiceUuids = rec?.serviceUuids?.map { it.toString() } ?: emptyList(),
                    connectable = r.isConnectable, seenAtMillis = System.currentTimeMillis(),
                    txPowerDbm = r.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT },
                    manufacturerData = mfg))
            }
            override fun onScanFailed(code: Int) { close(BleException(ErrorKind.SCAN_FAILED, describe(code))) }
        }
        val filters = buildList {
            if (spec.address != null || spec.serviceUuid != null) add(ScanFilter.Builder().apply {
                spec.address?.let { setDeviceAddress(it) }
                spec.serviceUuid?.let { setServiceUuid(ParcelUuid(it)) }
            }.build())
        }
        val mode = if (lowPower) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_LATENCY
        try {
            scanner.startScan(filters.ifEmpty { null }, ScanSettings.Builder().setScanMode(mode).build(), cb)
        } catch (e: SecurityException) {
            close(BleException(ErrorKind.PERMISSION_DENIED, "Thiếu quyền quét Bluetooth: ${e.message}")); return@callbackFlow
        } catch (e: IllegalStateException) {
            close(BleException(ErrorKind.BLUETOOTH_OFF, "Bluetooth vừa bị tắt: ${e.message}")); return@callbackFlow
        }
        if (timeoutMs > 0) launch { delay(timeoutMs); close() }
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }

    private fun describe(code: Int) = when (code) {
        1 -> "Quét lỗi: đã có lượt quét cùng cấu hình (1)"
        2 -> "Quét lỗi: không đăng ký được (2)"
        3 -> "Quét lỗi: không hỗ trợ (3)"
        4 -> "Quét lỗi nội bộ (4)"
        5 -> "Quét lỗi: thiếu tài nguyên phần cứng (5)"
        6 -> "Quét quá dày (Android giới hạn 5 lần bắt đầu quét / 30 giây), chờ rồi thử lại (6)"
        else -> "Quét lỗi, mã $code"
    }
}
