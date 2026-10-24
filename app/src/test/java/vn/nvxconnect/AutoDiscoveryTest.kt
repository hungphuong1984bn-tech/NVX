package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.auto.*
import vn.nvxconnect.bluetooth.AdvertRecord
import vn.nvxconnect.bluetooth.SavedDevice
import vn.nvxconnect.bluetooth.SavedDeviceCodec

/** Xếp hạng ứng viên theo phiên xe tắt/bật và so khớp hồ sơ nhiều tín hiệu. Dữ liệu là giả lập cho test, không phải dữ liệu xe. */
class AutoDiscoveryTest {
    private val NUS = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    private val PUB = "B8:27:EB:00:00:01"      // 0xB8 = 10xxxxxx -> công khai
    private val PUB2 = "B8:27:EB:00:00:02"
    private fun rnd(n: Int) = "4A:00:00:00:00:%02X".format(n)   // 0x4A = 01xxxxxx -> có thể đổi
    private fun rnd2(n: Int) = "5A:00:00:00:00:%02X".format(n)

    private fun adv(a: String, n: String?, rssi: Int = -60, conn: Boolean? = true, svc: List<String> = emptyList(), mfg: Map<Int, String> = emptyMap(), t: Long = 0) =
        AdvertRecord(a, n, rssi, svc, conn, t, manufacturerData = mfg)

    private fun session(id: Int, l: SessionLabel, vararg r: AdvertRecord) = ScanSession(id, l, id * 1000L, r.toList())

    @Test fun deviceThatAppearsOnlyWhenVehicleOnRanksFirst() {
        val phone = adv(PUB, "Galaxy S")
        val buds = adv(PUB2, "Buds")
        val bike = { n: Int, t: Long -> adv(rnd(n), "YCC-1A2B", -55, svc = listOf(NUS), t = t) }
        val s = listOf(
            session(1, SessionLabel.VEHICLE_OFF, phone, buds, phone, buds),
            session(2, SessionLabel.VEHICLE_ON, phone, buds, bike(1, 10), bike(1, 20), bike(1, 30)),
            session(3, SessionLabel.VEHICLE_ON, phone, buds, bike(2, 40), bike(2, 50), bike(2, 60)))
        val r = CandidateRanker.rank(s, emptyList())
        assertEquals("YCC-1A2B", r[0].displayName)
        assertEquals(Confidence.CIRCUMSTANTIAL, r[0].confidence)
        assertEquals(2, r[0].addresses.size)               // địa chỉ đổi giữa hai phiên nhưng vẫn là một ứng viên
        assertTrue(r.drop(1).none { it.confidence == Confidence.CIRCUMSTANTIAL })
        assertTrue(r.first { it.displayName == "Galaxy S" }.evidence.any { it.kind == EvidenceKind.ON_AND_OFF })
    }

    @Test fun withoutOffAndOnSessionsItAsksForMoreEvidence() {
        val r = CandidateRanker.rank(listOf(session(1, SessionLabel.VEHICLE_ON, adv(rnd(1), "YCC-1A2B", svc = listOf(NUS)))), emptyList())
        assertNotEquals(Confidence.CIRCUMSTANTIAL, r[0].confidence)
        assertTrue(r[0].nextStep!!.contains("TẮT"))
        assertFalse(r[0].isLikelyEnough)
    }

    @Test fun yamahaLookingNameAloneNeverRaisesConfidence() {
        val r = CandidateRanker.rank(listOf(session(1, SessionLabel.UNLABELED, adv(PUB, "Yamaha NVX"))), emptyList())
        assertEquals(Confidence.INSUFFICIENT, r[0].confidence)
        assertFalse(r[0].isLikelyEnough)
        assertNotNull(r[0].nextStep)
    }

    @Test fun noConfidenceLevelClaimsToBeConfirmedYamaha() {
        // Mọi mức tin cậy có thể có: không mức nào nói "đã xác nhận Yamaha".
        Confidence.values().forEach { assertFalse(it.title.contains("Yamaha", ignoreCase = true)) }
    }

    @Test fun deviceSeenOnlyWhenOffIsPenalised() {
        val s = listOf(session(1, SessionLabel.VEHICLE_OFF, adv(PUB, "Tivi")), session(2, SessionLabel.VEHICLE_ON, adv(PUB2, "Xe", -50), adv(PUB2, "Xe", -50)))
        val r = CandidateRanker.rank(s, emptyList())
        assertEquals("Xe", r[0].displayName)
        assertTrue(r.first { it.displayName == "Tivi" }.evidence.any { it.kind == EvidenceKind.OFF_ONLY })
    }

    @Test fun changingAdvertisedNameIsMergedBySignature() {
        val m = mapOf(0x0A5C to "01 02")
        val s = listOf(
            session(1, SessionLabel.VEHICLE_OFF, adv(PUB, "Galaxy S")),
            session(2, SessionLabel.VEHICLE_ON, adv(rnd(1), "Alpha", svc = listOf(NUS), mfg = m)),
            session(3, SessionLabel.VEHICLE_ON, adv(rnd2(9), "Bravo", svc = listOf(NUS), mfg = m)))
        val r = CandidateRanker.rank(s, emptyList()).filter { it.latest.advertisedServiceUuids.isNotEmpty() }
        assertEquals(1, r.size)
        assertEquals(2, r[0].sightingsOn)
        assertTrue(r[0].evidence.any { it.kind == EvidenceKind.NAME_CHANGES })
    }

    @Test fun nameSuffixVariantsShareAKey() {
        assertEquals(DeviceKey.normalizedName("YCC-1A2B"), DeviceKey.normalizedName("ycc-77"))
        assertNotEquals(DeviceKey.normalizedName("NVX"), DeviceKey.normalizedName("Buds"))
        assertNull(DeviceKey.normalizedName("  "))
    }

    @Test fun savedProfileIsUserConfirmedNotYamahaConfirmed() {
        val saved = listOf(SavedDevice(rnd(1), "YCC-1A2B", serviceUuids = listOf(NUS)))
        val r = CandidateRanker.rank(listOf(session(1, SessionLabel.UNLABELED, adv(rnd2(7), "YCC-9F", svc = listOf(NUS)))), saved)
        assertEquals(Confidence.USER_CONFIRMED, r[0].confidence)
    }

    // ---------------------------------------------------------------- ProfileMatcher
    @Test fun publicAddressMustMatchExactly() {
        val p = SavedDevice(PUB, "YCC-1", serviceUuids = listOf(NUS))
        assertTrue(ProfileMatcher.match(p, adv(PUB.lowercase(), null)).matched)
        assertFalse(ProfileMatcher.match(p, adv(PUB2, "YCC-1", svc = listOf(NUS))).matched)
    }

    @Test fun rotatingAddressNeedsTwoIndependentSignalsWhenProfileHasThem() {
        val p = SavedDevice(rnd(1), "YCC-1A2B", serviceUuids = listOf(NUS), manufacturerIds = listOf(0x0A5C))
        assertFalse("tên một mình không đủ", ProfileMatcher.match(p, adv(rnd2(2), "YCC-1A2B")).matched)
        assertTrue(ProfileMatcher.match(p, adv(rnd2(2), "YCC-1A2B", svc = listOf(NUS))).matched)
        assertTrue(ProfileMatcher.match(p, adv(rnd2(2), "Khác", svc = listOf(NUS), mfg = mapOf(0x0A5C to "00"))).matched)
        assertFalse(ProfileMatcher.match(p, adv(rnd2(2), "Khác", svc = listOf(NUS))).matched)
    }

    @Test fun nameOnlyProfileFallsBackToNameButGenericServicesDoNotCount() {
        val p = SavedDevice(rnd(1), "YCC-1A2B")
        assertTrue(ProfileMatcher.match(p, adv(rnd2(2), "ycc-9999")).matched)
        val q = SavedDevice(rnd(1), null, serviceUuids = listOf("00001800-0000-1000-8000-00805f9b34fb"))
        assertFalse(ProfileMatcher.match(q, adv(rnd2(2), "x", svc = listOf("00001800-0000-1000-8000-00805f9b34fb"))).matched)
    }

    // ---------------------------------------------------------------- lưu trữ tương thích ngược
    @Test fun legacySixFieldSavedDeviceStillDecodes() {
        val legacy = "AA:BB:CC:11:22:33\tTên\tBí danh\tu1,u2\t5\t1"
        val d = SavedDeviceCodec.decode(legacy).single()
        assertEquals("Tên", d.name); assertTrue(d.preferred); assertEquals(emptyList<Int>(), d.manufacturerIds)
    }

    @Test fun manufacturerIdsRoundTrip() {
        val l = listOf(SavedDevice("AA:BB:CC:11:22:33", "n", manufacturerIds = listOf(1, 0x0A5C)))
        assertEquals(l, SavedDeviceCodec.decode(SavedDeviceCodec.encode(l)))
    }
}
