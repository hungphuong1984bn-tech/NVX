package vn.nvxconnect.navigation

/** Giải mã "encoded polyline" (thuật toán của Google). OSRM dùng precision 5 (polyline) hoặc 6 (polyline6). */
object PolylineCodec {
    fun decode(encoded: String, precision: Int = 6): List<LatLon> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<LatLon>(encoded.length / 4)
        var i = 0; var lat = 0L; var lon = 0L
        fun next(): Long {
            var result = 0L; var shift = 0
            while (true) {
                require(i < encoded.length) { "Polyline bị cắt cụt" }
                val b = encoded[i++].code - 63
                require(b in 0..63) { "Ký tự polyline không hợp lệ" }
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
                require(shift <= 60) { "Polyline quá dài" }
                if (b < 0x20) break
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (i < encoded.length) {
            lat += next()
            require(i < encoded.length) { "Polyline thiếu kinh độ" }
            lon += next()
            out += LatLon(lat / factor, lon / factor)
        }
        return out
    }

    fun encode(points: List<LatLon>, precision: Int = 6): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder(); var pLat = 0L; var pLon = 0L
        fun put(v: Long) {
            var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) { sb.append((((x and 0x1f) or 0x20) + 63).toInt().toChar()); x = x shr 5 }
            sb.append((x + 63).toInt().toChar())
        }
        for (p in points) {
            val la = Math.round(p.lat * factor); val lo = Math.round(p.lon * factor)
            put(la - pLat); put(lo - pLon); pLat = la; pLon = lo
        }
        return sb.toString()
    }
}
