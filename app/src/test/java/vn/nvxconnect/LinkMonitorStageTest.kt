package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.bluetooth.GattCharInfo
import vn.nvxconnect.bluetooth.GattServiceInfo
import vn.nvxconnect.protocol.*
import vn.nvxconnect.telemetry.*

/** Dòng "Mức liên kết" của bảng giám sát: không bao giờ báo PROTOCOL VERIFIED chỉ vì có gói thô. */
class LinkMonitorStageTest {
    private val TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    private val ready = ConnState.Ready(listOf(GattServiceInfo("6e400001-b5a3-f393-e0a9-e50e24dcca9e", listOf(GattCharInfo(TX, listOf("NOTIFY"))))))
    private val NOW_MS = 1_000_000L; private val NOW_NS = 5_000_000_000_000L
    private fun input(state: ConnState = ready, subs: Map<String, SubscriptionState> = emptyMap(), readyAgoMs: Long? = 10_000, lastFrameAgoMs: Long? = null,
                      verified: Int = 0, adapter: Boolean = false) =
        MonitorInput(state, LinkHint.NONE, null, 247, subs, null, null, readyAgoMs?.let { NOW_NS - it * 1_000_000 }, NOW_NS, NOW_MS,
            CaptureStats(received = if (lastFrameAgoMs != null) 100 else 0, lastFrameWallMs = lastFrameAgoMs?.let { NOW_MS - it }), emptyList(), LinkDiagnostics(), 0, 0, verified, 0, null, adapter)
    private fun stage(i: MonitorInput) = LinkMonitorModel.build(i).levels.stage

    @Test fun walksThroughTheFiveStagesWithHonestMissingText() {
        assertTrue(stage(input(ConnState.Disconnected, readyAgoMs = null)).value.startsWith("Chưa kết nối"))
        assertTrue(stage(input(subs = emptyMap())).value.startsWith("GATT READY") && stage(input()).value.contains("còn thiếu"))
        assertTrue(stage(input(subs = mapOf(TX to SubscriptionState.ACTIVE))).value.startsWith("SUBSCRIPTION SUCCESS"))
        assertTrue(stage(input(subs = mapOf(TX to SubscriptionState.ACTIVE), lastFrameAgoMs = 500)).value.startsWith("DATA RECEIVED"))
        val full = stage(input(subs = mapOf(TX to SubscriptionState.ACTIVE), lastFrameAgoMs = 500, verified = 1, adapter = true))
        assertEquals("PROTOCOL VERIFIED", full.value); assertEquals(MonitorTone.GOOD, full.tone)
    }
    @Test fun rawFramesOnlyIsWarnNotGood() {
        val s = stage(input(subs = mapOf(TX to SubscriptionState.ACTIVE), lastFrameAgoMs = 100))
        assertEquals(MonitorTone.WARN, s.tone); assertFalse(s.value.contains("PROTOCOL VERIFIED") && !s.value.contains("còn thiếu"))
    }
    @Test fun framesFromAnOlderGattSessionDoNotCountForCurrentOne() {
        // Gói cuối đến 50 s trước, nhưng phiên GATT hiện tại mới Ready 10 s trước → chưa có gói nào của phiên này.
        val s = stage(input(subs = mapOf(TX to SubscriptionState.ACTIVE), readyAgoMs = 10_000, lastFrameAgoMs = 50_000))
        assertTrue(s.value, s.value.startsWith("SUBSCRIPTION SUCCESS"))
    }
}
