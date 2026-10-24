package vn.nvxconnect

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import vn.nvxconnect.bluetooth.*
import vn.nvxconnect.protocol.*

/** Kiểm thử máy trạng thái phiên GATT bằng GattPort giả. Timeout rất ngắn nên chạy nhanh. */
class GattSessionTest {
    private val addr = "AA:BB:CC:11:22:33"
    private val addr2 = "AA:BB:CC:44:55:66"
    private val NUS = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    private val TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    private val RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    private val DEVNAME = "00002a00-0000-1000-8000-00805f9b34fb"
    private val SERIAL = "00002a25-0000-1000-8000-00805f9b34fb"
    private val services = listOf(
        GattServiceInfo(NUS, listOf(GattCharInfo(RX, listOf("WRITE", "WRITE_NO_RESP")), GattCharInfo(TX, listOf("NOTIFY")))),
        GattServiceInfo("00001800-0000-1000-8000-00805f9b34fb", listOf(GattCharInfo(DEVNAME, listOf("READ")), GattCharInfo(SERIAL, listOf("READ")))))

    private class FakePort : GattPort {
        override var events: GattEvents? = null
        val opened = mutableListOf<String>(); var closes = 0
        var openResult: PortOpen = PortOpen.Started
        val calls = mutableListOf<String>()
        var discoverOk = true
        override fun open(address: String): PortOpen { opened += address; return openResult }
        override fun requestMtu(mtu: Int) = true.also { calls += "mtu" }
        override fun discoverServices() = discoverOk.also { calls += "discover" }
        override fun readRssi() = true.also { calls += "rssi" }
        override fun setNotification(charUuid: String, enable: Boolean) = true.also { calls += "cccd:$charUuid:$enable" }
        override fun readCharacteristic(charUuid: String) = true.also { calls += "read:$charUuid" }
        override fun close() { closes++ }
    }

    private lateinit var scope: CoroutineScope
    private lateinit var port: FakePort
    @Before fun setUp() { scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); port = FakePort() }
    @After fun tearDown() { scope.cancel() }

    private fun ctl(maxAttempts: Int = 2, connectTimeout: Long = 150) =
        GattSessionController(port, scope, ReconnectPolicy(maxAttempts, 20, 40), connectTimeout, 247, 60_000, 150)

    private fun await(c: GattSessionController, ms: Long = 3000, p: (ConnState) -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (p(c.state.value)) return; Thread.sleep(5) }
        fail("Hết giờ chờ trạng thái; hiện tại: ${c.state.value}")
    }

    private fun toReady(c: GattSessionController) {
        c.connect(addr); assertEquals(ConnState.Connecting, c.state.value)
        port.events!!.onLinkState(true, 0); assertEquals(ConnState.Connected, c.state.value)
        port.events!!.onMtu(247, 0)
        assertEquals(ConnState.DiscoveringServices, c.state.value)
        port.events!!.onServices(services, 0)
        assertTrue(c.state.value is ConnState.Ready)
    }

    @Test fun happyPathGoesThroughEveryState() {
        val c = ctl(); val seen = mutableListOf<String>()
        val job = scope.launch(Dispatchers.Unconfined) { c.state.collect { seen += it.code } }
        toReady(c); job.cancel()
        assertEquals(listOf("DISCONNECTED", "CONNECTING", "CONNECTED", "DISCOVERING_SERVICES", "READY"), seen)
        assertEquals(247, c.mtu.value)
        assertEquals(listOf("mtu", "discover"), port.calls)
    }

    @Test fun readyIsNeverReportedBeforeServiceDiscovery() {
        val c = ctl(); c.connect(addr); port.events!!.onLinkState(true, 0)
        assertFalse(c.state.value is ConnState.Ready)
        port.events!!.onMtu(23, 133) // MTU lỗi vẫn phải đi tiếp sang discover
        assertEquals(ConnState.DiscoveringServices, c.state.value)
    }

    @Test fun onlyOneSessionPerDevice() {
        val c = ctl(); c.connect(addr); c.connect(addr)
        assertEquals(1, port.opened.size)
        c.connect(addr2)
        assertEquals(listOf(addr, addr2), port.opened)
        assertTrue(port.closes >= 1)
    }

    @Test fun connectTimeoutRetriesThenGivesUpAsOutOfRange() {
        val c = ctl(maxAttempts = 1)
        c.connect(addr)
        await(c) { it is ConnState.Error }
        val e = c.state.value as ConnState.Error
        assertEquals(ErrorKind.OUT_OF_RANGE, e.kind)
        assertEquals(2, port.opened.size) // lần đầu + 1 lần thử lại, không hơn
    }

    @Test fun status133ReconnectsWithLimit() {
        val c = ctl(maxAttempts = 2)
        c.connect(addr); port.events!!.onLinkState(false, 133)
        assertTrue(c.state.value is ConnState.Reconnecting)
        await(c) { port.opened.size == 2 }
    }

    @Test fun authRequiredStopsWithoutRetry() {
        val c = ctl(); c.connect(addr); port.events!!.onLinkState(false, 5)
        val e = c.state.value as ConnState.Error
        assertEquals(ErrorKind.AUTH_REQUIRED, e.kind)
        Thread.sleep(150)
        assertEquals(1, port.opened.size)
        assertTrue(e.reason.contains("không ghép nối"))
    }

    @Test fun serviceDiscoveryFailureIsRetried() {
        val c = ctl(); c.connect(addr); port.events!!.onLinkState(true, 0); port.events!!.onMtu(247, 0)
        port.events!!.onServices(emptyList(), 129)
        assertTrue(c.state.value is ConnState.Reconnecting)
    }

    @Test fun discoverThatCannotStartFailsSession() {
        port.discoverOk = false
        val c = ctl(); c.connect(addr); port.events!!.onLinkState(true, 0); port.events!!.onMtu(247, 0)
        assertTrue(c.state.value is ConnState.Reconnecting)
    }

    @Test fun discoveryWatchdogFailsSession() {
        val c = ctl(connectTimeout = 5_000); c.connect(addr); port.events!!.onLinkState(true, 0); port.events!!.onMtu(247, 0)
        assertEquals(ConnState.DiscoveringServices, c.state.value)
        await(c) { it is ConnState.Reconnecting } // opTimeout 150 ms
    }

    @Test fun bluetoothTurnedOffMidSessionIsAnError() {
        val c = ctl(); toReady(c); port.events!!.onBluetoothState(false)
        assertEquals(ErrorKind.BLUETOOTH_OFF, (c.state.value as ConnState.Error).kind)
        Thread.sleep(100); assertEquals(1, port.opened.size)
    }

    @Test fun permissionDeniedAndBluetoothOffFromPortDoNotRetry() {
        val c = ctl(); port.openResult = PortOpen.Failed(ErrorKind.PERMISSION_DENIED, "thiếu quyền")
        c.connect(addr)
        assertEquals(ErrorKind.PERMISSION_DENIED, (c.state.value as ConnState.Error).kind)
        port.openResult = PortOpen.Failed(ErrorKind.BLUETOOTH_OFF, "tắt")
        c.connect(addr); assertEquals(ErrorKind.BLUETOOTH_OFF, (c.state.value as ConnState.Error).kind)
        Thread.sleep(100); assertEquals(2, port.opened.size)
    }

    @Test fun invalidAddressIsRejectedBeforeAnyGattCall() {
        val c = ctl(); c.connect("not-a-mac")
        assertEquals(ErrorKind.INVALID_ADDRESS, (c.state.value as ConnState.Error).kind)
        assertTrue(port.opened.isEmpty())
    }

    @Test fun userDisconnectIgnoresLateCallbacksAndClosesPort() {
        val c = ctl(); toReady(c); val before = port.closes
        c.disconnect()
        assertEquals(ConnState.Disconnected, c.state.value); assertTrue(port.closes > before)
        port.events!!.onLinkState(false, 133)
        assertEquals(ConnState.Disconnected, c.state.value)
        Thread.sleep(100); assertEquals(1, port.opened.size)
    }

    @Test fun subscribeNeedsReadyAndNotifyAndIsConfirmedByDescriptorWrite() {
        val c = ctl(); assertFalse(c.subscribe(TX))
        toReady(c)
        assertFalse(c.subscribe(RX))                 // không có NOTIFY/INDICATE
        assertFalse(c.subscribe("deadbeef-0000-1000-8000-00805f9b34fb")) // không tồn tại
        assertTrue(c.subscribe(TX.uppercase()))      // UUID không phân biệt hoa thường
        assertEquals(SubscriptionState.PENDING, c.subscriptions.value[TX])
        assertTrue(port.calls.contains("cccd:$TX:true"))
        port.events!!.onDescriptorWrite(TX, 0)
        assertEquals(SubscriptionState.ACTIVE, c.subscriptions.value[TX])
        assertTrue(c.unsubscribe(TX)); port.events!!.onDescriptorWrite(TX, 0)
        assertNull(c.subscriptions.value[TX])
    }

    @Test fun failedDescriptorWriteMarksFailedAndAuthIsNotBypassed() {
        val c = ctl(); toReady(c); c.subscribe(TX); port.events!!.onDescriptorWrite(TX, 15)
        assertEquals(SubscriptionState.FAILED, c.subscriptions.value[TX])
        assertTrue(c.state.value is ConnState.Ready) // lỗi ở một kênh không làm sập phiên
    }

    @Test fun subscriptionsAreRestoredAfterReconnect() {
        val c = ctl(); toReady(c); c.subscribe(TX); port.events!!.onDescriptorWrite(TX, 0)
        port.events!!.onLinkState(false, 8)
        assertTrue(c.state.value is ConnState.Reconnecting)
        await(c) { port.opened.size == 2 }
        port.events!!.onLinkState(true, 0); port.events!!.onMtu(247, 0); port.events!!.onServices(services, 0)
        assertEquals(SubscriptionState.PENDING, c.subscriptions.value[TX])
        assertEquals(2, port.calls.count { it == "cccd:$TX:true" })
    }

    @Test fun standardInfoReadIsAllowlisted() {
        val c = ctl(); toReady(c)
        assertTrue(c.readStandardInfo(DEVNAME))
        assertFalse(c.readStandardInfo(SERIAL))      // serial không nằm trong danh sách cho phép
        assertFalse(port.calls.contains("read:$SERIAL"))
        port.events!!.onRead(DEVNAME, "YCC-1234".toByteArray(), 0)
        assertEquals("YCC-1234", c.deviceInfo.value["Tên thiết bị (Device Name)"])
    }

    @Test fun notificationsBecomeRawFrames() = runBlocking {
        val c = ctl(); toReady(c)
        val got = CompletableDeferred<RawFrame>()
        val j = launch(Dispatchers.Unconfined) { c.frames.collect { got.complete(it) } }
        port.events!!.onNotification(TX, byteArrayOf(1, 2, 3))
        val f = withTimeout(1000) { got.await() }; j.cancel()
        assertArrayEquals(byteArrayOf(1, 2, 3), f.bytes); assertEquals(TX, f.charUuid)
    }

    @Test fun noWritePathExistsInPortInterface() {
        val names = GattPort::class.java.methods.map { it.name.lowercase() }
        assertTrue(names.none { it.contains("write") && !it.contains("notification") })
    }
}
