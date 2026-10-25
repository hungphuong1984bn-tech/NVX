package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.TelemetryTestSupport.NUS_NOTIFY
import vn.nvxconnect.TelemetryTestSupport.NUS_SERVICE
import vn.nvxconnect.TelemetryTestSupport.newRecorder
import vn.nvxconnect.bluetooth.*
import vn.nvxconnect.telemetry.*

class TelemetryLogIoTest {
    private val addr = "AA:BB:CC:DD:EE:01"
    private val gatt = mapOf("dev-01" to listOf(GattServiceInfo(NUS_SERVICE, listOf(
        GattCharInfo("6e400002-b5a3-f393-e0a9-e50e24dcca9e", listOf("WRITE", "WRITE_NO_RESP")),
        GattCharInfo(NUS_NOTIFY, listOf("NOTIFY"), listOf(GattDescriptorInfo("00002902-0000-1000-8000-00805f9b34fb")))))))

    private fun filled(): RawFrameRecorder {
        val r = newRecorder()
        r.event(addr, ConnEventType.CONNECTED); r.event(addr, ConnEventType.DISCOVERY_DONE); r.event(addr, ConnEventType.SUBSCRIBE_OK, NUS_NOTIFY)
        r.record(addr, PayloadSource.NOTIFICATION, NUS_SERVICE, NUS_NOTIFY, TelemetryTestSupport.REAL_IDLE, 1000, -61)
        r.record(addr, PayloadSource.NOTIFICATION, NUS_SERVICE, NUS_NOTIFY, TelemetryTestSupport.REAL_RUNNING, 1100)
        r.record(addr, PayloadSource.READ, "180a", "2a29", "Yamaha".toByteArray(), 1200)
        return r
    }

    @Test fun jsonV3RoundTripPreservesRecordsEventsAndGatt() {
        val r = filled()
        val json = TelemetryLogExporter.toJson(r, gatt, "test", 5000, redact = false)
        val log = TelemetryLogImport.parse(json)
        assertEquals(TelemetryLogExporter.FORMAT, log.format); assertTrue(log.warnings.toString(), log.warnings.isEmpty())
        assertEquals(r.snapshot().map { it.hex }, log.records.map { it.hex })
        assertEquals(r.snapshot().map { it.source }, log.records.map { it.source })
        assertEquals(-61, log.records.first().rssi)
        assertTrue(log.events.any { it.type == ConnEventType.SUBSCRIBE_OK && it.uuid == NUS_NOTIFY })
        assertEquals(2, log.gatt.size); assertTrue(log.gatt.first { it.charUuid == NUS_NOTIFY }.canNotify)
        assertTrue(json.contains("ĐANG NGHIÊN CỨU"))                      // ghi rõ 0x6B/0x6C là khung đang nghiên cứu
    }

    @Test fun csvRoundTripAndFilterExport() {
        val r = filled()
        val csv = TelemetryLogExporter.toCsv(r, redact = false)
        assertTrue(csv.startsWith(TelemetryLogExporter.CSV_HEADER))
        val log = TelemetryLogImport.parse(csv)
        assertEquals(3, log.records.size); assertEquals(r.snapshot().map { it.hex }, log.records.map { it.hex })
        val onlyRead = TelemetryLogImport.parse(TelemetryLogExporter.toCsv(r, false, RecordFilter(source = PayloadSource.READ)))
        assertEquals(1, onlyRead.records.size); assertEquals(PayloadSource.READ, onlyRead.records.single().source)
    }

    @Test fun redactedExportMasksLongAsciiRunsButKeepsFrameBytes() {
        val r = newRecorder()
        r.record(addr, PayloadSource.READ, null, "2a29", "ABCDEFGHIJ".toByteArray(), 1)
        r.record(addr, PayloadSource.NOTIFICATION, null, NUS_NOTIFY, TelemetryTestSupport.REAL_IDLE, 2)
        val log = TelemetryLogImport.parse(TelemetryLogExporter.toJson(r, emptyMap(), "t", 9, redact = true))
        assertEquals("2A 2A 2A 2A 2A 2A 2A 2A 2A 2A", log.records[0].hex)
        assertTrue(log.records[1].hex.startsWith("6B 05 00 01 21"))
        assertFalse(TelemetryLogExporter.toJson(r, emptyMap(), "t", 9, true).contains("AA:BB:CC"))
    }

    @Test fun importsLegacyV2JsonLogProducedByLogExporter() {
        val entries = listOf(
            LogEntry(1000, LogKind.FRAME, "notify 59 byte", NUS_NOTIFY, "READY", "6B 05 00"),
            LogEntry(1100, LogKind.GATT, "Đọc Model", "2a24", "READY", "41 42"),
            LogEntry(1200, LogKind.INFO, "chỉ là chú thích", null, "READY", null))
        val json = LogExporter.toJson(entries, emptyList(), mapOf("AA:BB:CC:DD:EE:01" to gatt.values.first()), "salt", 5000, "0.9", redact = false)
        val log = TelemetryLogImport.parse(json)
        assertEquals(2, log.records.size)
        assertEquals(PayloadSource.NOTIFICATION, log.records[0].source); assertEquals(PayloadSource.READ, log.records[1].source)
        assertEquals("AA:BB:CC:DD:EE:01", log.records[0].deviceId); assertEquals(NUS_SERVICE, log.records[0].serviceUuid)
    }

    @Test fun legacyCsvImportsFrameRowsOnly() {
        val csv = "time_utc,time_ms,kind,state,uuid,hex,message\n" +
            "\"x\",1000,FRAME,\"READY\",\"$NUS_NOTIFY\",\"6B 05\",\"notify 2 byte\"\n" +
            "\"x\",1001,INFO,\"READY\",\"\",\"\",\"ghi chú\"\n"
        val log = TelemetryLogImport.parse(csv)
        assertEquals(1, log.records.size); assertTrue(log.warnings.any { it.contains("unknown") })
    }

    @Test fun garbageAndTruncatedInputNeverThrowAndAreReported() {
        for (bad in listOf("", "{", "{\"format\":\"nvx-connect-telemetry-log/3\",\"payloads\":[1,{\"source\":\"NOTIFICATION\"}]}", "a,b,c\n1,2,3", "{\"format\":\"khác\"}")) {
            val log = TelemetryLogImport.parse(bad)
            assertTrue("'$bad' → ${log.warnings}", log.warnings.isNotEmpty() || log.records.isNotEmpty())
        }
        val partial = TelemetryLogImport.parse("{\"format\":\"nvx-connect-telemetry-log/3\",\"payloads\":[1,{\"source\":\"NOTIFICATION\"}]}")
        assertEquals(0, partial.records.size); assertEquals(2, partial.warnings.size)
    }

    @Test fun csvCellsWithQuotesAndCommasSurvive() {
        assertEquals(listOf("a,b", "c\"d", "e"), TelemetryLogImport.splitCsv("\"a,b\",\"c\"\"d\",e"))
    }

    @Test fun exportReportsDroppedRecords() {
        val r = newRecorder(maxRecords = 5, pinned = 1)
        repeat(12) { r.record(addr, PayloadSource.NOTIFICATION, null, NUS_NOTIFY, byteArrayOf(it.toByte(), 1), it.toLong()) }
        val log = TelemetryLogImport.parse(TelemetryLogExporter.toJson(r, emptyMap(), "t", 1, false))
        assertEquals(7L, log.droppedOldest); assertEquals(5, log.records.size)
        assertTrue(OfflineLogAnalyzer.analyze(listOf(log)).findings.any { it.statement.contains("bị cắt 7") })
    }
}
