package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.TelemetryTestSupport.NUS_NOTIFY
import vn.nvxconnect.TelemetryTestSupport.NUS_SERVICE
import vn.nvxconnect.TelemetryTestSupport.newRecorder
import vn.nvxconnect.protocol.*
import vn.nvxconnect.telemetry.*

/**
 * Mọi dữ liệu ở đây là TỔNG HỢP (synth*) hoặc khung thật chỉ dùng kiểm cấu trúc. Kết quả VERIFIED trong các test này
 * chứng minh LOGIC của engine, không chứng minh bất kỳ công thức Yamaha nào.
 */
class TelemetryVerificationTest {
    private val addr = "AA:BB:CC:DD:EE:01"
    private val rpm = FieldCatalog.spec(FieldIds.RPM)!!
    private val h = BuiltInHypotheses.RPM
    private val crit = CriteriaRegistry.defaultFor(FieldIds.RPM)!!
    private val levels = listOf(800, 1500, 2300, 3100, 3900)

    private class Run(val records: List<PayloadRecord>, val series: List<Pair<Long, Int>>)
    private fun run(sessions: Int = 2, lv: List<Int> = levels, badChecksum: Boolean = false): Run {
        val r = newRecorder(); val all = ArrayList<Pair<Long, Int>>()
        for (s in 1..sessions) {
            if (s > 1) r.start("phiên $s")
            all += TelemetryTestSupport.synthSession(r, addr, lv, s * 100_000L, 10)
        }
        return Run(r.snapshot(), all)
    }
    private fun eval(run: Run, refs: List<ReferenceSample>?, crit: LockedCriteria? = this.crit, importedAt: Long = 10, now: Long? = null): FieldVerdict {
        val (samples, proofs) = TelemetryValidationEngine.samplesFor(h, run.records)
        return TelemetryValidationEngine.evaluate(rpm, h, crit, samples, proofs, refs?.let { ReferenceDataset(it, importedAt, emptyList()) }, now ?: (samples.maxOfOrNull { it.timeMs } ?: 0))
    }

    @Test fun noReferenceNeverBeyondHypothesis() {
        val v = eval(run(), null)
        assertEquals(VerificationStatus.DECODED_HYPOTHESIS, v.status); assertEquals(EvidenceGrade.SAMPLE_INFERENCE, v.grade)
        assertTrue(v.missingEvidence.any { it.contains("tham chiếu") })
        assertTrue(v.sampleCount > 0)      // checksum hợp lệ + parser chạy được KHÔNG đủ để VERIFIED
    }

    @Test fun independentReferenceOverTwoSessionsGivesVerified() {
        val r = run(); val v = eval(r, TelemetryTestSupport.refs(r.series, noise = { if (it % 2 == 0) 8.0 else -8.0 }))
        assertEquals(v.reason, VerificationStatus.VERIFIED, v.status); assertEquals(EvidenceGrade.INDEPENDENT_MEASUREMENT, v.grade)
        assertTrue(v.unmet.isEmpty()); assertTrue(v.metrics!!.pearson > 0.999); assertTrue(v.metrics!!.mae in 7.0..9.0)
    }

    @Test fun vehicleClusterReferenceCapsAtCorrelated() {
        val r = run(); val v = eval(r, TelemetryTestSupport.refs(r.series, grade = EvidenceGrade.VEHICLE_CLUSTER))
        assertEquals(VerificationStatus.CORRELATED, v.status); assertEquals(EvidenceGrade.VEHICLE_CLUSTER, v.grade)
        assertTrue(v.unmet.any { it.contains("VERIFIED cần") })
    }

    @Test fun inferenceOnlyReferenceStaysHypothesis() {
        val r = run(); val v = eval(r, TelemetryTestSupport.refs(r.series, grade = EvidenceGrade.SAMPLE_INFERENCE))
        assertEquals(VerificationStatus.DECODED_HYPOTHESIS, v.status)
    }

    @Test fun singleSessionCannotBeVerified() {
        val r = run(sessions = 1); val v = eval(r, TelemetryTestSupport.refs(r.series))
        assertEquals(VerificationStatus.CORRELATED, v.status); assertTrue(v.unmet.any { it.contains("phiên") })
    }

    @Test fun narrowCoverageOrFewLevelsCannotBeVerified() {
        val r = run(lv = listOf(1000, 1200, 1400)); val v = eval(r, TelemetryTestSupport.refs(r.series))
        assertEquals(VerificationStatus.CORRELATED, v.status)
        assertTrue(v.unmet.any { it.contains("Độ phủ tham chiếu") }); assertTrue(v.unmet.any { it.contains("Số mức") })
    }

    @Test fun biasBeyondToleranceBlocksVerified() {
        val r = run(); val v = eval(r, TelemetryTestSupport.refs(r.series, noise = { 120.0 }))
        assertEquals(VerificationStatus.CORRELATED, v.status)
        assertTrue(v.unmet.any { it.contains("Sai số tuyệt đối TB") }); assertTrue(v.unmet.any { it.contains("Sai số lớn nhất") })
        assertEquals(-120.0, v.metrics!!.bias, 1.0)   // sai số = giải mã − tham chiếu
    }

    @Test fun absoluteAndRelativeErrorAreMeasured() {
        val r = run(); val m = eval(r, TelemetryTestSupport.refs(r.series, noise = { -50.0 })).metrics!!
        assertEquals(50.0, m.mae, 0.5); assertEquals(50.0, m.maxAbs, 0.5); assertEquals(50.0, m.bias, 0.5)
        assertNotNull(m.meanRelPct); assertTrue(m.meanRelPct!! in 1.0..7.0)
    }

    @Test fun lowCorrelationIsNotEvenCorrelated() {
        val r = run(); val shuffled = r.series.mapIndexed { i, p -> p.first to levels[(i * 7 + 3) % levels.size] }
        val v = eval(r, TelemetryTestSupport.refs(shuffled))
        assertEquals(VerificationStatus.DECODED_HYPOTHESIS, v.status)
    }

    @Test fun referenceDelayIsEstimatedAndTolerated() {
        val r = run(); val v = eval(r, TelemetryTestSupport.refs(r.series, lagMs = 400))
        assertEquals(v.reason, VerificationStatus.VERIFIED, v.status); assertEquals(-400L, v.metrics!!.lagMs)
    }

    @Test fun criteriaLockedAfterReferenceImportBlocksVerified() {
        val r = run(); val late = CriteriaRegistry.lock(crit.criteria, nowMs = 5_000)
        val v = eval(r, TelemetryTestSupport.refs(r.series), crit = late, importedAt = 1_000)
        assertEquals(VerificationStatus.CORRELATED, v.status); assertTrue(v.unmet.any { it.contains("SAU khi nhập") })
        val early = CriteriaRegistry.lock(crit.criteria, nowMs = 500)
        assertEquals(VerificationStatus.VERIFIED, eval(r, TelemetryTestSupport.refs(r.series), crit = early, importedAt = 1_000).status)
    }

    @Test fun tamperedCriteriaAreDetected() {
        val loose = crit.criteria.copy(maxMeanAbsError = 9999.0, maxAbsError = 9999.0, maxErrStd = 9999.0)
        val forged = LockedCriteria(loose, 0, CriteriaOrigin.BUILT_IN, crit.fingerprint)   // vân tay cũ, nội dung đã nới
        val r = run(); val v = eval(r, TelemetryTestSupport.refs(r.series, noise = { 300.0 }), crit = forged)
        assertNotEquals(VerificationStatus.VERIFIED, v.status); assertTrue(v.unmet.any { it.contains("vân tay") })
    }

    @Test fun invalidChecksumFramesAreExcluded() {
        val r = newRecorder()
        for (c in 0 until 30) r.record(addr, PayloadSource.NOTIFICATION, NUS_SERVICE, NUS_NOTIFY, TelemetryTestSupport.synth6B(1000 + c, c, badChecksum = true), c * 100L)
        val (samples, proofs) = TelemetryValidationEngine.samplesFor(h, r.snapshot())
        assertTrue(samples.isEmpty()); assertEquals(30, proofs.framesOfType); assertEquals(0, proofs.checksumPass)
    }

    @Test fun wrongLengthFramesAreExcluded() {
        val short = TelemetryTestSupport.synth6B(1000, 0).copyOf(58)
        val r = newRecorder(); r.record(addr, PayloadSource.NOTIFICATION, NUS_SERVICE, NUS_NOTIFY, short, 1)
        val (samples, proofs) = TelemetryValidationEngine.samplesFor(h, r.snapshot())
        assertTrue(samples.isEmpty()); assertEquals(1, proofs.framesOfType); assertEquals(0, proofs.lengthOk)
    }

    @Test fun staleDataOverridesStatusButKeepsBase() {
        val r = run(); val refs = TelemetryTestSupport.refs(r.series)
        val last = TelemetryValidationEngine.samplesFor(h, r.records).first.maxOf { it.timeMs }
        val v = eval(r, refs, now = last + 60_000)
        assertEquals(VerificationStatus.STALE, v.status); assertEquals(VerificationStatus.VERIFIED, v.baseStatus)
        assertEquals(VerificationStatus.VERIFIED, eval(r, refs, now = last + 1_000).status)
    }

    @Test fun referenceInWrongUnitIsRejectedNotConverted() {
        val r = run(); val refs = TelemetryTestSupport.refs(r.series, unit = "krpm")
        val v = eval(r, refs)
        assertEquals(VerificationStatus.DECODED_HYPOTHESIS, v.status); assertTrue(v.missingEvidence.any { it.contains("đã bị loại") })
    }

    @Test fun referenceOfAnotherFieldIsIgnored() {
        val r = run(); val v = eval(r, TelemetryTestSupport.refs(r.series, field = FieldIds.BATTERY_V))
        assertEquals(VerificationStatus.DECODED_HYPOTHESIS, v.status)
    }

    @Test fun verifiedCannotBeConstructedWithoutEvidence() {
        try { FieldVerdict(rpm, VerificationStatus.VERIFIED, VerificationStatus.VERIFIED, EvidenceGrade.INDEPENDENT_MEASUREMENT, h, 0, null, null, emptyList(), emptyList(), null, "x"); fail("phải ném lỗi") }
        catch (_: IllegalArgumentException) { }
        try { val r = run(); val ok = eval(r, TelemetryTestSupport.refs(r.series)); FieldVerdict(rpm, VerificationStatus.VERIFIED, VerificationStatus.VERIFIED, ok.grade, h, 1, null, ok.metrics, listOf("còn tiêu chí chưa đạt"), emptyList(), null, "x"); fail() }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun unavailableAndRawOnlyStatuses() {
        val dtc = FieldCatalog.spec(FieldIds.DTC)!!
        val u = TelemetryValidationEngine.evaluate(dtc, null, CriteriaRegistry.defaultFor(FieldIds.DTC), emptyList(), null, null, 0, inaccessibleReason = "Không có phương thức chẩn đoán được hỗ trợ")
        assertEquals(VerificationStatus.UNAVAILABLE, u.status); assertEquals(EvidenceGrade.INACCESSIBLE, u.grade)
        val temp = FieldCatalog.spec(FieldIds.ENGINE_TEMP)!!
        val noData = TelemetryValidationEngine.evaluate(temp, null, CriteriaRegistry.defaultFor(FieldIds.ENGINE_TEMP), emptyList(), null, null, 0)
        assertEquals(VerificationStatus.UNAVAILABLE, noData.status); assertEquals(EvidenceGrade.NO_DATA, noData.grade)
        val raw = TelemetryValidationEngine.evaluate(temp, null, CriteriaRegistry.defaultFor(FieldIds.ENGINE_TEMP), emptyList(), null, null, 0, rawSeen = true)
        assertEquals(VerificationStatus.RAW_UNVERIFIED, raw.status)
    }

    // ---- giải mã: thứ tự byte, có dấu/không dấu ----
    private fun hyp(order: ByteOrderKind, signed: Boolean, n: Int = 2, scale: Double = 1.0) = DecoderHypothesis("t", "x", null, 0x6B, 4, 1, n, order, signed, scale, 0.0, "u", null, "test")
    @Test fun byteOrderAndSignedness() {
        val b = byteArrayOf(0x6B, 0xFF.toByte(), 0xFE.toByte(), 0x00)
        assertEquals(65534.0, hyp(ByteOrderKind.BIG, false).raw(b)!!, 0.0)
        assertEquals(-2.0, hyp(ByteOrderKind.BIG, true).raw(b)!!, 0.0)
        val c = byteArrayOf(0x6B, 0x01, 0x02, 0x00)
        assertEquals(0x0102.toDouble(), hyp(ByteOrderKind.BIG, false).raw(c)!!, 0.0)
        assertEquals(0x0201.toDouble(), hyp(ByteOrderKind.LITTLE, false).raw(c)!!, 0.0)
        assertEquals(-1.0, hyp(ByteOrderKind.BIG, true, n = 1).raw(byteArrayOf(0x6B, 0xFF.toByte(), 0, 0))!!, 0.0)
        assertNull(hyp(ByteOrderKind.BIG, false).raw(byteArrayOf(0x6B, 1, 2)))            // sai độ dài
        assertNull(hyp(ByteOrderKind.BIG, false).raw(byteArrayOf(0x6C, 1, 2, 3)))         // sai loại khung
    }

    @Test fun builtInHypothesesAgreeWithExistingCandidateDecoderOnRealFrames() {
        val f = (YccFrames.parse(TelemetryTestSupport.REAL_RUNNING) as YccParse.Valid).frame
        val cands = YccCandidates.decode(f, 0).associateBy { it.id }
        assertEquals(cands.getValue(YccCandidates.RPM_ID).value, BuiltInHypotheses.RPM.value(TelemetryTestSupport.REAL_RUNNING)!!, 1e-9)
        assertEquals(cands.getValue(YccCandidates.BATT_ID).value, BuiltInHypotheses.BATTERY.value(TelemetryTestSupport.REAL_RUNNING)!!, 1e-9)
        assertEquals(1692.0, BuiltInHypotheses.RPM.value(TelemetryTestSupport.REAL_RUNNING)!!, 0.0)
        assertEquals(0.0, BuiltInHypotheses.RPM.value(TelemetryTestSupport.REAL_IDLE)!!, 0.0)
    }

    // ---- nhập tham chiếu ----
    @Test fun referenceCsvAcceptsGoodRowsAndRejectsUntrustedOnes() {
        val csv = "time_ms,field,value,unit,source,grade,method,session\n" +
            "1000,rpm,1500,rpm,máy đo rời,independent,đo kẹp,s1\n" +
            "2000,rpm,\"1600,5\",rpm,máy đo rời,independent,,\n" +
            "3000,rpm,1700,rpm,,independent,,\n" +                // thiếu nguồn
            "4000,rpm,1800,rpm,máy đo,tuyet_doi_dung,,\n" +       // cấp lạ không được thành cấp cao
            "5000,rpm,abc,rpm,máy đo,independent,,\n" +
            "6000,khong_co,1,u,máy đo,independent,,\n" +
            "2026-10-24T07:11:31.000Z,battery_voltage,12.6,V,đồng hồ vạn năng,independent,,\n"
        val d = ReferenceCsv.parse(csv, 9)
        assertEquals(3, d.samples.size); assertEquals(4, d.warnings.size)
        assertEquals(1600.5, d.samples[1].value, 0.0); assertEquals(EvidenceGrade.INDEPENDENT_MEASUREMENT, d.samples[0].grade)
        assertTrue(d.samples.last().timeMs > 1_700_000_000_000L)
        assertTrue(d.warnings.any { it.contains("thiếu nguồn") }); assertTrue(d.warnings.any { it.contains("cấp bằng chứng") })
    }

    @Test fun referenceCsvMissingColumnsOrEmpty() {
        assertTrue(ReferenceCsv.parse("a,b\n1,2", 0).warnings.single().contains("Thiếu cột"))
        assertTrue(ReferenceCsv.parse("", 0).samples.isEmpty())
    }

    // ---- tiêu chí đặt trước ----
    @Test fun builtInCriteriaArePinnedAgainstPostHocTuning() {
        val c = CriteriaRegistry.defaultFor(FieldIds.RPM)!!.criteria
        assertEquals(30, c.minPairs); assertEquals(2, c.minSessions); assertEquals(1500.0, c.minRefSpan, 0.0); assertEquals(4, c.minLevels)
        assertEquals(0.997, c.minPearson, 0.0); assertEquals(50.0, c.maxMeanAbsError, 0.0); assertEquals(100.0, c.maxAbsError, 0.0)
        assertEquals(setOf(EvidenceGrade.OFFICIAL_SOURCE, EvidenceGrade.INDEPENDENT_MEASUREMENT), c.allowedGrades)
        assertNull(CriteriaRegistry.defaultFor(FieldIds.DTC))
        assertTrue(FieldCatalog.ALL.map { it.id }.filter { it != FieldIds.DTC }.all { CriteriaRegistry.defaultFor(it)?.intact() == true })
    }

    @Test fun userCriteriaFingerprintChangesWhenAnyThresholdChanges() {
        val a = CriteriaRegistry.fingerprintOf(crit.criteria); val b = CriteriaRegistry.fingerprintOf(crit.criteria.copy(maxAbsError = 101.0))
        assertNotEquals(a, b)
    }
}
