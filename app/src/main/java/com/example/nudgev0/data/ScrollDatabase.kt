package com.example.nudgev0.data

import android.content.Context // <-- Add this import
import androidx.room.*

// THE DATA MODEL (This part is perfect)
@Entity(tableName = "scroll_history")
data class ScrollDay(
    @PrimaryKey val date: String, // e.g., "2026-02-11"
    val count: Int
)

// THE QUERIES (DAO) (This part is perfect)
@Dao
interface ScrollDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(day: ScrollDay)

    @Query("SELECT AVG(count) FROM scroll_history WHERE date >= :startDate")
    suspend fun getAverageSince(startDate: String): Double?

    @Query("SELECT * FROM scroll_history ORDER BY date DESC LIMIT 7")
    fun getLast7Days(): kotlinx.coroutines.flow.Flow<List<ScrollDay>>

    @Query("SELECT * FROM scroll_history WHERE date >= :startDate ORDER BY date ASC")
    fun getHistorySince(startDate: String): kotlinx.coroutines.flow.Flow<List<ScrollDay>>
}

// THE DATABASE INSTANCE (This is the corrected part)
@Database(entities = [ScrollDay::class], version = 1)
abstract class ScrollDatabase : RoomDatabase() { // <-- RENAMED THE CLASS

    abstract fun scrollDao(): ScrollDao

    // Companion object to create and manage the single database instance
    companion object {
        @Volatile
        private var INSTANCE: ScrollDatabase? = null

        fun getDatabase(context: Context): ScrollDatabase {
            // Return the existing instance if it's already created
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ScrollDatabase::class.java,
                    "scroll_database" // This is the name of the actual database file on the device
                ).build()
                INSTANCE = instance
                // return instance
                instance
            }
        }
    }
}
