package vn.nvxconnect.navigation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

object NetDefaults {
    /** Máy chủ DEMO công cộng của OSRM: chỉ dùng thử, có hồ sơ ô tô, không cam kết dịch vụ. Nên tự dựng máy chủ khi dùng thật. */
    const val OSRM_BASE = "https://router.project-osrm.org"
    /** Nominatim công cộng của OpenStreetMap: tối đa 1 yêu cầu/giây, không tự gợi ý khi gõ, phải có User-Agent định danh. */
    const val NOMINATIM_BASE = "https://nominatim.openstreetmap.org"
}

private fun mapHttpFailure(code: Int, what: String): NetResult.Fail = when {
    code == 429 -> NetResult.Fail(NetError.RATE_LIMITED, "$what đang giới hạn tần suất (429). Hãy đợi một lúc rồi thử lại.")
    code == 403 -> NetResult.Fail(NetError.BLOCKED, "$what từ chối yêu cầu (403). Có thể do chính sách sử dụng máy chủ công cộng.")
    code in 400..499 -> NetResult.Fail(NetError.INVALID_REQUEST, "$what từ chối yêu cầu (HTTP $code)")
    else -> NetResult.Fail(NetError.SERVER, "$what đang lỗi (HTTP $code)")
}

private const val OFFLINE_MSG = "Không có mạng hoặc máy chủ không phản hồi"

interface RoutePlanner { suspend fun route(from: LatLon, to: LatLon): NetResult<Route> }

class RoutingClient(
    private val baseUrl: String, private val http: HttpFetcher, private val userAgent: String
) : RoutePlanner {
    override suspend fun route(from: LatLon, to: LatLon): NetResult<Route> {
        val base = UrlPolicy.normalize(baseUrl) ?: return NetResult.Fail(NetError.INVALID_REQUEST, "Địa chỉ máy chủ định tuyến không hợp lệ (cần https)")
        if (!from.valid || !to.valid) return NetResult.Fail(NetError.INVALID_REQUEST, "Tọa độ không hợp lệ")
        val coords = String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f", from.lon, from.lat, to.lon, to.lat)
        val url = "$base/route/v1/driving/$coords?overview=full&geometries=polyline6&steps=true&alternatives=false"
        val resp = try {
            withContext(Dispatchers.IO) { http.get(url, mapOf("User-Agent" to userAgent, "Accept" to "application/json")) }
        } catch (e: IOException) { return NetResult.Fail(NetError.OFFLINE, OFFLINE_MSG) }
        // OSRM trả HTTP 400 kèm JSON {"code":"NoRoute"|...} nên thử đọc thân trước khi coi là lỗi HTTP.
        if (resp.code == 200 || resp.code == 400) {
            val parsed = OsrmParser.parse(resp.body)
            if (parsed is NetResult.Ok || resp.code == 200) return parsed
            if (parsed is NetResult.Fail && parsed.kind != NetError.BAD_RESPONSE) return parsed
        }
        return mapHttpFailure(resp.code, "Máy chủ định tuyến")
    }
}

class GeocodeClient(
    private val baseUrl: String, private val http: HttpFetcher, private val userAgent: String,
    private val limiter: MinIntervalLimiter = MinIntervalLimiter(1100),
    private val cacheSize: Int = 20
) {
    private val cache = object : LinkedHashMap<String, List<Place>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<Place>>?) = size > cacheSize
    }

    /** Tìm địa điểm theo từ khóa. Chỉ gọi khi người dùng bấm Tìm (không tự gợi ý khi gõ). near dùng để ưu tiên kết quả gần đó. */
    suspend fun search(query: String, near: LatLon? = null): NetResult<List<Place>> {
        val q = query.trim()
        if (q.length < 2) return NetResult.Fail(NetError.INVALID_REQUEST, "Hãy nhập ít nhất 2 ký tự")
        if (q.length > 200) return NetResult.Fail(NetError.INVALID_REQUEST, "Từ khóa quá dài")
        val base = UrlPolicy.normalize(baseUrl) ?: return NetResult.Fail(NetError.INVALID_REQUEST, "Địa chỉ máy chủ tìm kiếm không hợp lệ (cần https)")
        val key = q.lowercase() + "|" + (near?.let { String.format(Locale.US, "%.1f,%.1f", it.lat, it.lon) } ?: "")
        synchronized(cache) { cache[key] }?.let { return NetResult.Ok(it) }
        var url = "$base/search?q=${URLEncoder.encode(q, "UTF-8")}&format=jsonv2&limit=6&accept-language=vi"
        if (near != null) {
            val d = 1.0
            url += String.format(Locale.US, "&viewbox=%.4f,%.4f,%.4f,%.4f&bounded=0", near.lon - d, near.lat + d, near.lon + d, near.lat - d)
        }
        val wait = limiter.reserve()
        if (wait > 0) delay(wait)
        val resp = try {
            withContext(Dispatchers.IO) { http.get(url, mapOf("User-Agent" to userAgent, "Accept" to "application/json")) }
        } catch (e: IOException) { return NetResult.Fail(NetError.OFFLINE, OFFLINE_MSG) }
        if (resp.code != 200) return mapHttpFailure(resp.code, "Máy chủ tìm kiếm")
        val parsed = NominatimParser.parse(resp.body)
        if (parsed is NetResult.Ok) synchronized(cache) { cache[key] = parsed.value }
        return parsed
    }
}
