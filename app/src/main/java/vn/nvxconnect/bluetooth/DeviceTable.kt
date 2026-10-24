package vn.nvxconnect.bluetooth

/** Gộp các lần thấy quảng bá theo địa chỉ: RSSI làm mượt (EMA), gộp tên/service, đánh dấu mất tín hiệu. */
class DeviceTable(private val alpha: Double = 0.3, private val staleMs: Long = 30_000) {
    data class Entry(val record: AdvertRecord, val smoothedRssi: Double, val firstSeenMs: Long, val lastSeenMs: Long, val count: Int)
    data class Row(val entry: Entry, val stale: Boolean)

    private val map = LinkedHashMap<String, Entry>()

    @Synchronized fun update(r: AdvertRecord): Entry {
        val old = map[r.address]
        val e = if (old == null) Entry(r, r.rssi.toDouble(), r.seenAtMillis, r.seenAtMillis, 1) else {
            val merged = r.copy(
                name = r.name ?: old.record.name,
                advertisedServiceUuids = (old.record.advertisedServiceUuids + r.advertisedServiceUuids).distinct(),
                connectable = r.connectable ?: old.record.connectable,
                txPowerDbm = r.txPowerDbm ?: old.record.txPowerDbm,
                manufacturerData = old.record.manufacturerData + r.manufacturerData
            )
            Entry(merged, old.smoothedRssi + alpha * (r.rssi - old.smoothedRssi), old.firstSeenMs, r.seenAtMillis, old.count + 1)
        }
        map[r.address] = e
        return e
    }

    /** Còn tín hiệu trước, mạnh trước. */
    @Synchronized fun rows(nowMs: Long): List<Row> =
        map.values.map { Row(it, nowMs - it.lastSeenMs > staleMs) }
            .sortedWith(compareBy<Row>({ it.stale }, { -it.entry.smoothedRssi }))

    @Synchronized fun records(): List<AdvertRecord> = map.values.map { it.record }
    @Synchronized fun clear() = map.clear()
    @Synchronized fun size(): Int = map.size
}
