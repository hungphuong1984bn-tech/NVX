package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.TelemetryTestSupport.newRecorder
import vn.nvxconnect.util.MiniJson
import vn.nvxconnect.telemetry.*

class EvidenceRepositoryTest {
    private val addr = "AA:BB:CC:DD:EE:01"
    private fun twoSessions(): Pair<List<PayloadRecord>, List<Pair<Long, Int>>> {
        val r = newRecorder(); val s = ArrayList<Pair<Long, Int>>()
        s += TelemetryTestSupport.synthSession(r, addr, listOf(800, 1500, 2300, 3100, 3900), 100_000)
        r.start("2"); s += TelemetryTestSupport.synthSession(r, addr, listOf(800, 1500, 2300, 3100, 3900), 200_000)
        return r.snapshot() to s
    }
    private fun csv(series: List<Pair<Long, Int>>, grade: String) = "time_ms,field,value,unit,source,grade,method\n" +
        series.filterIndexed { i, _ -> i % 3 == 0 }.joinToString("\n") { "${it.first},rpm,${it.second},rpm,máy đo rời (test),$grade,đo kẹp" }

    @Test fun withoutAnyDataEveryFieldIsHypothesisOrUnavailableNeverVerified() {
        val v = ProtocolEvidenceRepository().evaluateAll(emptyList(), 0)
        assertEquals(FieldCatalog.ALL.size, v.size)
        assertTrue(v.none { it.status == VerificationStatus.VERIFIED || it.status == VerificationStatus.CORRELATED })
        val by = v.associateBy { it.field.id }
        assertEquals(VerificationStatus.DECODED_HYPOTHESIS, by.getValue(FieldIds.RPM).status)       // giả thuyết có sẵn, chưa có dữ liệu/tham chiếu
        assertEquals(VerificationStatus.DECODED_HYPOTHESIS, by.getValue(FieldIds.BATTERY_V).status)
        for (id in listOf(FieldIds.ENGINE_TEMP, FieldIds.SPEED, FieldIds.CHARGING_V, FieldIds.ENGINE_STATUS)) assertEquals(id, VerificationStatus.UNAVAILABLE, by.getValue(id).status)
        assertEquals(EvidenceGrade.INACCESSIBLE, by.getValue(FieldIds.DTC).grade)
    }

    @Test fun importThenEvaluateReachesVerifiedOnlyWithIndependentGrade() {
        val (recs, series) = twoSessions()
        val a = ProtocolEvidenceRepository(); a.importReference(csv(series, "independent"), 10)
        assertEquals(VerificationStatus.VERIFIED, a.evaluateAll(recs, recs.maxOf { it.timeMs }).first { it.field.id == FieldIds.RPM }.status)
        assertEquals(setOf(TelemetryTestSupport.NUS_NOTIFY), a.verifiedCharUuids())
        val b = ProtocolEvidenceRepository(); b.importReference(csv(series, "cluster"), 10)
        assertEquals(VerificationStatus.CORRELATED, b.evaluateAll(recs, recs.maxOf { it.timeMs }).first { it.field.id == FieldIds.RPM }.status)
        assertTrue(b.verifiedCharUuids().isEmpty())
    }

    @Test fun cannotLockCriteriaAfterReferenceDataExistsForThatField() {
        val repo = ProtocolEvidenceRepository()
        val c = repo.criteriaFor(FieldIds.RPM)!!.criteria.copy(maxMeanAbsError = 500.0)
        assertNotNull(repo.lockCriteria(c, 1))                                  // trước dữ liệu: được
        repo.importReference("time_ms,field,value,unit,source,grade\n1,rpm,1000,rpm,x,independent", 2)
        assertNull(repo.lockCriteria(c.copy(maxAbsError = 999.0), 3))           // sau dữ liệu: từ chối
        assertEquals(500.0, repo.criteriaFor(FieldIds.RPM)!!.criteria.maxMeanAbsError, 0.0)
        assertNotNull(repo.lockCriteria(repo.criteriaFor(FieldIds.BATTERY_V)!!.criteria.copy(minPairs = 25), 4))   // trường khác không bị ảnh hưởng
    }

    @Test fun reportsListEveryFieldAndParseAsJson() {
        val repo = ProtocolEvidenceRepository(); val v = repo.evaluateAll(emptyList(), 0)
        val json = MiniJson.parse(TelemetryEvidenceReport.toJson(v)) as Map<*, *>
        assertEquals(FieldCatalog.ALL.size, (json["fields"] as List<*>).size)
        val md = TelemetryEvidenceReport.toMarkdown(v)
        for (f in FieldCatalog.ALL) assertTrue(md.contains(f.id))
        assertTrue(md.contains("KHÔNG đủ để VERIFIED"))
        assertEquals(FieldCatalog.ALL.size + 1, TelemetryEvidenceReport.toCsv(v).trim().lines().size)
    }
}
