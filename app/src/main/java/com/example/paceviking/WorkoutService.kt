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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
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
                ) { status, phase, index, timeLeft, paused ->
                    if (status != WorkoutStatus.RUNNING || phase == null) {
                        null
                    } else {
                        val time = String.format(Locale.US, "%02d:%02d", timeLeft / 60, timeLeft % 60)
                        val speed = phase.speedKmh?.let { String.format(Locale.US, " · %.1f km/h", it) } ?: ""
                        val title = "${phase.type.name} — fase ${index + 1}/${engine.phases.value.size}"
                        title to if (paused) "$time$speed (en pausa)" else "$time$speed"
                    }
                }.collect { content ->
                    if (content == null) {
                        ServiceCompat.stopForeground(this@WorkoutService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(NOTIFICATION_ID, buildNotification(content.first, content.second))
                    }
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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Entrenamiento en curso", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "workout"
        private const val NOTIFICATION_ID = 1
        private const val MAX_WAKELOCK_MS = 3 * 60 * 60 * 1000L
    }
}
