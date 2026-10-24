package vn.nvxconnect.bluetooth

/**
 * Một lần thấy quảng bá. Các trường sau connectable có giá trị mặc định để mã cũ/test cũ vẫn dùng được.
 * manufacturerData: companyId -> hex (dùng cho phân tích, được che khi xuất log).
 */
data class AdvertRecord(
    val address: String, val name: String?, val rssi: Int,
    val advertisedServiceUuids: List<String>, val connectable: Boolean?, val seenAtMillis: Long,
    val addressKind: AddressKind = AddressKind.classify(address),
    val txPowerDbm: Int? = null,
    val manufacturerData: Map<Int, String> = emptyMap()
)

data class GattDescriptorInfo(val uuid: String)
data class GattCharInfo(val uuid: String, val properties: List<String>, val descriptors: List<GattDescriptorInfo> = emptyList()) {
    val canRead get() = "READ" in properties
    val canNotify get() = "NOTIFY" in properties
    val canIndicate get() = "INDICATE" in properties
    val canSubscribe get() = canNotify || canIndicate
    val canWrite get() = "WRITE" in properties || "WRITE_NO_RESP" in properties
}
data class GattServiceInfo(val uuid: String, val characteristics: List<GattCharInfo>)
data class SurveyLog(val createdAtMillis: Long, val adverts: List<AdvertRecord>, val services: Map<String, List<GattServiceInfo>>)

/** Hằng số thuộc tính characteristic theo chuẩn Android BluetoothGattCharacteristic. */
internal fun gattProps(p: Int): List<String> = buildList {
    if (p and 0x02 != 0) add("READ")
    if (p and 0x08 != 0) add("WRITE")
    if (p and 0x04 != 0) add("WRITE_NO_RESP")
    if (p and 0x10 != 0) add("NOTIFY")
    if (p and 0x20 != 0) add("INDICATE")
}
