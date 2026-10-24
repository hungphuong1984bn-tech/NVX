package vn.nvxconnect.bluetooth

import java.security.MessageDigest

/** Che địa chỉ định danh: giữ 3 octet đầu (OUI nhà sản xuất), băm phần còn lại bằng muối cục bộ. */
object SurveyRedactor {
    fun address(addr: String, salt: String): String {
        val parts = addr.uppercase().split(":")
        if (parts.size != 6) return "REDACTED"
        val h = MessageDigest.getInstance("SHA-256").digest((salt + addr.uppercase()).toByteArray())
            .joinToString("") { "%02x".format(it) }.take(6)
        return parts.take(3).joinToString(":") + ":#" + h
    }
    fun name(n: String?): String? = n?.let { if (it.length <= 3) "***" else it.take(3) + "***" }
    fun redact(r: AdvertRecord, salt: String) = r.copy(address = address(r.address, salt), name = name(r.name))
}
