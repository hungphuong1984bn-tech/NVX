package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.protocol.*
import java.util.Random

class YccCalibrationTest {
    private val rpm = CalSpecs.RPM
    private fun pts(vararg p: Pair<Double, Double>) = p.mapIndexed { i, (r, f) -> CalPoint(r, f, i.toLong()) }

    @Test fun exactLinearFitRecoversScaleAndOffset() {
        val f = Calibration.fit(rpm.id, pts(1000.0 to 1020.0, 2000.0 to 2040.0, 3000.0 to 3060.0, 4000.0 to 4080.0))!!
        assertEquals(1.02, f.scale, 1e-9); assertEquals(0.0, f.offset, 1e-6); assertEquals(1.0, f.r2, 1e-9)
        assertEquals(CalQuality.GOOD, Calibration.quality(f, rpm))
    }
    @Test fun tooFewPointsOrNarrowSpanIsInsufficient() {
        assertNull(Calibration.fit(rpm.id, pts(1000.0 to 1000.0)))
        val few = Calibration.fit(rpm.id, pts(1000.0 to 1000.0, 3000.0 to 3000.0, 3500.0 to 3500.0))!!
        assertEquals(CalQuality.INSUFFICIENT, Calibration.quality(few, rpm))
        val narrow = Calibration.fit(rpm.id, pts(1500.0 to 1500.0, 1600.0 to 1600.0, 1700.0 to 1700.0, 1800.0 to 1800.0))!!
        assertEquals(CalQuality.INSUFFICIENT, Calibration.quality(narrow, rpm))
    }
    @Test fun implausibleScaleIsRejectedEvenIfPerfectlyLinear() {
        val f = Calibration.fit(rpm.id, pts(1000.0 to 2000.0, 2000.0 to 4000.0, 3000.0 to 6000.0, 4000.0 to 8000.0))!!
        assertEquals(CalQuality.POOR, Calibration.quality(f, rpm)) // hệ số 2,0 ngoài 0,8–1,25: nghi nhập nhầm đơn vị
    }
    @Test fun noisyDataWithinToleranceStillGood() {
        val r = Random(3); val p = (0 until 8).map { CalPoint(800.0 + it * 500, (800.0 + it * 500) * 1.01 + r.nextGaussian() * 12, it.toLong()) }
        val f = Calibration.fit(rpm.id, p)!!
        assertEquals(CalQuality.GOOD, Calibration.quality(f, rpm)); assertTrue(f.rmse < 30); assertNotNull(f.looRmse)
    }
    @Test fun gross_typo_is_flagged_as_suspect_and_degrades_quality() {
        val p = pts(1000.0 to 1000.0, 2000.0 to 2000.0, 3000.0 to 3000.0, 4000.0 to 4000.0, 3500.0 to 5300.0) // 1 điểm gõ sai
        assertEquals(listOf(4), Calibration.suspects(p, rpm))
        assertNotEquals(CalQuality.GOOD, Calibration.quality(Calibration.fit(rpm.id, p), rpm))
    }
    @Test fun steadyRawRequiresStabilityAndEnoughSamples() {
        assertNull(Calibration.steadyRaw(listOf(1600.0, 1610.0), 0.06))
        assertEquals(1600.0, Calibration.steadyRaw(List(20) { 1600.0 + (it % 3) * 10 }, 0.06)!!, 15.0)
        assertNull(Calibration.steadyRaw(List(20) { 1500.0 + it * 100 }, 0.06)) // đang tăng ga
    }
    @Test fun validationNeedsGoodFitAndResetsWhenFitChanges() {
        val s = CalSession(rpm)
        assertNull(s.validate(2000.0, 2000.0))
        listOf(1000.0, 2000.0, 3000.0, 4000.0).forEachIndexed { i, v -> s.addPoint(CalPoint(v, v, i.toLong())) }
        assertEquals(CalQuality.GOOD, s.quality)
        assertEquals(0.0, s.validate(2500.0, 2500.0)!!, 1e-6); s.validate(3500.0, 3510.0); s.validate(1500.0, 1495.0)
        assertTrue(s.promotionReady)
        s.addPoint(CalPoint(5000.0, 5000.0, 9)); assertFalse(s.promotionReady) // đổi hệ số: kiểm chứng cũ mất hiệu lực
    }
    @Test fun badValidationBlocksPromotion() {
        val s = CalSession(rpm); listOf(1000.0, 2000.0, 3000.0, 4000.0).forEachIndexed { i, v -> s.addPoint(CalPoint(v, v, i.toLong())) }
        s.validate(2000.0, 2000.0); s.validate(2500.0, 2500.0); s.validate(3000.0, 3400.0)
        assertFalse(s.promotionReady)
    }
    @Test fun filterRejectsImpossibleAndSpikes() {
        val f = YccSignalFilter(0.0, 12000.0, 4000.0)
        assertNull(f.push(1600.0)); assertNull(f.push(1610.0))
        assertEquals(1610.0, f.push(1620.0)!!, 0.0)
        assertNull(f.push(60000.0)); assertNull(f.push(Double.NaN))
        assertNull(f.push(9000.0))       // nhảy 7400 rpm trong 1 mẫu: bất khả thi
        assertEquals(1620.0, f.push(1630.0)!!, 20.0)
        assertEquals(3L, f.rejected)
    }
    @Test fun filterResetAfterDisconnect() {
        val f = YccSignalFilter(0.0, 12000.0, 4000.0); repeat(5) { f.push(1600.0) }; f.reset()
        assertNull(f.push(8000.0)) // không so với giá trị trước khi mất kết nối
    }
    @Test fun codecRoundTripAndCorruptionTolerance() {
        val m = mapOf(YccCandidates.RPM_ID to pts(1000.5 to 1001.0, 2000.0 to 2004.0))
        assertEquals(m, CalCodec.decode(CalCodec.encode(m)))
        assertTrue(CalCodec.decode("rác\nycc.rpm.u16be@5=x:y:z\nunknown=1:2:3").isEmpty())
        assertTrue(CalCodec.decode(null).isEmpty())
    }

    // ---- bộ điều phối end-to-end với gói thật dựng từ khung 0x6B ----
    private class MemStore : CalibrationStore { var m: Map<String, List<CalPoint>> = emptyMap(); override fun load() = m; override fun save(points: Map<String, List<CalPoint>>) { m = points } }
    private fun statusFrame(rpmRaw: Int, batt: Int, counter: Int): YccFrame {
        val b = ByteArray(59); b[0] = 0x6B; b[1] = 5; b[3] = 1; b[4] = 0x21
        b[5] = (rpmRaw shr 8).toByte(); b[6] = rpmRaw.toByte(); b[11] = batt.toByte(); b[57] = counter.toByte()
        var s = 0; for (i in 0 until 58) s += b[i].toInt() and 0xFF; b[58] = (-s).toByte()
        return (YccFrames.parse(b) as YccParse.Valid).frame
    }
    private fun feed(c: YccCalibrationController, rpmRaw: Int, batt: Int, t0: Long, n: Int = 30) {
        for (k in 0 until n) c.process(YccCandidates.decode(statusFrame(rpmRaw + (k % 3) * 5, batt, k), t0 + k * 80L))
    }
    @Test fun controllerEndToEndCalibratesAndAppliesFit() {
        val store = MemStore(); val c = YccCalibrationController(store)
        var t = 10_000L
        // đồng hồ xe hiển thị = 1,03 × thô
        for (raw in listOf(1000, 2000, 3000, 4000, 5000)) {
            feed(c, raw, 190, t); val r = c.record(YccCandidates.RPM_ID, raw * 1.03, false, t + 30 * 80L)
            assertTrue(r.toString(), r is YccCalibrationController.Result.Ok); t += 10_000
        }
        val st = c.status().getValue(YccCandidates.RPM_ID)
        assertEquals(CalQuality.GOOD, st.quality); assertEquals(1.03, st.fit!!.scale, 0.005)
        val out = c.process(YccCandidates.decode(statusFrame(3000, 190, 1), t)) + c.process(YccCandidates.decode(statusFrame(3000, 190, 2), t + 80)) +
            c.process(YccCandidates.decode(statusFrame(3000, 190, 3), t + 160))
        val v = out.last { it.param == VehicleParam.RPM }
        assertTrue(v.calibrated); assertEquals(3090.0, v.value, 30.0); assertTrue(v.label.contains("chưa xác minh"))
        assertEquals(5, store.m.getValue(YccCandidates.RPM_ID).size)                 // đã lưu
        assertEquals(CalQuality.GOOD, YccCalibrationController(store).status().getValue(YccCandidates.RPM_ID).quality) // nạp lại sau khởi động
    }
    @Test fun controllerRefusesUnsteadyAndNoDataAndZeroRpm() {
        val c = YccCalibrationController(null)
        assertTrue(c.record(YccCandidates.RPM_ID, 1600.0, false, 1000) is YccCalibrationController.Result.Refused)
        for (k in 0 until 30) c.process(YccCandidates.decode(statusFrame(1000 + k * 100, 190, k), k * 80L))
        assertTrue(c.record(YccCandidates.RPM_ID, 2000.0, false, 30 * 80L) is YccCalibrationController.Result.Refused)
        val z = YccCalibrationController(null); for (k in 0 until 30) z.process(YccCandidates.decode(statusFrame(0, 190, k), k * 80L))
        assertTrue(z.record(YccCandidates.RPM_ID, 0.0, false, 30 * 80L) is YccCalibrationController.Result.Refused)
    }
    @Test fun uncalibratedValuesKeepDefaultScaleAndNeverVerified() {
        val c = YccCalibrationController(null)
        val o = (0 until 5).flatMap { c.process(YccCandidates.decode(statusFrame(1600, 180, it), it * 80L)) }
        val b = o.last { it.param == VehicleParam.BATTERY_V }; assertFalse(b.calibrated); assertEquals(180 * 0.07, b.value, 1e-9)
    }

    // ---- đối chiếu GPS có độ trễ ----
    @Test fun speedCrossCheckFindsGpsLagAndReportsRmse() {
        val uuid = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
        fun speed(k: Int) = 2.0 + 10.0 * (1 + Math.sin(k / 8.0))
        val gps = (0 until 200).map { SpeedSample(it * 100L + 1000, speed(it)) }          // GPS đến trễ 1 s
        val fr = (0 until 200).map { k -> val raw = (speed(k) * 3.6 * 10).toInt(); RawFrame(k * 100L, uuid, byteArrayOf(0x55, (raw and 0xFF).toByte(), (raw shr 8).toByte(), (k and 0xFF).toByte())) }
        val m = SpeedCrossCheck.find(fr, gps).first { it.index == 1 && it.encoding == SpeedCrossCheck.Encoding.U16_LE }
        assertEquals(1000L, m.lagMs); assertTrue(m.r > 0.999); assertTrue(m.rmseKmh < 0.5)
    }
    @Test fun speedCrossCheckScansLongerFramesToo() {
        val uuid = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
        fun speed(k: Int) = 2.0 + 10.0 * (1 + Math.sin(k / 8.0))
        val gps = (0 until 200).map { SpeedSample(it * 100L, speed(it)) }
        val fr = (0 until 200).map { k -> val raw = (speed(k) * 3.6).toInt()
            RawFrame(k * 100L, uuid, ByteArray(if (k % 3 == 0) 143 else 59).also { it[10] = raw.toByte() }) }  // 59 byte phổ biến nhất, 143 ít hơn
        assertTrue(SpeedCrossCheck.find(fr, gps).any { it.index == 10 })
    }
}
