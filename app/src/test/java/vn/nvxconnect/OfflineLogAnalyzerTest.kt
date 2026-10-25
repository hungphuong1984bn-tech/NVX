package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.TelemetryTestSupport.NUS_NOTIFY
import vn.nvxconnect.TelemetryTestSupport.NUS_SERVICE
import vn.nvxconnect.TelemetryTestSupport.newRecorder
import vn.nvxconnect.bluetooth.*
import vn.nvxconnect.telemetry.*

class OfflineLogAnalyzerTest {
    private val addr = "AA:BB:CC:DD:EE:01"
    private val gatt = mapOf("dev-01" to listOf(GattServiceInfo(NUS_SERVICE, listOf(
        GattCharInfo("6e400002-b5a3-f393-e0a9-e50e24dcca9e", listOf("WRITE", "WRITE_NO_RESP")), GattCharInfo(NUS_NOTIFY, listOf("NOTIFY"))))))
    private fun reimport(r: RawFrameRecorder, withGatt: Boolean = true) = TelemetryLogImport.parse(TelemetryLogExporter.toJson(r, if (withGatt) gatt else emptyMap(), "t", 9, redact = false))

    @Test fun realFramesFormatEvidenceChecksumAllTypes() {
        val r = newRecorder()
        var t = 0L
        for (f in listOf(TelemetryTestSupport.REAL_HELLO, TelemetryTestSupport.REAL_INFO, TelemetryTestSupport.REAL_BLOCK, TelemetryTestSupport.REAL_IDLE,
            TelemetryTestSupport.REAL_IDLE_NEXT, TelemetryTestSupport.REAL_RUNNING, TelemetryTestSupport.REAL_PARAMS)) r.record(addr, PayloadSource.NOTIFICATION, NUS_SERVICE, NUS_NOTIFY, f, t++ * 80)
        val rep = OfflineLogAnalyzer.analyze(listOf(reimport(r)))
        val groups = rep.streams.single().groups.associateBy { it.typeByte }
        assertEquals(setOf(0x5A, 0x59, 0x6A, 0x6B, 0x6C), groups.keys)
        for (g in groups.values) { assertEquals("SUM8(toàn gói) ≡ 0", g.checksumScheme); assertEquals(g.count, g.checksumPass); assertEquals(0, g.checksumFail) }
        assertEquals(59, groups.getValue(0x6B).length); assertEquals(3, groups.getValue(0x6B).count); assertEquals(143, groups.getValue(0x6C).length)
    }

    @Test fun counterDetectionAndMissingFrameEstimateOnSyntheticStream() {
        val r = newRecorder()
        // SYNTHETIC: 40 khung, bỏ 3 khung liên tiếp (bộ đếm 20,21,22)
        var t = 0L
        for (c in 0 until 40) { if (c in 20..22) continue; r.record(addr, PayloadSource.NOTIFICATION, NUS_SERVICE, NUS_NOTIFY, TelemetryTestSupport.synth6B(1500 + (c * 37) % 400, c), t); t += 83 }
        val g = OfflineLogAnalyzer.analyze(listOf(reimport(r))).streams.single().groups.single()
        assertEquals(57, g.counterPos); assertEquals(3, g.estimatedMissing)
        assertTrue(g.counterOk < g.counterSteps && g.counterOk.toDouble() / g.counterSteps >= 0.9)
        assertTrue(g.variablePositions.containsAll(listOf(5, 6, 57, 58))); assertTrue(g.constantPositions.containsKey(0))
    }

    @Test fun badChecksumFramesCountedAsFailures() {
        val r = newRecorder()
        for (c in 0 until 20) r.record(addr, PayloadSource.NOTIFICATION, NUS_SERVICE, NUS_NOTIFY, TelemetryTestSupport.synth6B(1000 + c, c, badChecksum = c % 10 == 0), c * 80L)
        val g = OfflineLogAnalyzer.analyze(listOf(reimport(r))).streams.single().groups.single()
        assertEquals(18, g.checksumPass); assertEquals(2, g.checksumFail)
    }

    @Test fun timingStatsDetectLongGapAndRate() {
        val r = newRecorder()
        val times = (0 until 30).map { it * 80L } + listOf(30 * 80L + 5000, 30 * 80L + 5080)
        times.forEachIndexed { i, t -> r.record(addr, PayloadSource.NOTIFICATION, NUS_SERVICE, NUS_NOTIFY, TelemetryTestSupport.synth6B(1000, i), t) }
        val s = OfflineLogAnalyzer.analyze(listOf(reimport(r))).streams.single()
        assertEquals(80L, s.medianIntervalMs); assertEquals(1, s.longGaps); assertTrue(s.maxGapMs!! >= 5000)
    }

    @Test fun sessionComparisonSeparatesStableSessionSpecificAndVarying() {
        val r = newRecorder()
        for (s in 1..2) {
            if (s == 2) r.start("phiên 2")
            for (c in 0 until 12) {
                val f = TelemetryTestSupport.synth6B(1000 + c * 10, c); f[20] = (0x10 * s).toByte()
                var sum = 0; for (i in 0 until 58) sum += f[i].toInt() and 0xFF; f[58] = ((-sum) and 0xFF).toByte()
                r.record(addr, PayloadSource.NOTIFICATION, NUS_SERVICE, NUS_NOTIFY, f, s * 10_000L + c * 80)
            }
        }
        val cmp = OfflineLogAnalyzer.analyze(listOf(reimport(r))).comparisons.single()
        assertEquals(2, cmp.sessions.size); assertTrue(20 in cmp.sessionSpecific); assertTrue(0 in cmp.stable); assertTrue(5 in cmp.varying || 6 in cmp.varying)
    }

    @Test fun subscribedButNoDataIsFlaggedNotTreatedAsData() {
        val r = newRecorder()
        r.event(addr, ConnEventType.CONNECTED); r.event(addr, ConnEventType.DISCOVERY_DONE); r.event(addr, ConnEventType.SUBSCRIBE_OK, NUS_NOTIFY)
        val rep = OfflineLogAnalyzer.analyze(listOf(reimport(r)))
        val row = rep.rows.first { it.charUuid == NUS_NOTIFY }
        assertFalse(row.receivedPayload); assertEquals(0, row.payloadCount); assertTrue(row.nextStep, row.nextStep.contains("Đã đăng ký nhưng 0 gói"))
        assertTrue(rep.findings.any { it.statement.contains("đăng ký thành công không phải là có dữ liệu") })
        assertTrue(rep.findings.any { it.kind == FindingKind.NOT_PROVEN && it.subject == "log" })
        assertEquals(DeviceStage.CONNECTED, rep.devices.first { it.deviceId == "dev-01" }.stage)
    }

    @Test fun deviceWithDataIsTransmittedButNeverVehicleLinkedByAnalysisAlone() {
        val r = newRecorder()
        r.event(addr, ConnEventType.CONNECTED)
        TelemetryTestSupport.synthSession(r, addr, listOf(1000, 2000), 0)
        val rep = OfflineLogAnalyzer.analyze(listOf(reimport(r)))
        assertEquals(DeviceStage.TRANSMITTED, rep.devices.first { it.deviceId == "dev-01" }.stage)
        val row = rep.rows.first { it.charUuid == NUS_NOTIFY }
        assertTrue(row.receivedPayload); assertEquals(OfflineLogAnalyzer.NOT_PROVEN_LINK, row.vehicleLink)
        assertTrue(rep.devices.first { it.deviceId == "dev-01" }.note.contains("Nordic UART"))
        // chỉ khi có trường VERIFIED trên UUID đó mới thành ENGINE_LINK_PROVEN
        val proven = OfflineLogAnalyzer.analyze(listOf(reimport(r)), verifiedCharUuids = setOf(NUS_NOTIFY))
        assertEquals(DeviceStage.ENGINE_LINK_PROVEN, proven.devices.first { it.deviceId == "dev-01" }.stage)
    }

    @Test fun analyzerNeverAssignsPhysicalMeaning() {
        val r = newRecorder(); TelemetryTestSupport.synthSession(r, addr, listOf(0, 1000, 2000, 3000), 0)
        val rep = OfflineLogAnalyzer.analyze(listOf(reimport(r)))
        val text = OfflineLogAnalyzer.toMarkdown(rep) + OfflineLogAnalyzer.toJson(rep)
        for (w in listOf("RPM", "vòng tua", "nhiệt độ", "điện áp")) assertFalse("không được gán nghĩa '$w'", text.contains(w, ignoreCase = true))
        assertTrue(rep.findings.any { it.statement.contains("Chưa gán nghĩa") })
    }

    @Test fun advertisementOnlyDataIsSeparatedFromNotifications() {
        val r = newRecorder()
        r.recordAdvert(AdvertRecord(addr, "YCC-1", -50, listOf(NUS_SERVICE), true, 0, manufacturerData = mapOf(0x0059 to "01 02")))
        val rep = OfflineLogAnalyzer.analyze(listOf(reimport(r, withGatt = false)))
        assertEquals(1, rep.bySource[PayloadSource.ADVERTISEMENT]); assertNull(rep.bySource[PayloadSource.NOTIFICATION])
        assertEquals("chỉ có dữ liệu quảng bá (advertisement)", rep.rows.first { it.charUuid == "mfr:0059" }.structure)
        assertEquals(DeviceStage.DISCOVERED, rep.devices.first { it.deviceId == "dev-01" }.stage)
    }

    @Test fun multipleLogsAreComparedByUuidAcrossLogs() {
        fun log(rpmBase: Int): ImportedLog { val r = newRecorder(); TelemetryTestSupport.synthSession(r, addr, listOf(rpmBase, rpmBase + 500), 0, 12); return reimport(r) }
        val rep = OfflineLogAnalyzer.analyze(listOf(log(1000), log(1500)))
        assertEquals(2, rep.comparisons.single().sessions.size); assertTrue(rep.comparisons.single().sessions.all { it.startsWith("L") })
        assertTrue(rep.findings.any { it.statement.contains("so sánh phiên theo UUID") })
    }

    @Test fun emptyLogYieldsNotProvenFinding() {
        val rep = OfflineLogAnalyzer.analyze(listOf(TelemetryLogImport.parse("")))
        assertEquals(0, rep.totalPayloads); assertTrue(rep.warnings.isNotEmpty())
        assertTrue(rep.findings.any { it.kind == FindingKind.NOT_PROVEN })
    }
}
