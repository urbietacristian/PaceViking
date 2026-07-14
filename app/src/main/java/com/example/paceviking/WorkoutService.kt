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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the process (and therefore [WorkoutEngine]'s timer) alive while a
 * workout runs, mirroring the countdown in an ongoing notification. Stops
 * itself when the engine returns to IDLE.
 */
class WorkoutService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var observing = false

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
        startForegroundCompat(buildNotification("Entrenamiento", "En curso"))
        if (!observing) {
            observing = true
            val engine = (application as PaceVikingApplication).workoutEngine
            scope.launch {
                combine(
                    engine.status, engine.currentPhase, engine.phaseIndex,
                    engine.timeLeftSeconds, engine.isPaused
                ) { status, entry, index, timeLeft, paused ->
                    if (status != WorkoutStatus.RUNNING || entry == null) {
                        null
                    } else {
                        val time = String.format(Locale.US, "%02d:%02d", timeLeft / 60, timeLeft % 60)
                        val speed = entry.phase.speedKmh?.let { String.format(Locale.US, " · %.1f km/h", it) } ?: ""
                        val serie = if (entry.totalRepetitions > 1) " · serie ${entry.repetition}/${entry.totalRepetitions}" else ""
                        val title = "${entry.phase.type.name} — fase ${index + 1}/${engine.phases.value.size}$serie"
                        title to if (paused) "$time$speed (en pausa)" else "$time$speed"
                    }
                }.collect { content ->
                    if (content == null) {
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.cancel(PHASE_NOTIFICATION_ID)
                        ServiceCompat.stopForeground(this@WorkoutService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(NOTIFICATION_ID, buildNotification(content.first, content.second))
                    }
                }
            }
            scope.launch {
                engine.phaseChanges.collect { (index, entry) ->
                    if (!(application as PaceVikingApplication).isInForeground) {
                        showPhaseChangeNotification(index, entry, engine.phases.value.size)
                    }
                }
            }
            scope.launch {
                // Posted regardless of foreground state: it's the record of the
                // finished session, and it survives the service stopping. The
                // engine emits this before resetting to IDLE, so it lands
                // before the collector above tears the service down.
                engine.sessionCompletions.collect { info ->
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(COMPLETED_NOTIFICATION_ID, buildCompletedNotification(info))
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

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_workout)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()
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
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Cancel first so each phase change posts a brand-new notification,
        // which re-triggers the heads-up popup instead of silently updating.
        nm.cancel(PHASE_NOTIFICATION_ID)
        nm.notify(PHASE_NOTIFICATION_ID, notification)
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
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, PHASE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_workout)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setAutoCancel(true)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Entrenamiento en curso", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
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
