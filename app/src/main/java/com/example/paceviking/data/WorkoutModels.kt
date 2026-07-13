package com.example.paceviking.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

enum class PhaseType {
    WARM_UP, WORK, RECOVERY, COOL_DOWN
}

enum class HrZone {
    ZONE_1, ZONE_2, ZONE_3, ZONE_4, ZONE_5, NONE
}

@Entity(tableName = "sessions")
data class WorkoutSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String
)

@Entity(
    tableName = "phases",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class WorkoutPhase(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val type: PhaseType,
    val durationSeconds: Int,
    val targetHrZone: HrZone,
    val orderIndex: Int
)

data class SessionWithPhases(
    val session: WorkoutSession,
    val phases: List<WorkoutPhase>
)
