package vn.nvxconnect.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.location.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import vn.nvxconnect.domain.GeoPoint

/** Nguồn GPS của điện thoại (GPS_ESTIMATE) qua Fused Location. Người gọi phải đã được cấp quyền vị trí. */
class TripRecorder(private val context: Context) : LocationSource {
    @SuppressLint("MissingPermission")
    override fun updates(): Flow<GeoPoint> = callbackFlow {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000).setMinUpdateIntervalMillis(1000).build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                r.locations.forEach {
                    trySend(GeoPoint(it.latitude, it.longitude, if (it.hasSpeed()) it.speed else 0f, if (it.hasAccuracy()) it.accuracy else 999f,
                        System.currentTimeMillis(), if (it.hasBearing()) it.bearing else Float.NaN))
                }
            }
        }
        try { client.requestLocationUpdates(req, cb, Looper.getMainLooper()) }
        catch (e: SecurityException) { close(IllegalStateException("Thiếu quyền vị trí")); return@callbackFlow }
        awaitClose { client.removeLocationUpdates(cb) }
    }

    companion object {
        /** GPS hệ thống có đang bật không (để hiển thị "GPS đang tắt"). */
        fun isGpsEnabled(context: Context): Boolean {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
            return try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { false }
        }
    }
}
