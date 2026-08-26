package com.example.paceviking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.paceviking.data.HrZone
import com.example.paceviking.data.TimelinePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the ongoing notification says that is not the countdown itself.
 * Deliberately holds no seconds: it is the key `distinctUntilChanged` dedupes
 * on, so anything in here that changed once a second would cost a notification
 * post per tick. [speed] is the bare "12.5 km/h" — where the separator goes
 * depends on whether a frozen time precedes it.
 */
private data class OngoingContent(
    val title: String,
    val speed: String?,
    val paused: Boolean
)

/**
 * Keeps the process (and therefore [WorkoutEngine]'s timer) alive while a
 * workout runs, mirroring the countdown in an ongoing notification. Stops
 * itself when the engine returns to IDLE.
 */
class WorkoutService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var observing = false

    private val notificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    // Built once: asking ActivityManager for it is a binder call.
    private val contentIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Partial wakelock so timer ticks stay on schedule with the screen off;
        // capped in case the service somehow outlives the workout.
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PaceViking:workout").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKELOCK_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildOngoingNotification("Entrenamiento", "En curso", null))
        if (!observing) {
            observing = true
            val engine = (application as PaceVikingApplication).workoutEngine
            // The seconds are deliberately absent from what is combined here.
            // Keeping them in meant one emission — and one rebuilt, re-parcelled,
            // re-posted notification — per tick: ~2700 binder round trips over a
            // 45 minute session, each one waking system_server to re-render the
            // row. The countdown is handed to the system as a deadline instead
            // (see [buildOngoingNotification]), which leaves only the phase, the
            // pause state and the end of the workout to post about.
            //
            // Still off the main thread: a notify() is a binder call either way,
            // and on the main thread it competed for frames with the clock.
            scope.launch(Dispatchers.Default) {
                combine(
                    engine.status, engine.currentPhase, engine.phaseIndex, engine.isPaused
                ) { status, entry, index, paused ->
                    if (status != WorkoutStatus.RUNNING || entry == null) {
                        null
                    } else {
                        val serie = if (entry.totalRepetitions > 1) " · serie ${entry.repetition}/${entry.totalRepetitions}" else ""
                        OngoingContent(
                            title = "${entry.phase.type.name} — fase ${index + 1}/${engine.phases.value.size}$serie",
                            speed = entry.phase.speedKmh?.let { String.format(Locale.US, "%.1f km/h", it) },
                            paused = paused
                        )
                    }
                }.distinctUntilChanged().collect { content ->
                    when {
                        content == null -> {
                            notificationManager.cancel(PHASE_NOTIFICATION_ID)
                            // Service lifecycle calls belong on the main thread.
                            withContext(Dispatchers.Main) {
                                ServiceCompat.stopForeground(this@WorkoutService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }
                        // A paused countdown has no deadline to count towards, so
                        // the remaining time is read once, here, and written into
                        // the text where it stays put until the workout resumes.
                        content.paused -> {
                            val timeLeft = engine.timeLeftSeconds.value
                            val time = String.format(Locale.US, "%02d:%02d", timeLeft / 60, timeLeft % 60)
                            val speed = content.speed?.let { " · $it" } ?: ""
                            notificationManager.notify(
                                NOTIFICATION_ID,
                                buildOngoingNotification(content.title, "$time$speed (en pausa)", null)
                            )
                        }
                        else -> notificationManager.notify(
                            NOTIFICATION_ID,
                            buildOngoingNotification(
                                content.title,
                                content.speed ?: "En curso",
                                engine.currentPhaseEndAtMillis()
                            )
                        )
                    }
                }
            }
            scope.launch(Dispatchers.Default) {
                engine.phaseChanges.collect { (index, entry) ->
                    if (!(application as PaceVikingApplication).isInForeground) {
                        showPhaseChangeNotification(index, entry, engine.phases.value.size)
                    }
                }
            }
            scope.launch(Dispatchers.Default) {
                // Posted regardless of foreground state: it's the record of the
                // finished session, and it survives the service stopping. The
                // engine emits this before resetting to IDLE, so it lands
                // before the collector above tears the service down.
                engine.sessionCompletions.collect { info ->
                    notificationManager.notify(COMPLETED_NOTIFICATION_ID, buildCompletedNotification(info))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away from recents ends the workout: the service
        // exists to survive backgrounding, not to outlive an explicit close.
        // reset() drives the observer above to remove the notification and stop.
        (application as PaceVikingApplication).workoutEngine.reset()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * The ongoing notification. When [endsAtMillis] is given, the countdown is
     * rendered by the system as a count-down chronometer against that deadline
     * — the notification is then posted once, at the start of the phase, and
     * keeps ticking on its own instead of being re-posted every second. Pass
     * null for a notification with no live countdown (paused, or the initial
     * one posted before the engine's state has been read).
     *
     * The count-down direction needs API 24, which is the project's minSdk.
     */
    private fun buildOngoingNotification(
        title: String,
        text: String,
        endsAtMillis: Long?
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_workout)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
        return if (endsAtMillis == null) {
            builder.setShowWhen(false).build()
        } else {
            builder
                .setWhen(endsAtMillis)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .build()
        }
    }

    /**
     * Heads-up notification announcing the phase the workout just moved into,
     * shown only while the app is backgrounded. The engine already vibrates
     * and beeps on every transition, so the channel itself is silent — the
     * HIGH importance is what makes it pop over other apps.
     */
    private fun showPhaseChangeNotification(index: Int, entry: TimelinePhase, totalPhases: Int) {
        val phase = entry.phase
        val duration = String.format(
            Locale.US, "%02d:%02d", phase.durationSeconds / 60, phase.durationSeconds % 60
        )
        val details = buildList {
            add(duration)
            if (phase.targetHrZone != HrZone.NONE) add(phase.targetHrZone.name.replace("ZONE_", "Zona "))
            phase.speedKmh?.let { add(String.format(Locale.US, "%.1f km/h", it)) }
        }.joinToString(" · ")
        val serie = if (entry.totalRepetitions > 1) " · Serie ${entry.repetition}/${entry.totalRepetitions}" else ""

        val notification = alertNotificationBuilder()
            .setContentTitle("Nueva fase: ${phase.type.name} (${index + 1}/$totalPhases)$serie")
            .setContentText(details)
            .build()
        // Cancel first so each phase change posts a brand-new notification,
        // which re-triggers the heads-up popup instead of silently updating.
        notificationManager.cancel(PHASE_NOTIFICATION_ID)
        notificationManager.notify(PHASE_NOTIFICATION_ID, notification)
    }

    private fun buildCompletedNotification(info: CompletedWorkoutInfo): Notification {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val start = timeFormat.format(Date(info.startedAtMillis))
        val end = timeFormat.format(Date(info.endedAtMillis))
        return alertNotificationBuilder()
            .setContentTitle("Sesión finalizada: ${info.sessionTitle}")
            .setContentText("Inicio $start · Fin $end")
            // The record should outlive taps and re-entering the app; only an
            // explicit swipe dismisses it.
            .setAutoCancel(false)
            .build()
    }

    private fun alertNotificationBuilder(): NotificationCompat.Builder {
        return NotificationCompat.Builder(this, PHASE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_workout)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setAutoCancel(true)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Entrenamiento en curso", NotificationManager.IMPORTANCE_LOW)
            )
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    PHASE_CHANNEL_ID, "Avisos del entrenamiento", NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    // The engine handles sound/vibration on phase change.
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "workout"
        private const val PHASE_CHANNEL_ID = "workout_phase"
        internal const val NOTIFICATION_ID = 1
        internal const val PHASE_NOTIFICATION_ID = 2
        internal const val COMPLETED_NOTIFICATION_ID = 3
        private const val MAX_WAKELOCK_MS = 3 * 60 * 60 * 1000L
    }
}
