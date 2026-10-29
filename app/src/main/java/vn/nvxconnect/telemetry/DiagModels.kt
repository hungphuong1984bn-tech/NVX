package vn.nvxconnect.telemetry

import vn.nvxconnect.util.MiniJson
import vn.nvxconnect.util.MiniJson.obj
import vn.nvxconnect.util.MiniJson.text
import java.util.zip.CRC32

// ======================================================================================================
//  Advanced Vehicle Data Logger — mô hình dữ liệu (thuần Kotlin, không phụ thuộc Android, test được trên JVM).
//  Nguyên tắc: CHỈ ĐỌC. Không có kiểu/hàm nào ở đây mô tả việc gửi lệnh tới xe hay ECU. Mọi bản ghi là thứ app đã QUAN SÁT.
// ======================================================================================================

/**
 * Phân loại độ tin cậy của MỘT số liệu. Giao diện, nhật ký và báo cáo dùng cùng một bộ nhãn.
 *  - REAL: đọc thật (từ xe qua adapter đã xác minh, hoặc đo thật ở lớp Bluetooth của điện thoại).
 *  - DERIVED: app/điện thoại tính ra (GPS điện thoại, bộ đếm, tốc độ nhận gói...). KHÔNG phải số của ECU.
 *  - UNSUPPORTED: giao thức đã xác minh chưa có tham số này (hoặc chưa có giao thức nào): app không đoán.
 *  - INVALID: đã nhận nhưng không dùng được (ngoài khoảng hợp lệ, quá hạn, liên kết không còn xác minh).
 *  - NO_DATA: chưa nhận được mẫu nào.
 */
enum class DiagClass(val title: String) {
    REAL("Dữ liệu thực"), DERIVED("Dữ liệu suy tính"), UNSUPPORTED("Chưa hỗ trợ"), INVALID("Không hợp lệ"), NO_DATA("Chưa có dữ liệu")
}

/** Nguồn gốc vật lý của số liệu. Một số GPS không bao giờ được trình bày như số liệu của xe. */
enum class DiagSource(val title: String) {
    VEHICLE("Xe (adapter đã xác minh)"), PHONE_BLE("Bluetooth điện thoại"), PHONE_GPS("GPS điện thoại (không phải ECU)"), APP("Ứng dụng tính toán")
}

enum class DiagKind { SESSION, SAMPLE, MISSING, BLE_EVENT, ERROR, NOTE }

/** Tên các bản ghi điều khiển phiên (kind = SESSION). */
object DiagControl {
    const val START = "START"; const val PAUSE = "PAUSE"; const val RESUME = "RESUME"; const val STOP = "STOP"
    /** Ghi khi mở lại app và thấy phiên cũ chưa kết thúc (app bị đóng đột ngột). */
    const val RECOVERED = "RECOVERED"
    /** Người dùng chủ động tiếp tục một phiên đã khôi phục. */
    const val CONTINUE = "CONTINUE"
    /** Đạt giới hạn dung lượng / thiếu bộ nhớ: từ đây mẫu thường bị loại và ĐƯỢC ĐẾM. */
    const val LIMIT = "LIMIT"
}

/**
 * Một bản ghi nhật ký. [wallMs] = giờ hệ thống (UTC epoch ms) lúc SỰ KIỆN/MẪU xảy ra (với mẫu từ xe là lúc giải mã xong gói, không phải lúc ghi).
 * [monoMs] = đồng hồ đơn điệu (không nhảy khi người dùng chỉnh giờ) tại cùng thời điểm, dùng để tính thời lượng. [seq] tăng theo thứ tự ghi.
 */
data class DiagRecord(
    val seq: Long, val wallMs: Long, val monoMs: Long, val kind: DiagKind, val name: String,
    val value: Double? = null, val unit: String? = null, val cls: DiagClass? = null, val source: DiagSource? = null,
    val text: String? = null, val attrs: Map<String, String> = emptyMap()
) {
    /** Bản ghi quan trọng: được phép dùng phần dung lượng dự trữ khi đã chạm giới hạn. */
    val critical: Boolean get() = kind == DiagKind.SESSION || kind == DiagKind.NOTE || kind == DiagKind.ERROR
}

/** Siêu dữ liệu phiên (dòng đầu tệp). [deviceKey] chỉ là mã băm ẩn danh, KHÔNG bao giờ là địa chỉ MAC. */
data class DiagMeta(
    val id: String, val startedWallMs: Long, val label: String, val appVersion: String, val deviceKey: String?,
    val sampleIntervalMs: Long, val verifiedOnVehicle: Boolean, val adapterId: String?,
    val format: String = DiagCodec.JOURNAL_FORMAT
)

enum class DiagSessionState(val title: String) {
    RECORDING("Đang ghi"), PAUSED("Tạm dừng"), STOPPED("Đã kết thúc"),
    /** Phiên đã bị đóng đột ngột và được app khôi phục: dữ liệu tới thời điểm cuối cùng ghi được vẫn còn. */
    INTERRUPTED("Bị gián đoạn (đã khôi phục)"),
    /** Tệp chưa có bản ghi kết thúc và chưa được khôi phục (chỉ thấy trước khi gọi recover). */
    UNFINISHED("Chưa kết thúc")
}

data class DiagSessionData(val meta: DiagMeta, val records: List<DiagRecord>, val corruptLines: Int, val tornTail: Boolean)

object DiagCodec {
    const val JOURNAL_FORMAT = "nvx-connect-diag-journal/1"
    private const val PREFIX = "D1,"

    private fun crc(s: String) = "%08x".format(CRC32().also { it.update(s.toByteArray(Charsets.UTF_8)) }.value)

    private fun num(d: Double): String { require(d.isFinite()) { "Giá trị không hữu hạn không được ghi" }; return d.toString() }

    fun encodeMeta(m: DiagMeta): String = wrap(buildString {
        append("{\"t\":\"meta\",\"format\":").append(MiniJson.quote(m.format)).append(",\"id\":").append(MiniJson.quote(m.id))
        append(",\"started\":").append(m.startedWallMs).append(",\"label\":").append(MiniJson.quote(m.label))
        append(",\"app\":").append(MiniJson.quote(m.appVersion)).append(",\"device\":").append(MiniJson.quoteOrNull(m.deviceKey))
        append(",\"interval\":").append(m.sampleIntervalMs).append(",\"verified_vehicle\":").append(m.verifiedOnVehicle)
        append(",\"adapter\":").append(MiniJson.quoteOrNull(m.adapterId)).append('}')
    })

    fun encode(r: DiagRecord): String = wrap(buildString {
        append("{\"t\":\"r\",\"seq\":").append(r.seq).append(",\"wall\":").append(r.wallMs).append(",\"mono\":").append(r.monoMs)
        append(",\"k\":").append(MiniJson.quote(r.kind.name)).append(",\"n\":").append(MiniJson.quote(r.name))
        if (r.value != null) append(",\"v\":").append(num(r.value))
        if (r.unit != null) append(",\"u\":").append(MiniJson.quote(r.unit))
        if (r.cls != null) append(",\"c\":").append(MiniJson.quote(r.cls.name))
        if (r.source != null) append(",\"s\":").append(MiniJson.quote(r.source.name))
        if (r.text != null) append(",\"x\":").append(MiniJson.quote(r.text))
        if (r.attrs.isNotEmpty()) { append(",\"a\":{"); r.attrs.entries.joinTo(this, ",") { MiniJson.quote(it.key) + ":" + MiniJson.quote(it.value) }; append('}') }
        append('}')
    })

    private fun wrap(json: String) = PREFIX + crc(json) + "," + json

    /** Kết quả giải mã một dòng. */
    sealed interface Line { data class Meta(val meta: DiagMeta) : Line; data class Rec(val rec: DiagRecord) : Line }

    /** null nếu dòng hỏng (sai CRC, JSON hỏng, thiếu trường, giá trị lạ). Không bao giờ ném lỗi. */
    fun decode(line: String): Line? {
        if (!line.startsWith(PREFIX)) return null
        val c = line.indexOf(',', PREFIX.length); if (c < 0) return null
        val json = line.substring(c + 1)
        if (crc(json) != line.substring(PREFIX.length, c)) return null
        return try {
            val o = MiniJson.parse(json).obj() ?: return null
            when (o["t"].text()) {
                "meta" -> Line.Meta(DiagMeta(
                    id = o["id"].text() ?: return null, startedWallMs = (o["started"] as? Number)?.toLong() ?: return null, label = o["label"].text() ?: "",
                    appVersion = o["app"].text() ?: "", deviceKey = o["device"].text(), sampleIntervalMs = (o["interval"] as? Number)?.toLong() ?: return null,
                    verifiedOnVehicle = o["verified_vehicle"] as? Boolean ?: false, adapterId = o["adapter"].text(), format = o["format"].text() ?: JOURNAL_FORMAT))
                "r" -> Line.Rec(DiagRecord(
                    seq = (o["seq"] as? Number)?.toLong() ?: return null, wallMs = (o["wall"] as? Number)?.toLong() ?: return null,
                    monoMs = (o["mono"] as? Number)?.toLong() ?: return null, kind = DiagKind.valueOf(o["k"].text() ?: return null), name = o["n"].text() ?: return null,
                    value = (o["v"] as? Number)?.toDouble(), unit = o["u"].text(), cls = o["c"].text()?.let { DiagClass.valueOf(it) },
                    source = o["s"].text()?.let { DiagSource.valueOf(it) }, text = o["x"].text(),
                    attrs = (o["a"].obj() ?: emptyMap()).mapValues { (it.value as? String) ?: it.value.toString() }))
                else -> null
            }
        } catch (_: Exception) { null }
    }
}
