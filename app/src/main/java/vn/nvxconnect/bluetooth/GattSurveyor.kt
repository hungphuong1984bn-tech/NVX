package vn.nvxconnect.bluetooth

import android.bluetooth.BluetoothGattService

/**
 * Chỉ CHUYỂN cây dịch vụ GATT của Android thành mô hình của app (service → characteristic → descriptor, kèm thuộc tính).
 * Bản cũ của lớp này tự mở một phiên GATT thứ hai; điều đó vi phạm nguyên tắc "một phiên GATT cho một thiết bị",
 * nên nay mọi kết nối đi qua GattSessionController/BleVehicleConnection và lớp này không còn gọi connectGatt.
 */
object GattSurveyor {
    fun snapshot(services: List<BluetoothGattService>): List<GattServiceInfo> = services.map { s ->
        GattServiceInfo(s.uuid.toString(), s.characteristics.map { c ->
            GattCharInfo(c.uuid.toString(), gattProps(c.properties), c.descriptors.map { GattDescriptorInfo(it.uuid.toString()) })
        })
    }
}
