package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.bluetooth.BleBlocker
import vn.nvxconnect.helpcenter.*
import vn.nvxconnect.helpcenter.HelpContent.Id
import vn.nvxconnect.protocol.ConnState
import vn.nvxconnect.protocol.LinkLevel
import vn.nvxconnect.protocol.VehicleLinkStatus

class HelpProgressTest {
    private val store = InMemoryHelpProgressStore()
    private fun tracker() = HelpProgressTracker(store)
    private fun results(i: ChecklistInputs) = ConnectionChecklist.evaluate(i)
    private val btOk = emptyList<BleBlocker>()

    @Test fun ungatedGuideCanBeMarkedAndIsPersisted() {
        assertEquals(CompleteResult.Done, tracker().markComplete(Id.GETTING_STARTED))
        assertTrue(tracker().isCompleted(Id.GETTING_STARTED))
        assertEquals(setOf(Id.GETTING_STARTED), store.load())
    }

    @Test fun gatedGuideRefusedWithoutChecklistOrWhenStepNotPassed() {
        val t = tracker()
        assertTrue(t.markComplete(Id.PERMISSIONS) is CompleteResult.NotAllowed)
        val fail = results(ChecklistInputs(blockers = listOf(BleBlocker.PERMISSION_MISSING)))
        val r = t.markComplete(Id.PERMISSIONS, fail)
        assertTrue(r is CompleteResult.NotAllowed); assertTrue((r as CompleteResult.NotAllowed).reason.contains("Đã cấp quyền"))
        assertFalse(t.isCompleted(Id.PERMISSIONS)); assertTrue(store.load().isEmpty())
    }

    @Test fun gatedGuideAllowedWhenStepPasses() {
        val t = tracker()
        val ok = results(ChecklistInputs(blockers = btOk))
        assertEquals(CompleteResult.Done, t.markComplete(Id.PREPARE_PHONE, ok)); assertEquals(CompleteResult.Done, t.markComplete(Id.PERMISSIONS, ok))
        // ... nhưng bước kết nối vẫn chưa đạt nên bài kết nối vẫn bị từ chối
        assertTrue(t.markComplete(Id.CONNECT_STATES, ok) is CompleteResult.NotAllowed)
    }

    @Test fun referenceOnlyGuidesCannotBeCompleted() {
        for (id in listOf(Id.VEHICLE_DATA, Id.YCONNECT, Id.TFT_NAV, Id.NOT_FOUND, Id.LOST_NO_DATA))
            assertTrue(id, tracker().markComplete(id, results(ChecklistInputs(blockers = btOk))) is CompleteResult.NotAllowed)
    }

    @Test fun unknownIdRefusedAndStaleSavedIdsDroppedOnLoad() {
        assertTrue(tracker().markComplete("khong-co") is CompleteResult.NotAllowed)
        val t = HelpProgressTracker(InMemoryHelpProgressStore(setOf("bai-cu-da-xoa", Id.PRIVACY)))
        assertEquals(setOf(Id.PRIVACY), t.completedIds())
    }

    @Test fun ratioCountsOnlyTrackableGuidesAndResetClears() {
        val t = tracker()
        assertEquals(0.0, t.ratio(), 0.0)
        t.markComplete(Id.GETTING_STARTED); t.markComplete(Id.PRIVACY); t.markComplete(Id.EXPORT_DIAG); t.markComplete(Id.GPS_TRIPS)
        assertTrue(t.ratio() > 0.0 && t.ratio() < 1.0)
        assertEquals(HelpContent.guides.count { it.manualComplete }, t.trackableCount())
        t.reset(); assertEquals(0, t.completedCount()); assertTrue(store.load().isEmpty())
    }

    @Test fun progressSurvivesRecreationOfTrackerAndUnmarkWorks() {
        tracker().markComplete(Id.EXPORT_DIAG)
        val t2 = tracker(); assertTrue(t2.isCompleted(Id.EXPORT_DIAG))
        t2.unmark(Id.EXPORT_DIAG); assertFalse(tracker().isCompleted(Id.EXPORT_DIAG))
    }

    @Test fun codecRoundTripAndTolerance() {
        assertEquals(setOf("a", "b"), HelpProgressCodec.decode(HelpProgressCodec.encode(setOf("b", "a"))))
        assertEquals(emptySet<String>(), HelpProgressCodec.decode(null)); assertEquals(emptySet<String>(), HelpProgressCodec.decode(" , ,"))
    }

    // ---------------------------------------------------------------- quy trình kiểm tra
    private val rawLink = VehicleLinkStatus(LinkLevel.RAW_DATA_ONLY, VehicleLinkStatus.RAW_ONLY, VehicleLinkStatus.NOT_VERIFIED, false)
    private val okLink = VehicleLinkStatus(LinkLevel.VERIFIED, "CONNECTED — test", null, true)
    private fun st(r: List<StepResult>, s: CheckStep) = r.first { it.step == s }.status

    @Test fun nothingKnownMeansNothingPassed() {
        val r = results(ChecklistInputs())
        assertEquals(0, ConnectionChecklist.passedCount(r)); assertEquals(CheckStatus.PENDING, st(r, CheckStep.BLUETOOTH_AVAILABLE))
        assertEquals(CheckStatus.UNSUPPORTED, st(r, CheckStep.TFT_SUPPORT))
    }

    @Test fun bluetoothOffFailsStepOneAndBlocksTheRest() {
        val r = results(ChecklistInputs(blockers = listOf(BleBlocker.BLUETOOTH_OFF), deviceFound = true, conn = ConnState.Ready(emptyList())))
        assertEquals(CheckStatus.FAILED, st(r, CheckStep.BLUETOOTH_AVAILABLE))
        assertEquals(CheckStatus.PENDING, st(r, CheckStep.PERMISSIONS_GRANTED)); assertEquals(CheckStatus.PENDING, st(r, CheckStep.CONNECTED))
        assertEquals(CheckStep.BLUETOOTH_AVAILABLE, ConnectionChecklist.firstProblem(r)!!.step)
    }

    @Test fun missingPermissionFailsStepTwo() {
        val r = results(ChecklistInputs(blockers = listOf(BleBlocker.PERMISSION_MISSING)))
        assertEquals(CheckStatus.PASSED, st(r, CheckStep.BLUETOOTH_AVAILABLE)); assertEquals(CheckStatus.FAILED, st(r, CheckStep.PERMISSIONS_GRANTED))
        assertEquals(Id.PERMISSIONS, ConnectionChecklist.firstProblem(r)!!.guideId)
    }

    @Test fun connectionErrorFailsConnectedStepWithReason() {
        val r = results(ChecklistInputs(blockers = btOk, deviceFound = true,
            conn = ConnState.Error(vn.nvxconnect.bluetooth.ErrorKind.TIMEOUT, "hết 15 giây")))
        val c = r.first { it.step == CheckStep.CONNECTED }
        assertEquals(CheckStatus.FAILED, c.status); assertTrue(c.detail.contains("hết 15 giây"))
        assertEquals(CheckStatus.PENDING, st(r, CheckStep.SERVICES_DISCOVERED))
    }

    @Test fun gattReadyWithoutVerifiedProtocolNeverReachesProtocolOrData() {
        val r = results(ChecklistInputs(blockers = btOk, deviceFound = true, conn = ConnState.Ready(emptyList()), link = rawLink, verifiedValueSeen = true))
        assertEquals(CheckStatus.PASSED, st(r, CheckStep.SERVICES_DISCOVERED)); assertEquals(CheckStatus.FAILED, st(r, CheckStep.PROTOCOL_VERIFIED))
        assertEquals(CheckStatus.PENDING, st(r, CheckStep.VALID_DATA_RECEIVED)) // bị chặn dù cờ verifiedValueSeen = true
        assertEquals(CheckStep.PROTOCOL_VERIFIED, ConnectionChecklist.firstProblem(r)!!.step)
    }

    @Test fun onlyAFullVerifiedChainPassesEverythingAndTftStaysHonest() {
        val r = results(ChecklistInputs(blockers = btOk, deviceFound = true, conn = ConnState.Ready(emptyList()), link = okLink, verifiedValueSeen = true))
        assertNull(ConnectionChecklist.firstProblem(r)); assertEquals(7, ConnectionChecklist.passedCount(r))
        assertEquals(CheckStatus.UNSUPPORTED, st(r, CheckStep.TFT_SUPPORT)) // chuỗi xe đạt KHÔNG kéo theo TFT đạt
    }

    @Test fun tftStepPassesOnlyForVerifiedReadyBridge() {
        fun tft(s: vn.nvxconnect.protocol.TftBridgeStatus) = st(results(ChecklistInputs(tftStatus = s)), CheckStep.TFT_SUPPORT)
        assertEquals(CheckStatus.PASSED, tft(vn.nvxconnect.protocol.TftBridgeStatus.VERIFIED_READY))
        assertEquals(CheckStatus.PENDING, tft(vn.nvxconnect.protocol.TftBridgeStatus.NOT_VERIFIED))
        assertEquals(CheckStatus.UNSUPPORTED, tft(vn.nvxconnect.protocol.TftBridgeStatus.UNSUPPORTED))
    }

    @Test fun everyStepPointsToAnExistingGuide() = CheckStep.entries.forEach { assertNotNull(it.name, HelpContent.find(it.guideId)) }
}
