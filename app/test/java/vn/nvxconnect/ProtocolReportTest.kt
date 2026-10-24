package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.auto.*
import vn.nvxconnect.bluetooth.GattCharInfo
import vn.nvxconnect.bluetooth.GattServiceInfo
import vn.nvxconnect.protocol.*
import java.util.Random

/**
 * Báo cáo giao thức: các gói dưới đây là DỮ LIỆU TỔNG HỢP chỉ để thử thuật toán, không phải gói của xe.
 * Điều cần chứng minh: phân tích tự động không bao giờ tự tạo ra VERIFIED và không gán ý nghĩa cho byte.
 */
class ProtocolReportTest {
    private val C1 = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    private val C2 = "6e400004-b5a3-f393-e0a9-e50e24dcca9e"
    private val svc = listOf(GattServiceInfo("u", listOf(GattCharInfo(C1, listOf("NOTIFY")))))

    private fun f(t: Long, vararg b: Int, c: String = C1) = RawFrame(t, c, ByteArray(b.size) { b[it].toByte() })
    private fun input(frames: List<RawFrame>, tags: List<CorrelationEngine.Tag> = emptyList(), reg: AdapterRegistry = AdapterRegistry(),
                      auth: Boolean = false, seg: Map<String, List<RawFrame>> = emptyMap()) = ReportInput(frames, tags, svc, reg, auth, seg)
    private fun levels(r: List<Finding>) = r.map { it.level }.toSet()

    @Test fun noFramesMeansUnknownAndNeverVerified() {
        val r = ProtocolReport.build(input(emptyList()))
        assertTrue(r.any { it.level == EvidenceLevel.UNKNOWN && it.title.contains("Chưa có gói") })
        assertFalse(EvidenceLevel.VERIFIED in levels(r))
        assertFalse(EvidenceLevel.OBSERVED in levels(r))
    }

    @Test fun authRequiredNamesWhatIsNeededAndForbidsBypass() {
        val r = ProtocolReport.build(input(emptyList(), auth = true))
        val f = r.first { it.title.contains("xác thực") }
        assertEquals(EvidenceLevel.ENCRYPTED_OR_AUTHENTICATED, f.level)
        assertTrue(f.detail!!.contains("tài liệu giao thức"))
        assertTrue(f.detail!!.contains("không vượt xác thực"))
    }

    @Test fun rich_looking_data_still_never_becomes_verified_without_a_registered_adapter() {
        // Gói có tiêu đề cố định, bộ đếm và checksum tổng khớp hoàn toàn: vẫn chỉ là HYPOTHESIS.
        val frames = (0 until 40).map { i ->
            val a = (i * 7) and 0xFF; val b = (i * 13 + 5) and 0xFF
            val sum = (0x55 + 0xAA + (i and 0xFF) + a + b) and 0xFF
            f(i * 100L, 0x55, 0xAA, i and 0xFF, a, b, sum)
        }
        val r = ProtocolReport.build(input(frames))
        assertFalse(EvidenceLevel.VERIFIED in levels(r))
        assertTrue(r.any { it.level == EvidenceLevel.HYPOTHESIS && it.title.contains("SUM8") })
        assertTrue(r.any { it.level == EvidenceLevel.HYPOTHESIS && it.title.contains("bộ đếm") })
        assertTrue(r.any { it.level == EvidenceLevel.OBSERVED && it.title.contains("byte đầu luôn cố định") })
        // Không có nhãn kiểu RPM/nhiệt độ/điện áp trong báo cáo.
        val text = ProtocolReport.render(r)
        assertFalse(text.contains("RPM", ignoreCase = false)); assertFalse(text.contains("nhiệt độ", ignoreCase = true))
    }

    @Test fun verifiedAppearsOnlyThroughAnEvidencedRegisteredAdapter() {
        val ok = ProtocolEvidence("t", "1", listOf("u"), "f", "u", null, listOf("01"), "xe-thu-nghiem", "pass", true)
        val reg = AdapterRegistry(); reg.register(Adapter(ok))
        val r = ProtocolReport.build(input(listOf(f(0, 1, 2)), reg = reg))
        assertTrue(r.any { it.level == EvidenceLevel.VERIFIED && it.title.contains("t") })
        assertFalse(r.any { it.level == EvidenceLevel.UNKNOWN && it.title.startsWith("Giao thức xe: chưa") })
        // adapter thiếu bằng chứng không đăng ký được => không bao giờ ra VERIFIED
        val bad = ProtocolEvidence("t", "1", listOf("u"), "f", "u", null, listOf("01"), null, null, true)
        assertThrows(IllegalArgumentException::class.java) { AdapterRegistry().register(Adapter(bad)) }
    }

    @Test fun counterAndLengthFieldAreHypothesesOnly() {
        val frames = (0 until 12).map { i -> f(i * 50L, 6, i, 0x10, 0x20, 0x30, 0x40) } // byte0 = độ dài 6, byte1 tăng dần
        val r = ProtocolReport.build(input(frames))
        assertTrue(r.any { it.level == EvidenceLevel.HYPOTHESIS && it.title.contains("độ dài gói") })
        assertTrue(r.any { it.level == EvidenceLevel.HYPOTHESIS && it.title.contains("byte 1 có thể là bộ đếm") })
    }

    @Test fun identicalRepeatedFramesAreObserved() {
        val frames = (0 until 10).map { f(it * 100L, 1, 2, 3, 4) }
        val r = ProtocolReport.build(input(frames))
        assertTrue(r.any { it.level == EvidenceLevel.OBSERVED && it.title.contains("giống hệt lặp 10/10") })
    }

    @Test fun highEntropyIsReportedAsPossibleEncryptionAndNamesWhatIsNeeded() {
        val rnd = Random(42)
        val frames = (0 until 50).map { i -> RawFrame(i * 100L, C1, ByteArray(32).also { rnd.nextBytes(it) }) }
        val r = ProtocolReport.build(input(frames))
        val e = r.first { it.title.contains("entropy") }
        assertEquals(EvidenceLevel.ENCRYPTED_OR_AUTHENTICATED, e.level)
        assertTrue(e.detail!!.contains("tài liệu giao thức"))
        // Chỉ là DẤU HIỆU (entropy cao cũng là dữ liệu nén) và tuyệt đối không thành VERIFIED.
        assertTrue(e.title.contains("Dấu hiệu")); assertTrue(e.detail!!.contains("nén"))
        assertFalse(EvidenceLevel.VERIFIED in levels(r))
    }

    @Test fun lowEntropyStructuredDataIsNotFlaggedAsEncrypted() {
        val frames = (0 until 50).map { i -> RawFrame(i * 100L, C1, ByteArray(32) { (i % 3).toByte() }) }
        assertFalse(ProtocolReport.build(input(frames)).any { it.title.contains("entropy") })
    }

    @Test fun correlationWithTaggedActionIsAHypothesisWithWarning() {
        val frames = (0 until 40).map { i -> val t = i * 500L; val on = (t / 5000) % 2 == 1L; f(t, 0x55, if (on) 1 else 0, 0x00, 0x00) }
        val tags = listOf(4750L, 9750L, 14750L).map { CorrelationEngine.Tag("Đèn pha", it) }
        val r = ProtocolReport.build(input(frames, tags))
        val h = r.first { it.level == EvidenceLevel.HYPOTHESIS && it.title.contains("Đèn pha") }
        assertTrue(h.detail!!.contains("Trùng hợp không phải bằng chứng"))
        assertFalse(EvidenceLevel.VERIFIED in levels(r))
    }

    @Test fun unexplainedVaryingBytesAreUnknownNotLabelled() {
        val rnd = Random(7)
        val frames = (0 until 30).map { i -> f(i * 100L, 0x55, rnd.nextInt(256), rnd.nextInt(256), 9) }
        val r = ProtocolReport.build(input(frames))
        val u = r.first { it.level == EvidenceLevel.UNKNOWN && it.title.contains("chưa giải thích") }
        assertTrue(u.detail!!.contains("Chưa gán ý nghĩa"))
    }

    @Test fun segmentComparisonReportsDifferencesOnly() {
        val a = (0 until 5).map { f(it * 100L, 0x55, 0x00, it) }
        val b = (0 until 5).map { f(1000 + it * 100L, 0x55, 0x01, it) }
        val r = ProtocolReport.build(input(a + b, seg = mapOf("Đèn tắt" to a, "Đèn bật" to b)))
        val d = r.first { it.title.contains("khác biệt giữa các phiên") }
        assertEquals(EvidenceLevel.OBSERVED, d.level)
        assertTrue(d.detail!!.contains("byte 1"))
        assertTrue(d.detail!!.contains("không nói ý nghĩa"))
    }

    @Test fun repeatedChannelSequenceIsObservedAndNotCalledRequestResponse() {
        val frames = (0 until 12).map { i -> f(i * 100L, 1, i, c = if (i % 2 == 0) C1 else C2) }
        val s = ProtocolReport.build(input(frames)).first { it.title.startsWith("Chuỗi kênh") }
        assertEquals(EvidenceLevel.OBSERVED, s.level)
        assertTrue(s.detail!!.contains("không gửi lệnh"))
    }

    @Test fun renderUsesTheFiveLabels() {
        val t = ProtocolReport.render(listOf(Finding(EvidenceLevel.OBSERVED, "a"), Finding(EvidenceLevel.HYPOTHESIS, "b"),
            Finding(EvidenceLevel.VERIFIED, "c"), Finding(EvidenceLevel.UNKNOWN, "d"), Finding(EvidenceLevel.ENCRYPTED_OR_AUTHENTICATED, "e")))
        listOf("[OBSERVED]", "[HYPOTHESIS]", "[VERIFIED]", "[UNKNOWN]", "[ENCRYPTED_OR_AUTHENTICATED]").forEach { assertTrue(t.contains(it)) }
    }

    @Test fun gattInspectorCountsAndFlagsWritableWithoutWriting() {
        val s = listOf(GattServiceInfo("s", listOf(GattCharInfo("c1", listOf("READ", "NOTIFY")), GattCharInfo("c2", listOf("WRITE", "WRITE_NO_RESP")), GattCharInfo("c3", listOf("INDICATE")))))
        val x = GattInspector.summarize(s)
        assertEquals(1, x.services); assertEquals(3, x.characteristics); assertEquals(2, x.subscribable); assertEquals(1, x.readable); assertEquals(1, x.writable)
        assertTrue(x.render().contains("KHÔNG ghi"))
    }

    private class Adapter(override val evidence: ProtocolEvidence) : VehicleProtocolAdapter {
        override fun matches(services: List<GattServiceInfo>) = services.any { it.uuid == "u" }
        override fun decode(frame: RawFrame) = listOf<DecodedItem>(DecodedItem.Unknown(frame))
    }
}
