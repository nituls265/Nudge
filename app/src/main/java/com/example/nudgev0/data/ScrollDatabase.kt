package com.example.nudgev0.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ── Scroll entities ──────────────────────────────────────────────────────────

@Entity(tableName = "scroll_history")
data class ScrollDay(
    @PrimaryKey val date: String,
    val count: Int
)

@Entity(tableName = "scroll_hours", primaryKeys = ["date", "hour"])
data class ScrollHour(
    val date: String,
    val hour: Int,
    val count: Int
)

data class HourTotal(val hour: Int, val total: Int)

@Dao
interface ScrollDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(day: ScrollDay)

    @Query("SELECT * FROM scroll_history ORDER BY date DESC LIMIT 7")
    fun getLast7Days(): Flow<List<ScrollDay>>

    @Query("SELECT * FROM scroll_history WHERE date >= :startDate ORDER BY date ASC")
    fun getHistorySince(startDate: String): Flow<List<ScrollDay>>

    @Query("SELECT * FROM scroll_history WHERE date = :date")
    suspend fun getDay(date: String): ScrollDay?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateHour(hour: ScrollHour)

    @Query("SELECT * FROM scroll_hours WHERE date = :date")
    suspend fun getHoursForDate(date: String): List<ScrollHour>

    @Query("SELECT hour, SUM(count) as total FROM scroll_hours WHERE date >= :startDate GROUP BY hour ORDER BY total DESC LIMIT 1")
    fun getPeakHourSince(startDate: String): Flow<HourTotal?>

    @Query("SELECT hour, SUM(count) as total FROM scroll_hours WHERE date = :date GROUP BY hour ORDER BY total DESC LIMIT 1")
    suspend fun getPeakHourForDate(date: String): HourTotal?

    @Query("DELETE FROM scroll_hours WHERE date = :date")
    suspend fun deleteHoursForDate(date: String)
}

// ── Unlock entities ──────────────────────────────────────────────────────────

@Entity(tableName = "unlock_history")
data class UnlockDay(
    @PrimaryKey val date: String,
    val count: Int,
    val firstUnlockMs: Long,
    val lastUnlockMs: Long,
    val avgSessionMin: Float,
    val longestSessionMin: Int
)

@Entity(tableName = "unlock_hours", primaryKeys = ["date", "hour"])
data class UnlockHour(
    val date: String,
    val hour: Int,
    val count: Int
)

@Dao
interface UnlockDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(day: UnlockDay)

    @Query("SELECT * FROM unlock_history WHERE date >= :startDate ORDER BY date ASC")
    fun getHistorySince(startDate: String): Flow<List<UnlockDay>>

    @Query("SELECT * FROM unlock_history WHERE date = :date")
    suspend fun getDay(date: String): UnlockDay?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateHour(hour: UnlockHour)

    @Query("SELECT * FROM unlock_hours WHERE date = :date")
    suspend fun getHoursForDate(date: String): List<UnlockHour>

    @Query("SELECT hour, SUM(count) as total FROM unlock_hours WHERE date >= :startDate GROUP BY hour ORDER BY total DESC LIMIT 1")
    fun getPeakHourSince(startDate: String): Flow<HourTotal?>

    @Query("SELECT hour, SUM(count) as total FROM unlock_hours WHERE date = :date GROUP BY hour ORDER BY total DESC LIMIT 1")
    suspend fun getPeakHourForDate(date: String): HourTotal?

    @Query("DELETE FROM unlock_hours WHERE date = :date")
    suspend fun deleteHoursForDate(date: String)
}

// ── App scroll entities ───────────────────────────────────────────────────────

@Entity(tableName = "app_scroll_history", primaryKeys = ["date", "packageName"])
data class AppScrollDay(
    val date: String,
    val packageName: String,
    val count: Int
)

data class AppScrollTotal(val packageName: String, val total: Int)

@Dao
interface AppScrollDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entry: AppScrollDay)

    @Query("SELECT packageName, SUM(count) as total FROM app_scroll_history WHERE date >= :startDate AND date < :endDate GROUP BY packageName ORDER BY total DESC")
    fun getTotalsBetween(startDate: String, endDate: String): Flow<List<AppScrollTotal>>

    @Query("DELETE FROM app_scroll_history WHERE date = :date")
    suspend fun deleteForDate(date: String)
}

// ── Wellness entities ─────────────────────────────────────────────────────────

@Entity(tableName = "wellness_history")
data class WellnessDay(
    @PrimaryKey val date: String,
    val score: Int,
    val tier: String,               // WellnessTier.name
    val scrollVolume: Int,
    val sessionBehaviour: Int,
    val unlockFrequency: Int,
    val timeHygiene: Int,
    val appQuality: Int,
    val bedtimeScore: Int = -1,       // 0–10 Time Hygiene sub-component; -1 = pre-migration row
    val gapScore: Int = -1,           // 0–6  Time Hygiene sub-component; -1 = pre-migration row
    val consistencyScore: Int = -1    // 0–4  Time Hygiene sub-component; -1 = pre-migration row
)

@Dao
interface WellnessDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(day: WellnessDay)

    @Query("SELECT * FROM wellness_history WHERE date >= :startDate ORDER BY date ASC")
    fun getHistorySince(startDate: String): Flow<List<WellnessDay>>

    @Query("SELECT * FROM wellness_history WHERE date = :date")
    suspend fun getDay(date: String): WellnessDay?
}

// ── Migrations ───────────────────────────────────────────────────────────────

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `scroll_hours` (`date` TEXT NOT NULL, `hour` INTEGER NOT NULL, `count` INTEGER NOT NULL, PRIMARY KEY(`date`, `hour`))"
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `unlock_history` (`date` TEXT NOT NULL, `count` INTEGER NOT NULL, `firstUnlockMs` INTEGER NOT NULL, `lastUnlockMs` INTEGER NOT NULL, `avgSessionMin` REAL NOT NULL, `longestSessionMin` INTEGER NOT NULL, PRIMARY KEY(`date`))"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `unlock_hours` (`date` TEXT NOT NULL, `hour` INTEGER NOT NULL, `count` INTEGER NOT NULL, PRIMARY KEY(`date`, `hour`))"
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `app_scroll_history` (`date` TEXT NOT NULL, `packageName` TEXT NOT NULL, `count` INTEGER NOT NULL, PRIMARY KEY(`date`, `packageName`))"
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `wellness_history` (`date` TEXT NOT NULL, `score` INTEGER NOT NULL, `tier` TEXT NOT NULL, `scrollVolume` INTEGER NOT NULL, `sessionBehaviour` INTEGER NOT NULL, `unlockFrequency` INTEGER NOT NULL, `timeHygiene` INTEGER NOT NULL, `appQuality` INTEGER NOT NULL, PRIMARY KEY(`date`))"
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE wellness_history ADD COLUMN bedtimeScore INTEGER NOT NULL DEFAULT -1")
        database.execSQL("ALTER TABLE wellness_history ADD COLUMN gapScore INTEGER NOT NULL DEFAULT -1")
        database.execSQL("ALTER TABLE wellness_history ADD COLUMN consistencyScore INTEGER NOT NULL DEFAULT -1")
    }
}

// ── Database ─────────────────────────────────────────────────────────────────

@Database(
    entities = [ScrollDay::class, ScrollHour::class, UnlockDay::class, UnlockHour::class, AppScrollDay::class, WellnessDay::class],
    version = 6,
    exportSchema = false
)
abstract class ScrollDatabase : RoomDatabase() {
    abstract fun scrollDao(): ScrollDao
    abstract fun unlockDao(): UnlockDao
    abstract fun appScrollDao(): AppScrollDao
    abstract fun wellnessDao(): WellnessDao

    companion object {
        @Volatile private var INSTANCE: ScrollDatabase? = null

        fun getDatabase(context: Context): ScrollDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ScrollDatabase::class.java,
                    "scroll_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
