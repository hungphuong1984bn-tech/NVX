package vn.nvxconnect.domain

/**
 * Thông số THAM KHẢO của Yamaha NVX 155 (tài liệu công bố của hãng/đại lý, KHÔNG đọc từ xe).
 * Không thuộc DataSource nào trong 6 nguồn đo/nhập: luôn hiển thị kèm nhãn "Tham khảo" và nguồn.
 * Thông số khác nhau theo thị trường/đời xe (ví dụ bình xăng 4,6 L ở đời đầu, 5,5 L ở đời mới),
 * nên người dùng có thể ghi đè dung tích bình theo sổ tay xe của mình.
 */
data class SpecItem(val label: String, val value: String, val note: String? = null)

object VehicleSpecs {
    const val REFERENCE_LABEL = "Tham khảo"
    const val DISCLAIMER = "Thông số tham khảo từ tài liệu công bố, có thể khác theo thị trường/đời xe. Hãy đối chiếu sổ tay chủ xe. App không đọc các giá trị này từ xe."

    /** Dung tích bình mặc định (lít) cho đời mới (Yamaha Malaysia công bố 5,5 L). Đời đầu: 4,6 L. */
    const val DEFAULT_TANK_L = 5.5
    const val OLD_GEN_TANK_L = 4.6

    val nvx155: List<SpecItem> = listOf(
        SpecItem("Động cơ", "155 cc, 4 thì, SOHC 4 van, làm mát dung dịch, VVA"),
        SpecItem("Đường kính × hành trình", "58,0 × 58,7 mm"),
        SpecItem("Tỷ số nén", "11,6 : 1"),
        SpecItem("Công suất cực đại", "11,3 kW (15,4 PS) / 8.000 vòng/phút", "Công bố thị trường Malaysia"),
        SpecItem("Mô-men cực đại", "13,9 N·m / 6.500 vòng/phút", "Công bố thị trường Malaysia"),
        SpecItem("Dung tích dầu máy", "1,0 L", "Lượng thay dầu theo công bố; đối chiếu sổ tay"),
        SpecItem("Bình xăng", "5,5 L (đời mới) · 4,6 L (đời đầu)"),
        SpecItem("Chiều cao yên", "790 mm"),
        SpecItem("Khối lượng ướt", "122 kg (thường) · 125 kg (ABS)")
    )

    fun tankOrDefault(userTankL: Double?): Double = userTankL?.takeIf { it in 2.0..30.0 } ?: DEFAULT_TANK_L
}

/** Ước tính tầm chạy: CALCULATED từ dung tích bình (tham khảo/bạn nhập) và L/100km (bạn nhập đổ xăng). */
data class RangeEstimate(val rangeKm: Int, val tankL: Double, val lPer100Km: Double)

object RangeCalc {
    /** Trả null nếu thiếu dữ liệu hợp lệ. fractionOfTank 0..1 = mức xăng hiện có (mặc định đầy bình). */
    fun estimate(tankL: Double, lPer100Km: Double?, fractionOfTank: Double = 1.0): RangeEstimate? {
        if (lPer100Km == null || lPer100Km <= 0.5 || lPer100Km > 20.0) return null
        if (tankL <= 0.0 || fractionOfTank !in 0.0..1.0) return null
        val km = tankL * fractionOfTank / lPer100Km * 100.0
        return RangeEstimate(km.toInt(), tankL, lPer100Km)
    }
}
