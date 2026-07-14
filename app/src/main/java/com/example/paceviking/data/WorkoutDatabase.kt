package com.example.paceviking.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM sessions ORDER BY id ASC")
    fun getAllSessions(): Flow<List<WorkoutSession>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: WorkoutSession): Long

    @Delete
    suspend fun deleteSession(session: WorkoutSession)

    @Query("SELECT * FROM phases WHERE sessionId = :sessionId ORDER BY orderIndex ASC")
    fun getPhasesForSession(sessionId: Long): Flow<List<WorkoutPhase>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhases(phases: List<WorkoutPhase>)

    @Query("DELETE FROM phases WHERE sessionId = :sessionId")
    suspend fun deletePhasesForSession(sessionId: Long)
    
    @Transaction
    suspend fun updateSessionWithPhases(session: WorkoutSession, phases: List<WorkoutPhase>) {
        val id = insertSession(session)
        deletePhasesForSession(id)
        insertPhases(phases.map { it.copy(sessionId = id) })
    }
}

@Database(entities = [WorkoutSession::class, WorkoutPhase::class], version = 2)
abstract class WorkoutDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE phases ADD COLUMN speedKmh REAL")
            }
        }

        @Volatile
        private var INSTANCE: WorkoutDatabase? = null

        fun getDatabase(context: Context): WorkoutDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WorkoutDatabase::class.java,
                    "workout_database"
                ).addMigrations(MIGRATION_1_2).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
