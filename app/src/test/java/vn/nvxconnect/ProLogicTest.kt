package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.auto.EvidenceLevel
import vn.nvxconnect.bluetooth.LogEntry
import vn.nvxconnect.bluetooth.LogKind
import vn.nvxconnect.pro.*
import vn.nvxconnect.telemetry.EvidenceGrade
import vn.nvxconnect.telemetry.VerificationStatus

class ProLogicTest {
    // ---- VERIFIED chỉ đi một chiều từ engine nguồn
    @Test fun verifiedOnlyWhenEngineSaysVerified() {
        for (s in VerificationStatus.values()) for (g in EvidenceGrade.values()) {
            val m = LabStatusMapper.fromVerification(s, g)
            if (m == LabStatus.VERIFIED) assertTrue("$s/$g", s == VerificationStatus.VERIFIED && g != EvidenceGrade.INACCESSIBLE)
        }
        for (l in EvidenceLevel.values()) assertEquals(l == EvidenceLevel.VERIFIED, LabStatusMapper.fromEvidence(l) == LabStatus.VERIFIED)
    }
    @Test fun statusMappingsAreExhaustiveAndHonest() {
        assertEquals(LabStatus.HYPOTHESIS, LabStatusMapper.fromVerification(VerificationStatus.DECODED_HYPOTHESIS, EvidenceGrade.SAMPLE_INFERENCE))
        assertEquals(LabStatus.CORRELATED, LabStatusMapper.fromVerification(VerificationStatus.CORRELATED, EvidenceGrade.VEHICLE_CLUSTER))
        assertEquals(LabStatus.OBSERVED, LabStatusMapper.fromVerification(VerificationStatus.RAW_UNVERIFIED, EvidenceGrade.SAMPLE_INFERENCE))
        assertEquals(LabStatus.UNAVAILABLE, LabStatusMapper.fromVerification(VerificationStatus.UNAVAILABLE, EvidenceGrade.NO_DATA))
        assertEquals(LabStatus.INACCESSIBLE, LabStatusMapper.fromVerification(VerificationStatus.UNAVAILABLE, EvidenceGrade.INACCESSIBLE))
        assertEquals(LabStatus.INACCESSIBLE, LabStatusMapper.fromEvidence(EvidenceLevel.ENCRYPTED_OR_AUTHENTICATED))
        assertEquals(LabStatus.UNAVAILABLE, LabStatusMapper.fromEvidence(EvidenceLevel.UNKNOWN))
        assertEquals(setOf("OBSERVED", "HYPOTHESIS", "CORRELATED", "VERIFIED", "UNAVAILABLE", "INACCESSIBLE"), LabStatus.values().map { it.title }.toSet())
    }
    // ---- cài đặt
    @Test fun themeDefaultsToDarkAndParses() {
        assertEquals(ThemeMode.DARK, ProSettings().theme); assertEquals(ThemeMode.DARK, ThemeMode.parse(null)); assertEquals(ThemeMode.DARK, ThemeMode.parse("???"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.parse("light")); assertEquals(ThemeMode.SYSTEM, ThemeMode.parse("system"))
        assertEquals(3, ThemeMode.values().size)
    }
    @Test fun sanitizeClampsBadValues() {
        val s = ProSettings.sanitize(ProSettings(uiRefreshMs = 7, logMaxRecords = 3, fuelPriceVnd = -5, consumptionL100 = Double.NaN, cockpitHidden = setOf("rpm", "x", "gps_speed")))
        assertEquals(500, s.uiRefreshMs); assertEquals(20_000, s.logMaxRecords); assertEquals(0, s.fuelPriceVnd); assertEquals(0.0, s.consumptionL100, 0.0)
        assertEquals(setOf("rpm"), s.cockpitHidden)
        assertEquals(30.0, ProSettings.sanitize(ProSettings(consumptionL100 = 99.0)).consumptionL100, 0.0)
    }
    @Test fun validChoicesPreserved() {
        val s = ProSettings.sanitize(ProSettings(uiRefreshMs = 1000, logMaxRecords = 50_000, fuelPriceVnd = 25_000, consumptionL100 = 2.2))
        assertEquals(1000, s.uiRefreshMs); assertEquals(50_000, s.logMaxRecords); assertEquals(25_000, s.fuelPriceVnd); assertEquals(2.2, s.consumptionL100, 1e-9)
    }
    // ---- chẩn đoán
    private fun e(kind: LogKind, msg: String, t: Long) = LogEntry(t, kind, msg, state = "ERROR")
    @Test fun issuesOnlyFromRealErrorLogNewestFirstWithHints() {
        val log = listOf(e(LogKind.INFO, "ok", 1), e(LogKind.ERROR, "Thiếu quyền Bluetooth", 2), e(LogKind.FRAME, "notify", 3), e(LogKind.ERROR, "Kết nối quá thời gian", 5))
        val i = DiagnosticsModel.issues(log)
        assertEquals(2, i.size); assertEquals(5L, i[0].timeMs); assertTrue(i[0].hint.contains("lại gần")); assertTrue(i[1].hint.contains("quyền"))
    }
    @Test fun unknownErrorSaysNoSpecificHintAndInventsNoEcuCode() {
        val h = DiagnosticsModel.hintFor("lỗi lạ xyz"); assertTrue(h.contains("Chưa có gợi ý riêng")); assertFalse(Regex("P0\\d{3}|DTC", RegexOption.IGNORE_CASE).containsMatchIn(h))
    }
    @Test fun noErrorsMeansNoIssues() { assertTrue(DiagnosticsModel.issues(listOf(e(LogKind.INFO, "x", 1))).isEmpty()) }
    @Test fun issuesLimit() { assertEquals(3, DiagnosticsModel.issues((1..10).map { e(LogKind.ERROR, "e$it", it.toLong()) }, 3).size) }
    @Test fun collectionStateStaleAndDisconnected() {
        assertTrue(DiagnosticsModel.collectionState(false, 100).contains("dừng ghi"))
        assertTrue(DiagnosticsModel.collectionState(true, null).contains("chưa nhận"))
        assertTrue(DiagnosticsModel.collectionState(true, 9000).contains("cũ"))
        assertTrue(DiagnosticsModel.collectionState(true, 1000).contains("Đang nhận"))
    }
}
