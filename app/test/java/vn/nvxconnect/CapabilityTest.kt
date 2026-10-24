package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.bluetooth.GattServiceInfo
import vn.nvxconnect.capability.*
import vn.nvxconnect.domain.DataSource
import vn.nvxconnect.protocol.*
import vn.nvxconnect.util.MiniJson

/** Danh mục chức năng xe/app, đối chiếu và báo cáo. Adapter dưới đây là GIẢ, chỉ để thử logic; không phải giao thức xe. */
class CapabilityTest {
    private val NOW = 100_000L
    private class FakeAdapter(declared: Set<VehicleParam>?, secret: String = "x") : VehicleProtocolAdapter {
        override val evidence = ProtocolEvidence("fake", "1.0", listOf("svc-uuid"), "f", "u", null, listOf("01"), secret, "pass", true)
        override val supportedParams: Set<VehicleParam>? = declared
        override fun matches(services: List<GattServiceInfo>) = true
        override fun decode(frame: RawFrame) = emptyList<DecodedItem>()
    }
    private fun tele(vararg v: Pair<VehicleParam, Long>): VehicleTelemetry {
        val hub = TelemetryHub()
        hub.ingest(v.map { (p, t) -> DecodedItem.Value(p, 42.0, "u", t, "fake") }, verified = true)
        return hub.state.value
    }
    private fun ev(a: VehicleProtocolAdapter? = null, t: VehicleTelemetry = VehicleTelemetry(), speed: SpeedCrossCheck.Match? = null,
                   tft: TftBridgeStatus = TftBridgeStatus.UNSUPPORTED) =
        VehicleEvidenceInputs(a, t, NOW, speed, null, tft, "TFT không hỗ trợ")
    private fun cap(l: List<VehicleCapability>, id: String) = l.first { it.id == id }

    // ------------------------------------------------------------ danh mục xe
    @Test fun registryCoversTheElevenGroupsWithUniqueIds() {
        val d = VehicleCapabilityRegistry.defs
        assertEquals(11, d.size); assertEquals(d.size, d.map { it.id }.toSet().size)
        assertEquals("veh.speed", VehicleCapabilityRegistry.idOf(VehicleParam.SPEED_KMH))
    }

    @Test fun withoutEvidenceEverythingIsUnknownAndInventsNothing() {
        val l = VehicleCapabilityRegistry.evaluate(ev())
        l.filter { it.id !in setOf("veh.navigation_data", "veh.tft_control") }.forEach {
            assertEquals(it.id, CapabilityStatus.UNKNOWN, it.status)
            assertNull(it.dataType); assertNull(it.unit); assertNull(it.method); assertNull(it.freshnessMs)
            assertTrue(it.serviceRefs.isEmpty()); assertFalse(it.verified)
        }
        assertTrue(l.none { it.status == CapabilityStatus.AVAILABLE || it.status == CapabilityStatus.OBSERVED })
    }

    @Test fun speedMatchIsOnlyAHypothesisAndOthersStayUnknown() {
        val m = SpeedCrossCheck.Match("6e400003", 3, SpeedCrossCheck.Encoding.U16_LE, 0.99, 2.0, 1.0, 60, 80.0)
        val l = VehicleCapabilityRegistry.evaluate(ev(speed = m))
        val s = cap(l, "veh.speed")
        assertEquals(CapabilityStatus.HYPOTHESIS, s.status); assertFalse(s.verified)
        assertTrue(s.serviceRefs.isEmpty()); assertTrue(s.evidence.contains("CHƯA xác minh"))
        assertEquals(CapabilityStatus.UNKNOWN, cap(l, "veh.rpm").status)
    }

    @Test fun verifiedAdapterWithFreshValueIsAvailableWithFreshness() {
        val l = VehicleCapabilityRegistry.evaluate(ev(FakeAdapter(setOf(VehicleParam.SPEED_KMH)), tele(VehicleParam.SPEED_KMH to NOW - 1000)))
        val s = cap(l, "veh.speed")
        assertEquals(CapabilityStatus.AVAILABLE, s.status); assertTrue(s.verified)
        assertEquals(listOf("svc-uuid"), s.serviceRefs); assertEquals("u", s.unit); assertEquals(AcquisitionMethod.NOTIFY, s.method)
        assertEquals(1000L, s.freshnessMs)
    }

    @Test fun staleValueBecomesObservedNotAvailable() {
        val l = VehicleCapabilityRegistry.evaluate(ev(FakeAdapter(null), tele(VehicleParam.FUEL_LEVEL to NOW - 20_000)))
        assertEquals(CapabilityStatus.OBSERVED, cap(l, "veh.fuel_level").status)
        assertEquals(20_000L, cap(l, "veh.fuel_level").freshnessMs)
    }

    @Test fun unsupportedOnlyWhenVerifiedAdapterDeclaresSupportedSetWithoutIt() {
        val declared = VehicleCapabilityRegistry.evaluate(ev(FakeAdapter(setOf(VehicleParam.SPEED_KMH))))
        assertEquals(CapabilityStatus.UNSUPPORTED, cap(declared, "veh.rpm").status)
        assertEquals(CapabilityStatus.UNKNOWN, cap(declared, "veh.speed").status)   // khai báo có, nhưng chưa nhận giá trị
        val silent = VehicleCapabilityRegistry.evaluate(ev(FakeAdapter(null)))
        assertEquals(CapabilityStatus.UNKNOWN, cap(silent, "veh.rpm").status)       // adapter không khai báo: không suy ra "không hỗ trợ"
    }

    @Test fun tftCapabilitiesFollowTheBridgeStatus() {
        val un = VehicleCapabilityRegistry.evaluate(ev())
        assertEquals(CapabilityStatus.UNSUPPORTED, cap(un, "veh.navigation_data").status)
        assertEquals(CapabilityStatus.UNSUPPORTED, cap(un, "veh.tft_control").status)
        val nv = VehicleCapabilityRegistry.evaluate(ev(tft = TftBridgeStatus.NOT_VERIFIED))
        assertEquals(CapabilityStatus.HYPOTHESIS, cap(nv, "veh.navigation_data").status)
        val ok = VehicleCapabilityRegistry.evaluate(ev(tft = TftBridgeStatus.VERIFIED_READY))
        assertEquals(CapabilityStatus.AVAILABLE, cap(ok, "veh.navigation_data").status)
        // Cầu nối chỉ đường đã xác minh không chứng minh điều khiển TFT nói chung.
        assertEquals(CapabilityStatus.UNKNOWN, cap(ok, "veh.tft_control").status)
    }

    // ------------------------------------------------------------ danh mục app
    @Test fun appCatalogIsConsistent() {
        val a = AppCapabilityRegistry.all
        assertEquals(a.size, a.map { it.id }.toSet().size)
        val vehIds = VehicleCapabilityRegistry.defs.map { it.id }.toSet()
        a.mapNotNull { it.consumes }.forEach { assertTrue(it, it in vehIds) }
        a.filter { it.kind == AppKind.VEHICLE_DATA_CONSUMER }.forEach { assertNotNull(it.id, it.consumes); assertFalse(it.id, it.worksWithoutVehicle) }
        // Chức năng chỉ cần điện thoại phải chạy khi không có xe và không đòi thiết bị ngoài.
        a.filter { it.kind == AppKind.PHONE_ONLY }.forEach { assertTrue(it.id, it.worksWithoutVehicle); assertFalse(it.id, it.needsExternalDevice); assertNull(it.consumes) }
    }

    @Test fun testClaimsMatchRealTestClassesAndNothingClaimsOnDevice() {
        AppCapabilityRegistry.all.forEach { c ->
            assertTrue(c.id, c.testLevel != TestLevel.ON_DEVICE)   // chưa có chức năng nào được thử trên máy thật
            if (c.testLevel == TestLevel.NONE) assertTrue(c.id, c.testRefs.isEmpty()) else assertTrue(c.id, c.testRefs.isNotEmpty())
            c.testRefs.forEach { Class.forName("vn.nvxconnect.$it") }   // ném lỗi nếu lớp test không tồn tại
            if (!c.implemented) assertTrue(c.id, c.testLevel == TestLevel.NONE || c.testLevel == TestLevel.JVM_UNIT)
        }
    }

    @Test fun knownGapsAreDeclaredAsNotImplemented() {
        listOf("app.connection_lost_alert", "app.tft_navigation", "app.tft_control", "app.show_vehicle_trip").forEach {
            assertFalse(it, AppCapabilityRegistry.byId(it)!!.implemented)
        }
        assertEquals(DataKind.SAMPLE, AppCapabilityRegistry.byId("app.demo_data")!!.dataKind)
    }

    // ------------------------------------------------------------ đối chiếu
    private fun v(id: String, st: CapabilityStatus) = VehicleCapability(id, id, CapabilitySource.VEHICLE, emptyList(), null, null, null, st, "e", NOW, "t", null, null)
    private fun a(id: String, consumes: String?, impl: Boolean, kind: AppKind = AppKind.VEHICLE_DATA_CONSUMER, offline: Boolean = false) =
        AppCapability(id, id, kind, impl, TestLevel.NONE, emptyList(), "", emptyList(), false, false, !offline, DataKind.NONE, offline, consumes)
    private fun one(vs: CapabilityStatus, impl: Boolean, withApp: Boolean = true) =
        CapabilityMatcher.match(listOf(v("x", vs)), if (withApp) listOf(a("ax", "x", impl)) else emptyList()).first()

    @Test fun fiveCasesOfTheSpec() {
        assertEquals(Integration.CAN_INTEGRATE, one(CapabilityStatus.AVAILABLE, true).integration)        // 1
        assertEquals(Integration.AWAITING_ADAPTER, one(CapabilityStatus.UNKNOWN, true).integration)       // 2
        assertEquals(Integration.AWAITING_ADAPTER, one(CapabilityStatus.HYPOTHESIS, true).integration)   // giả thuyết ≠ đã xác minh
        assertEquals(Integration.NEEDS_APP_WORK, one(CapabilityStatus.AVAILABLE, false).integration)      // 3
        assertEquals(Integration.NEEDS_APP_WORK, one(CapabilityStatus.OBSERVED, true, withApp = false).integration)
        assertEquals(Integration.UNKNOWN, one(CapabilityStatus.UNKNOWN, false).integration)               // 4
        assertEquals(Integration.UNKNOWN, one(CapabilityStatus.UNKNOWN, true, withApp = false).integration)
        assertEquals(Integration.BLOCKED, one(CapabilityStatus.UNSUPPORTED, true).integration)            // mở rộng
        val phone = CapabilityMatcher.match(emptyList(), listOf(a("gps", null, true, AppKind.PHONE_ONLY, offline = true))).first()
        assertEquals(Integration.PHONE_INDEPENDENT, phone.integration)                                    // 5
    }

    @Test fun phoneOnlyRowsDoNotDependOnVehicleEvidence() {
        val apps = AppCapabilityRegistry.all
        fun phoneRows(l: List<VehicleCapability>) = CapabilityMatcher.match(l, apps).filter { it.integration == Integration.PHONE_INDEPENDENT }.map { it.function to it.missing }
        val none = phoneRows(VehicleCapabilityRegistry.evaluate(ev()))
        val all = phoneRows(VehicleCapabilityRegistry.defs.map { v(it.id, CapabilityStatus.AVAILABLE) })
        assertTrue(none.isNotEmpty()); assertEquals(none, all)
    }

    @Test fun defaultReportClaimsNoIntegrationWithoutVerifiedEvidence() {
        val r = CapabilityReport.build(VehicleCapabilityRegistry.evaluate(ev()), NOW, "t")
        val veh = r.matches.filter { it.vehicleCapabilityId != null }
        assertEquals(11, veh.size)
        assertTrue(veh.none { it.integration == Integration.CAN_INTEGRATE || it.integration == Integration.NEEDS_APP_WORK })
        assertTrue(r.unsupported.any { it.integration == Integration.BLOCKED })
        assertTrue(r.unsupported.any { it.integration == Integration.AWAITING_ADAPTER })
    }

    // ------------------------------------------------------------ xuất báo cáo
    @Test fun exportsAreValidAndLeakNoVehicleSecrets() {
        val l = VehicleCapabilityRegistry.evaluate(ev(FakeAdapter(setOf(VehicleParam.SPEED_KMH), secret = "VIN-SECRET-123"), tele(VehicleParam.SPEED_KMH to NOW)))
        val r = CapabilityReport.build(l, NOW, "t", adapters = listOf("fake (1.0)"))
        val json = r.toJson()
        val o = MiniJson.parse(json) as Map<*, *>
        assertEquals(11, (o["vehicleCapabilities"] as List<*>).size)
        assertEquals(AppCapabilityRegistry.all.size, (o["appCapabilities"] as List<*>).size)
        assertEquals(r.matches.size, (o["matches"] as List<*>).size)
        listOf(json, r.toText(), r.toCsv()).forEach { assertFalse(it.contains("VIN-SECRET-123")) }
        assertTrue(r.toText().contains("fake (1.0)"))
        assertTrue(CapabilityReport.build(VehicleCapabilityRegistry.evaluate(ev()), NOW, "t").toText().contains("KHÔNG CÓ"))
    }

    @Test fun csvHasHeaderAndNeutralisesFormulas() {
        val row = MatchRow("=HYPERLINK(\"x\")", null, listOf("a"), "s", "p", Integration.UNKNOWN, listOf("m"), "n")
        val r = CapabilityReport(NOW, "t", emptyList(), emptyList(), listOf(row), emptyList(), emptyList())
        val lines = r.toCsv().trim().lines()
        assertTrue(lines[0].startsWith("function,vehicle_capability"))
        assertTrue(lines[1].startsWith("\"'=HYPERLINK"))
    }

    // ------------------------------------------------------------ hồ sơ giao thức
    @Test fun noBuiltInProfileIsVerifiedForNvxAndEachNamesItsSource() {
        val p = ProtocolProfileCatalog.builtIn
        assertTrue(p.isNotEmpty()); assertTrue(ProtocolProfileCatalog.verifiedForNvx().isEmpty())
        p.forEach {
            assertEquals(NvxCompatibility.UNKNOWN, it.nvxCompatibility)
            assertTrue(it.sourceUrl.startsWith("https://")); assertTrue(it.sourceLicense.isNotBlank()); assertTrue(it.retrievedOn.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
            assertTrue(it.compatibilityEvidence.isNotBlank()); assertTrue(it.usageNote.isNotBlank())
        }
        assertTrue(p.first { it.id == "ref.pillion.navilite" }.transport.contains("RFCOMM"))
        val custom = p.first().copy(nvxCompatibility = NvxCompatibility.VERIFIED)
        assertEquals(listOf(custom), ProtocolProfileCatalog.verifiedForNvx(listOf(custom) + p.drop(1)))
    }

    @Test fun adapterRegistryExposesEvidencesOnlyForRegisteredAdapters() {
        val reg = AdapterRegistry(); assertTrue(reg.evidences().isEmpty())
        reg.register(FakeAdapter(null)); assertEquals(listOf("fake"), reg.evidences().map { it.id })
    }

    @Test fun dataSourceEnumUntouched() { assertEquals(6, DataSource.values().size) }
}
