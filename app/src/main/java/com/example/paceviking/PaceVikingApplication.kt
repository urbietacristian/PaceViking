package com.example.paceviking

import android.app.Application

class PaceVikingApplication : Application() {
    val workoutEngine: WorkoutEngine by lazy { WorkoutEngine(this) }
}
