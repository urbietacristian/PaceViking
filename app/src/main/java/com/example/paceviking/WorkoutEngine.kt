package com.example.paceviking

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.paceviking.data.TimelinePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class WorkoutStatus { IDLE, LOADING, READY, RUNNING }

/** Summary of a naturally finished workout, for the completion notification. */
data class CompletedWorkoutInfo(
    val sessionTitle: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long
)

/**
 * Process-level singleton (owned by [PaceVikingApplication]). The timer lives
 * here instead of the ViewModel so a workout keeps running while only
 * [WorkoutService] holds the process alive (Activity and ViewModel destroyed).
 */
class WorkoutEngine(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val _status = MutableStateFlow(WorkoutStatus.IDLE)
    val status: StateFlow<WorkoutStatus> = _status

    private val _phases = MutableStateFlow<List<TimelinePhase>>(emptyList())
    val phases: StateFlow<List<TimelinePhase>> = _phases

    private val _phaseIndex = MutableStateFlow(-1)
    val phaseIndex: StateFlow<Int> = _phaseIndex

    private val _currentPhase = MutableStateFlow<TimelinePhase?>(null)
    val currentPhase: StateFlow<TimelinePhase?> = _currentPhase

    private val _timeLeftSeconds = MutableStateFlow(0)
    val timeLeftSeconds: StateFlow<Int> = _timeLeftSeconds

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused

    // Latched on natural completion; stays set until acknowledged so the UI
    // still sees it if the workout finished while no Activity was alive.
    private val _completed = MutableStateFlow(false)
    val completed: StateFlow<Boolean> = _completed

    // Fires once per advance into a new phase (index, phase); buffered so
    // emitting from the timer never suspends even with no collector attached.
    private val _phaseChanges = MutableSharedFlow<Pair<Int, TimelinePhase>>(extraBufferCapacity = 8)
    val phaseChanges: SharedFlow<Pair<Int, TimelinePhase>> = _phaseChanges

    // Fires once on natural completion, before the engine resets to IDLE.
    private val _sessionCompletions = MutableSharedFlow<CompletedWorkoutInfo>(extraBufferCapacity = 1)
    val sessionCompletions: SharedFlow<CompletedWorkoutInfo> = _sessionCompletions

    private var timerJob: Job? = null
    private var sessionTitle: String = ""
    private var startedAtMillis: Long = 0L

    /** Claims the engine for a new workout. False if one is already loading/running. */
    fun beginLoading(): Boolean {
        if (_status.value != WorkoutStatus.IDLE) return false
        _status.value = WorkoutStatus.LOADING
        return true
    }

    fun abortLoading() {
        if (_status.value == WorkoutStatus.LOADING) _status.value = WorkoutStatus.IDLE
    }

    /** Loads the session and shows phase 1, but does not start the timer. */
    fun load(title: String, phases: List<TimelinePhase>) {
        if (phases.isEmpty()) {
            abortLoading()
            return
        }
        sessionTitle = title
        _completed.value = false
        _phases.value = phases
        _phaseIndex.value = 0
        _currentPhase.value = phases[0]
        _timeLeftSeconds.value = phases[0].phase.durationSeconds
        _isPaused.value = false
        _status.value = WorkoutStatus.READY
    }

    /** Starts the countdown of a READY workout. */
    fun begin() {
        if (_status.value != WorkoutStatus.READY) return
        startedAtMillis = System.currentTimeMillis()
        _status.value = WorkoutStatus.RUNNING
        startTimer()
    }

    /** Discards the rest of the current phase and jumps to the next one. */
    fun skipToNextPhase() {
        if (_status.value != WorkoutStatus.RUNNING) return
        if (_phaseIndex.value >= _phases.value.size - 1) return
        timerJob?.cancel()
        // Skipping always lands running: a paused workout resumes on the new phase.
        _isPaused.value = false
        transitionToNextPhase()
    }

    fun pause() {
        _isPaused.value = true
        timerJob?.cancel()
    }

    fun resume() {
        if (_status.value != WorkoutStatus.RUNNING) return
        _isPaused.value = false
        startTimer()
    }

    fun reset() {
        timerJob?.cancel()
        _status.value = WorkoutStatus.IDLE
        _phaseIndex.value = -1
        _currentPhase.value = null
        _timeLeftSeconds.value = 0
        _isPaused.value = false
    }

    fun acknowledgeCompletion() {
        _completed.value = false
    }

    private fun startTimer() {
        timerJob?.cancel()
        // Anchor the phase end to the real clock so late ticks (backgrounding,
        // doze) never lose time — remaining is always recomputed from elapsedRealtime.
        val phaseEndElapsed = SystemClock.elapsedRealtime() + _timeLeftSeconds.value * 1000L
        timerJob = scope.launch {
            while (isActive) {
                val remainingMs = phaseEndElapsed - SystemClock.elapsedRealtime()
                _timeLeftSeconds.value = (((remainingMs + 999) / 1000).coerceAtLeast(0)).toInt()
                if (remainingMs <= 0) {
                    transitionToNextPhase()
                    break
                }
                delay(((remainingMs - 1) % 1000) + 1)
            }
        }
    }

    private fun transitionToNextPhase() {
        alertPhaseChange()
        val nextIndex = _phaseIndex.value + 1
        if (nextIndex < _phases.value.size) {
            _phaseIndex.value = nextIndex
            val nextPhase = _phases.value[nextIndex]
            _currentPhase.value = nextPhase
            _timeLeftSeconds.value = nextPhase.phase.durationSeconds
            _phaseChanges.tryEmit(nextIndex to nextPhase)
            startTimer()
        } else {
            // Emit the summary and latch completion BEFORE reset: observers
            // reacting to the IDLE transition (WorkoutService) must already
            // know this was a natural completion, not a manual stop.
            _sessionCompletions.tryEmit(
                CompletedWorkoutInfo(sessionTitle, startedAtMillis, System.currentTimeMillis())
            )
            _completed.value = true
            reset()
        }
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

        scope.launch {
            val toneGen = try {
                ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
            } catch (_: RuntimeException) {
                null
            } ?: return@launch
            try {
                toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
                delay(700)
            } finally {
                toneGen.release()
            }
        }
    }
}
