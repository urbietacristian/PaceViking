package com.example.paceviking

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.paceviking.data.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * [isBlock] is UI-only: blocks created with "Añadir bloque" keep their block
 * frame even with one phase and one repetition; on reopening the editor it is
 * re-derived from the persisted shape.
 *
 * [key] is a client-side stable identity for drag-to-reorder; it is not
 * persisted (order comes from list position at save time). Phases reuse their
 * [WorkoutPhase.id] as the reorder key — new phases get a unique negative temp
 * id since the DAO re-zeroes ids on save.
 */
data class EditorBlock(
    val key: Long,
    val repetitions: Int,
    val phases: List<WorkoutPhase>,
    val isBlock: Boolean
)

data class EditorState(
    val session: WorkoutSession,
    val title: String,
    val blocks: List<EditorBlock>
)

class WorkoutViewModel(application: Application) : AndroidViewModel(application) {

    private val db = WorkoutDatabase.getDatabase(application)
    private val dao = db.workoutDao()
    private val engine = (application as PaceVikingApplication).workoutEngine

    // Sessions from Database. Null until the first database emission so the UI
    // can distinguish "loading" from "no sessions".
    val sessions: StateFlow<List<WorkoutSession>?> = dao.getAllSessions()
        .map<List<WorkoutSession>, List<WorkoutSession>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Workout state is owned by the process-level WorkoutEngine; re-exposed for the UI.
    val workoutStatus: StateFlow<WorkoutStatus> = engine.status
    val currentPhases: StateFlow<List<TimelinePhase>> = engine.phases
    val currentPhaseIndex: StateFlow<Int> = engine.phaseIndex
    val currentPhase: StateFlow<TimelinePhase?> = engine.currentPhase
    val timeLeftSeconds: StateFlow<Int> = engine.timeLeftSeconds
    val isPaused: StateFlow<Boolean> = engine.isPaused

    // Editor state lives here (not in the composable) so in-progress edits
    // survive configuration changes like rotation.
    private val _editorState = mutableStateOf<EditorState?>(null)
    val editorState: State<EditorState?> = _editorState

    // Id of a just-created phase the editor should scroll into view; cleared
    // once it has been centred.
    private val _pendingScrollPhaseId = mutableStateOf<Long?>(null)
    val pendingScrollPhaseId: State<Long?> = _pendingScrollPhaseId

    // Just-created phase to flash green; cleared by the editor once the flash
    // has played.
    private val _highlightPhaseId = mutableStateOf<Long?>(null)
    val highlightPhaseId: State<Long?> = _highlightPhaseId

    // Deletions are deferred so the card can flash red first: the editor waits
    // out the animation and then calls the matching commit.
    private val _deletingPhaseId = mutableStateOf<Long?>(null)
    val deletingPhaseId: State<Long?> = _deletingPhaseId
    private val _deletingBlockKey = mutableStateOf<Long?>(null)
    val deletingBlockKey: State<Long?> = _deletingBlockKey

    // One-shot message shown as a snackbar.
    private val _userMessage = mutableStateOf<String?>(null)
    val userMessage: State<String?> = _userMessage

    // Monotonic sources of stable editor identities (main-thread only). Block
    // keys are transient; phase temp ids are negative so they never collide
    // with real (positive) DB ids and are discarded by the DAO on save.
    private var nextBlockKeyValue = 0L
    private fun nextBlockKey(): Long = nextBlockKeyValue++
    private var nextTempPhaseIdValue = -1L
    private fun nextTempPhaseId(): Long = nextTempPhaseIdValue--

    init {
        viewModelScope.launch {
            engine.completed.collect { completed ->
                if (completed) {
                    _userMessage.value = "¡Entrenamiento completado!"
                    engine.acknowledgeCompletion()
                }
            }
        }
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    // Session Management
    fun saveSession(session: WorkoutSession, blocks: List<EditorBlock>) {
        viewModelScope.launch {
            // Re-number block and phase orderIndex from list position:
            // deletes/adds in the editor can leave duplicate indices, making
            // the persisted order unstable.
            val ordered = blocks.mapIndexed { blockIdx, block ->
                BlockWithPhases(
                    block = WorkoutBlock(
                        sessionId = session.id,
                        repetitions = block.repetitions,
                        orderIndex = blockIdx
                    ),
                    phases = block.phases.mapIndexed { phaseIdx, p -> p.copy(orderIndex = phaseIdx) }
                )
            }
            dao.updateSessionWithBlocks(session, ordered)
        }
    }

    fun deleteSession(session: WorkoutSession) {
        viewModelScope.launch {
            dao.deleteSession(session)
        }
    }

    fun createDefaultSession() {
        fun phase(type: PhaseType, seconds: Int, zone: HrZone) =
            WorkoutPhase(blockId = 0, type = type, durationSeconds = seconds, targetHrZone = zone, orderIndex = 0)

        val defaultSession = WorkoutSession(title = "Protocolo Nórdico (4x4)")
        // 4 work intervals with 3 recoveries between them: a 3× block plus the
        // final standalone work phase keeps the protocol exact.
        val defaultBlocks = listOf(
            EditorBlock(nextBlockKey(), 1, listOf(phase(PhaseType.WARM_UP, 600, HrZone.ZONE_2)), isBlock = false),
            EditorBlock(
                nextBlockKey(),
                3,
                listOf(
                    phase(PhaseType.WORK, 240, HrZone.ZONE_4),
                    phase(PhaseType.RECOVERY, 180, HrZone.ZONE_2)
                ),
                isBlock = true
            ),
            EditorBlock(nextBlockKey(), 1, listOf(phase(PhaseType.WORK, 240, HrZone.ZONE_4)), isBlock = false),
            EditorBlock(nextBlockKey(), 1, listOf(phase(PhaseType.COOL_DOWN, 300, HrZone.ZONE_1)), isBlock = false)
        )
        saveSession(defaultSession, defaultBlocks)
    }

    // Editor
    private fun resetEditorFeedback() {
        _pendingScrollPhaseId.value = null
        _highlightPhaseId.value = null
        _deletingPhaseId.value = null
        _deletingBlockKey.value = null
    }

    fun openEditor(session: WorkoutSession) {
        viewModelScope.launch {
            val blocks = dao.getBlocksWithPhasesForSession(session.id).map { entry ->
                EditorBlock(
                    key = nextBlockKey(),
                    repetitions = entry.block.repetitions,
                    phases = entry.phases.sortedBy { it.orderIndex },
                    isBlock = entry.block.repetitions > 1 || entry.phases.size > 1
                )
            }
            resetEditorFeedback()
            _editorState.value = EditorState(session, session.title, blocks)
        }
    }

    fun openEditorForNew() {
        val session = WorkoutSession(title = "Nueva Sesión")
        resetEditorFeedback()
        _editorState.value = EditorState(session, session.title, emptyList())
    }

    fun updateEditorTitle(title: String) {
        _editorState.value = _editorState.value?.copy(title = title)
    }

    private fun newDefaultPhase() = WorkoutPhase(
        id = nextTempPhaseId(),
        blockId = 0,
        type = PhaseType.WORK,
        durationSeconds = 60,
        targetHrZone = HrZone.NONE,
        orderIndex = 0
    )

    // Every creation announces the new phase so the editor can scroll it into
    // view and flash it.
    private fun announceCreated(phase: WorkoutPhase) {
        _pendingScrollPhaseId.value = phase.id
        _highlightPhaseId.value = phase.id
    }

    fun addEditorPhase() {
        val editor = _editorState.value ?: return
        val phase = newDefaultPhase()
        _editorState.value = editor.copy(
            blocks = editor.blocks + EditorBlock(nextBlockKey(), 1, listOf(phase), isBlock = false)
        )
        announceCreated(phase)
    }

    fun addEditorBlock() {
        val editor = _editorState.value ?: return
        val phase = newDefaultPhase()
        _editorState.value = editor.copy(
            blocks = editor.blocks + EditorBlock(nextBlockKey(), 2, listOf(phase), isBlock = true)
        )
        announceCreated(phase)
    }

    /** Moves a top-level block/phase from one position to another (drag reorder). */
    fun moveBlock(fromIndex: Int, toIndex: Int) {
        val editor = _editorState.value ?: return
        val blocks = editor.blocks
        if (fromIndex !in blocks.indices || toIndex !in blocks.indices) return
        _editorState.value = editor.copy(
            blocks = blocks.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        )
    }

    /** Moves a phase within a block from one position to another (drag reorder). */
    fun movePhaseInBlock(blockIndex: Int, fromIndex: Int, toIndex: Int) {
        val editor = _editorState.value ?: return
        _editorState.value = editor.copy(blocks = editor.blocks.mapIndexed { i, block ->
            if (i != blockIndex) block
            else {
                if (fromIndex !in block.phases.indices || toIndex !in block.phases.indices) block
                else block.copy(phases = block.phases.toMutableList().apply { add(toIndex, removeAt(fromIndex)) })
            }
        })
    }

    fun updateBlockRepetitions(blockIndex: Int, repetitions: Int) {
        val editor = _editorState.value ?: return
        _editorState.value = editor.copy(blocks = editor.blocks.mapIndexed { i, block ->
            if (i == blockIndex) block.copy(repetitions = repetitions.coerceIn(1, 99)) else block
        })
    }

    fun addPhaseToBlock(blockIndex: Int) {
        val editor = _editorState.value ?: return
        val phase = newDefaultPhase()
        _editorState.value = editor.copy(blocks = editor.blocks.mapIndexed { i, block ->
            if (i == blockIndex) block.copy(phases = block.phases + phase) else block
        })
        announceCreated(phase)
    }

    /**
     * Inserts an identical copy right after the given phase. A standalone phase
     * is duplicated as another standalone phase (its own block); a phase inside
     * a block is duplicated within that block. The copy's temp id is published
     * as [pendingScrollPhaseId] so the editor can scroll it into view.
     */
    fun duplicateEditorPhase(blockIndex: Int, phaseIndex: Int) {
        val editor = _editorState.value ?: return
        val block = editor.blocks.getOrNull(blockIndex) ?: return
        val original = block.phases.getOrNull(phaseIndex) ?: return
        val copy = original.copy(id = nextTempPhaseId())
        val blocks = if (!block.isBlock) {
            editor.blocks.toMutableList().apply {
                add(blockIndex + 1, EditorBlock(nextBlockKey(), 1, listOf(copy), isBlock = false))
            }
        } else {
            editor.blocks.mapIndexed { i, b ->
                if (i != blockIndex) b
                else b.copy(phases = b.phases.toMutableList().apply { add(phaseIndex + 1, copy) })
            }
        }
        _editorState.value = editor.copy(blocks = blocks)
        announceCreated(copy)
    }

    /** Cleared by the editor once the new phase has been scrolled to. */
    fun clearPendingScroll() {
        _pendingScrollPhaseId.value = null
    }

    /** Cleared by the editor once the creation flash has played. */
    fun clearHighlight() {
        _highlightPhaseId.value = null
    }

    /** Marks a phase as deleting; [commitPhaseDeletion] removes it for good. */
    fun requestRemoveEditorPhase(blockIndex: Int, phaseIndex: Int) {
        // A deletion still playing is committed now, so its card can't be left
        // tinted red forever by a second delete tap.
        commitPhaseDeletion()
        val editor = _editorState.value ?: return
        val phase = editor.blocks.getOrNull(blockIndex)?.phases?.getOrNull(phaseIndex) ?: return
        _deletingPhaseId.value = phase.id
    }

    fun commitPhaseDeletion() {
        val id = _deletingPhaseId.value ?: return
        _deletingPhaseId.value = null
        val editor = _editorState.value ?: return
        // Matched by id, not index: the list may have been reordered while the
        // deletion animation played. Removing a block's last phase removes the
        // block itself.
        _editorState.value = editor.copy(blocks = editor.blocks.mapNotNull { block ->
            if (block.phases.none { it.id == id }) block
            else {
                val remaining = block.phases.filter { it.id != id }
                if (remaining.isEmpty()) null else block.copy(phases = remaining)
            }
        })
    }

    /** Marks a block as deleting; [commitBlockDeletion] removes it for good. */
    fun requestRemoveEditorBlock(blockIndex: Int) {
        commitBlockDeletion()
        val editor = _editorState.value ?: return
        _deletingBlockKey.value = editor.blocks.getOrNull(blockIndex)?.key ?: return
    }

    fun commitBlockDeletion() {
        val key = _deletingBlockKey.value ?: return
        _deletingBlockKey.value = null
        val editor = _editorState.value ?: return
        _editorState.value = editor.copy(blocks = editor.blocks.filter { it.key != key })
    }

    fun updateEditorPhase(blockIndex: Int, phaseIndex: Int, phase: WorkoutPhase) {
        val editor = _editorState.value ?: return
        _editorState.value = editor.copy(blocks = editor.blocks.mapIndexed { i, block ->
            if (i != blockIndex) block
            else block.copy(phases = block.phases.mapIndexed { j, p -> if (j == phaseIndex) phase else p })
        })
    }


    fun saveEditor() {
        // Saving mid-animation must not resurrect a phase already deleted.
        commitPhaseDeletion()
        commitBlockDeletion()
        val editor = _editorState.value ?: return
        val allPhases = editor.blocks.flatMap { it.phases }
        if (allPhases.any { it.durationSeconds <= 0 }) {
            _userMessage.value = "Cada fase debe durar al menos 1 segundo."
            return
        }
        if (allPhases.any { (it.speedKmh ?: 0.0) !in 0.0..25.0 }) {
            _userMessage.value = "La velocidad debe estar entre 0.0 y 25.0 km/h."
            return
        }
        saveSession(editor.session.copy(title = editor.title), editor.blocks)
        _editorState.value = null
    }

    fun cancelEditor() {
        _editorState.value = null
    }

    // Workout Logic

    /** Loads the session into the engine and shows the READY screen. */
    fun startWorkout(session: WorkoutSession) {
        if (!engine.beginLoading()) return
        viewModelScope.launch {
            val timeline = flattenToTimeline(dao.getBlocksWithPhasesForSession(session.id))
            if (timeline.isEmpty()) {
                engine.abortLoading()
                _userMessage.value = "La sesión no tiene fases. Edítala y añade fases antes de empezar."
                return@launch
            }
            engine.load(session.title, timeline)
        }
    }

    /** Starts the countdown (INICIAR button) and the foreground service. */
    fun beginWorkout() {
        engine.begin()
        val app = getApplication<Application>()
        ContextCompat.startForegroundService(app, Intent(app, WorkoutService::class.java))
    }

    fun skipToNextPhase() = engine.skipToNextPhase()

    fun pauseWorkout() = engine.pause()

    fun resumeWorkout() = engine.resume()

    fun resetWorkout() = engine.reset()
}
