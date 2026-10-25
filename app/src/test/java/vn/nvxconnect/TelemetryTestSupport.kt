package vn.nvxconnect

import vn.nvxconnect.telemetry.*

/**
 * Dữ liệu cho test telemetry.
 *  - REAL_*: khung bắt thật từ xe (2026-10-23, đã che) — chép từ YccFrameTest. Chỉ chứng minh ĐỊNH DẠNG.
 *  - synth*: khung TỔNG HỢP dựng trong test để kiểm logic. KHÔNG phải bằng chứng giao thức thật.
 */
object TelemetryTestSupport {
    const val NUS_NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    const val NUS_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    val REAL_IDLE = hex("6B0500012100000000283AB22300000010000079520000CB808000810000000000008080808000480400001617011D01FF011701FF011901FF0047")
    val REAL_IDLE_NEXT = hex("6B0500012100000000283AB22300000010000079520000CB808000810000000000008080808000480400001617011D01FF011701FF011901FF0146")
    val REAL_RUNNING = hex("6B05000121069C00062335AA2300000010000074586100CB808000810000000150008080808000480400001617011D01FF011701FF011901FF609E")
    val REAL_PARAMS = hex("6C18022601FE022E010002A303000800059001FE020903020000020A0100021503FEFEFE021603FEFEFE023A0100023E020000024501FE02AA070000000000020802AB030A081A026008FEFEFEFEFEFEFEFE02680400000000055501FE040401FE02B102FEFE02BA05000000080003BA080011460E43A00000027A02FEFE038302FEFE02BC03FEFEFE020401FE00CF")
    val REAL_BLOCK = hex("5901FF6046000012970000127300000AC30000001A00000D49000039A4000001EC00000006000000000000050900005E6500002DA90000034600000000000000000000000000000000000000D6")
    val REAL_INFO = hex("6A09F190110000000000000000000000000000000000F19404129E0040F1970442575031F1A1040001362FF1A20200DBF421020000F44D020000F7010400004AF7F70204000000430099")
    val REAL_HELLO = hex("5A017F0101010023")

    /** SYNTHETIC: khung 0x6B dài 59 byte, rpm u16be@5, bộ đếm @57, SUM8 toàn gói ≡ 0. */
    fun synth6B(rpm: Int, counter: Int, battRaw: Int = 0xB2, badChecksum: Boolean = false): ByteArray {
        val b = ByteArray(59); b[0] = 0x6B; b[1] = 0x05; b[3] = 0x01; b[4] = 0x21
        b[5] = (rpm shr 8).toByte(); b[6] = rpm.toByte(); b[11] = battRaw.toByte(); b[57] = counter.toByte()
        var s = 0; for (i in 0 until 58) s += b[i].toInt() and 0xFF
        b[58] = ((-s) and 0xFF).toByte(); if (badChecksum) b[58] = (b[58] + 1).toByte()
        return b
    }

    /** SYNTHETIC: một phiên với các mức rpm cho trước, 10 mẫu mỗi mức, cách nhau 100 ms. */
    fun synthSession(rec: RawFrameRecorder, addr: String, levels: List<Int>, startMs: Long, perLevel: Int = 10): List<Pair<Long, Int>> {
        val out = ArrayList<Pair<Long, Int>>(); var t = startMs; var c = 0
        for (lv in levels) repeat(perLevel) {
            rec.record(addr, PayloadSource.NOTIFICATION, NUS_SERVICE, NUS_NOTIFY, synth6B(lv, c++ and 0xFF), t)
            out += t to lv; t += 100
        }
        return out
    }

    fun newRecorder(maxRecords: Int = 20_000, pinned: Int = 200) = RawFrameRecorder(maxRecords, pinned, anonymize = { "dev-" + it.takeLast(2) }).also { it.start("test") }

    fun refs(series: List<Pair<Long, Int>>, field: String = FieldIds.RPM, unit: String = "rpm", grade: EvidenceGrade = EvidenceGrade.INDEPENDENT_MEASUREMENT,
             every: Int = 3, noise: (Int) -> Double = { 0.0 }, lagMs: Long = 0, source: String = "máy đo vòng tua rời (test)"): List<ReferenceSample> =
        series.filterIndexed { i, _ -> i % every == 0 }.mapIndexed { i, (t, v) -> ReferenceSample(t + lagMs, field, v + noise(i), unit, source, grade, "synthetic", null) }
}
