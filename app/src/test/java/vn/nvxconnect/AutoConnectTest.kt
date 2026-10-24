package vn.nvxconnect

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import vn.nvxconnect.auto.*
import vn.nvxconnect.bluetooth.*
import vn.nvxconnect.protocol.*

/** AutoConnectController với kết nối và nguồn quét giả: kiểm tra quyết định, không kiểm tra BLE thật. */
class AutoConnectTest {
    private val addr = "4A:00:00:00:00:01"
    private val profile = SavedDevice(addr, "YCC-1A2B", preferred = true)
    private lateinit var scope: CoroutineScope

    @Before fun setUp() { scope = CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    @After fun tearDown() { scope.cancel() }

    private class FakeConn(val onConnect: (FakeConn) -> Unit = {}) : VehicleConnection {
        val st = MutableStateFlow<ConnState>(ConnState.Disconnected)
        val connects = ArrayList<String>(); var disconnects = 0
        override val state: StateFlow<ConnState> = st
        override val frames: Flow<RawFrame> = emptyFlow()
        override val log: Flow<LogEntry> = emptyFlow()
        override val rssi = MutableStateFlow<Int?>(null)
        override val mtu = MutableStateFlow(23)
        override val subscriptions = MutableStateFlow<Map<String, SubscriptionState>>(emptyMap())
        override val deviceInfo = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun connect(address: String) { connects += address; st.value = ConnState.Connecting; onConnect(this) }
        override fun disconnect() { disconnects++; st.value = ConnState.Disconnected }
        override fun subscribe(charUuid: String) = false
        override fun unsubscribe(charUuid: String) = false
        override fun readStandardInfo(charUuid: String) = false
    }

    private fun hit(rssi: Int = -55, connectable: Boolean? = true, a: String = addr) = AdvertRecord(a, "YCC-1A2B", rssi, emptyList(), connectable, 1L)
    private val cfg = AutoConnectConfig(maxCycles = 3, scanWindowMs = 200, backoffBaseMs = 5, backoffCapMs = 10, connectGiveUpMs = 300)

    private fun controller(conn: FakeConn, cfg: AutoConnectConfig = this.cfg, scans: MutableList<Long> = ArrayList(), src: (Int) -> Flow<AdvertRecord>) =
        AutoConnectController(scope, conn, { t -> scans += t; src(scans.size) }, cfg)

    private fun wait(p: () -> Boolean) {
        val end = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < end) { if (p()) return; Thread.sleep(5) }
        fail("quá thời gian chờ; run=")
    }
    private fun AutoConnectController.waitPhase(ph: RunPhase) = wait { run.value.phase == ph }

    @Test fun connectsWhenMatchingDeviceIsSeen() {
        val conn = FakeConn { it.st.value = ConnState.Ready(emptyList()) }
        val c = controller(conn) { flowOf(hit()) }
        assertTrue(c.start(profile) { true })
        c.waitPhase(RunPhase.CONNECTED)
        assertEquals(listOf(addr), conn.connects)
    }

    @Test fun connectsToRotatedAddressOfSameProfile() {
        val conn = FakeConn { it.st.value = ConnState.Ready(emptyList()) }
        val c = controller(conn) { flowOf(hit(a = "5A:11:22:33:44:55")) }
        c.start(profile) { true }; c.waitPhase(RunPhase.CONNECTED)
        assertEquals(listOf("5A:11:22:33:44:55"), conn.connects)
    }

    @Test fun neverConnectsToUnrelatedDevices() {
        val conn = FakeConn()
        val other = AdvertRecord("B8:27:EB:00:00:09", "Headset", -40, emptyList(), true, 1L)
        val c = controller(conn) { flowOf(other, other) }
        c.start(profile) { true }; c.waitPhase(RunPhase.FAILED)
        assertEquals(AutoFailure.NOT_FOUND, c.run.value.failure)
        assertTrue(conn.connects.isEmpty())
    }

    @Test fun scansAreSpacedAndLimitedByMaxCycles() {
        val scans = ArrayList<Long>()
        val c = controller(FakeConn(), scans = scans) { emptyFlow() }
        c.start(profile) { true }; c.waitPhase(RunPhase.FAILED)
        assertEquals(3, scans.size)
        assertTrue(scans.all { it == 200L })
    }

    @Test fun authRequiredStopsImmediatelyWithoutRetry() {
        val conn = FakeConn { it.st.value = ConnState.Error(ErrorKind.AUTH_REQUIRED, "cần ghép đôi") }
        val c = controller(conn) { flowOf(hit()) }
        c.start(profile) { true }; c.waitPhase(RunPhase.FAILED)
        assertEquals(AutoFailure.AUTH_REQUIRED, c.run.value.failure)
        assertEquals(1, conn.connects.size)
    }

    @Test fun repeatedFailuresWithStrongSignalSuggestExclusiveHolderAndStop() {
        val conn = FakeConn { it.st.value = ConnState.Error(ErrorKind.OUT_OF_RANGE, "timeout") }
        val c = controller(conn) { flowOf(hit(rssi = -50)) }
        c.start(profile) { true }; c.waitPhase(RunPhase.FAILED)
        assertEquals(AutoFailure.EXCLUSIVE_HOLDER_SUSPECTED, c.run.value.failure)
        assertEquals("dừng sau số lần giới hạn, không chiếm kết nối liên tục", 2, conn.connects.size)
        assertTrue(c.run.value.message!!.contains("Y-Connect"))
        assertTrue(conn.disconnects >= 1)
    }

    @Test fun weakSignalFailuresAreRetriedUntilLimitThenReportedAsRetriesExhausted() {
        val conn = FakeConn { it.st.value = ConnState.Error(ErrorKind.OUT_OF_RANGE, "timeout") }
        val c = controller(conn) { flowOf(hit(rssi = -95)) }
        c.start(profile) { true }; c.waitPhase(RunPhase.FAILED)
        assertEquals(AutoFailure.RETRIES_EXHAUSTED, c.run.value.failure)
        assertEquals(3, conn.connects.size)
    }

    @Test fun advertisingAsNotConnectableTwiceSuggestsExclusiveHolderWithoutConnecting() {
        val conn = FakeConn()
        val c = controller(conn) { flowOf(hit(connectable = false)) }
        c.start(profile) { true }; c.waitPhase(RunPhase.FAILED)
        assertEquals(AutoFailure.EXCLUSIVE_HOLDER_SUSPECTED, c.run.value.failure)
        assertTrue(conn.connects.isEmpty())
    }

    @Test fun refusesToStartWhenAutoConnectIsDisabled() {
        val scans = ArrayList<Long>()
        val c = controller(FakeConn(), scans = scans) { flowOf(hit()) }
        assertFalse(c.start(profile) { false })
        assertTrue(scans.isEmpty())
    }

    @Test fun refusesToStartWhenGattSessionAlreadyActive() {
        val conn = FakeConn().also { it.st.value = ConnState.Connecting }
        val c = controller(conn) { flowOf(hit()) }
        assertFalse(c.start(profile) { true })
        assertTrue(conn.connects.isEmpty())
    }

    @Test fun settingTurnedOffMidRunStopsBeforeNextCycle() {
        var allowed = true
        val c = controller(FakeConn()) { allowed = false; emptyFlow() }
        c.start(profile) { allowed }
        wait { !c.isRunning }
        assertNull(c.run.value.failure)
        assertEquals(RunPhase.IDLE, c.run.value.phase)
    }

    @Test fun permissionErrorFromScanStopsWithoutRetry() {
        val scans = ArrayList<Long>()
        val c = controller(FakeConn(), scans = scans) { flow { throw BleException(ErrorKind.PERMISSION_DENIED, "thiếu quyền") } }
        c.start(profile) { true }; c.waitPhase(RunPhase.FAILED)
        assertEquals(AutoFailure.PERMISSION, c.run.value.failure)
        assertEquals(1, scans.size)
    }

    @Test fun bluetoothOffStopsWithoutRetry() {
        val c = controller(FakeConn()) { flow { throw BleException(ErrorKind.BLUETOOTH_OFF, "tắt") } }
        c.start(profile) { true }; c.waitPhase(RunPhase.FAILED)
        assertEquals(AutoFailure.BLUETOOTH_OFF, c.run.value.failure)
    }

    @Test fun startingTwiceRunsOnlyOneLoop() {
        val scans = ArrayList<Long>()
        val c = controller(FakeConn(), cfg.copy(scanWindowMs = 150, maxCycles = 1), scans) { flow { delay(100) } }
        assertTrue(c.start(profile) { true })
        assertFalse(c.start(profile) { true })
        c.waitPhase(RunPhase.FAILED)
        assertEquals(1, scans.size)
    }

    @Test fun emergencyStopCancelsScanAndClosesGatt() {
        val conn = FakeConn()
        val c = controller(conn, cfg.copy(scanWindowMs = 60_000)) { flow { delay(60_000) } }
        c.start(profile) { true }
        wait { c.run.value.phase == RunPhase.SCANNING }
        c.emergencyStop()
        wait { !c.isRunning }
        assertEquals(RunPhase.IDLE, c.run.value.phase)
        assertTrue(conn.disconnects >= 1)
        assertEquals(ConnState.Disconnected, conn.state.value)
    }

    @Test fun backoffDoublesAndCaps() {
        val c = AutoConnectConfig(backoffBaseMs = 1000, backoffCapMs = 3000)
        assertEquals(1000L, c.backoff(1)); assertEquals(2000L, c.backoff(2)); assertEquals(3000L, c.backoff(3)); assertEquals(3000L, c.backoff(9))
    }
}
