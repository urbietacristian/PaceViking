package com.example.paceviking

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.paceviking.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WorkoutViewModel(application: Application) : AndroidViewModel(application) {

    private val db = WorkoutDatabase.getDatabase(application)
    private val dao = db.workoutDao()

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (application.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        application.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private fun alertPhaseChange() {
        // Three short pulses: 0ms delay, 100ms on, 100ms off, 100ms on, 100ms off, 100ms on
        val pattern = longArrayOf(0, 100, 100, 100, 100, 100)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }

        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
            toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
            toneGen.release()
        } catch (_: Exception) { }
    }

    // Sessions from Database
    val sessions: StateFlow<List<WorkoutSession>> = dao.getAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Workout State
    private val _currentPhaseIndex = mutableIntStateOf(-1)
    val currentPhaseIndex: State<Int> = _currentPhaseIndex

    private val _currentPhases = mutableStateOf<List<WorkoutPhase>>(emptyList())
    val currentPhases: State<List<WorkoutPhase>> = _currentPhases

    private val _currentPhase = mutableStateOf<WorkoutPhase?>(null)
    val currentPhase: State<WorkoutPhase?> = _currentPhase

    private val _timeLeftSeconds = mutableIntStateOf(0)
    val timeLeftSeconds: State<Int> = _timeLeftSeconds

    private val _isPaused = mutableStateOf(false)
    val isPaused: State<Boolean> = _isPaused

    private var timerJob: Job? = null

    // Session Management
    fun saveSession(session: WorkoutSession, phases: List<WorkoutPhase>) {
        viewModelScope.launch {
            dao.updateSessionWithPhases(session, phases)
        }
    }

    fun deleteSession(session: WorkoutSession) {
        viewModelScope.launch {
            dao.deleteSession(session)
        }
    }

    suspend fun getPhasesForSession(sessionId: Long): List<WorkoutPhase> {
        return dao.getPhasesForSession(sessionId).first()
    }

    // Workout Logic
    fun startWorkout(sessionId: Long) {
        viewModelScope.launch {
            val phases = dao.getPhasesForSession(sessionId).first()
            if (phases.isNotEmpty()) {
                _currentPhases.value = phases
                _currentPhaseIndex.intValue = 0
                _currentPhase.value = phases[0]
                _timeLeftSeconds.intValue = phases[0].durationSeconds
                _isPaused.value = false
                startTimer()
            }
        }
    }

    fun pauseWorkout() {
        _isPaused.value = true
        timerJob?.cancel()
    }

    fun resumeWorkout() {
        _isPaused.value = false
        startTimer()
    }

    fun resetWorkout() {
        timerJob?.cancel()
        _currentPhaseIndex.intValue = -1
        _currentPhase.value = null
        _timeLeftSeconds.intValue = 0
        _isPaused.value = false
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive && _timeLeftSeconds.intValue > 0) {
                delay(1000)
                _timeLeftSeconds.intValue -= 1
            }
            if (_timeLeftSeconds.intValue == 0) {
                transitionToNextPhase()
            }
        }
    }

    private fun transitionToNextPhase() {
        alertPhaseChange()
        val nextIndex = _currentPhaseIndex.intValue + 1
        if (nextIndex < _currentPhases.value.size) {
            _currentPhaseIndex.intValue = nextIndex
            val nextPhase = _currentPhases.value[nextIndex]
            _currentPhase.value = nextPhase
            _timeLeftSeconds.intValue = nextPhase.durationSeconds
            startTimer()
        } else {
            // Finished
            resetWorkout()
        }
    }
}
