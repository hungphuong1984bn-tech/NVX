package vn.nvxconnect

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import vn.nvxconnect.TelemetryTestSupport.newRecorder
import vn.nvxconnect.bluetooth.*
import vn.nvxconnect.protocol.*
import vn.nvxconnect.telemetry.*
import java.util.Collections

/** Controller ghi sự kiện có cấu trúc đúng sự thật (thành công/thất bại/chưa kiểm tra), và vòng đời trường không bị nâng cấp sai. */
class ConnectionEventsAndLifecycleTest {
    private val addr = "AA:BB:CC:11:22:33"
    private val NUS = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"; private val TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"; private val RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    private val services = listOf(GattServiceInfo(NUS, listOf(GattCharInfo(RX, listOf("WRITE")), GattCharInfo(TX, listOf("NOTIFY")))))

    private class FakePort : GattPort {
        override var events: GattEvents? = null
        val calls = Collections.synchronizedList(mutableListOf<String>())
        override fun open(address: String): PortOpen = PortOpen.Started
        override fun requestMtu(mtu: Int) = true.also { calls += "mtu" }
        override fun discoverServices() = true.also { calls += "discover" }
        override fun readRssi() = true.also { calls += "rssi" }
        override fun setNotification(charUuid: String, enable: Boolean) = true.also { calls += "cccd:$charUuid:$enable" }
        override fun readCharacteristic(charUuid: String) = true.also { calls += "read:$charUuid" }
        override fun close() {}
    }
    private lateinit var scope: CoroutineScope; private lateinit var port: FakePort; private val events = Collections.synchronizedList(mutableListOf<ConnectionEvent>())
    @Before fun setUp() { scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); port = FakePort(); events.clear() }
    @After fun tearDown() { scope.cancel() }
    private fun ctl() = GattSessionController(port, scope, ReconnectPolicy(2, 20, 40), 150, 247, 60_000, 150, autoListen = { listOf(TX) }, eventSink = ConnectionEventSink { events += it })
    private fun toReady(c: GattSessionController) {
        c.connect(addr); port.events!!.onLinkState(true, 0); port.events!!.onMtu(247, 0); port.events!!.onServices(services, 0)
    }
    private fun kinds() = events.map { it.kind to it.outcome }

    @Test fun happyPathRecordsAttemptMtuDiscoveryAndPendingSubscriptionAsNotTested() {
        val c = ctl(); toReady(c)
        val k = kinds()
        assertEquals(EventKinds.CONNECT_ATTEMPT to EventOutcome.INFO, k.first())
        assertTrue(EventKinds.MTU to EventOutcome.SUCCESS in k); assertTrue(EventKinds.DISCOVERY to EventOutcome.SUCCESS in k)
        // Subscription mới ở mức PENDING: phải ghi là NOT_TESTED, chưa phải SUCCESS.
        assertTrue(EventKinds.SUBSCRIBE to EventOutcome.NOT_TESTED in k); assertFalse(EventKinds.SUBSCRIBE to EventOutcome.SUCCESS in k)
        assertEquals("services", "1", events.first { it.kind == EventKinds.DISCOVERY }.attrs["services"])
    }
    @Test fun subscriptionSuccessOnlyAfterDeviceConfirmsCccd() {
        val c = ctl(); toReady(c); port.events!!.onDescriptorWrite(TX, 0)
        assertTrue(EventKinds.SUBSCRIBE to EventOutcome.SUCCESS in kinds())
        assertEquals(LinkStage.SUBSCRIPTION_SUCCESS, LinkStages.evaluate(LinkStageInput(c.state.value, c.subscriptions.value, 0, false, 0)).stage)
    }
    @Test fun failedCccdIsRecordedAsFailure() {
        val c = ctl(); toReady(c); port.events!!.onDescriptorWrite(TX, 5)
        assertTrue(EventKinds.SUBSCRIBE to EventOutcome.FAILURE in kinds()); assertEquals(LinkStage.GATT_READY, LinkStages.evaluate(LinkStageInput(c.state.value, c.subscriptions.value, 0, false, 0)).stage)
    }
    @Test fun firstFrameEventOncePerGattSessionAndCarriesNoPayload() {
        val c = ctl(); toReady(c); port.events!!.onDescriptorWrite(TX, 0)
        repeat(20) { port.events!!.onNotification(TX, byteArrayOf(0x6B, 5, 0, 1, 0x21)) }
        val first = events.filter { it.kind == EventKinds.DATA_FIRST }; assertEquals(1, first.size)
        assertEquals("5", first[0].attrs["length"]); assertTrue(first[0].attrs.keys.none { it.contains("hex", true) || it.contains("payload", true) })
        assertEquals(1, first[0].epoch)
    }
    @Test fun linkLossWithGattStatusIsAFailureEventWithStatusAndUptime() {
        val c = ctl(); toReady(c); port.events!!.onDescriptorWrite(TX, 0); port.events!!.onNotification(TX, byteArrayOf(1, 2, 3))
        port.events!!.onLinkState(false, 19)
        val lost = events.first { it.kind == EventKinds.DISCONNECT }
        assertEquals(EventOutcome.FAILURE, lost.outcome); assertEquals(19, lost.gattStatus); assertEquals("1", lost.attrs["framesInSession"]); assertNotNull(lost.attrs["upMs"])
    }
    @Test fun authRequiredIsRecordedAsBondFailure() {
        val c = ctl(); c.connect(addr); port.events!!.onLinkState(false, 5)
        assertTrue(EventKinds.BOND to EventOutcome.FAILURE in kinds())
    }
    @Test fun userDisconnectIsInfoNotFailure() {
        val c = ctl(); toReady(c); c.disconnect()
        val d = events.last { it.kind == EventKinds.DISCONNECT }; assertEquals(EventOutcome.INFO, d.outcome)
    }
    @Test fun sinkThatThrowsCannotBreakTheGattSession() {
        val c = GattSessionController(port, scope, ReconnectPolicy(2, 20, 40), 150, 247, 60_000, 150, eventSink = ConnectionEventSink { throw IllegalStateException("đĩa đầy") })
        toReady(c); assertTrue(c.state.value is ConnState.Ready)
    }
    @Test fun passiveSurveyNeverWritesToVehicle() {
        val c = ctl(); toReady(c); port.events!!.onDescriptorWrite(TX, 0); repeat(5) { port.events!!.onNotification(TX, byteArrayOf(1)) }
        // Thao tác duy nhất được phép tới cổng: mtu, discover, rssi, CCCD trên TX. Không đọc/ghi characteristic ghi (RX).
        assertTrue(port.calls.all { it == "mtu" || it == "discover" || it == "rssi" || it == "cccd:$TX:true" })
        assertTrue(port.calls.none { it.contains(RX) })
        assertTrue(VehicleConnection::class.java.methods.map { it.name.lowercase() }.none { it.contains("write") || it.contains("send") })
    }
    @Test fun controllerWithoutSinkStillWorks() {
        val c = GattSessionController(port, scope, ReconnectPolicy(2, 20, 40), 150, 247, 60_000, 150); toReady(c); assertTrue(c.state.value is ConnState.Ready)
    }

    // ---------------- vòng đời trường ----------------
    private val addr2 = "AA:BB:CC:DD:EE:01"
    private fun twoSessions(): Pair<List<PayloadRecord>, List<Pair<Long, Int>>> {
        val r = newRecorder(); val s = ArrayList<Pair<Long, Int>>()
        s += TelemetryTestSupport.synthSession(r, addr2, listOf(800, 1500, 2300, 3100, 3900), 100_000)
        r.start("2"); s += TelemetryTestSupport.synthSession(r, addr2, listOf(800, 1500, 2300, 3100, 3900), 200_000)
        return r.snapshot() to s
    }
    private fun csv(series: List<Pair<Long, Int>>, grade: String, f: (Int) -> Int = { it }) = "time_ms,field,value,unit,source,grade,method\n" +
        series.filterIndexed { i, _ -> i % 3 == 0 }.joinToString("\n") { "${it.first},rpm,${f(it.second)},rpm,máy đo rời (test),$grade,đo kẹp" }
    private fun life(repo: ProtocolEvidenceRepository, recs: List<PayloadRecord>, now: Long) =
        FieldLifecycles.all(repo.evaluateAll(recs, now)) { repo.criteriaFor(it) }.associateBy { it.fieldId }

    @Test fun lifecycleWithoutDataNeverExceedsCandidateAndNothingIsShownAsCurrent() {
        val l = life(ProtocolEvidenceRepository(), emptyList(), 0)
        assertEquals(FieldLifecycle.CANDIDATE, l.getValue(FieldIds.RPM).lifecycle)         // có công thức khai báo nhưng chưa giải mã được mẫu thật
        for (id in listOf(FieldIds.ENGINE_TEMP, FieldIds.SPEED, FieldIds.CHARGING_V, FieldIds.ENGINE_STATUS, FieldIds.DTC)) assertEquals(id, FieldLifecycle.UNAVAILABLE, l.getValue(id).lifecycle)
        assertTrue(l.values.none { it.displayAsCurrent })
    }
    @Test fun decodedSamplesWithoutReferenceAreHypothesisNotVerifiedEvenIfChecksumPasses() {
        val (recs, _) = twoSessions()
        val l = life(ProtocolEvidenceRepository(), recs, recs.maxOf { it.timeMs })
        assertEquals(FieldLifecycle.DECODED_HYPOTHESIS, l.getValue(FieldIds.RPM).lifecycle); assertFalse(l.getValue(FieldIds.RPM).displayAsCurrent)
    }
    @Test fun independentReferenceAcrossTwoSessionsReachesVerifiedAndIsCurrent() {
        val (recs, series) = twoSessions(); val repo = ProtocolEvidenceRepository(); repo.importReference(csv(series, "independent"), 10)
        val s = life(repo, recs, recs.maxOf { it.timeMs }).getValue(FieldIds.RPM)
        assertEquals(FieldLifecycle.VERIFIED, s.lifecycle); assertTrue(s.displayAsCurrent); assertFalse(s.stale)
    }
    @Test fun clusterReferenceStopsAtCorrelated() {
        val (recs, series) = twoSessions(); val repo = ProtocolEvidenceRepository(); repo.importReference(csv(series, "cluster"), 10)
        val s = life(repo, recs, recs.maxOf { it.timeMs }).getValue(FieldIds.RPM)
        assertEquals(FieldLifecycle.CORRELATED, s.lifecycle); assertFalse(s.displayAsCurrent)
    }
    @Test fun staleVerifiedKeepsEvidenceLevelButIsNotCurrent() {
        val (recs, series) = twoSessions(); val repo = ProtocolEvidenceRepository(); repo.importReference(csv(series, "independent"), 10)
        val s = life(repo, recs, recs.maxOf { it.timeMs } + 60_000).getValue(FieldIds.RPM)
        assertEquals(FieldLifecycle.VERIFIED, s.lifecycle); assertTrue(s.stale); assertFalse(s.displayAsCurrent)
    }
    @Test fun uncorrelatedReferenceWithEnoughPairsRefutesHypothesisAsInvalid() {
        val (recs, series) = twoSessions(); val repo = ProtocolEvidenceRepository()
        // Tham chiếu "ngược" hoàn toàn với tín hiệu (giá trị đảo thứ tự) → r âm, đủ cặp → giả thuyết bị bác bỏ.
        val max = series.maxOf { it.second }; repo.importReference(csv(series, "independent") { max + 800 - it }, 10)
        val s = life(repo, recs, recs.maxOf { it.timeMs }).getValue(FieldIds.RPM)
        assertEquals(s.rationale, FieldLifecycle.INVALID, s.lifecycle); assertFalse(s.displayAsCurrent)
    }
    @Test fun tooFewPairsIsNotInvalidJustInsufficient() {
        val (recs, series) = twoSessions(); val repo = ProtocolEvidenceRepository()
        repo.importReference("time_ms,field,value,unit,source,grade,method\n${series[0].first},rpm,100000,rpm,x,independent,m\n${series[3].first},rpm,1,rpm,x,independent,m", 10)
        assertNotEquals(FieldLifecycle.INVALID, life(repo, recs, recs.maxOf { it.timeMs }).getValue(FieldIds.RPM).lifecycle)
    }

    // ---------------- khai báo bộ giải mã ----------------
    @Test fun declarationReportsSourceRecognitionFormulaAndHonestChecksumStatus() {
        val h = BuiltInHypotheses.RPM
        val noProof = DecoderDeclarations.from(h, null, 0)
        assertEquals(TelemetryTestSupport.NUS_NOTIFY, noProof.sourceCharUuid); assertTrue(noProof.recognition.contains("0x6B") && noProof.recognition.contains("59"))
        assertEquals(5, noProof.byteOffset); assertEquals(2, noProof.byteWidth); assertEquals(ByteOrderKind.BIG, noProof.byteOrder); assertFalse(noProof.signed)
        assertFalse("chưa thử khung nào → checksum chưa được coi là đã xác minh", noProof.checksumVerified)
        assertEquals("1", noProof.decoderVersion); assertTrue(noProof.evidenceBasis.contains("giả thuyết", true) || noProof.evidenceBasis.contains("Giả thuyết"))
        val ok = DecoderDeclarations.from(h, FrameProofs(100, 100, 100, 100), 100); assertTrue(ok.checksumVerified); assertEquals(0.0, ok.rejectedRatio, 0.0)
        val partial = DecoderDeclarations.from(h, FrameProofs(100, 90, 100, 97), 90); assertFalse(partial.checksumVerified); assertEquals(0.1, partial.rejectedRatio, 1e-9)
        assertTrue(partial.limits.contains("GIẢ THUYẾT"))
    }
}
