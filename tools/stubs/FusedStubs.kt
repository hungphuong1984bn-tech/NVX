package com.google.android.gms.location
import android.content.Context
import android.location.Location
import android.os.Looper
object Priority { const val PRIORITY_HIGH_ACCURACY = 100 }
class LocationRequest { class Builder(p: Int, i: Long) { fun setMinUpdateIntervalMillis(m: Long) = this; fun build() = LocationRequest() } }
class LocationResult { val locations: List<Location> = emptyList() }
abstract class LocationCallback { open fun onLocationResult(r: LocationResult) {} }
class FusedLocationProviderClient { fun requestLocationUpdates(r: LocationRequest, c: LocationCallback, l: Looper) {}; fun removeLocationUpdates(c: LocationCallback) {} }
object LocationServices { fun getFusedLocationProviderClient(c: Context) = FusedLocationProviderClient() }
