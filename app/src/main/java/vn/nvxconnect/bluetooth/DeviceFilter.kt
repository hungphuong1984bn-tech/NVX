package vn.nvxconnect.bluetooth

data class DeviceQuery(
    val text: String = "",
    val savedOnly: Boolean = false,
    val connectableOnly: Boolean = false,
    val hideStale: Boolean = false,
    val namedOnly: Boolean = false
) {
    val isActive get() = text.isNotBlank() || savedOnly || connectableOnly || hideStale || namedOnly
}

/** Tìm kiếm và lọc danh sách thiết bị đang thấy. Thuần logic. */
object DeviceFilter {
    fun apply(rows: List<DeviceTable.Row>, q: DeviceQuery, saved: List<SavedDevice>): List<DeviceTable.Row> {
        val needle = q.text.trim().lowercase()
        return rows.filter { row ->
            val r = row.entry.record
            (!q.hideStale || !row.stale) &&
                (!q.connectableOnly || r.connectable != false) &&
                (!q.namedOnly || !r.name.isNullOrBlank()) &&
                (!q.savedOnly || saved.any { SavedDeviceMatcher.matches(it, r) }) &&
                (needle.isEmpty() || haystack(r).any { it.contains(needle) })
        }
    }

    private fun haystack(r: AdvertRecord): List<String> = buildList {
        r.name?.let { add(it.lowercase()) }
        add(r.address.lowercase())
        r.advertisedServiceUuids.forEach { u -> add(u.lowercase()); KnownUuids.label(u)?.let { add(it.lowercase()) } }
        r.manufacturerData.keys.forEach { add("%04x".format(it)) }
    }
}
