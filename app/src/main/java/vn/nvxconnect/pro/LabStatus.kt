package vn.nvxconnect.pro

import vn.nvxconnect.auto.EvidenceLevel
import vn.nvxconnect.telemetry.EvidenceGrade
import vn.nvxconnect.telemetry.VerificationStatus

/** 6 nhãn trạng thái bằng chứng hiển thị ở Protocol Lab. */
enum class LabStatus(val title: String, val meaning: String) {
    OBSERVED("OBSERVED", "Đã thấy dữ liệu thô; chưa giải nghĩa"),
    HYPOTHESIS("HYPOTHESIS", "Giả thuyết giải mã; chưa xác minh"),
    CORRELATED("CORRELATED", "Đã đối chiếu nhưng chưa đủ tiêu chí xác minh"),
    VERIFIED("VERIFIED", "Đã xác minh bằng bằng chứng độc lập"),
    UNAVAILABLE("UNAVAILABLE", "Chưa tìm thấy nguồn dữ liệu"),
    INACCESSIBLE("INACCESSIBLE", "Không truy cập được bằng phương thức hiện tại (cần xác thực/mã hóa)")
}

/**
 * Ánh xạ MỘT CHIỀU từ trạng thái do engine hiện có cấp sang nhãn hiển thị. Giao diện không có đường nào tự tạo VERIFIED:
 * VERIFIED chỉ xuất hiện khi engine nguồn đã báo VERIFIED.
 */
object LabStatusMapper {
    fun fromVerification(s: VerificationStatus, g: EvidenceGrade): LabStatus = when {
        g == EvidenceGrade.INACCESSIBLE -> LabStatus.INACCESSIBLE
        s == VerificationStatus.VERIFIED -> LabStatus.VERIFIED
        s == VerificationStatus.CORRELATED -> LabStatus.CORRELATED
        s == VerificationStatus.DECODED_HYPOTHESIS -> LabStatus.HYPOTHESIS
        s == VerificationStatus.RAW_UNVERIFIED -> LabStatus.OBSERVED
        s == VerificationStatus.STALE -> LabStatus.OBSERVED
        else -> LabStatus.UNAVAILABLE
    }

    fun fromEvidence(l: EvidenceLevel): LabStatus = when (l) {
        EvidenceLevel.OBSERVED -> LabStatus.OBSERVED
        EvidenceLevel.HYPOTHESIS -> LabStatus.HYPOTHESIS
        EvidenceLevel.VERIFIED -> LabStatus.VERIFIED
        EvidenceLevel.UNKNOWN -> LabStatus.UNAVAILABLE
        EvidenceLevel.ENCRYPTED_OR_AUTHENTICATED -> LabStatus.INACCESSIBLE
    }
}
