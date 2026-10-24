package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.protocol.*

/** Gói THẬT bắt từ xe (2026-10-23, địa chỉ đã che). Kiểm tra định dạng, KHÔNG kiểm tra ý nghĩa vật lý. */
class YccFrameTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private val idle = hex("6B0500012100000000283AB22300000010000079520000CB808000810000000000008080808000480400001617011D01FF011701FF011901FF0047")
    private val idleNext = hex("6B0500012100000000283AB22300000010000079520000CB808000810000000000008080808000480400001617011D01FF011701FF011901FF0146")
    private val running = hex("6B05000121069C00062335AA2300000010000074586100CB808000810000000150008080808000480400001617011D01FF011701FF011901FF609E")
    private val params = hex("6C18022601FE022E010002A303000800059001FE020903020000020A0100021503FEFEFE021603FEFEFE023A0100023E020000024501FE02AA070000000000020802AB030A081A026008FEFEFEFEFEFEFEFE02680400000000055501FE040401FE02B102FEFE02BA05000000080003BA080011460E43A00000027A02FEFE038302FEFE02BC03FEFEFE020401FE00CF")
    private val paramsNext = hex("6C18022601FE022E010002A303000800059001FE020903020000020A0100021503FEFEFE021603FEFEFE023A0100023E020000024501FE02AA070000000000020802AB030A081A026008FEFEFEFEFEFEFEFE02680400000000055501FE040401FE02B102FEFE02BA05000000080003BA080011460E43A00000027A02FEFE038302FEFE02BC03FEFEFE020401FE01CE")
    private val block = hex("5901FF6046000012970000127300000AC30000001A00000D49000039A4000001EC00000006000000000000050900005E6500002DA90000034600000000000000000000000000000000000000D6")
    private val info = hex("6A09F190110000000000000000000000000000000000F19404129E0040F1970442575031F1A1040001362FF1A20200DBF421020000F44D020000F7010400004AF7F70204000000430099")
    private val hello = hex("5A017F0101010023")

    @Test fun realFramesOfEveryTypeParse() {
        for ((b, t) in listOf(idle to 0x6B, params to 0x6C, block to 0x59, info to 0x6A, hello to 0x5A)) {
            val r = YccFrames.parse(b); assertTrue(r.toString(), r is YccParse.Valid); assertEquals(t, (r as YccParse.Valid).frame.type)
        }
    }
    @Test fun checksumMismatchRejected() {
        val bad = idle.copyOf(); bad[10] = (bad[10] + 1).toByte()
        assertTrue(YccFrames.parse(bad) is YccParse.Invalid)
    }
    @Test fun wrongLengthAndUnknownTypeRejected() {
        assertTrue(YccFrames.parse(idle.copyOf(58)) is YccParse.Invalid)
        assertTrue(YccFrames.parse(byteArrayOf(0x11, 0x22, 0x33)) is YccParse.Invalid)
        assertTrue(YccFrames.parse(ByteArray(0)) is YccParse.Invalid)
    }
    @Test fun tlvOfParamsFrameHas24ItemsAndKnownKeys() {
        val f = (YccFrames.parse(params) as YccParse.Valid).frame
        assertEquals(24, f.tlv!!.size)
        assertNotNull(f.item(0x02, 0xBA)); assertEquals(5, f.item(0x02, 0xBA)!!.data.size)
        assertTrue(f.item(0x02, 0x26)!!.allFe)
    }
    @Test fun tlvWithBadLengthRejected() {
        val bad = params.copyOf(); bad[4] = 0x7F; // len của mục đầu quá lớn
        var s = 0; for (i in 0 until bad.size - 1) s += bad[i].toInt() and 0xFF; bad[bad.size - 1] = (-s).toByte()
        assertTrue(YccFrames.parse(bad) is YccParse.Invalid)
    }
    @Test fun monitorCountsGapsAndInvalid() {
        val m = YccLinkMonitor()
        assertNotNull(m.accept(idle, 1)); assertNotNull(m.accept(idleNext, 2))
        assertEquals(0, m.stats.counterGaps)
        assertNotNull(m.accept(idle, 3)); // bộ đếm nhảy lùi => 1 khoảng trống
        assertEquals(1, m.stats.counterGaps)
        val bad = idle.copyOf(); bad[3] = 9
        assertNull(m.accept(bad, 4)); assertEquals(1, m.stats.invalid); assertEquals(3, m.stats.valid)
    }
    @Test fun disconnectResetsCounterTracking() {
        val m = YccLinkMonitor(); m.accept(idle, 1); m.onDisconnect(); m.accept(running, 2)
        assertEquals(0, m.stats.counterGaps)
    }
    @Test fun candidatesAreNeverVerifiedAndOnlyFromStatusFrames() {
        val st = (YccFrames.parse(running) as YccParse.Valid).frame
        val c = YccCandidates.decode(st, 5)
        val rpm = c.first { it.param == VehicleParam.RPM }
        assertEquals(1692.0, rpm.value, 0.0); assertTrue(rpm.label.contains("chưa xác minh"))
        val bat = c.first { it.param == VehicleParam.BATTERY_V }; assertEquals(Confidence.LOW, bat.confidence)
        assertTrue(YccCandidates.decode((YccFrames.parse(params) as YccParse.Valid).frame, 5).isEmpty())
        // kênh ứng viên không đi qua TelemetryHub
        val hub = TelemetryHub(); assertEquals(0, hub.ingest(emptyList(), true)); assertTrue(hub.state.value.values.isEmpty())
    }
    @Test fun idleFrameGivesZeroRpm() {
        val st = (YccFrames.parse(idle) as YccParse.Valid).frame
        assertEquals(0.0, YccCandidates.decode(st, 1).first { it.param == VehicleParam.RPM }.value, 0.0)
    }
    @Test fun hubFreshness() {
        val hub = YccCandidateHub(); val st = (YccFrames.parse(running) as YccParse.Valid).frame
        hub.ingest(YccCandidates.decode(st, 1000))
        assertNotNull(YccCandidateHub.fresh(hub.state.value, YccCandidates.RPM_ID, 2000))
        assertNull(YccCandidateHub.fresh(hub.state.value, YccCandidates.RPM_ID, 9000))
    }
}
