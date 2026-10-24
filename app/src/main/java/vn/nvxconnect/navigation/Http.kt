package vn.nvxconnect.navigation

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val code: Int, val body: String)

/** Ranh giới HTTP (chặn luồng; gọi từ Dispatchers.IO). Ném IOException khi mất mạng. */
interface HttpFetcher {
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

class UrlConnectionFetcher(private val connectTimeoutMs: Int = 10_000, private val readTimeoutMs: Int = 15_000,
                           private val maxBytes: Int = 8 * 1024 * 1024) : HttpFetcher {
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = connectTimeoutMs; c.readTimeout = readTimeoutMs; c.instanceFollowRedirects = true
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val out = ByteArrayOutputStream()
            if (stream != null) stream.use {
                val buf = ByteArray(8192); var total = 0
                while (true) {
                    val n = it.read(buf); if (n < 0) break
                    total += n; if (total > maxBytes) throw IOException("Phản hồi quá lớn")
                    out.write(buf, 0, n)
                }
            }
            return HttpResponse(code, out.toString(Charsets.UTF_8.name()))
        } finally { c.disconnect() }
    }
}

/** Giãn cách tối thiểu giữa hai yêu cầu (Nominatim yêu cầu tối đa 1 yêu cầu/giây). */
class MinIntervalLimiter(private val minMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private var last = Long.MIN_VALUE / 2
    /** Số ms cần chờ trước khi được gửi, và ghi nhận lượt gửi tại thời điểm đó. */
    @Synchronized fun reserve(): Long {
        val t = now()
        val wait = maxOf(0L, last + minMs - t)
        last = t + wait
        return wait
    }
}

/** Chấp nhận https; http chỉ cho máy nội bộ (localhost, mạng LAN) để tự dựng máy chủ định tuyến. */
object UrlPolicy {
    fun normalize(raw: String): String? {
        val s = raw.trim().trimEnd('/')
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        val lower = s.lowercase()
        if (lower.startsWith("https://")) return if (host(lower).isNotEmpty()) s else null
        if (lower.startsWith("http://")) { val h = host(lower); return if (isLocal(h)) s else null }
        return null
    }
    private fun host(lowerUrl: String) = lowerUrl.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
    private fun isLocal(h: String) = h == "localhost" || h == "127.0.0.1" || h == "10.0.2.2" || h.startsWith("192.168.") ||
        h.startsWith("10.") || Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(h)
}
