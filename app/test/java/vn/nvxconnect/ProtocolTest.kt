package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.bluetooth.GattServiceInfo
import vn.nvxconnect.protocol.*

class ProtocolTest {
    private val frame = RawFrame(1L, "c", byteArrayOf(1, 2, 3))

    @Test fun backoffDoublesCapsAndGivesUp() {
        val p = ReconnectPolicy(maxAttempts = 3, baseMs = 1000, capMs = 1500)
        assertEquals(1000L, p.nextDelayMs(0)); assertEquals(1500L, p.nextDelayMs(1)); assertEquals(1500L, p.nextDelayMs(2)); assertNull(p.nextDelayMs(3))
    }
    @Test fun emptyRegistryYieldsOnlyUnknown() {
        val out = AdapterRegistry().decode(emptyList(), frame)
        assertEquals(1, out.size); assertTrue(out[0] is DecodedItem.Unknown)
    }
    @Test fun registryRejectsUnverifiedAdapter() {
        val a = FakeAdapter(ProtocolEvidence("x", "0", listOf("u"), "f", "u", null, listOf("01"), null, null, false))
        assertThrows(IllegalArgumentException::class.java) { AdapterRegistry().register(a) }
    }
    @Test fun registryAcceptsFullyEvidencedAdapter() {
        val a = FakeAdapter(ProtocolEvidence("t", "1", listOf("u"), "f", "u", null, listOf("01"), "xe-thu-nghiem", "pass", true))
        val r = AdapterRegistry(); r.register(a)
        assertEquals(1, r.count)
        assertTrue(r.decode(listOf(GattServiceInfo("u", emptyList())), frame)[0] is DecodedItem.Value)
    }
    @Test fun redactorMasksLongAscii() {
        val b = "ABCDEFGH".toByteArray() + byteArrayOf(0x01)
        assertEquals("2A 2A 2A 2A 2A 2A 2A 2A 01", FrameRedactor.hexRedacted(b))
        assertEquals("01 02 03", FrameRedactor.hexRedacted(byteArrayOf(1, 2, 3)))
    }
    /** Chỉ dùng trong test để kiểm tra cơ chế đăng ký; không phải giao thức thật. */
    private class FakeAdapter(override val evidence: ProtocolEvidence) : VehicleProtocolAdapter {
        override fun matches(services: List<GattServiceInfo>) = services.any { it.uuid == "u" }
        override fun decode(frame: RawFrame) = listOf<DecodedItem>(DecodedItem.Value(VehicleParam.RPM, 0.0, "rpm", frame.timeMs, evidence.id))
    }
}
