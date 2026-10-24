package vn.nvxconnect.bluetooth

/** Xuất danh sách quảng bá (CSV) và bản khảo sát (JSON). Địa chỉ/tên luôn được che. Xem thêm LogExporter. */
object SurveyExporter {
    fun toCsv(adverts: List<AdvertRecord>, salt: String): String = buildString {
        appendLine("seen_at_ms,address,address_kind,name,rssi,connectable,tx_power_dbm,advertised_service_uuids,manufacturer_ids")
        adverts.map { SurveyRedactor.redact(it, salt) }.forEach {
            appendLine(listOf(it.seenAtMillis.toString(), LogExporter.csvCell(it.address), it.addressKind.name,
                LogExporter.csvCell(it.name ?: ""), it.rssi.toString(), it.connectable?.toString() ?: "unknown",
                it.txPowerDbm?.toString() ?: "", LogExporter.csvCell(it.advertisedServiceUuids.joinToString(";")),
                LogExporter.csvCell(it.manufacturerData.keys.joinToString(";") { k -> "%04x".format(k) })).joinToString(","))
        }
    }

    fun toJson(log: SurveyLog, salt: String): String =
        LogExporter.toJson(emptyList(), log.adverts, log.services, salt, log.createdAtMillis, "n/a", redact = true)
}
