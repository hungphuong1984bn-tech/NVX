package vn.nvxconnect

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import vn.nvxconnect.bluetooth.*
import vn.nvxconnect.protocol.*

/**
 * Tái hiện hành vi QUAN SÁT trong log thật 2026-10-25 (nvx-ble-log-20261025-190509/194402):
 *  - xe tự ngắt (status 19) ~29,3 s sau READY khi khóa điện tắt, 0 gói; app phải nối lại MÃI, không báo lỗi;
 *  - sau khi nối lại phải TỰ bật nghe (không chờ người dùng bấm "Nghe");
 *  - khi có gói → hint STREAMING; app KHÔNG BAO GIỜ ghi vào 6e400002.
 */
class LiveLinkTest {
    private val addr = "AA:BB:CC:11:22:33"
    private val NUS = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    private val RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    private val TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    private val services = listOf(
        GattServiceInfo("00001800-0000-1000-8000-00805f9b34fb", listOf(GattCharInfo("00002a00-0000-1000-8000-00805f9b34fb", listOf("READ", "WRITE")))),
        GattServiceInfo(NUS, listOf(GattCharInfo(RX, listOf("WRITE", "WRITE_NO_RESP")), GattCharInfo(TX, listOf("NOTIFY"), listOf(GattDescriptorInfo("00002902-0000-1000-8000-00805f9b34fb"))))))

    private class FakePort : GattPort {
        override var events: GattEvents? = null
        val opens = mutableListOf<Pair<String, Boolean>>(); val calls = mutableListOf<String>()
        override fun open(address: String): PortOpen { opens += address to false; return PortOpen.Started }
        override fun open(address: String, background: Boolean): PortOpen { opens += address to background; return PortOpen.Started }
        override fun requestMtu(mtu: Int) = true
        override fun discoverServices() = true
        override fun readRssi() = true
        override fun setNotification(charUuid: String, enable: Boolean) = true.also { calls += "cccd:$charUuid:$enable" }
        override fun readCharacteristic(charUuid: String) = true
        override fun close() {}
    }

    private lateinit var scope: CoroutineScope
    private lateinit var port: FakePort
    @Before fun setUp() { scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); port = FakePort() }
    @After fun tearDown() { scope.cancel() }

    private fun vehicleCtl() = GattSessionController(port, scope, ReconnectPolicy(0, 20, 40, persistent = true, persistentCapMs = 40),
        connectTimeoutMs = 120, preferredMtu = 247, rssiPollMs = 60_000, opTimeoutMs = 150, autoListen = YccAutoListen::pick)

    private fun waitFor(ms: Long = 3000, p: () -> Boolean) { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { if (p()) return; Thread.sleep(5) }; fail("hết giờ chờ") }
    private fun linkUp(c: GattSessionController) {
        port.events!!.onLinkState(true, 0); port.events!!.onMtu(247, 0); port.events!!.onServices(services, 0)
        assertTrue(c.state.value is ConnState.Ready)
    }

    @Test fun autoListenPicksOnlyTheNotifyCharacteristic() {
        assertEquals(listOf(TX), YccAutoListen.pick(services))
        assertTrue(YccAutoListen.pick(emptyList()).isEmpty())
        // service lạ có cùng UUID characteristic nhưng khác service → không chọn
        assertTrue(YccAutoListen.pick(listOf(GattServiceInfo("0000aaaa-0000-1000-8000-00805f9b34fb", listOf(GattCharInfo(TX, listOf("NOTIFY")))))).isEmpty())
    }

    @Test fun listensAutomaticallyOnReadyAndNeverWritesTheDataChannel() {
        val c = vehicleCtl(); c.connect(addr); linkUp(c)
        assertEquals(listOf("cccd:$TX:true"), port.calls)             // chỉ bật nghe kênh notify
        assertTrue(port.calls.none { it.contains(RX) })              // không đụng 6e400002
        port.events!!.onDescriptorWrite(TX, 0)
        assertEquals(SubscriptionState.ACTIVE, c.subscriptions.value[TX])
    }

    @Test fun defaultControllerStillDoesNotAutoListen() {
        val c = GattSessionController(port, scope, ReconnectPolicy(2, 20, 40), 150, 247, 60_000, 150); c.connect(addr); linkUp(c)
        assertTrue(port.calls.isEmpty())
    }

    @Test fun remoteDropAt29sWithNoFramesReconnectsForeverAndExplainsWhy() {
        val c = vehicleCtl(); c.connect(addr)
        repeat(12) { n -> // nhiều hơn maxAttempts mặc định (5) rất nhiều: log thật có 25+ vòng liên tiếp
            linkUp(c)
            port.events!!.onLinkState(false, 19)                      // xe chủ động ngắt
            assertTrue("vòng $n phải ở Reconnecting, không phải Error: ${c.state.value}", c.state.value is ConnState.Reconnecting)
            assertEquals(0, (c.state.value as ConnState.Reconnecting).maxAttempts)   // 0 = không giới hạn
            assertEquals(LinkHint.WAITING_IGNITION, c.hint.value)
            val before = port.opens.size
            waitFor { port.opens.size > before }
            assertEquals(false, port.opens.last().second)             // xe vừa ngắt = đang ở gần → nối trực tiếp, nhanh
        }
        assertFalse(c.state.value is ConnState.Error)
    }

    @Test fun framesSwitchHintToStreamingAndSurviveResubscribe() {
        val c = vehicleCtl(); c.connect(addr); linkUp(c)
        port.events!!.onLinkState(false, 19); waitFor { port.opens.size >= 2 }
        port.calls.clear(); linkUp(c)
        assertEquals(listOf("cccd:$TX:true"), port.calls)             // nối lại cũng tự nghe lại
        assertEquals(LinkHint.WAITING_IGNITION, c.hint.value)
        port.events!!.onNotification(TX, byteArrayOf(0x6B, 5))
        assertEquals(LinkHint.STREAMING, c.hint.value)
    }

    @Test fun neverSeenVehicleEndsInBackgroundConnectInsteadOfError() {
        val c = vehicleCtl(); c.connect(addr)                         // không bao giờ có onLinkState → hết giờ nhiều lần
        waitFor(5000) { port.opens.any { it.second } }
        assertEquals(LinkHint.NOT_IN_RANGE, c.hint.value)
        assertTrue(c.state.value is ConnState.Reconnecting)
        assertFalse(port.opens.first().second)                        // lần đầu nối trực tiếp
        val bg = port.opens.indexOfFirst { it.second }; assertTrue("nền chỉ sau vài lần thử: $bg", bg >= 3)
        Thread.sleep(400)                                             // kết nối nền không có hết giờ → không mở thêm
        assertEquals(1, port.opens.count { it.second })
        // bật khóa điện: Android nối nền xong → Ready bình thường
        linkUp(c); assertTrue(c.state.value is ConnState.Ready)
    }

    @Test fun userUnsubscribeIsRespectedAndDisconnectClearsHint() {
        val c = vehicleCtl(); c.connect(addr); linkUp(c); port.events!!.onDescriptorWrite(TX, 0)
        assertTrue(c.unsubscribe(TX)); port.events!!.onDescriptorWrite(TX, 0)
        port.events!!.onLinkState(false, 19); waitFor { port.opens.size >= 2 }
        port.calls.clear(); linkUp(c)
        assertTrue("người dùng đã tắt nghe thì không tự bật lại", port.calls.isEmpty())
        c.disconnect(); assertEquals(LinkHint.NONE, c.hint.value); assertEquals(ConnState.Disconnected, c.state.value)
    }

    @Test fun authRequiredStillStopsEvenInVehicleMode() {
        val c = vehicleCtl(); c.connect(addr)
        port.events!!.onLinkState(false, 5)
        assertTrue(c.state.value is ConnState.Error)
        assertEquals(ErrorKind.AUTH_REQUIRED, (c.state.value as ConnState.Error).kind)
    }

    @Test fun linkStatusSaysWhyInsteadOfShowingAnError() {
        val reg = AdapterRegistry()
        val wait = VehicleLinkStatus.resolve(ConnState.Reconnecting(1, 0), reg, LinkHint.WAITING_IGNITION)
        assertEquals("CHỜ BẬT KHÓA ĐIỆN", wait.headline); assertEquals(LinkLevel.CONNECTING, wait.level)
        assertEquals("ĐANG TÌM XE", VehicleLinkStatus.resolve(ConnState.Reconnecting(2, 0), reg, LinkHint.NOT_IN_RANGE).headline)
        val live = VehicleLinkStatus.resolve(ConnState.Ready(services), reg, LinkHint.STREAMING)
        assertEquals(VehicleLinkStatus.STREAMING_UNVERIFIED, live.headline)
        assertFalse("không được tự nhận là đã xác minh", live.protocolVerified)
        // hành vi cũ giữ nguyên
        assertEquals("ĐANG KẾT NỐI LẠI 1/5", VehicleLinkStatus.resolve(ConnState.Reconnecting(1, 5), reg).headline)
        assertEquals(VehicleLinkStatus.RAW_ONLY, VehicleLinkStatus.resolve(ConnState.Ready(services), reg).headline)
    }

    @Test fun vehiclePolicyNeverGivesUp() {
        val p = ReconnectPolicy.vehicle()
        assertEquals(1000L, p.nextDelayMs(0)); assertEquals(4000L, p.nextDelayMs(50)); assertEquals(4000L, p.nextDelayMs(100_000))
        assertEquals(0, p.reportedMax)
    }
}
