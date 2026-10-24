package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.auto.*
import vn.nvxconnect.bluetooth.ErrorKind
import vn.nvxconnect.bluetooth.GattServiceInfo
import vn.nvxconnect.navigation.Maneuver
import vn.nvxconnect.navigation.TurnInstruction
import vn.nvxconnect.protocol.*

/** Quy tắc "không hiển thị trạng thái cao khi chưa có sự kiện xác nhận", xác thực, adapter TFT và các rào chắn an toàn. */
class AuthAndStateTest {
    private val raw = VehicleLinkStatus(LinkLevel.RAW_DATA_ONLY, VehicleLinkStatus.RAW_ONLY, VehicleLinkStatus.NOT_VERIFIED, false)
    private val verified = VehicleLinkStatus(LinkLevel.VERIFIED, "CONNECTED — t", null, true)
    private val ready = ConnState.Ready(emptyList())

    private fun inp(conn: ConnState, bond: BondState = BondState.NONE, link: VehicleLinkStatus = raw, scanning: Boolean = false,
                    cand: Boolean = false, value: Boolean = false, fail: AutoFailure? = null) =
        AutoInputs(scanning, cand, conn, bond, link, value, fail, null)

    // ---------------------------------------------------------------- trạng thái
    @Test fun readyWithoutBondIsOnlyGattConnectedAndSaysRawOnly() {
        val s = AutoStatusResolver.resolve(inp(ready))
        assertEquals(AutoState.GATT_CONNECTED, s.state)
        assertTrue(s.detail!!.contains(VehicleLinkStatus.RAW_ONLY)); assertTrue(s.detail!!.contains(VehicleLinkStatus.NOT_VERIFIED))
    }

    @Test fun nothingBeforeReadyIsEverShownAsConnected() {
        listOf(ConnState.Connecting, ConnState.Connected, ConnState.DiscoveringServices, ConnState.Reconnecting(1, 5)).forEach {
            val s = AutoStatusResolver.resolve(inp(it, bond = BondState.BONDED))
            assertEquals(AutoState.CANDIDATE_FOUND, s.state)
            assertNotEquals(AutoState.GATT_CONNECTED, s.state); assertNotEquals(AutoState.BONDED, s.state); assertNotEquals(AutoState.AUTHENTICATED, s.state)
        }
    }

    @Test fun bondedNeedsBothBondAndGattReadyAndIsNotAuthenticated() {
        val s = AutoStatusResolver.resolve(inp(ready, BondState.BONDED))
        assertEquals(AutoState.BONDED, s.state)
        assertNotEquals(AutoState.AUTHENTICATED, s.state)   // BONDED không phải bằng chứng xác thực giao thức
        assertTrue(s.detail!!.contains("KHÔNG chứng minh"))
        assertNotEquals(AutoState.BONDED, AutoStatusResolver.resolve(inp(ConnState.Disconnected, BondState.BONDED)).state)
        assertEquals(AutoState.GATT_CONNECTED, AutoStatusResolver.resolve(inp(ready, BondState.BONDING)).state)
    }

    @Test fun authenticatedNeedsProtocolLevelEvidenceAndNeverComesFromBondAlone() {
        fun withAuth(bond: BondState, authenticated: Boolean, link: VehicleLinkStatus = raw) =
            AutoStatusResolver.resolve(AutoInputs(false, false, ready, bond, link, false, null, null, authenticated))
        assertEquals(AutoState.AUTHENTICATED, withAuth(BondState.NONE, authenticated = true).state)   // chỉ khi có bằng chứng lớp giao thức
        assertEquals(AutoState.AUTHENTICATED, withAuth(BondState.BONDED, authenticated = true).state)
        assertEquals(AutoState.BONDED, withAuth(BondState.BONDED, authenticated = false).state)
        // Xác thực rồi vẫn chưa phải đã xác minh giao thức/có dữ liệu.
        assertNotEquals(AutoState.PROTOCOL_VERIFIED, withAuth(BondState.BONDED, authenticated = true).state)
        assertEquals(AutoState.PROTOCOL_VERIFIED, withAuth(BondState.BONDED, authenticated = true, link = verified).state)
        // Không ở trạng thái Ready thì authenticated không có tác dụng.
        val off = AutoStatusResolver.resolve(AutoInputs(false, false, ConnState.Disconnected, BondState.BONDED, raw, false, null, null, true))
        assertEquals(AutoState.IDLE, off.state)
    }

    @Test fun suspectedExclusiveHolderIsConnectionBusyNotAFailureOfTheApp() {
        val y = AutoStatusResolver.resolve(inp(ConnState.Disconnected, fail = AutoFailure.EXCLUSIVE_HOLDER_SUSPECTED))
        assertEquals(AutoState.CONNECTION_BUSY, y.state); assertEquals(AutoFailure.EXCLUSIVE_HOLDER_SUSPECTED, y.failure)
        assertTrue(y.detail!!.contains("không chiếm"))
        // Lỗi GATT chung (133) cùng lúc vẫn được báo là bận, không phải THẤT BẠI chung chung.
        val e = AutoStatusResolver.resolve(inp(ConnState.Error(ErrorKind.TIMEOUT, "x"), fail = AutoFailure.EXCLUSIVE_HOLDER_SUSPECTED))
        assertEquals(AutoState.CONNECTION_BUSY, e.state)
        // Phiên đã Ready thì không bao giờ báo bận.
        assertEquals(AutoState.GATT_CONNECTED, AutoStatusResolver.resolve(inp(ready, fail = AutoFailure.EXCLUSIVE_HOLDER_SUSPECTED)).state)
    }

    @Test fun protocolVerifiedAndDataAvailableNeedAVerifiedAdapter() {
        assertEquals(AutoState.PROTOCOL_VERIFIED, AutoStatusResolver.resolve(inp(ready, link = verified)).state)
        assertEquals(AutoState.DATA_AVAILABLE, AutoStatusResolver.resolve(inp(ready, link = verified, value = true)).state)
        // Có "giá trị" nhưng không có adapter xác minh: không bao giờ DATA_AVAILABLE.
        assertEquals(AutoState.GATT_CONNECTED, AutoStatusResolver.resolve(inp(ready, link = raw, value = true)).state)
    }

    @Test fun errorsAndRunFailuresMapToFailed() {
        val a = AutoStatusResolver.resolve(inp(ConnState.Error(ErrorKind.AUTH_REQUIRED, "x")))
        assertEquals(AutoState.FAILED, a.state); assertEquals(AutoFailure.AUTH_REQUIRED, a.failure)
        assertEquals(AutoFailure.PERMISSION, AutoStatusResolver.resolve(inp(ConnState.Error(ErrorKind.PERMISSION_DENIED, "x"))).failure)
        assertEquals(AutoFailure.BLUETOOTH_OFF, AutoStatusResolver.resolve(inp(ConnState.Error(ErrorKind.BLUETOOTH_OFF, "x"))).failure)
        assertEquals(AutoFailure.TIMEOUT, AutoStatusResolver.resolve(inp(ConnState.Error(ErrorKind.OUT_OF_RANGE, "x"))).failure)
        val r = AutoStatusResolver.resolve(inp(ConnState.Disconnected, fail = AutoFailure.RETRIES_EXHAUSTED))
        assertEquals(AutoState.FAILED, r.state); assertEquals(AutoFailure.RETRIES_EXHAUSTED, r.failure)
    }

    @Test fun scanningAndIdleStates() {
        assertEquals(AutoState.SCANNING, AutoStatusResolver.resolve(inp(ConnState.Disconnected, scanning = true)).state)
        assertEquals(AutoState.CANDIDATE_FOUND, AutoStatusResolver.resolve(inp(ConnState.Disconnected, scanning = true, cand = true)).state)
        assertEquals(AutoState.IDLE, AutoStatusResolver.resolve(inp(ConnState.Disconnected)).state)
    }

    // ---------------------------------------------------------------- xác thực
    @Test fun authRequiredGivesPairingGuidanceAndNoBypass() {
        val a = AuthAssessor.assess(ConnState.Error(ErrorKind.AUTH_REQUIRED, "x"), BondState.NONE, null)
        assertEquals(AuthLevel.REQUIRED, a.level)
        val t = a.guidance.joinToString(" ")
        assertTrue(t.contains("Ghép đôi")); assertTrue(t.contains("Y-Connect")); assertTrue(t.contains("không đoán PIN"))
        assertFalse(a.linkAuthenticated)
    }

    @Test fun bondedIsLinkLevelOnlyAndSaysSo() {
        val a = AuthAssessor.assess(ready, BondState.BONDED, null)
        assertTrue(a.linkAuthenticated)
        assertTrue(a.guidance.joinToString(" ").contains("KHÔNG chứng minh"))
    }

    @Test fun rejectedPairingIsRecordedWithReasonAndGuidance() {
        val ev = BondEvent("AA:BB:CC:11:22:33", BondState.BONDING, BondState.NONE, 4, 0)
        val a = AuthAssessor.assess(ConnState.Error(ErrorKind.AUTH_REQUIRED, "x"), BondState.NONE, ev)
        assertEquals(AuthLevel.REJECTED, a.level)
        assertTrue(a.headline.contains("4")); assertTrue(a.guidance.isNotEmpty())
        assertFalse(a.linkAuthenticated)
    }

    @Test fun gattOpenWithoutBondIsNotClaimedAsAuthenticated() {
        val a = AuthAssessor.assess(ready, BondState.NONE, null)
        assertEquals(AuthLevel.NOT_REQUIRED_OBSERVED, a.level); assertFalse(a.linkAuthenticated)
        assertEquals(AuthLevel.PAIRING, AuthAssessor.assess(ready, BondState.BONDING, null).level)
        assertEquals(AuthLevel.UNKNOWN, AuthAssessor.assess(ConnState.Disconnected, BondState.NONE, null).level)
    }

    // ---------------------------------------------------------------- adapter TFT
    private val turn = TurnInstruction(Maneuver.TURN_LEFT, "Đường A", 120.0, null)

    private class FakeNav(override val evidence: NavProtocolEvidence, override val status: TftBridgeStatus = TftBridgeStatus.VERIFIED_READY) : NavigationProtocolAdapter {
        override val limitation = "test"
        override fun sendManeuver(instruction: TurnInstruction): TftSendResult = TftSendResult.Sent
    }

    @Test fun navRegistryIsEmptyAndRejectsEverythingByDefault() {
        val r = NavAdapterRegistry()
        assertEquals(0, r.count)
        assertEquals(TftBridgeStatus.UNSUPPORTED, r.resolve().status)
        assertTrue(r.resolve().sendManeuver(turn) is TftSendResult.Rejected)
    }

    @Test fun navRegistryRejectsAdapterWithoutDocumentationOrVehicleTest() {
        listOf(
            NavProtocolEvidence("a", null, "xe", "pass", true),
            NavProtocolEvidence("a", "tài liệu", null, "pass", true),
            NavProtocolEvidence("a", "tài liệu", "xe", null, true),
            NavProtocolEvidence("a", "tài liệu", "xe", "pass", false)
        ).forEach { e -> assertThrows(IllegalArgumentException::class.java) { NavAdapterRegistry().register(FakeNav(e)) } }
        assertThrows(IllegalArgumentException::class.java) {
            NavAdapterRegistry().register(FakeNav(NavProtocolEvidence("a", "d", "xe", "pass", true), TftBridgeStatus.NOT_VERIFIED))
        }
    }

    @Test fun navRegistryAcceptsFullyEvidencedAdapter() {
        val r = NavAdapterRegistry(); val a = FakeNav(NavProtocolEvidence("a", "doc-1", "xe-1", "pass", true))
        r.register(a)
        assertSame(a, r.resolve()); assertEquals(1, r.count)
    }

    // ---------------------------------------------------------------- rào chắn an toàn
    private val writeLike = Regex("(?i)^(write|send|createbond|setpin|pair|bond|replay|probe|inject)")

    @Test fun gattPortAndVehicleConnectionExposeNoWriteOrPairingOperations() {
        listOf(GattPort::class.java, VehicleConnection::class.java).forEach { c ->
            val bad = c.methods.map { it.name }.filter { writeLike.containsMatchIn(it) }
            assertTrue("${c.simpleName} có hàm ghi/ghép đôi: $bad", bad.isEmpty())
        }
    }

    @Test fun noClassInAutoPackageStoresSecrets() {
        // Không có trường nào tên pin/password/token/key trong mô hình dữ liệu của gói auto và hồ sơ đã lưu.
        val secret = Regex("(?i)(pin|passw|token|secret|passkey|^key$)")
        val classes = listOf(AutoStatus::class.java, AutoRun::class.java, AuthAssessment::class.java, BondEvent::class.java,
            Candidate::class.java, ScanSession::class.java, vn.nvxconnect.bluetooth.SavedDevice::class.java, NavProtocolEvidence::class.java)
        classes.forEach { c -> assertTrue("${c.simpleName}: ${c.declaredFields.map { it.name }}", c.declaredFields.none { secret.containsMatchIn(it.name) }) }
    }

    @Test fun verifiedStatusOnlyFromRegistry() {
        val empty = AdapterRegistry()
        val st = VehicleLinkStatus.resolve(ConnState.Ready(listOf(GattServiceInfo("u", emptyList()))), empty)
        assertFalse(st.protocolVerified); assertEquals(LinkLevel.RAW_DATA_ONLY, st.level)
    }
}
