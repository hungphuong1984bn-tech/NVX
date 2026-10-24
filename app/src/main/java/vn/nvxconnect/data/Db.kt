package vn.nvxconnect.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity data class Trip(@PrimaryKey(autoGenerate = true) val id: Long = 0, val startMs: Long, val endMs: Long,
    val distanceM: Double, val maxSpeedKmh: Double, val endLat: Double?, val endLon: Double?, val isDemo: Boolean = false)
@Entity data class TrackPoint(@PrimaryKey(autoGenerate = true) val id: Long = 0, val tripId: Long,
    val lat: Double, val lon: Double, val speedMps: Float, val accuracyM: Float, val timeMs: Long)
@Entity data class FuelEntry(@PrimaryKey(autoGenerate = true) val id: Long = 0, val odometerKm: Int, val liters: Double,
    val pricePerLiter: Int, val totalVnd: Long, val fullTank: Boolean, val dateMs: Long, val isDemo: Boolean = false)
@Entity data class MaintenanceItem(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String,
    val intervalKm: Int?, val intervalDays: Int?, val lastDoneOdoKm: Int, val lastDoneMs: Long, val isDemo: Boolean = false)

@Dao interface AppDao {
    /** Chỉ các chuyến đã kết thúc (endMs > 0). Chuyến đang ghi có endMs = 0 và được khôi phục khi mở app nếu tiến trình bị tắt. */
    @Query("SELECT * FROM Trip WHERE endMs > 0 ORDER BY startMs DESC") fun trips(): Flow<List<Trip>>
    @Query("SELECT * FROM Trip WHERE endMs = 0") suspend fun unfinishedTrips(): List<Trip>
    @Query("SELECT * FROM Trip WHERE id = :id") suspend fun trip(id: Long): Trip?
    @Query("SELECT * FROM TrackPoint WHERE tripId = :id ORDER BY timeMs") suspend fun points(id: Long): List<TrackPoint>
    @Query("SELECT * FROM TrackPoint ORDER BY tripId, timeMs") suspend fun allPoints(): List<TrackPoint>
    @Query("SELECT * FROM Trip ORDER BY startMs") suspend fun allTrips(): List<Trip>
    @Query("SELECT * FROM FuelEntry ORDER BY odometerKm") suspend fun allFuel(): List<FuelEntry>
    @Query("SELECT * FROM MaintenanceItem ORDER BY name") suspend fun allMaintenance(): List<MaintenanceItem>
    @Upsert suspend fun upsertTrip(t: Trip): Long
    @Query("DELETE FROM TrackPoint WHERE tripId = :id") suspend fun deletePoints(id: Long)
    @Query("DELETE FROM Trip WHERE id = :id") suspend fun deleteTrip(id: Long)
    @Query("DELETE FROM TrackPoint") suspend fun deleteAllPoints()
    @Query("DELETE FROM Trip") suspend fun deleteAllTrips()
    @Query("DELETE FROM FuelEntry") suspend fun deleteAllFuel()
    @Query("DELETE FROM MaintenanceItem") suspend fun deleteAllMaintenance()
    @Query("SELECT * FROM FuelEntry ORDER BY odometerKm DESC") fun fuel(): Flow<List<FuelEntry>>
    @Query("SELECT * FROM MaintenanceItem ORDER BY name") fun maintenance(): Flow<List<MaintenanceItem>>
    @Insert suspend fun insertTrip(t: Trip): Long
    @Insert suspend fun insertPoints(p: List<TrackPoint>)
    @Upsert suspend fun upsertFuel(f: FuelEntry)
    @Upsert suspend fun upsertMaintenance(m: MaintenanceItem)
    @Query("DELETE FROM FuelEntry WHERE id = :id") suspend fun deleteFuel(id: Long)
    @Query("DELETE FROM MaintenanceItem WHERE id = :id") suspend fun deleteMaintenance(id: Long)
    @Query("DELETE FROM TrackPoint WHERE tripId IN (SELECT id FROM Trip WHERE isDemo = 1)") suspend fun deleteDemoPoints()
    @Query("DELETE FROM Trip WHERE isDemo = 1") suspend fun deleteDemoTrips()
    @Query("DELETE FROM FuelEntry WHERE isDemo = 1") suspend fun deleteDemoFuel()
    @Query("DELETE FROM MaintenanceItem WHERE isDemo = 1") suspend fun deleteDemoMaintenance()
}

@Database(entities = [Trip::class, TrackPoint::class, FuelEntry::class, MaintenanceItem::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
    companion object {
        @Volatile private var inst: AppDatabase? = null
        fun get(c: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDatabase::class.java, "nvx.db").build().also { inst = it }
        }
    }
}

/** Xóa toàn bộ dữ liệu người dùng (quyền riêng tư). Không động tới cài đặt/thiết bị đã lưu. */
suspend fun AppDao.deleteEverything() { deleteAllPoints(); deleteAllTrips(); deleteAllFuel(); deleteAllMaintenance() }

object DemoData {
    suspend fun seed(d: AppDao) {
        val now = System.currentTimeMillis(); val day = 86_400_000L
        d.insertTrip(Trip(startMs = now - 2 * day, endMs = now - 2 * day + 1_800_000, distanceM = 12_500.0, maxSpeedKmh = 58.0, endLat = null, endLon = null, isDemo = true))
        d.upsertFuel(FuelEntry(odometerKm = 1000, liters = 4.0, pricePerLiter = 22000, totalVnd = 88000, fullTank = true, dateMs = now - 20 * day, isDemo = true))
        d.upsertFuel(FuelEntry(odometerKm = 1160, liters = 4.4, pricePerLiter = 22500, totalVnd = 99000, fullTank = true, dateMs = now - 5 * day, isDemo = true))
        d.upsertMaintenance(MaintenanceItem(name = "Thay dầu (DEMO)", intervalKm = 2000, intervalDays = 180, lastDoneOdoKm = 0, lastDoneMs = now - 60 * day, isDemo = true))
    }
    suspend fun clear(d: AppDao) { d.deleteDemoPoints(); d.deleteDemoTrips(); d.deleteDemoFuel(); d.deleteDemoMaintenance() }
}
