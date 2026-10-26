package vn.nvxconnect

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import vn.nvxconnect.bluetooth.*
import vn.nvxconnect.protocol.*
import vn.nvxconnect.service.VehicleServicePolicy
import vn.nvxconnect.telemetry.*

/** Kịch bản kết nối/ngắt/nối lại với GattPort giả: callback muộn, CCCD lỗi, Bluetooth tắt/bật, xe tắt khóa điện... */
class LinkResilienceTest {
    private val addr = "AA:BB:CC:11:22:33"
    private val NUS = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"; private val TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"; private val RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    private val services = listOf(GattServiceInfo(NUS, listOf(GattCharInfo(RX, listOf("WRITE")), GattCharInfo(TX, listOf("NOTIFY")))))

    private class FakePort : GattPort {
        override var events: GattEvents? = null
        val opened = java.util.concurrent.CopyOnWriteArrayList<String>(); val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var openResult: PortOpen = PortOpen.Started; @Volatile var closes = 0; @Volatile var cccdOk = true
        override fun open(address: String): PortOpen { opened += address; return openResult }
        override fun requestMtu(mtu: Int) = true.also { calls += "mtu" }
        override fun discoverServices() = true.also { calls += "discover" }
        override fun readRssi() = true.also { calls += "rssi" }
        override fun setNotification(charUuid: String, enable: Boolean) = cccdOk.also { calls += "cccd:$charUuid:$enable" }
        override fun readCharacteristic(charUuid: String) = true
        override fun close() { closes++ }
    }
    private class Rec(val epoch: Int, val char: String, val svc: String?, val state: String, val indication: Boolean, val bytes: ByteArray)
    private class Sink : FrameSink {
        val frames = java.util.concurrent.CopyOnWriteArrayList<Rec>(); @Volatile var sessions = 0
        override fun onSessionStart() { sessions++ }
        override fun onFrame(epoch: Int, charUuid: String, serviceUuid: String?, bytes: ByteArray, wallMs: Long, monoNs: Long, rssi: Int?, connState: String, indication: Boolean) { frames += Rec(epoch, charUuid, serviceUuid, connState, indication, bytes) }
    }

    private lateinit var scope: CoroutineScope; private lateinit var port: FakePort; private lateinit var sink: Sink
    @Before fun setUp() { scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); port = FakePort(); sink = Sink() }
    @After fun tearDown() { scope.cancel() }

    private fun vehicle(rnd: () -> Double = { 0.5 }) = GattSessionController(port, scope, ReconnectPolicy(0, 20, 40, persistent = true, persistentCapMs = 40, jitter = 0.2), 150, 247, 60_000, 150,
        autoListen = YccAutoListen::pick, sink = sink, random = rnd)
    private fun waitFor(ms: Long = 3000, p: () -> Boolean) { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { if (p()) return; Thread.sleep(5) }; fail("hết giờ chờ") }
    private fun up(c: GattSessionController) {
        port.events!!.onLinkState(true, 0); port.events!!.onMtu(247, 0); port.events!!.onServices(services, 0)
        assertTrue(c.state.value is ConnState.Ready)
    }
    private fun ack(c: GattSessionController) { port.events!!.onDescriptorWrite(TX, 0); assertEquals(SubscriptionState.ACTIVE, c.subscriptions.value[TX]) }

    @Test fun notifyReachesSinkWithEpochServiceAndStateAndIsCopied() {
        val c = vehicle(); c.connect(addr); up(c); ack(c)
        val raw = byteArrayOf(0x6B, 1, 2); port.events!!.onNotification(TX, raw); raw[0] = 0   // đổi mảng gốc sau khi giao: bản đã nhận không bị ảnh hưởng
        val r = sink.frames.single()
        assertEquals(1, r.epoch); assertEquals(TX, r.char); assertEquals(NUS, r.svc); assertEquals("READY", r.state); assertFalse(r.indication)
        assertEquals(0x6B.toByte(), r.bytes[0]); assertEquals(1, sink.sessions)
    }

    @Test fun lateCallbacksFromClosedSessionAreDroppedAndCounted() {
        val c = vehicle(); c.connect(addr); up(c); ack(c)
        port.events!!.onLinkState(false, 19)                       // xe tự ngắt → Reconnecting
        waitFor { c.state.value is ConnState.Reconnecting }
        port.events!!.onNotification(TX, byteArrayOf(1))           // gói muộn của phiên cũ
        port.events!!.onServices(services, 0)                      // khám phá muộn: không được đẩy sang Ready
        port.events!!.onDescriptorWrite(TX, 0)
        assertFalse(c.state.value is ConnState.Ready)
        assertEquals(0, sink.frames.size); assertTrue(c.diagnostics.value.lateEventsDropped >= 3)
    }

    @Test fun ignitionOffThenOnResubscribesAndEpochAdvances() {
        val c = vehicle(); c.connect(addr); up(c); ack(c)
        port.events!!.onNotification(TX, byteArrayOf(1))
        port.events!!.onLinkState(false, 19)                       // tắt khóa điện: xe ngắt
        waitFor { port.opened.size >= 2 }                          // app tự nối lại, không bỏ cuộc
        repeat(3) { port.events!!.onLinkState(false, 133); waitFor(2000) { port.opened.size >= 3 + it } }   // xe không quảng bá: nhiều lần lỗi 133
        assertTrue("không được bỏ cuộc", c.state.value is ConnState.Reconnecting)
        up(c)                                                      // bật khóa điện: nối được
        assertTrue(port.calls.count { it == "cccd:$TX:true" } >= 2)  // đăng ký lại Notify
        ack(c); port.events!!.onNotification(TX, byteArrayOf(2))
        assertEquals(listOf(1, c.diagnostics.value.epoch), sink.frames.map { it.epoch })
        assertTrue(c.diagnostics.value.epoch > 1)
        val h = c.linkHistory.value
        assertTrue(h.any { it.kind == LinkEventKind.LINK_LOST && it.status == 19 && it.upMs != null })
        assertTrue(h.any { it.kind == LinkEventKind.ATTEMPT_FAILED && it.status == 133 })
        assertEquals(LinkEventKind.READY, h.last().kind)
    }

    @Test fun cccdFailureMarksSubscriptionFailedButKeepsLink() {
        val c = vehicle(); c.connect(addr); up(c)
        port.events!!.onDescriptorWrite(TX, 133)
        assertEquals(SubscriptionState.FAILED, c.subscriptions.value[TX]); assertTrue(c.state.value is ConnState.Ready)
    }
    @Test fun cccdThatCannotStartMarksFailedWithoutHanging() {
        port.cccdOk = false
        val c = vehicle(); c.connect(addr); up(c)
        assertEquals(SubscriptionState.FAILED, c.subscriptions.value[TX])
        c.subscribe(TX); assertTrue(c.state.value is ConnState.Ready)
    }
    @Test fun cccdTimeoutDoesNotBlockQueueForever() {
        val c = vehicle(); c.connect(addr); up(c)       // không bao giờ trả onDescriptorWrite
        waitFor { c.subscriptions.value[TX] == SubscriptionState.FAILED }
        assertTrue(c.state.value is ConnState.Ready)
    }

    @Test fun bluetoothOffStopsRetriesAndOnResumesExactlyOnce() {
        val c = vehicle(); c.connect(addr); up(c)
        port.events!!.onBluetoothState(false)
        assertEquals(ErrorKind.BLUETOOTH_OFF, (c.state.value as ConnState.Error).kind)
        val opens = port.opened.size; Thread.sleep(400)
        assertEquals("không nối lặp khi Bluetooth tắt", opens, port.opened.size)
        port.events!!.onBluetoothState(true)
        assertEquals(opens + 1, port.opened.size); assertTrue(c.state.value is ConnState.Reconnecting || c.state.value is ConnState.Connecting)
        assertTrue(c.linkHistory.value.any { it.kind == LinkEventKind.BLUETOOTH_RESUMED })
    }
    @Test fun bluetoothOffAtStartThenOnResumes() {
        port.openResult = PortOpen.Failed(ErrorKind.BLUETOOTH_OFF, "tắt")
        val c = vehicle(); c.connect(addr)
        assertEquals(ErrorKind.BLUETOOTH_OFF, (c.state.value as ConnState.Error).kind)
        Thread.sleep(300); assertEquals(1, port.opened.size)        // không lặp
        port.openResult = PortOpen.Started; port.events!!.onBluetoothState(true)
        assertEquals(2, port.opened.size)
    }
    @Test fun userDisconnectBlocksResumeAfterBluetoothComesBack() {
        val c = vehicle(); c.connect(addr); up(c)
        port.events!!.onBluetoothState(false); c.disconnect()
        val opens = port.opened.size; port.events!!.onBluetoothState(true)
        assertEquals(opens, port.opened.size); assertEquals(ConnState.Disconnected, c.state.value); assertNull(c.address)
    }
    @Test fun permissionDeniedDoesNotLoop() {
        port.openResult = PortOpen.Failed(ErrorKind.PERMISSION_DENIED, "thiếu quyền")
        val c = vehicle(); c.connect(addr); Thread.sleep(400)
        assertEquals(1, port.opened.size); assertEquals(ErrorKind.PERMISSION_DENIED, (c.state.value as ConnState.Error).kind)
        assertFalse(VehicleServicePolicy.shouldRun(c.state.value))
    }
    @Test fun authRequiredStopsRetrying() {
        val c = vehicle(); c.connect(addr); port.events!!.onLinkState(false, 5); Thread.sleep(300)
        assertEquals(1, port.opened.size); assertEquals(ErrorKind.AUTH_REQUIRED, (c.state.value as ConnState.Error).kind)
        assertTrue(c.linkHistory.value.any { it.kind == LinkEventKind.AUTH_REQUIRED })
    }

    @Test fun rapidConnectDisconnectLeavesNoTimersOrExtraSessions() {
        val c = vehicle()
        repeat(100) { c.connect(if (it % 2 == 0) addr else "AA:BB:CC:44:55:66"); c.disconnect() }
        assertEquals(ConnState.Disconnected, c.state.value)
        val opens = port.opened.size; Thread.sleep(500)
        assertEquals("timer cũ vẫn chạy sau khi ngắt", opens, port.opened.size)
        assertEquals(100, sink.sessions)
    }
    @Test fun changingDeviceClosesOldSessionFirst() {
        val c = vehicle(); c.connect(addr); up(c); val closesBefore = port.closes
        c.connect("AA:BB:CC:44:55:66")
        assertTrue(port.closes > closesBefore); assertEquals("AA:BB:CC:44:55:66", port.opened.last()); assertTrue(c.state.value is ConnState.Connecting)
    }
    @Test fun userDisconnectCancelsPendingReconnectTimer() {
        val c = vehicle(); c.connect(addr); up(c); port.events!!.onLinkState(false, 133)
        c.disconnect(); val opens = port.opened.size; Thread.sleep(300)
        assertEquals(opens, port.opened.size)
        assertEquals(LinkEventKind.USER_DISCONNECT, c.linkHistory.value.last().kind)
    }

    @Test fun highRateNotifyAllReachSinkInOrder() {
        val c = vehicle(); c.connect(addr); up(c); ack(c)
        repeat(20_000) { port.events!!.onNotification(TX, byteArrayOf(0x6B, (it and 0xFF).toByte())) }
        assertEquals(20_000, sink.frames.size)
        assertTrue(sink.frames.withIndex().all { (i, f) -> f.bytes[1] == (i and 0xFF).toByte() })
    }

    // ---- chính sách chờ ----
    @Test fun jitterStaysWithinBoundsAndIsDeterministicWhenOff() {
        val p = ReconnectPolicy.vehicle()
        assertEquals(1000L, p.nextDelayMs(0))
        assertEquals(800L, p.nextDelayJitteredMs(0, 0.0)); assertEquals(1199L, p.nextDelayJitteredMs(0, 0.9995).let { minOf(it!!, 1199L) })
        assertTrue(p.nextDelayJitteredMs(50, 0.5)!! in 3_200..4_800)
        assertEquals(1000L, ReconnectPolicy(5, 1000, 30_000).nextDelayJitteredMs(0, 0.3))
    }

    // ---- dịch vụ nền ----
    @Test fun serviceRunsOnlyWhileUserStillWantsTheLink() {
        assertFalse(VehicleServicePolicy.shouldRun(ConnState.Disconnected))
        for (s in listOf(ConnState.Connecting, ConnState.Connected, ConnState.DiscoveringServices, ConnState.Ready(emptyList()), ConnState.Reconnecting(3, 0))) assertTrue(VehicleServicePolicy.shouldRun(s))
        assertTrue(VehicleServicePolicy.shouldRun(ConnState.Error(ErrorKind.BLUETOOTH_OFF, "")))
        for (k in listOf(ErrorKind.AUTH_REQUIRED, ErrorKind.PERMISSION_DENIED, ErrorKind.INVALID_ADDRESS)) assertFalse(VehicleServicePolicy.shouldRun(ConnState.Error(k, "")))
    }
    @Test fun notificationTextNeverClaimsDataWithoutRecentFrames() {
        val none = CaptureStats(); val now = 10_000L
        assertTrue(VehicleServicePolicy.notification(ConnState.Ready(emptyList()), LinkHint.NONE, none, now).text.contains("Chưa có dữ liệu"))
        val live = CaptureStats(received = 50, lastFrameWallMs = now - 500, ratePerSec = 17.6)
        assertTrue(VehicleServicePolicy.notification(ConnState.Ready(emptyList()), LinkHint.STREAMING, live, now).text.contains("18 gói/giây"))
        val stale = live.copy(lastFrameWallMs = now - 60_000)
        assertTrue(VehicleServicePolicy.notification(ConnState.Ready(emptyList()), LinkHint.NONE, stale, now).text.contains("Chưa có dữ liệu"))
        val bad = live.copy(writer = WriterHealth.DEGRADED, droppedIngress = 4)
        assertTrue(VehicleServicePolicy.notification(ConnState.Ready(emptyList()), LinkHint.STREAMING, bad, now).text.let { it.contains("LỖI GHI") && it.contains("mất 4") })
    }

    // ---- bảng giám sát ----
    private fun input(state: ConnState, cap: CaptureStats = CaptureStats(), verified: Int = 0, hyp: Int = 0) = MonitorInput(state, LinkHint.NONE, null, 23, emptyMap(), null, null, null, 0, 100_000, cap, emptyList(), LinkDiagnostics(), 0, 0, verified, hyp, null)
    @Test fun monitorSeparatesConnectedReceivingRecordingVerified() {
        val ready = ConnState.Ready(services)
        val noData = LinkMonitorModel.build(input(ready, CaptureStats(), 0, 2)).levels
        assertEquals(MonitorTone.GOOD, noData.bluetooth.tone); assertEquals(MonitorTone.WARN, noData.receiving.tone)
        assertEquals(MonitorTone.NEUTRAL, noData.recording.tone); assertEquals(MonitorTone.WARN, noData.verified.tone)
        assertTrue(noData.verified.value.contains("Chưa có trường nào được xác minh"))
        val live = LinkMonitorModel.build(input(ready, CaptureStats(received = 10, persisted = 10, lastFrameWallMs = 100_000 - 500, ratePerSec = 17.5, writer = WriterHealth.OK))).levels
        assertEquals(MonitorTone.GOOD, live.receiving.tone); assertEquals(MonitorTone.GOOD, live.recording.tone); assertNotEquals(MonitorTone.GOOD, live.verified.tone)
        val failing = LinkMonitorModel.build(input(ready, CaptureStats(received = 10, writer = WriterHealth.DEGRADED, writerError = "đầy"))).levels
        assertEquals(MonitorTone.BAD, failing.recording.tone)
    }
    @Test fun monitorShowsNoFakeNumbersWhenDisconnected() {
        val s = LinkMonitorModel.build(input(ConnState.Disconnected))
        val link = s.sections.first { it.title == "Liên kết" }.rows.associate { it.label to it.value }
        assertEquals("Chưa có dữ liệu", link["RSSI"]); assertEquals("Chưa có dữ liệu", link["MTU"]); assertEquals("Chưa có dữ liệu", link["Kết nối liên tục"])
        assertEquals("Chưa có dữ liệu", s.sections.first { it.title == "Dữ liệu nhận" }.rows.first { it.label == "Gói gần nhất" }.value)
    }
    @Test fun monitorReportsLostPacketsAsBad() {
        val c = CaptureStats(received = 100, persisted = 90, droppedIngress = 10, recentDrops = listOf(DropEvent(1, 2, DropReason.INGRESS_FULL, 10, 91, 100)))
        val s = LinkMonitorModel.build(input(ConnState.Ready(services), c))
        assertEquals(MonitorTone.BAD, s.sections.first { it.title == "Dữ liệu nhận" }.rows.first { it.label == "Gói app làm mất" }.tone)
        assertTrue(s.sections.first { it.title == "Ghi dữ liệu" }.rows.any { it.label == "Mất gói" && it.value.contains("#91–#100") })
    }
}
