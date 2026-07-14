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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EditorState(
    val session: WorkoutSession,
    val title: String,
    val phases: List<WorkoutPhase>
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
    val currentPhases: StateFlow<List<WorkoutPhase>> = engine.phases
    val currentPhaseIndex: StateFlow<Int> = engine.phaseIndex
    val currentPhase: StateFlow<WorkoutPhase?> = engine.currentPhase
    val timeLeftSeconds: StateFlow<Int> = engine.timeLeftSeconds
    val isPaused: StateFlow<Boolean> = engine.isPaused

    // Editor state lives here (not in the composable) so in-progress edits
    // survive configuration changes like rotation.
    private val _editorState = mutableStateOf<EditorState?>(null)
    val editorState: State<EditorState?> = _editorState

    // One-shot message shown as a snackbar.
    private val _userMessage = mutableStateOf<String?>(null)
    val userMessage: State<String?> = _userMessage

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
    fun saveSession(session: WorkoutSession, phases: List<WorkoutPhase>) {
        viewModelScope.launch {
            // Re-number orderIndex from list position: deletes/adds in the editor
            // can leave duplicate indices, making the persisted order unstable.
            dao.updateSessionWithPhases(session, phases.mapIndexed { i, p -> p.copy(orderIndex = i) })
        }
    }

    fun deleteSession(session: WorkoutSession) {
        viewModelScope.launch {
            dao.deleteSession(session)
        }
    }

    fun createDefaultSession() {
        val defaultSession = WorkoutSession(title = "Protocolo Nórdico (4x4)")
        val defaultPhases = listOf(
            WorkoutPhase(sessionId = 0, type = PhaseType.WARM_UP, durationSeconds = 600, targetHrZone = HrZone.ZONE_2, orderIndex = 0),
            WorkoutPhase(sessionId = 0, type = PhaseType.WORK, durationSeconds = 240, targetHrZone = HrZone.ZONE_4, orderIndex = 1),
            WorkoutPhase(sessionId = 0, type = PhaseType.RECOVERY, durationSeconds = 180, targetHrZone = HrZone.ZONE_2, orderIndex = 2),
            WorkoutPhase(sessionId = 0, type = PhaseType.WORK, durationSeconds = 240, targetHrZone = HrZone.ZONE_4, orderIndex = 3),
            WorkoutPhase(sessionId = 0, type = PhaseType.RECOVERY, durationSeconds = 180, targetHrZone = HrZone.ZONE_2, orderIndex = 4),
            WorkoutPhase(sessionId = 0, type = PhaseType.WORK, durationSeconds = 240, targetHrZone = HrZone.ZONE_4, orderIndex = 5),
            WorkoutPhase(sessionId = 0, type = PhaseType.RECOVERY, durationSeconds = 180, targetHrZone = HrZone.ZONE_2, orderIndex = 6),
            WorkoutPhase(sessionId = 0, type = PhaseType.WORK, durationSeconds = 240, targetHrZone = HrZone.ZONE_4, orderIndex = 7),
            WorkoutPhase(sessionId = 0, type = PhaseType.COOL_DOWN, durationSeconds = 300, targetHrZone = HrZone.ZONE_1, orderIndex = 8)
        )
        saveSession(defaultSession, defaultPhases)
    }

    // Editor
    fun openEditor(session: WorkoutSession) {
        viewModelScope.launch {
            val phases = dao.getPhasesForSession(session.id).first()
            _editorState.value = EditorState(session, session.title, phases)
        }
    }

    fun openEditorForNew() {
        val session = WorkoutSession(title = "Nueva Sesión")
        _editorState.value = EditorState(session, session.title, emptyList())
    }

    fun updateEditorTitle(title: String) {
        _editorState.value = _editorState.value?.copy(title = title)
    }

    fun updateEditorPhase(index: Int, phase: WorkoutPhase) {
        val editor = _editorState.value ?: return
        _editorState.value = editor.copy(phases = editor.phases.mapIndexed { i, p -> if (i == index) phase else p })
    }

    fun removeEditorPhase(index: Int) {
        val editor = _editorState.value ?: return
        _editorState.value = editor.copy(phases = editor.phases.filterIndexed { i, _ -> i != index })
    }

    fun addEditorPhase() {
        val editor = _editorState.value ?: return
        val newPhase = WorkoutPhase(
            sessionId = editor.session.id,
            type = PhaseType.WORK,
            durationSeconds = 60,
            targetHrZone = HrZone.NONE,
            orderIndex = editor.phases.size
        )
        _editorState.value = editor.copy(phases = editor.phases + newPhase)
    }

    fun saveEditor() {
        val editor = _editorState.value ?: return
        if (editor.phases.any { it.durationSeconds <= 0 }) {
            _userMessage.value = "Cada fase debe durar al menos 1 segundo."
            return
        }
        if (editor.phases.any { (it.speedKmh ?: 0.0) !in 0.0..25.0 }) {
            _userMessage.value = "La velocidad debe estar entre 0.0 y 25.0 km/h."
            return
        }
        saveSession(editor.session.copy(title = editor.title), editor.phases)
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
            val phases = dao.getPhasesForSession(session.id).first()
            if (phases.isEmpty()) {
                engine.abortLoading()
                _userMessage.value = "La sesión no tiene fases. Edítala y añade fases antes de empezar."
                return@launch
            }
            engine.load(session.title, phases)
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
