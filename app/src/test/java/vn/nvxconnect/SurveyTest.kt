package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.bluetooth.*

class SurveyTest {
    private val rec = AdvertRecord("AA:BB:CC:11:22:33", "NVX-1234", -60, listOf("0000180f-0000-1000-8000-00805f9b34fb"), true, 1L)

    @Test fun redactKeepsOuiHidesRest() {
        val r = SurveyRedactor.address(rec.address, "salt")
        assertTrue(r.startsWith("AA:BB:CC:#"))
        assertFalse(r.contains("11:22:33"))
    }
    @Test fun redactIsDeterministicPerSalt() {
        assertEquals(SurveyRedactor.address(rec.address, "s"), SurveyRedactor.address(rec.address, "s"))
        assertNotEquals(SurveyRedactor.address(rec.address, "s"), SurveyRedactor.address(rec.address, "t"))
    }
    @Test fun csvAndJsonDoNotLeakRawAddress() {
        assertFalse(SurveyExporter.toCsv(listOf(rec), "s").contains("11:22:33"))
        assertFalse(SurveyExporter.toJson(SurveyLog(1L, listOf(rec), mapOf(rec.address to emptyList())), "s").contains("11:22:33"))
    }
}
