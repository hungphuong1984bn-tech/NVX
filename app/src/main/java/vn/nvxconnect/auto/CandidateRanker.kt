package vn.nvxconnect.auto

import vn.nvxconnect.bluetooth.*

/** Nhãn người dùng gắn cho một phiên quét: lúc đó khóa điện xe đang tắt hay bật. */
enum class SessionLabel(val title: String) {
    VEHICLE_OFF("Xe TẮT"), VEHICLE_ON("Xe BẬT"), UNLABELED("Chưa gắn nhãn")
}

/** Một phiên quét đã kết thúc. Chỉ chứa quảng bá nhận được, không có gì gửi đi. */
data class ScanSession(val id: Int, val label: SessionLabel, val startedMs: Long, val records: List<AdvertRecord>)

/**
 * Mức độ tin cậy của một ứng viên. KHÔNG có mức nào nghĩa là "đã xác nhận là xe Yamaha":
 * quảng bá BLE không chứng minh được điều đó, chỉ GATT + giao thức đã xác minh mới chứng minh (xem ProtocolReport).
 */
enum class Confidence(val title: String) {
    INSUFFICIENT("Chưa đủ bằng chứng"),
    WEAK("Gợi ý yếu"),
    CIRCUMSTANTIAL("Bằng chứng gián tiếp (chênh lệch xe bật/tắt)"),
    USER_CONFIRMED("Bạn đã lưu làm xe của mình")
}

enum class EvidenceKind { ON_ONLY, ON_AND_OFF, OFF_ONLY, PROFILE_MATCH, SERVICE_HINT, NAME_HINT, CONNECTABLE, NOT_CONNECTABLE, SIGNAL_NEAR, ADDRESS_ROTATES, NAME_CHANGES, FEW_SIGHTINGS }

data class Evidence(val kind: EvidenceKind, val text: String, val weight: Int)

data class Candidate(
    val groupKey: String,
    val displayName: String,
    /** Mọi địa chỉ đã thấy cho ứng viên này (có thể >1 nếu địa chỉ xoay vòng). Địa chỉ mới nhất ở cuối. */
    val addresses: List<String>,
    val score: Int,
    val confidence: Confidence,
    val evidence: List<Evidence>,
    val latest: AdvertRecord,
    val sightingsOn: Int,
    val sightingsOff: Int,
    /** Việc cần làm tiếp để có thêm bằng chứng; null nếu không còn gợi ý. */
    val nextStep: String?
) {
    val isLikelyEnough get() = confidence == Confidence.CIRCUMSTANTIAL || confidence == Confidence.USER_CONFIRMED
}

/** Khóa gom các lần thấy thành một thiết bị khi địa chỉ BLE ngẫu nhiên xoay vòng hoặc tên quảng bá đổi. */
object DeviceKey {
    // Đuôi kiểu "-1A2B", "_07", hoặc toàn số ("155"): thường là phần định danh riêng của từng thiết bị.
    private val SUFFIX = Regex("([-_ ][0-9a-f]{2,6}|[0-9]{2,6})$")

    fun normalizedName(n: String?): String? {
        val t = n?.trim()?.lowercase().orEmpty()
        if (t.isEmpty()) return null
        val stripped = t.replace(SUFFIX, "").trim('-', '_', ' ')
        return if (stripped.length >= 3) stripped else t
    }

    /** Chữ ký không phụ thuộc địa chỉ/tên: tập service quảng bá + mã hãng. Rỗng nếu thiết bị không quảng bá gì thêm. */
    fun signature(r: AdvertRecord): String? {
        val svc = r.advertisedServiceUuids.map { it.lowercase() }.filterNot { isGeneric(it) }.sorted()
        val mfg = r.manufacturerData.keys.sorted().map { "%04x".format(it) }
        if (svc.isEmpty() && mfg.isEmpty()) return null
        return "svc=" + svc.joinToString(",") + ";mfg=" + mfg.joinToString(",")
    }

    fun isGeneric(uuid: String): Boolean {
        val u = uuid.lowercase()
        return u.length == 36 && u.endsWith("-0000-1000-8000-00805f9b34fb") && u.substring(4, 8) in setOf("1800", "1801", "180a", "180f")
    }

    fun primaryKey(r: AdvertRecord): String {
        if (!r.addressKind.mayChange) return "addr:" + r.address.uppercase()
        normalizedName(r.name)?.let { return "name:$it" }
        signature(r)?.let { return "sig:$it" }
        return "addr:" + r.address.uppercase()
    }
}

/**
 * Xếp hạng ứng viên từ nhiều phiên quét xe TẮT / xe BẬT.
 *
 * Ý tưởng: thiết bị chỉ xuất hiện khi khóa điện BẬT (và lặp lại) là ứng viên mạnh hơn thiết bị luôn có mặt.
 * Tên giống Yamaha, UUID phổ biến, RSSI mạnh chỉ là gợi ý yếu (điểm rất nhỏ) và không bao giờ đủ để nâng mức tin cậy.
 * Địa chỉ xoay vòng và tên đổi được gom lại bằng DeviceKey.
 */
object CandidateRanker {
    private const val NEAR_RSSI = -70.0

    private class Group(val key: String) {
        val records = ArrayList<AdvertRecord>()
        val bySession = HashMap<Int, Int>()
    }

    fun rank(sessions: List<ScanSession>, saved: List<SavedDevice>): List<Candidate> {
        val groups = LinkedHashMap<String, Group>()
        val sigOwner = HashMap<String, String>()
        val onSessions = sessions.count { it.label == SessionLabel.VEHICLE_ON }
        val offSessions = sessions.count { it.label == SessionLabel.VEHICLE_OFF }

        for (s in sessions) for (r in s.records) {
            var key = DeviceKey.primaryKey(r)
            // Gộp thêm theo chữ ký khi cả hai bên đều có địa chỉ có thể đổi: tên quảng bá khác nhưng cùng service + mã hãng.
            if (r.addressKind.mayChange) DeviceKey.signature(r)?.let { sig ->
                val owner = sigOwner.getOrPut(sig) { key }
                if (owner != key && groups[owner]?.records?.all { it.addressKind.mayChange } == true) key = owner
            }
            val g = groups.getOrPut(key) { Group(key) }
            g.records += r
            g.bySession[s.id] = (g.bySession[s.id] ?: 0) + 1
        }

        val labelOf = sessions.associate { it.id to it.label }
        return groups.values.map { g ->
            val seenOn = g.bySession.keys.count { labelOf[it] == SessionLabel.VEHICLE_ON }
            val seenOff = g.bySession.keys.count { labelOf[it] == SessionLabel.VEHICLE_OFF }
            val latest = g.records.maxByOrNull { it.seenAtMillis }!!
            val ev = ArrayList<Evidence>()
            fun add(k: EvidenceKind, t: String, w: Int) { ev += Evidence(k, t, w) }

            val differential = onSessions >= 1 && offSessions >= 1
            if (differential) {
                when {
                    seenOn >= 1 && seenOff == 0 -> {
                        add(EvidenceKind.ON_ONLY, "Chỉ thấy khi xe BẬT ($seenOn/$onSessions phiên bật), không thấy ở $offSessions phiên xe TẮT", 50)
                        if (seenOn >= 2) add(EvidenceKind.ON_ONLY, "Lặp lại qua $seenOn phiên bật", minOf(20, 10 * (seenOn - 1)))
                    }
                    seenOn >= 1 && seenOff >= 1 ->
                        add(EvidenceKind.ON_AND_OFF, "Thấy cả khi xe TẮT: có thể là thiết bị khác luôn phát", -20)
                    seenOn == 0 && seenOff >= 1 ->
                        add(EvidenceKind.OFF_ONLY, "Chỉ thấy khi xe TẮT: không giống thiết bị bật theo khóa điện", -30)
                }
            }

            val profile = saved.firstOrNull { p -> g.records.any { ProfileMatcher.match(p, it).matched } }
            if (profile != null) add(EvidenceKind.PROFILE_MATCH, "Khớp hồ sơ bạn đã lưu: ${profile.title}", 40)

            if (g.records.any { r -> r.advertisedServiceUuids.any { KnownUuids.label(it) != null && !DeviceKey.isGeneric(it) } })
                add(EvidenceKind.SERVICE_HINT, "Có service nối tiếp phổ biến (chỉ là gợi ý, không chứng minh hãng)", 5)
            if (g.records.any { looksLikeYamahaName(it.name) })
                add(EvidenceKind.NAME_HINT, "Tên giống Yamaha/NVX (chỉ đoán theo tên, ai cũng đặt được)", 5)
            if (latest.connectable == true) add(EvidenceKind.CONNECTABLE, "Quảng bá cho phép kết nối", 3)
            if (latest.connectable == false) add(EvidenceKind.NOT_CONNECTABLE, "Đang quảng bá KHÔNG cho kết nối (có thể đang có ứng dụng khác giữ kết nối)", -10)
            if (g.records.map { it.rssi }.average() >= NEAR_RSSI) add(EvidenceKind.SIGNAL_NEAR, "Tín hiệu mạnh (ở gần)", 5)

            val addresses = g.records.sortedBy { it.seenAtMillis }.map { it.address }.distinct()
            if (addresses.size > 1) add(EvidenceKind.ADDRESS_ROTATES, "Địa chỉ đổi ${addresses.size} lần: gom theo tên/service/mã hãng", 0)
            val names = g.records.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }.distinct()
            if (names.size > 1) add(EvidenceKind.NAME_CHANGES, "Tên quảng bá thay đổi (${names.size} tên)", 0)
            if (g.records.size < 3) add(EvidenceKind.FEW_SIGHTINGS, "Mới thấy ${g.records.size} lần: dữ liệu còn ít", -5)

            val score = ev.sumOf { it.weight }
            val onlyOn = ev.any { it.kind == EvidenceKind.ON_ONLY }
            val confidence = when {
                profile != null -> Confidence.USER_CONFIRMED
                onlyOn && seenOn >= 2 -> Confidence.CIRCUMSTANTIAL
                onlyOn || score >= 10 -> Confidence.WEAK
                else -> Confidence.INSUFFICIENT
            }
            val next = when {
                profile != null -> null
                !differential -> "Cần cả phiên quét lúc xe TẮT và lúc xe BẬT để so sánh (hiện có $offSessions phiên tắt, $onSessions phiên bật)."
                confidence == Confidence.CIRCUMSTANTIAL -> "Kết nối thử để xem cây GATT; bằng chứng quảng bá vẫn chưa chứng minh đây là xe Yamaha."
                onlyOn -> "Quét thêm một phiên xe BẬT để kiểm tra có lặp lại không."
                else -> "Chưa đủ bằng chứng: quét lặp lại ở gần xe, lúc tắt rồi bật khóa điện."
            }
            Candidate(g.key, latest.name?.takeIf { it.isNotBlank() } ?: names.lastOrNull() ?: "(không tên)", addresses, score, confidence,
                ev.filter { it.weight != 0 || it.kind == EvidenceKind.ADDRESS_ROTATES || it.kind == EvidenceKind.NAME_CHANGES }, latest, seenOn, seenOff, next)
        }.sortedWith(compareByDescending<Candidate> { it.score }.thenByDescending { it.latest.rssi })
    }

    fun looksLikeYamahaName(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return "nvx" in n || "yamaha" in n || "y-connect" in n || "yconnect" in n
    }
}

/**
 * So khớp hồ sơ xe đã lưu với một lần thấy quảng bá bằng NHIỀU tín hiệu (địa chỉ, tên, service, mã hãng).
 * Địa chỉ công khai: chỉ khớp khi trùng địa chỉ. Địa chỉ có thể đổi: cần đủ số tín hiệu độc lập (tối đa 2,
 * hoặc ít hơn nếu hồ sơ chỉ ghi được ít loại thông tin) để tránh nhận nhầm thiết bị khác cùng tên.
 */
object ProfileMatcher {
    data class Match(val matched: Boolean, val signals: List<String>)

    fun match(p: SavedDevice, r: AdvertRecord): Match {
        if (p.address.equals(r.address, ignoreCase = true)) return Match(true, listOf("địa chỉ trùng"))
        if (!AddressKind.classify(p.address).mayChange) return Match(false, emptyList())
        val signals = ArrayList<String>()
        val pn = DeviceKey.normalizedName(p.name); val rn = DeviceKey.normalizedName(r.name)
        if (pn != null && rn != null && pn == rn) signals += "tên"
        val ps = p.serviceUuids.map { it.lowercase() }.filterNot { DeviceKey.isGeneric(it) }.toSet()
        if (ps.isNotEmpty() && r.advertisedServiceUuids.map { it.lowercase() }.any { it in ps }) signals += "service"
        if (p.manufacturerIds.isNotEmpty() && r.manufacturerData.keys.any { it in p.manufacturerIds }) signals += "mã hãng"
        val kinds = listOf(pn != null, ps.isNotEmpty(), p.manufacturerIds.isNotEmpty()).count { it }
        val need = minOf(2, kinds)
        return Match(need > 0 && signals.size >= need, signals)
    }
}
