package vn.nvxconnect.protocol

import vn.nvxconnect.bluetooth.GattServiceInfo
import vn.nvxconnect.telemetry.EvidenceGrade

/** Bằng chứng của MỘT trường đã đạt VERIFIED (lấy từ [vn.nvxconnect.telemetry.FieldVerdict], không tự khai tay). */
data class FieldProof(val fieldId: String, val criteriaFingerprint: String, val sessions: Int, val grade: EvidenceGrade, val verifiedAtMs: Long)

/**
 * Hồ sơ tương thích bắt buộc của một adapter: xác định rõ mẫu xe, biến thể, thiết bị giao tiếp, firmware,
 * service/characteristic và phiên bản giao thức ĐÃ KIỂM CHỨNG, cùng bằng chứng từng trường.
 */
data class AdapterCompatibilityProfile(
    val vehicleModel: String, val variant: String, val commDevice: String, val firmware: String,
    val serviceUuids: List<String>, val characteristicUuids: List<String>, val protocolVersion: String,
    val verifiedFields: List<FieldProof>
)

/** Lý do từ chối đăng ký (rỗng = đạt). Hàm thuần để test và để giao diện giải thích tại sao adapter chưa được kích hoạt. */
object AdapterProfileGate {
    private val ALLOWED_GRADES = setOf(EvidenceGrade.OFFICIAL_SOURCE, EvidenceGrade.INDEPENDENT_MEASUREMENT)
    private const val MIN_SESSIONS = 2

    fun check(e: ProtocolEvidence, p: AdapterCompatibilityProfile?): List<String> {
        if (p == null) return listOf("Thiếu hồ sơ tương thích (mẫu xe, biến thể, thiết bị, firmware, service/characteristic, phiên bản giao thức)")
        val r = ArrayList<String>()
        if (!e.verified || e.testedOnVehicle.isNullOrBlank() || e.testResult.isNullOrBlank()) r += "Hồ sơ bằng chứng chưa xác minh trên xe thật"
        if (e.serviceUuids.isEmpty() || e.sampleHexRedacted.isEmpty()) r += "Thiếu service UUID hoặc mẫu gói đã ẩn"
        for ((name, v) in listOf("mẫu xe" to p.vehicleModel, "biến thể" to p.variant, "thiết bị giao tiếp" to p.commDevice, "firmware" to p.firmware, "phiên bản giao thức" to p.protocolVersion))
            if (v.isBlank()) r += "Hồ sơ thiếu $name"
        if (p.serviceUuids.isEmpty()) r += "Hồ sơ thiếu service UUID"
        else if (p.serviceUuids.map { it.lowercase() }.toSet() != e.serviceUuids.map { it.lowercase() }.toSet()) r += "Service UUID trong hồ sơ khác hồ sơ bằng chứng"
        if (p.characteristicUuids.isEmpty()) r += "Hồ sơ thiếu characteristic nguồn dữ liệu"
        if (p.verifiedFields.isEmpty()) r += "Chưa có trường nào đạt VERIFIED: manh mối (loại khung, Nordic UART, checksum) không đủ để kích hoạt adapter"
        for (f in p.verifiedFields) {
            if (f.criteriaFingerprint.isBlank()) r += "Trường ${f.fieldId}: thiếu vân tay tiêu chí đã khóa"
            if (f.sessions < MIN_SESSIONS) r += "Trường ${f.fieldId}: chỉ ${f.sessions} phiên độc lập (cần ≥$MIN_SESSIONS)"
            if (f.grade !in ALLOWED_GRADES) r += "Trường ${f.fieldId}: cấp bằng chứng '${f.grade.title}' không đủ"
        }
        return r
    }
}

/** Các characteristic hồ sơ yêu cầu phải có mặt trên thiết bị thật thì adapter mới được dùng. */
fun AdapterCompatibilityProfile.presentIn(services: List<GattServiceInfo>): Boolean {
    val have = services.flatMap { it.characteristics }.map { it.uuid.lowercase() }.toSet()
    return characteristicUuids.all { it.lowercase() in have }
}
