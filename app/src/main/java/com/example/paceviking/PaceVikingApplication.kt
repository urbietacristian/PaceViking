package com.example.paceviking

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle

class PaceVikingApplication : Application(), Application.ActivityLifecycleCallbacks {
    val workoutEngine: WorkoutEngine by lazy { WorkoutEngine(this) }

    // Started-activity count so the service can tell whether the user is
    // looking at the app (no heads-up needed) or it's in the background.
    private var startedActivities = 0

    @Volatile
    var isInForeground: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        val wasInBackground = startedActivities == 0
        startedActivities++
        isInForeground = true
        if (wasInBackground) {
            // Re-entering the app: the phase-change heads-up is stale — the
            // screen already shows the current state. The completed-session
            // notification is deliberately kept (it's the workout record), and
            // the countdown one is system-protected while the service runs.
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(WorkoutService.PHASE_NOTIFICATION_ID)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        isInForeground = startedActivities > 0
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
