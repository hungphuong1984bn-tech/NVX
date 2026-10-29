package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.bluetooth.GattCharInfo
import vn.nvxconnect.bluetooth.GattServiceInfo
import vn.nvxconnect.protocol.*
import vn.nvxconnect.telemetry.EvidenceGrade

class LinkStageAndAdapterProfileTest {
    private val NUS = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"; private val TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    private val svc = listOf(GattServiceInfo(NUS, listOf(GattCharInfo(TX, listOf("NOTIFY")))))
    private fun st(state: ConnState, subs: Map<String, SubscriptionState> = emptyMap(), frames: Long = 0, adapter: Boolean = false, verified: Int = 0) =
        LinkStages.evaluate(LinkStageInput(state, subs, frames, adapter, verified))

    // ---- 5 mức liên kết ----
    @Test fun stagesAreStrictlyOrderedAndNeverSkipped() {
        assertEquals(LinkStage.DISCONNECTED, st(ConnState.Disconnected).stage)
        assertEquals(LinkStage.DISCONNECTED, st(ConnState.Connecting).stage)
        assertEquals(LinkStage.CONNECTED, st(ConnState.Connected).stage)
        assertEquals(LinkStage.CONNECTED, st(ConnState.DiscoveringServices).stage)
        assertEquals(LinkStage.GATT_READY, st(ConnState.Ready(svc)).stage)
        assertEquals(LinkStage.SUBSCRIPTION_SUCCESS, st(ConnState.Ready(svc), mapOf(TX to SubscriptionState.ACTIVE)).stage)
        assertEquals(LinkStage.DATA_RECEIVED, st(ConnState.Ready(svc), mapOf(TX to SubscriptionState.ACTIVE), frames = 10).stage)
        assertEquals(LinkStage.PROTOCOL_VERIFIED, st(ConnState.Ready(svc), mapOf(TX to SubscriptionState.ACTIVE), 10, adapter = true, verified = 1).stage)
    }
    @Test fun pendingOrFailedSubscriptionIsNotSuccess() {
        assertEquals(LinkStage.GATT_READY, st(ConnState.Ready(svc), mapOf(TX to SubscriptionState.PENDING)).stage)
        val f = st(ConnState.Ready(svc), mapOf(TX to SubscriptionState.FAILED)); assertEquals(LinkStage.GATT_READY, f.stage); assertTrue(f.missingForNext!!.contains("THẤT BẠI"))
    }
    @Test fun rawFramesAloneNeverReachProtocolVerified() {
        val s = st(ConnState.Ready(svc), mapOf(TX to SubscriptionState.ACTIVE), frames = 100_000)
        assertEquals(LinkStage.DATA_RECEIVED, s.stage); assertNotNull(s.missingForNext)
        assertEquals(LinkStage.DATA_RECEIVED, st(ConnState.Ready(svc), mapOf(TX to SubscriptionState.ACTIVE), 5, adapter = true, verified = 0).stage)   // adapter mà chưa có trường VERIFIED
        assertEquals(LinkStage.DATA_RECEIVED, st(ConnState.Ready(svc), mapOf(TX to SubscriptionState.ACTIVE), 5, adapter = false, verified = 3).stage)    // trường VERIFIED mà không có adapter
    }
    @Test fun dataWithoutReadyCannotCountEvenIfFramesCounterIsStale() {
        assertEquals(LinkStage.CONNECTED, st(ConnState.Connected, mapOf(TX to SubscriptionState.ACTIVE), frames = 50, adapter = true, verified = 2).stage)
        assertEquals(LinkStage.DISCONNECTED, st(ConnState.Reconnecting(1, 0), mapOf(TX to SubscriptionState.ACTIVE), 50, true, 2).stage)
    }

    // ---- hồ sơ tương thích adapter ----
    private fun ev(verified: Boolean = true, tested: String? = "NVX 155 V3 SP #1", result: String? = "ok", services: List<String> = listOf(NUS), samples: List<String> = listOf("6B 05 …")) =
        ProtocolEvidence("t", "1", services, "fmt", "u16be", "SUM8", samples, tested, result, verified)
    private fun adapter(e: ProtocolEvidence = ev()) = object : VehicleProtocolAdapter {
        override val evidence = e
        override fun matches(services: List<GattServiceInfo>) = services.any { it.uuid.equals(NUS, true) }
        override fun decode(frame: RawFrame) = listOf<DecodedItem>(DecodedItem.Unknown(frame))
    }
    private fun proof(sessions: Int = 2, grade: EvidenceGrade = EvidenceGrade.INDEPENDENT_MEASUREMENT, fp: String = "abc123") = FieldProof("rpm", fp, sessions, grade, 1L)
    private fun profile(fields: List<FieldProof> = listOf(proof()), chars: List<String> = listOf(TX), fw: String = "fw-1.2", model: String = "NVX 155 V3 SP") =
        AdapterCompatibilityProfile(model, "2025 VN", "CCU-test", fw, listOf(NUS), chars, "p1", fields)

    @Test fun registryRejectsAdapterWithoutProfile() {
        val reg = AdapterRegistry(requireProfile = true)
        assertThrows(IllegalArgumentException::class.java) { reg.registerProfiled(adapter(), null) }
        assertThrows(IllegalArgumentException::class.java) { reg.register(adapter()) }     // đường cũ bị chặn ở registry bản chạy thật
        assertEquals(0, reg.count); assertNull(reg.find(svc)); assertEquals(1, reg.rejectionLog().size)
    }
    @Test fun nordicUartAndFrameTypesAloneAreNotEnough() {
        // Manh mối (service Nordic UART, khung 0x5A/0x6B, SUM8) nhưng chưa có trường nào VERIFIED → từ chối.
        val reg = AdapterRegistry(requireProfile = true)
        val e = assertThrows(IllegalArgumentException::class.java) { reg.registerProfiled(adapter(), profile(fields = emptyList())) }
        assertTrue(e.message!!.contains("VERIFIED")); assertEquals(0, reg.count)
    }
    @Test fun registryRejectsInsufficientFieldEvidence() {
        val reg = AdapterRegistry(requireProfile = true)
        for (bad in listOf(proof(sessions = 1), proof(grade = EvidenceGrade.VEHICLE_CLUSTER), proof(grade = EvidenceGrade.SAMPLE_INFERENCE), proof(fp = "")))
            assertThrows(IllegalArgumentException::class.java) { reg.registerProfiled(adapter(), profile(fields = listOf(bad))) }
        assertEquals(0, reg.count); assertEquals(4, reg.rejectionLog().size)
    }
    @Test fun registryRejectsIncompleteProfileAndUnverifiedEvidence() {
        val reg = AdapterRegistry(requireProfile = true)
        assertThrows(IllegalArgumentException::class.java) { reg.registerProfiled(adapter(), profile(fw = "")) }
        assertThrows(IllegalArgumentException::class.java) { reg.registerProfiled(adapter(), profile(model = " ")) }
        assertThrows(IllegalArgumentException::class.java) { reg.registerProfiled(adapter(), profile(chars = emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { reg.registerProfiled(adapter(ev(verified = false)), profile()) }
        assertThrows(IllegalArgumentException::class.java) { reg.registerProfiled(adapter(ev(tested = null)), profile()) }
        assertThrows(IllegalArgumentException::class.java) { reg.registerProfiled(adapter(ev(services = listOf("other"))), profile()) }   // service hồ sơ ≠ bằng chứng
        assertEquals(0, reg.count)
    }
    @Test fun validProfiledAdapterActivatesOnlyWhenDeclaredCharacteristicIsPresent() {
        val reg = AdapterRegistry(requireProfile = true); val a = adapter(); reg.registerProfiled(a, profile())
        assertEquals(1, reg.count); assertSame(a, reg.find(svc)); assertNotNull(reg.profileOf(a))
        val noChar = listOf(GattServiceInfo(NUS, listOf(GattCharInfo("6e400002-b5a3-f393-e0a9-e50e24dcca9e", listOf("WRITE")))))
        assertNull("service giống nhưng thiếu characteristic nguồn → không kích hoạt", reg.find(noChar))
        assertTrue(reg.decode(noChar, RawFrame(0, TX, byteArrayOf(1))).single() is DecodedItem.Unknown)
    }
    @Test fun emptyRegistryAlwaysReturnsRawUnknown() {
        val reg = AdapterRegistry(requireProfile = true)
        assertTrue(reg.decode(svc, RawFrame(0, TX, byteArrayOf(0x6B, 5))).single() is DecodedItem.Unknown)
        assertEquals(LinkLevel.RAW_DATA_ONLY, VehicleLinkStatus.resolve(ConnState.Ready(svc), reg).level)
    }
    @Test fun legacyRegistryStillWorksForExistingBehavior() {
        val reg = AdapterRegistry(); reg.register(adapter()); assertNotNull(reg.find(svc))
    }
}
