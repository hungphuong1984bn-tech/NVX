package vn.nvxconnect.auto

import vn.nvxconnect.bluetooth.GattServiceInfo
import vn.nvxconnect.bluetooth.KnownUuids

/** Một dòng trong bảng GATT. Chỉ mô tả, không thao tác gì lên thiết bị. */
data class InspectorRow(
    val serviceUuid: String, val charUuid: String, val label: String?, val properties: List<String>,
    val canSubscribe: Boolean, val hasWrite: Boolean, val standardInfo: Boolean
)

data class InspectorSummary(
    val services: Int, val characteristics: Int, val subscribable: Int, val readable: Int, val writable: Int, val rows: List<InspectorRow>
) {
    fun render(): String = buildString {
        appendLine("GATT: $services service, $characteristics characteristic (đọc được $readable, nghe được $subscribable, có thuộc tính ghi $writable)")
        if (writable > 0) appendLine("App KHÔNG ghi vào $writable characteristic có thuộc tính ghi vì chưa có giao thức được xác minh.")
        rows.forEach { r ->
            appendLine("- ${r.charUuid.take(8)} [${r.properties.joinToString("|").ifEmpty { "—" }}]" + (r.label?.let { " $it" } ?: ""))
        }
    }
}

/**
 * GATT Inspector: biến cây dịch vụ thành bảng có cờ an toàn. Thao tác thực tế (khám phá, đọc thông tin chuẩn,
 * bật notify) nằm ở GattSessionController, đi qua hàng đợi tuần tự và watchdog; không có thao tác ghi dữ liệu.
 */
object GattInspector {
    fun summarize(services: List<GattServiceInfo>): InspectorSummary {
        val rows = services.flatMap { s ->
            s.characteristics.map { c ->
                InspectorRow(s.uuid, c.uuid, KnownUuids.label(c.uuid), c.properties, c.canSubscribe, c.canWrite,
                    vn.nvxconnect.protocol.StandardInfo.isAllowed(c.uuid))
            }
        }
        return InspectorSummary(services.size, rows.size, rows.count { it.canSubscribe }, rows.count { "READ" in it.properties }, rows.count { it.hasWrite }, rows)
    }
}
