package com.example.paceviking.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {
    // id breaks ties so the order stays stable if two sessions ever share an
    // index (they shouldn't: reorderSessions re-numbers from list position).
    @Query("SELECT * FROM sessions ORDER BY orderIndex ASC, id ASC")
    fun getAllSessions(): Flow<List<WorkoutSession>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: WorkoutSession): Long

    @Delete
    suspend fun deleteSession(session: WorkoutSession)

    /** -1 when there are no sessions, so "+ 1" is the first index. */
    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM sessions")
    suspend fun maxSessionOrderIndex(): Int

    @Query("UPDATE sessions SET orderIndex = :orderIndex WHERE id = :id")
    suspend fun setSessionOrderIndex(id: Long, orderIndex: Int)

    /** Re-numbers every session from its position in [sessionIds]. */
    @Transaction
    suspend fun reorderSessions(sessionIds: List<Long>) {
        sessionIds.forEachIndexed { index, id -> setSessionOrderIndex(id, index) }
    }

    @Transaction
    @Query("SELECT * FROM blocks WHERE sessionId = :sessionId ORDER BY orderIndex ASC")
    suspend fun getBlocksWithPhasesForSession(sessionId: Long): List<BlockWithPhases>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlock(block: WorkoutBlock): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhases(phases: List<WorkoutPhase>)

    @Query("DELETE FROM blocks WHERE sessionId = :sessionId")
    suspend fun deleteBlocksForSession(sessionId: Long)

    @Transaction
    suspend fun updateSessionWithBlocks(session: WorkoutSession, blocks: List<BlockWithPhases>) {
        val sessionId = insertSession(session)
        // Deleting the blocks cascades to their phases.
        deleteBlocksForSession(sessionId)
        blocks.forEach { entry ->
            val blockId = insertBlock(entry.block.copy(id = 0, sessionId = sessionId))
            insertPhases(entry.phases.map { it.copy(id = 0, blockId = blockId) })
        }
    }
}

@Database(entities = [WorkoutSession::class, WorkoutBlock::class, WorkoutPhase::class], version = 4)
abstract class WorkoutDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE phases ADD COLUMN speedKmh REAL")
            }
        }

        // Introduces blocks: every existing phase becomes its own block with
        // repetitions = 1, and phases are re-parented from sessionId to blockId.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `blocks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` INTEGER NOT NULL, " +
                        "`repetitions` INTEGER NOT NULL, " +
                        "`orderIndex` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_blocks_sessionId` ON `blocks` (`sessionId`)")
                db.execSQL(
                    "INSERT INTO blocks (sessionId, repetitions, orderIndex) " +
                        "SELECT sessionId, 1, orderIndex FROM phases"
                )
                // SQLite can't alter foreign keys, so rebuild the phases table.
                // The join on (sessionId, orderIndex) is unambiguous: saveSession
                // always persisted contiguous, unique orderIndex per session.
                db.execSQL(
                    "CREATE TABLE `phases_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`blockId` INTEGER NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`durationSeconds` INTEGER NOT NULL, " +
                        "`targetHrZone` TEXT NOT NULL, " +
                        "`orderIndex` INTEGER NOT NULL, " +
                        "`speedKmh` REAL, " +
                        "FOREIGN KEY(`blockId`) REFERENCES `blocks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO phases_new (id, blockId, type, durationSeconds, targetHrZone, orderIndex, speedKmh) " +
                        "SELECT p.id, b.id, p.type, p.durationSeconds, p.targetHrZone, 0, p.speedKmh " +
                        "FROM phases p JOIN blocks b ON b.sessionId = p.sessionId AND b.orderIndex = p.orderIndex"
                )
                db.execSQL("DROP TABLE phases")
                db.execSQL("ALTER TABLE phases_new RENAME TO phases")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_phases_blockId` ON `phases` (`blockId`)")
            }
        }

        // User-defined session order. The list used to be sorted by id, so
        // seeding the new column with it keeps every existing library in the
        // order its owner already knows.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN orderIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE sessions SET orderIndex = id")
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
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
