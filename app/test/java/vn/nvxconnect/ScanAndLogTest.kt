package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.bluetooth.*
import vn.nvxconnect.protocol.*
import vn.nvxconnect.util.MiniJson
import vn.nvxconnect.util.MiniJson.list
import vn.nvxconnect.util.MiniJson.obj

class ScanAndLogTest {
    private fun adv(a: String, n: String?, rssi: Int = -60, conn: Boolean? = true, svc: List<String> = emptyList(), t: Long = 0) =
        AdvertRecord(a, n, rssi, svc, conn, t)

    // ---- AddressKind ----
    @Test fun addressKindFromTopBits() {
        assertEquals(AddressKind.PUBLIC, AddressKind.classify("B8:27:EB:00:00:01"))               // 10xxxxxx
        assertEquals(AddressKind.MAYBE_RESOLVABLE_PRIVATE, AddressKind.classify("4A:00:00:00:00:01")) // 01
        assertEquals(AddressKind.MAYBE_STATIC_RANDOM, AddressKind.classify("C1:00:00:00:00:01"))   // 11
        assertEquals(AddressKind.MAYBE_NON_RESOLVABLE, AddressKind.classify("00:1A:7D:DA:71:13"))  // 00
        assertEquals(AddressKind.INVALID, AddressKind.classify("xyz"))
        assertTrue(AddressKind.MAYBE_RESOLVABLE_PRIVATE.mayChange); assertFalse(AddressKind.PUBLIC.mayChange)
    }

    // ---- DeviceFilter ----
    private fun rows(vararg r: AdvertRecord): List<DeviceTable.Row> {
        val t = DeviceTable(staleMs = 1000); r.forEach { t.update(it) }; return t.rows(nowMs = 500)
    }
    @Test fun searchMatchesNameAddressUuidAndLabel() {
        val rs = rows(adv("4A:00:00:00:00:01", "YCC-123", svc = listOf("6e400001-b5a3-f393-e0a9-e50e24dcca9e")),
            adv("B8:27:EB:00:00:02", null, conn = false))
        fun f(q: DeviceQuery) = DeviceFilter.apply(rs, q, emptyList()).map { it.entry.record.address }
        assertEquals(2, f(DeviceQuery()).size)
        assertEquals(listOf("4A:00:00:00:00:01"), f(DeviceQuery(text = "ycc")))
        assertEquals(listOf("B8:27:EB:00:00:02"), f(DeviceQuery(text = "b8:27")))
        assertEquals(listOf("4A:00:00:00:00:01"), f(DeviceQuery(text = "nordic uart")))
        assertEquals(listOf("4A:00:00:00:00:01"), f(DeviceQuery(connectableOnly = true)))
        assertEquals(listOf("4A:00:00:00:00:01"), f(DeviceQuery(namedOnly = true)))
    }
    @Test fun devicesWithoutNameAreSupportedAndHideStaleWorks() {
        val t = DeviceTable(staleMs = 100); t.update(adv("B8:27:EB:00:00:02", null, t = 0))
        val r = t.rows(nowMs = 1000)
        assertEquals(1, r.size); assertTrue(r[0].stale)
        assertTrue(DeviceFilter.apply(r, DeviceQuery(hideStale = true), emptyList()).isEmpty())
    }
    @Test fun savedFilterMatchesByAddressOrNameWhenAddressMayRotate() {
        val saved = listOf(SavedDevice("4A:00:00:00:00:01", "YCC-123"))
        val rs = rows(adv("4B:11:11:11:11:11", "ycc-123"), adv("4A:00:00:00:00:01", null), adv("4C:00:00:00:00:09", "OTHER"))
        assertEquals(2, DeviceFilter.apply(rs, DeviceQuery(savedOnly = true), saved).size)
        // địa chỉ công khai thì KHÔNG khớp theo tên
        val pub = listOf(SavedDevice("B8:27:EB:00:00:01", "YCC-123"))
        assertEquals(0, DeviceFilter.apply(rows(adv("B8:27:EB:00:00:99", "YCC-123")), DeviceQuery(savedOnly = true), pub).size)
        assertFalse(SavedDeviceMatcher.matches(SavedDevice("4A:00:00:00:00:01", ""), adv("4B:00:00:00:00:02", "")))
    }
    @Test fun savedDeviceCodecRoundTripsAwkwardText() {
        val l = listOf(SavedDevice("AA:BB:CC:11:22:33", "Tên\tcó tab\nvà dòng mới \\", "Xe của tôi", listOf("u1", "u2"), 5L, true),
            SavedDevice("B8:27:EB:00:00:01", null))
        val back = SavedDeviceCodec.decode(SavedDeviceCodec.encode(l))
        assertEquals(l, back)
        assertTrue(SavedDeviceCodec.decode("garbage").isEmpty())
        val up = SavedDeviceCodec.upsert(l, SavedDevice("B8:27:EB:00:00:01", "N", preferred = true))
        assertEquals(1, up.count { it.preferred }); assertEquals(2, up.size)
        assertEquals(1, SavedDeviceCodec.remove(up, "b8:27:eb:00:00:01").size)
    }

    // ---- quyền / preflight ----
    @Test fun permissionsDifferByAndroidVersion() {
        assertEquals(listOf(BlePermissions.SCAN, BlePermissions.CONNECT), BlePermissions.forScan(31))
        assertEquals(listOf(BlePermissions.SCAN, BlePermissions.CONNECT), BlePermissions.forScan(34))
        assertEquals(listOf(BlePermissions.FINE_LOCATION), BlePermissions.forScan(26))
        assertEquals(listOf(BlePermissions.FINE_LOCATION), BlePermissions.forScan(30))
        assertTrue(BlePermissions.forConnect(30).isEmpty()); assertEquals(1, BlePermissions.forConnect(33).size)
    }
    @Test fun preflightReportsEveryBlocker() {
        assertTrue(BlePreflight.evaluate(33, true, true, emptyList(), false).isEmpty()) // 12+: không cần vị trí
        assertEquals(listOf(BleBlocker.LOCATION_SERVICES_OFF), BlePreflight.evaluate(29, true, true, emptyList(), false))
        assertEquals(listOf(BleBlocker.PERMISSION_MISSING, BleBlocker.BLUETOOTH_OFF), BlePreflight.evaluate(33, true, false, listOf("x"), true))
        assertEquals(listOf(BleBlocker.NO_BLE_HARDWARE), BlePreflight.evaluate(33, false, false, listOf("x"), false))
    }

    // ---- GattStatus ----
    @Test fun outOfRangeStatuses() { assertTrue(GattStatus.looksLikeOutOfRange(8)); assertFalse(GattStatus.looksLikeOutOfRange(5)) }

    // ---- xuất log ----
    private val entries = listOf(
        LogEntry(1000, LogKind.STATE, "Kết nối AA:BB:CC:11:22:33 xong", state = "READY"),
        LogEntry(2000, LogKind.FRAME, "notify", uuid = "6e400003-b5a3-f393-e0a9-e50e24dcca9e", hex = HexUtil.toHex("ABCDEFGH".toByteArray() + byteArrayOf(1))),
        LogEntry(3000, LogKind.ERROR, "=HYPERLINK(\"x\")\nxuống dòng \"quote\""))
    @Test fun jsonExportIsValidRedactedAndKeepsStructure() {
        val svc = mapOf("AA:BB:CC:11:22:33" to listOf(GattServiceInfo("s", listOf(GattCharInfo("c", listOf("NOTIFY"), listOf(GattDescriptorInfo("2902")))))))
        val ad = listOf(AdvertRecord("AA:BB:CC:11:22:33", "NVX-1234", -50, listOf("u"), true, 1, manufacturerData = mapOf(0x004C to "01 02")))
        val json = LogExporter.toJson(entries, ad, svc, "salt", 99, "0.3.0")
        val root = MiniJson.parse(json).obj()!!            // phải là JSON hợp lệ dù có xuống dòng, nháy kép
        assertEquals(3, root["events"].list()!!.size)
        assertFalse(json.contains("11:22:33")); assertFalse(json.contains("NVX-1234"))
        assertFalse(json.contains("41 42 43 44"))            // chuỗi ASCII dài trong gói bị che
        assertTrue(json.contains("2A 2A 2A 2A 2A 2A 2A 2A 01"))
        assertTrue(json.contains("2902")); assertEquals(true, root["redacted"])
        val raw = LogExporter.toJson(entries, ad, svc, "salt", 99, "0.3.0", redact = false)
        assertTrue(raw.contains("41 42 43 44")); assertTrue(raw.contains("NVX-1234"))
    }
    @Test fun csvExportEscapesAndNeutralisesFormulas() {
        val csv = LogExporter.toCsv(entries, "salt")
        assertFalse(csv.contains("11:22:33"))
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"x\"\")"))   // công thức bị vô hiệu hóa, nháy kép được nhân đôi
        assertTrue(csv.lines().first().startsWith("time_utc,time_ms,kind"))
        assertTrue(csv.contains("1970-01-01T00:00:01.000Z"))
    }
    @Test fun sessionBufferIsBounded() {
        val b = SessionLogBuffer(3); repeat(10) { b.add(LogEntry(it.toLong(), LogKind.INFO, "m$it")) }
        assertEquals(listOf(7L, 8L, 9L), b.snapshot().map { it.timeMs })
    }
    @Test fun hexRoundTrip() {
        assertArrayEquals(byteArrayOf(0, 0x7F, -1), HexUtil.fromHex("00 7F FF"))
        assertNull(HexUtil.fromHex("zz"))
    }

    // ---- trạng thái liên kết xe ----
    private val ready = ConnState.Ready(listOf(GattServiceInfo("s", emptyList())))
    @Test fun connectedLabelOnlyWhenReadyAndStillNotVerified() {
        val reg = AdapterRegistry()
        assertEquals("CHƯA KẾT NỐI", VehicleLinkStatus.resolve(ConnState.Disconnected, reg).headline)
        for (s in listOf(ConnState.Connecting, ConnState.Connected, ConnState.DiscoveringServices, ConnState.Reconnecting(1, 5))) {
            val st = VehicleLinkStatus.resolve(s, reg)
            assertFalse(st.headline.startsWith("CONNECTED")); assertEquals(LinkLevel.CONNECTING, st.level)
        }
        val r = VehicleLinkStatus.resolve(ready, reg)
        assertEquals("CONNECTED — RAW DATA ONLY", r.headline); assertEquals("VEHICLE PROTOCOL NOT VERIFIED", r.detail)
        assertFalse(r.protocolVerified)
        assertEquals(LinkLevel.ERROR, VehicleLinkStatus.resolve(ConnState.Error(ErrorKind.TIMEOUT, "x"), reg).level)
    }
    @Test fun verifiedOnlyWithEvidencedAdapter() {
        val reg = AdapterRegistry()
        reg.register(object : VehicleProtocolAdapter {
            override val evidence = ProtocolEvidence("test-adapter", "1", listOf("s"), "f", "u", null, listOf("01"), "xe-thu", "pass", true)
            override fun matches(services: List<GattServiceInfo>) = services.any { it.uuid == "s" }
            override fun decode(frame: RawFrame) = emptyList<DecodedItem>()
        })
        val st = VehicleLinkStatus.resolve(ready, reg)
        assertTrue(st.protocolVerified); assertEquals(LinkLevel.VERIFIED, st.level)
    }
    @Test fun tftBridgeRejectsEverything() {
        assertEquals(TftBridgeStatus.UNSUPPORTED, UnsupportedTftBridge.status)
        val r = UnsupportedTftBridge.sendManeuver(vn.nvxconnect.navigation.TurnInstruction(vn.nvxconnect.navigation.Maneuver.TURN_LEFT, "x", 10.0, null))
        assertTrue(r is TftSendResult.Rejected)
    }
    @Test fun standardInfoDecode() {
        assertEquals("87%", StandardInfo.decode("00002a19-0000-1000-8000-00805f9b34fb", byteArrayOf(87)))
        assertFalse(StandardInfo.isAllowed("00002a25-0000-1000-8000-00805f9b34fb"))
        assertFalse(StandardInfo.isAllowed("6e400002-b5a3-f393-e0a9-e50e24dcca9e"))
    }
}
