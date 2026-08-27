package com.example.paceviking.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

enum class PhaseType {
    WARM_UP, WORK, RECOVERY, COOL_DOWN
}

enum class HrZone {
    ZONE_1, ZONE_2, ZONE_3, ZONE_4, ZONE_5, NONE
}

/**
 * [orderIndex] is the position the list shows the session in — the user sets it
 * by dragging in the list's edit mode. A new session is appended past the
 * current maximum, so insertion order still decides where it lands.
 */
@Entity(tableName = "sessions")
data class WorkoutSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val orderIndex: Int = 0
)

/**
 * A group of phases repeated [repetitions] times. A standalone phase is just a
 * block with one phase and repetitions = 1 — the data layer has no special case.
 */
@Entity(
    tableName = "blocks",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class WorkoutBlock(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val repetitions: Int,
    val orderIndex: Int
)

@Entity(
    tableName = "phases",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutBlock::class,
            parentColumns = ["id"],
            childColumns = ["blockId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("blockId")]
)
data class WorkoutPhase(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val blockId: Long,
    val type: PhaseType,
    val durationSeconds: Int,
    val targetHrZone: HrZone,
    val orderIndex: Int,
    // Recommended treadmill speed in km/h (one decimal); null = no recommendation.
    val speedKmh: Double? = null
)

data class BlockWithPhases(
    @Embedded val block: WorkoutBlock,
    // @Relation does not guarantee order — sort by orderIndex before using.
    @Relation(parentColumn = "id", entityColumn = "blockId")
    val phases: List<WorkoutPhase>
)

/**
 * One entry of the expanded runtime timeline. Repetition is an editor/database
 * concept: at runtime blocks are flattened so the engine, progress bar and
 * notifications keep walking a plain list. [repetition] is 1-based.
 */
data class TimelinePhase(
    val phase: WorkoutPhase,
    val repetition: Int,
    val totalRepetitions: Int
)

fun flattenToTimeline(blocks: List<BlockWithPhases>): List<TimelinePhase> =
    blocks.sortedBy { it.block.orderIndex }.flatMap { entry ->
        val ordered = entry.phases.sortedBy { it.orderIndex }
        (1..entry.block.repetitions).flatMap { rep ->
            ordered.map { TimelinePhase(it, rep, entry.block.repetitions) }
        }
    }
