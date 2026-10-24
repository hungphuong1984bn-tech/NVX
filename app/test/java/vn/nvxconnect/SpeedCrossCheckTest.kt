package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.protocol.*
import java.util.Random

class SpeedCrossCheckTest {
    private val uuid = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    private fun speedAt(k: Int) = 2.0 + 10.0 * (1 + Math.sin(k / 8.0)) // 2..22 m/s
    private fun gps() = (0 until 200).map { SpeedSample(it * 100L, speedAt(it)) }
    private fun frames(make: (Int, Double) -> ByteArray) = (0 until 200).map { RawFrame(it * 100L, uuid, make(it, speedAt(it))) }

    @Test fun findsUint16LittleEndianLinearInSpeed() {
        val f = frames { k, v -> val raw = (v * 3.6 * 10).toInt(); byteArrayOf(0x55, (raw and 0xFF).toByte(), (raw shr 8).toByte(), (k and 0xFF).toByte()) }
        val m = SpeedCrossCheck.find(f, gps())
        assertTrue(m.any { it.index == 1 && it.encoding == SpeedCrossCheck.Encoding.U16_LE && it.r > 0.99 })
        assertTrue(m.none { it.index == 3 }) // bộ đếm không bị nhận nhầm
        assertTrue(m.none { it.index == 0 }) // byte hằng không bị nhận nhầm
    }
    @Test fun randomFieldsAreNotMatched() {
        val rnd = Random(7)
        val f = frames { _, _ -> ByteArray(6) { rnd.nextInt(256).toByte() } }
        assertTrue(SpeedCrossCheck.find(f, gps()).isEmpty())
    }
    @Test fun noMatchWhenSpeedBarelyChanges() {
        val flat = (0 until 200).map { SpeedSample(it * 100L, 10.0 + (it % 2) * 0.1) }
        val f = frames { _, v -> byteArrayOf((v * 10).toInt().toByte()) }
        assertTrue(SpeedCrossCheck.find(f, flat).isEmpty())
    }
    @Test fun noMatchWithoutGps() = assertTrue(SpeedCrossCheck.find(frames { _, v -> byteArrayOf(v.toInt().toByte()) }, emptyList()).isEmpty())
    @Test fun matchIsNeverVerifiedInReport() {
        val f = frames { _, v -> val raw = (v * 36).toInt(); byteArrayOf((raw and 0xFF).toByte(), (raw shr 8).toByte()) }
        val out = vn.nvxconnect.auto.ProtocolReport.build(vn.nvxconnect.auto.ReportInput(f, emptyList(), emptyList(), AdapterRegistry(), false, emptyMap(), gps()))
        assertTrue(out.any { it.level == vn.nvxconnect.auto.EvidenceLevel.HYPOTHESIS && it.title.contains("tốc độ GPS") })
        assertTrue(out.none { it.level == vn.nvxconnect.auto.EvidenceLevel.VERIFIED })
    }
}
