package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.navigation.Maneuver
import vn.nvxconnect.navigation.TurnInstruction
import vn.nvxconnect.protocol.*
import vn.nvxconnect.tftintegration.*

class TftIntegrationTest {
    private val turn = TurnInstruction(Maneuver.TURN_LEFT, "Đường A", 120.0, null)
    private val rawLink = VehicleLinkStatus(LinkLevel.RAW_DATA_ONLY, VehicleLinkStatus.RAW_ONLY, VehicleLinkStatus.NOT_VERIFIED, false)
    private val okLink = VehicleLinkStatus(LinkLevel.VERIFIED, "CONNECTED — t", null, true)
    private val ready = ConnState.Ready(emptyList())

    private class FakeAdapter(val confirm: Boolean = false, val reject: Boolean = false) : NavigationProtocolAdapter {
        var sent = 0
        override val evidence = NavProtocolEvidence("fake", "doc-123", "NVX test", "pass", true)
        override val status = TftBridgeStatus.VERIFIED_READY
        override val limitation = ""
        override fun sendManeuver(instruction: TurnInstruction): TftSendResult { sent++; return if (reject) TftSendResult.Rejected("từ chối") else TftSendResult.Sent }
        override fun displayConfirmed() = confirm
    }

    private fun inputs(conn: ConnState = ready, link: VehicleLinkStatus = rawLink, seen: Boolean = false, bridge: TftNavigationBridge = UnsupportedTftBridge,
                       nav: Boolean = false, out: TftDisplayPipeline.Outcome? = null) = TftIntegrationInputs(conn, link, seen, bridge, nav, out)

    @Test fun defaultStateReachesOnlyBluetoothAndSaysUnsupported() {
        val r = TftIntegrationManager.evaluate(inputs())
        assertTrue(r.reached(TftLevel.BLUETOOTH_CONNECTED))
        listOf(TftLevel.VEHICLE_PROTOCOL_VERIFIED, TftLevel.DATA_READ, TftLevel.TFT_INTEGRATED, TftLevel.NAV_RUNNING_ON_PHONE, TftLevel.NAV_CONFIRMED_ON_TFT)
            .forEach { assertFalse(it.name, r.reached(it)) }
        assertEquals("TFT chưa được hỗ trợ", r.headline); assertNotNull(r.limitation)
        assertTrue(r.levels.first { it.level == TftLevel.TFT_INTEGRATED }.detail.startsWith("KHÔNG HỖ TRỢ"))
    }

    @Test fun phoneNavigationDoesNotImplyTft() {
        val r = TftIntegrationManager.evaluate(inputs(nav = true))
        assertTrue(r.reached(TftLevel.NAV_RUNNING_ON_PHONE)); assertFalse(r.reached(TftLevel.TFT_INTEGRATED)); assertFalse(r.reached(TftLevel.NAV_CONFIRMED_ON_TFT))
        assertTrue(r.headline.contains("điện thoại"))
    }

    @Test fun disconnectedIsNotBluetoothConnected() {
        assertFalse(TftIntegrationManager.evaluate(inputs(conn = ConnState.Disconnected)).reached(TftLevel.BLUETOOTH_CONNECTED))
        assertFalse(TftIntegrationManager.evaluate(inputs(conn = ConnState.Connecting)).reached(TftLevel.BLUETOOTH_CONNECTED))
    }

    @Test fun dataReadNeedsVerifiedProtocolEvenIfValueFlagIsSet() {
        assertFalse(TftIntegrationManager.evaluate(inputs(seen = true)).reached(TftLevel.DATA_READ))
        assertTrue(TftIntegrationManager.evaluate(inputs(link = okLink, seen = true)).reached(TftLevel.DATA_READ))
    }

    @Test fun verifiedBridgeAloneIsNotIntegrationWithoutVerifiedVehicleLink() {
        assertFalse(TftIntegrationManager.evaluate(inputs(bridge = FakeAdapter())).reached(TftLevel.TFT_INTEGRATED))
        assertTrue(TftIntegrationManager.evaluate(inputs(link = okLink, bridge = FakeAdapter())).reached(TftLevel.TFT_INTEGRATED))
    }

    @Test fun sentIsNotConfirmedUnlessTheBridgeConfirms() {
        val sent = TftDisplayPipeline.Outcome.Sent
        val a = TftIntegrationManager.evaluate(inputs(link = okLink, bridge = FakeAdapter(confirm = false), nav = true, out = sent))
        assertTrue(a.reached(TftLevel.TFT_INTEGRATED)); assertFalse(a.reached(TftLevel.NAV_CONFIRMED_ON_TFT))
        assertTrue(a.levels.last().detail.contains("CHƯA có xác nhận"))
        val b = TftIntegrationManager.evaluate(inputs(link = okLink, bridge = FakeAdapter(confirm = true), nav = true, out = sent))
        assertTrue(b.reached(TftLevel.NAV_CONFIRMED_ON_TFT))
        // có xác nhận nhưng chưa gửi gì thì cũng không được coi là đã hiển thị
        assertFalse(TftIntegrationManager.evaluate(inputs(link = okLink, bridge = FakeAdapter(confirm = true), nav = true, out = null)).reached(TftLevel.NAV_CONFIRMED_ON_TFT))
    }

    @Test fun builtInBridgeNeverConfirms() { assertFalse(UnsupportedTftBridge.displayConfirmed()) }

    // ---------------------------------------------------------------- NavigationIntegrationAdapter
    @Test fun noRegisteredAdapterMeansNotSupportedAndNothingSent() {
        val a = NavigationIntegrationAdapter(NavAdapterRegistry())
        assertEquals(TftBridgeStatus.UNSUPPORTED, a.status())
        val r = a.deliver(turn, linkVerified = true)
        assertTrue(r is NavDeliveryResult.NotSupported); assertTrue(a.limitation().contains("Chưa hỗ trợ"))
    }

    @Test fun registryRefusesAdaptersWithoutEvidence() {
        val reg = NavAdapterRegistry()
        val weak = object : NavigationProtocolAdapter {
            override val evidence = NavProtocolEvidence("weak", null, "xe", "ok", true)
            override val status = TftBridgeStatus.VERIFIED_READY; override val limitation = ""
            override fun sendManeuver(instruction: TurnInstruction) = TftSendResult.Sent
        }
        assertThrows(IllegalArgumentException::class.java) { reg.register(weak) }
        assertEquals(0, reg.count)
    }

    @Test fun verifiedAdapterIsOnlyCalledWhenVehicleLinkIsVerified() {
        val fake = FakeAdapter(); val reg = NavAdapterRegistry().apply { register(fake) }; val a = NavigationIntegrationAdapter(reg)
        assertTrue(a.deliver(turn, linkVerified = false) is NavDeliveryResult.NotReady); assertEquals(0, fake.sent)
        val r = a.deliver(turn, linkVerified = true)
        assertEquals(NavDeliveryResult.Sent(confirmedOnTft = false), r); assertEquals(1, fake.sent)
    }

    @Test fun confirmationFlowsThroughOnlyWhenBridgeSaysSo() {
        val reg = NavAdapterRegistry().apply { register(FakeAdapter(confirm = true)) }
        assertEquals(NavDeliveryResult.Sent(true), NavigationIntegrationAdapter(reg).deliver(turn, true))
    }

    @Test fun rejectionIsReportedHonestly() {
        val reg = NavAdapterRegistry().apply { register(FakeAdapter(reject = true)) }
        val r = NavigationIntegrationAdapter(reg).deliver(turn, true)
        assertTrue(r is NavDeliveryResult.Rejected); assertEquals("từ chối", (r as NavDeliveryResult.Rejected).reason)
    }
}
