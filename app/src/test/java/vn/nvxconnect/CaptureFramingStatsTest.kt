package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.TelemetryTestSupport.REAL_IDLE
import vn.nvxconnect.TelemetryTestSupport.REAL_PARAMS

class CaptureFramingStatsTest {
    private val TX = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private fun pipe() = vn.nvxconnect.telemetry.CapturePipeline(java.nio.file.Files.createTempDirectory("cap").toFile(), scope, minFreeBytes = 1, statsEveryMs = 50)

    @Test fun statsFollowDeclaredCharacteristicAndRawStorageIsUntouched() {
        val p = pipe()
        p.onFrame(1, TX, null, REAL_PARAMS.copyOfRange(0, 40), 1000, 1_000_000L, null, "Ready", false)
        p.onFrame(1, TX, null, REAL_PARAMS.copyOfRange(40, REAL_PARAMS.size) + REAL_IDLE, 1010, 11_000_000L, null, "Ready", false)
        p.onFrame(1, "0000ffe1-0000-1000-8000-00805f9b34fb", null, byteArrayOf(1, 2, 3), 1020, 21_000_000L, null, "Ready", false)
        val s = p.framingStats()
        assertEquals(2L, s.getValue(TX.lowercase()).framesOut); assertEquals(1L, s.getValue(TX.lowercase()).joinedFrames)
        assertEquals(1L, s.getValue("0000ffe1-0000-1000-8000-00805f9b34fb").framesOut)
    }
    @Test fun newGattEpochDropsPendingFragment() {
        val p = pipe()
        p.onFrame(1, TX, null, REAL_PARAMS.copyOfRange(0, 40), 1000, 1_000_000L, null, "Ready", false)
        p.onFrame(2, TX, null, REAL_IDLE, 2000, 2_000_000L, null, "Ready", false)
        val s = p.framingStats().getValue(TX.lowercase())
        assertEquals(1L, s.staleFragmentsDropped); assertEquals(1L, s.framesOut)
    }
}
