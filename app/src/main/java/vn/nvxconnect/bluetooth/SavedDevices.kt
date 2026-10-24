package vn.nvxconnect.bluetooth

/** Thiết bị người dùng đã chọn lưu. Lưu địa chỉ, tên và service để còn tìm lại khi địa chỉ riêng tư xoay vòng. */
data class SavedDevice(
    val address: String,
    val name: String?,
    val alias: String? = null,
    val serviceUuids: List<String> = emptyList(),
    val savedAtMs: Long = 0L,
    /** Địa chỉ ưu tiên cho tự động kết nối (tối đa một thiết bị). */
    val preferred: Boolean = false,
    /** Mã hãng (company id) thấy trong quảng bá lúc lưu: tín hiệu nhận diện bổ sung khi địa chỉ xoay vòng. */
    val manufacturerIds: List<Int> = emptyList()
) {
    val title get() = alias?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() } ?: "(không tên)"
}

object SavedDeviceMatcher {
    /**
     * Khớp nếu cùng địa chỉ; hoặc nếu địa chỉ đã lưu CÓ THỂ thay đổi (xem AddressKind) và tên quảng bá trùng
     * (không phân biệt hoa thường). Tên rỗng không bao giờ được dùng để khớp.
     */
    fun matches(s: SavedDevice, r: AdvertRecord): Boolean {
        if (s.address.equals(r.address, ignoreCase = true)) return true
        val sn = s.name?.trim().orEmpty(); val rn = r.name?.trim().orEmpty()
        if (sn.isEmpty() || rn.isEmpty()) return false
        return AddressKind.classify(s.address).mayChange && sn.equals(rn, ignoreCase = true)
    }
}

/** Mã hóa danh sách thành chuỗi để lưu trong DataStore. Mỗi thiết bị một dòng, các trường cách nhau bằng TAB. */
object SavedDeviceCodec {
    private fun esc(s: String) = buildString {
        for (c in s) when (c) { '\\' -> append("\\\\"); '\t' -> append("\\t"); '\n' -> append("\\n"); '\r' -> append("\\r"); else -> append(c) }
    }
    private fun unesc(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 't' -> append('\t'); 'n' -> append('\n'); 'r' -> append('\r'); else -> append(s[i + 1]) }
                i += 2
            } else { append(c); i++ }
        }
    }

    fun encode(list: List<SavedDevice>): String = list.joinToString("\n") { d ->
        listOf(esc(d.address), esc(d.name ?: ""), esc(d.alias ?: ""), esc(d.serviceUuids.joinToString(",")),
            d.savedAtMs.toString(), if (d.preferred) "1" else "0", d.manufacturerIds.joinToString(",")).joinToString("\t")
    }

    fun decode(text: String): List<SavedDevice> = text.split("\n").filter { it.isNotBlank() }.mapNotNull { line ->
        val f = line.split("\t")
        if (f.size < 6 || f[0].isBlank()) return@mapNotNull null
        SavedDevice(
            address = unesc(f[0]), name = unesc(f[1]).ifEmpty { null }, alias = unesc(f[2]).ifEmpty { null },
            serviceUuids = unesc(f[3]).split(",").filter { it.isNotBlank() },
            savedAtMs = f[4].toLongOrNull() ?: 0L, preferred = f[5] == "1",
            // Bản cũ chỉ có 6 trường: thiếu trường 7 thì coi như chưa có mã hãng.
            manufacturerIds = f.getOrNull(6)?.let { unesc(it) }?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList())
    }

    /** Thêm hoặc cập nhật theo địa chỉ; nếu thiết bị mới là ưu tiên thì bỏ ưu tiên của các thiết bị khác. */
    fun upsert(list: List<SavedDevice>, d: SavedDevice): List<SavedDevice> {
        val rest = list.filterNot { it.address.equals(d.address, ignoreCase = true) }
            .map { if (d.preferred) it.copy(preferred = false) else it }
        return (rest + d).sortedBy { it.title.lowercase() }
    }
    fun remove(list: List<SavedDevice>, address: String) = list.filterNot { it.address.equals(address, ignoreCase = true) }
}
