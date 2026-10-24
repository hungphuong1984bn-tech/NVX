package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.bluetooth.LogEntry
import vn.nvxconnect.bluetooth.LogKind
import vn.nvxconnect.diagnostics.DiagnosticsInput
import vn.nvxconnect.diagnostics.DiagnosticsReport
import vn.nvxconnect.helpcenter.ChecklistInputs
import vn.nvxconnect.helpcenter.ConnectionChecklist
import vn.nvxconnect.protocol.*
import vn.nvxconnect.tftintegration.TftIntegrationInputs
import vn.nvxconnect.tftintegration.TftIntegrationManager
import vn.nvxconnect.util.MiniJson
import vn.nvxconnect.util.MiniJson.list
import vn.nvxconnect.util.MiniJson.obj
import vn.nvxconnect.util.MiniJson.text
import vn.nvxconnect.vehicledata.VehicleDataRepository

class DiagnosticsReportTest {
    private val mac = "AA:BB:CC:11:22:33"
    private fun input(): DiagnosticsInput {
        val repo = VehicleDataRepository(TelemetryHub(), { 5_000L })
        val link = VehicleLinkStatus(LinkLevel.RAW_DATA_ONLY, VehicleLinkStatus.RAW_ONLY, VehicleLinkStatus.NOT_VERIFIED, false)
        val conn = ConnState.Error(vn.nvxconnect.bluetooth.ErrorKind.TIMEOUT, "Hết thời gian chờ khi nối $mac")
        return DiagnosticsInput(
            appVersion = "test", androidSdk = 34, generatedAtMs = 1_700_000_000_000,
            checklist = ConnectionChecklist.evaluate(ChecklistInputs(blockers = emptyList(), deviceFound = true, conn = conn, link = link)),
            values = repo.snapshot(false, null, 5_000L),
            tft = TftIntegrationManager.evaluate(TftIntegrationInputs(conn, link, false, UnsupportedTftBridge, false)),
            log = listOf(
                LogEntry(1000, LogKind.STATE, "CONNECTING", state = "CONNECTING"),
                LogEntry(2000, LogKind.ERROR, "Timeout nối $mac", state = "ERROR"),
                LogEntry(2100, LogKind.FRAME, "gói thô", hex = "01 02 03")),
            salt = "salt")
    }

    @Test fun jsonIsValidRedactedAndKeepsTimestampsAndErrors() {
        val json = DiagnosticsReport.toJson(input())
        val root = MiniJson.parse(json).obj()!!
        assertEquals("nvx-diagnostics-1", root["schema"].text()); assertEquals(34, (root["androidSdk"] as Number).toInt())
        assertFalse("MAC phải được che", json.contains(mac))
        val timeline = root["timeline"].list()!!
        assertEquals(2, timeline.size) // STATE + ERROR; không có FRAME thô
        assertTrue(timeline.any { it.obj()!!["kind"].text() == "ERROR" && (it.obj()!!["timeMs"] as Number).toLong() == 2000L })
        assertEquals(8, root["checklist"].list()!!.size); assertEquals(VehicleParam.entries.size, root["values"].list()!!.size)
    }

    @Test fun jsonChecklistCarriesStatusAndHelpGuideForTheFailure() {
        val root = MiniJson.parse(DiagnosticsReport.toJson(input())).obj()!!
        val conn = root["checklist"].list()!!.map { it.obj()!! }.first { it["step"].text() == "CONNECTED" }
        assertEquals("FAILED", conn["status"].text()); assertEquals("ket-noi-trang-thai", conn["helpGuide"].text())
    }

    @Test fun jsonStatesTftUnsupportedAndNoVehicleValuesWereInvented() {
        val root = MiniJson.parse(DiagnosticsReport.toJson(input())).obj()!!
        val levels = root["tft"].obj()!!["levels"].list()!!.map { it.obj()!! }
        assertTrue(levels.first { it["level"].text() == "TFT_INTEGRATED" }["reached"] == false)
        assertTrue(root["values"].list()!!.map { it.obj()!! }.all { it["status"].text() == "UNAVAILABLE" && it["value"] == null })
    }

    @Test fun csvHasHeaderRowsPerSectionAndRedaction() {
        val csv = DiagnosticsReport.toCsv(input())
        val lines = csv.trim().lines()
        assertEquals("section,time_utc,time_ms,name,status,detail", lines.first())
        listOf("\"CHECK\"", "\"VALUE\"", "\"TFT\"", "\"EVENT\"").forEach { s -> assertTrue(s, lines.any { it.startsWith(s) }) }
        assertFalse(csv.contains(mac)); assertFalse(csv.contains("01 02 03"))
        assertEquals(1 + 8 + VehicleParam.entries.size + 6 + 2, lines.size)
    }
}
