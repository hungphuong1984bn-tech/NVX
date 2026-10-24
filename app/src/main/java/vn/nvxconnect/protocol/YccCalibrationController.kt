package vn.nvxconnect.protocol

data class CalStatus(
    val id: String, val quality: CalQuality, val fit: CalFit?, val points: Int, val validations: List<Double>,
    val promotionReady: Boolean, val suspectPoints: List<Int>
)

/**
 * Điều phối lọc + hiệu chỉnh + điểm tham chiếu. Thuần Kotlin (test được), không chạm vào xe.
 * Luồng dữ liệu: gói → ứng viên thô → lọc bất khả thi/trung vị → quy đổi bằng hệ số (nếu đạt) → kho ứng viên.
 */
class YccCalibrationController(private val store: CalibrationStore?, private val windowMs: Long = 3000L) {
    private val sessions = CalSpecs.ALL.mapValues { CalSession(it.value) }
    private val rings = HashMap<String, ArrayDeque<Pair<Long, Double>>>()
    private val filters = mapOf(
        YccCandidates.RPM_ID to YccSignalFilter(0.0, 12000.0, 4000.0),
        YccCandidates.BATT_ID to YccSignalFilter(0.0, 255.0, 40.0)
    )

    init { store?.load()?.forEach { (id, pts) -> sessions[id]?.let { s -> pts.forEach { s.addPoint(it) } } } }

    fun fits(): Map<String, CalFit> = sessions.mapNotNull { (id, s) -> s.fit?.let { id to it } }.toMap()

    /** Nhận ứng viên thô từ một khung; trả về ứng viên đã lọc/quy đổi (bỏ mục bị loại hoặc chưa đủ mẫu). */
    fun process(raw: List<YccCandidate>): List<YccCandidate> {
        val cal = fits()
        return raw.mapNotNull { c ->
            val ring = rings.getOrPut(c.id) { ArrayDeque() }
            ring.addLast(c.timeMs to c.rawValue); while (ring.size > 200) ring.removeFirst()
            val filtered = filters[c.id]?.push(c.rawValue) ?: return@mapNotNull null
            YccCandidates.withRaw(c, filtered, cal)
        }
    }

    sealed interface Result {
        data class Ok(val message: String) : Result
        data class Refused(val message: String) : Result
    }

    /** Ghi điểm tham chiếu (validate=false) hoặc điểm kiểm chứng độc lập (validate=true) cho kênh [id] tại thời điểm [nowMs]. */
    fun record(id: String, ref: Double, validate: Boolean, nowMs: Long): Result {
        val s = sessions[id] ?: return Result.Refused("Kênh không hỗ trợ hiệu chỉnh")
        val spec = s.spec
        if (!ref.isFinite() || ref < 0) return Result.Refused("Giá trị tham chiếu không hợp lệ")
        val window = rings[id]?.filter { nowMs - it.first in 0..windowMs }?.map { it.second } ?: emptyList()
        val raw = Calibration.steadyRaw(window, spec.maxSteadyRelSpread)
            ?: return Result.Refused(if (window.size < 8) "Chưa đủ dữ liệu từ xe (cần ~1 giây gói liên tục)" else "Tín hiệu chưa ổn định — giữ nguyên ga/tải rồi thử lại")
        if (raw == 0.0 && id == YccCandidates.RPM_ID) return Result.Refused("Giá trị thô bằng 0 (động cơ chưa nổ): không dùng làm điểm hiệu chỉnh vòng tua")
        if (validate) {
            val e = s.validate(raw, ref) ?: return Result.Refused("Cần hệ số đạt chất lượng (${CalQuality.GOOD.title}) trước khi kiểm chứng")
            persist()
            return Result.Ok("Sai số kiểm chứng ${"%.2f".format(e)} ${spec.unit} (${s.validationErrors.size}/${CalSession.MIN_VALIDATIONS}).")
        }
        s.addPoint(CalPoint(raw, ref, nowMs)); persist()
        val sus = Calibration.suspects(s.pointList, spec)
        return Result.Ok("Đã ghi điểm ${s.pointList.size}: thô ${"%.0f".format(raw)} ↔ $ref ${spec.unit}. Chất lượng: ${s.quality.title}." +
            if (sus.isNotEmpty()) " Nghi sai: điểm ${sus.joinToString { (it + 1).toString() }}." else "")
    }

    fun undo(id: String) { sessions[id]?.removeLast(); persist() }
    fun reset(id: String) { sessions[id]?.reset(); persist() }

    fun status(): Map<String, CalStatus> = sessions.mapValues { (_, s) ->
        CalStatus(s.spec.id, s.quality, s.fit, s.pointList.size, s.validationErrors, s.promotionReady, Calibration.suspects(s.pointList, s.spec))
    }

    /** Mất kết nối: xóa cửa sổ lọc/mẫu để không trộn dữ liệu trước và sau khi nối lại. */
    fun onDisconnect() { filters.values.forEach { it.reset() }; rings.clear() }

    private fun persist() { store?.save(sessions.mapValues { it.value.pointList }) }
}
