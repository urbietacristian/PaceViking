package com.example.paceviking.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Records which classes and methods the app actually runs on the way to its
 * first screens, so ART can compile them ahead of time instead of interpreting
 * them on the user's first launch.
 *
 * Run it with a device or a rooted (AOSP) emulator attached:
 *
 *     ./gradlew :app:generateReleaseBaselineProfile
 *
 * That writes app/src/release/generated/baselineProfiles/, which the app module
 * picks up on its next release build. Nothing here runs during a normal build,
 * and until it has been run once the app ships without a profile of its own —
 * only the ones the Compose libraries bring with them.
 *
 * The journey below deliberately stops at the session list. Driving a whole
 * workout would cover WorkoutScreen too, but it depends on a session already
 * existing and on timings that make the collection flaky; extend it once the
 * plain startup path is known to record cleanly.
 */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(packageName = PACKAGE_NAME) {
        pressHome()
        startActivityAndWait()

        // The list arrives from Room, so the first frame is a spinner: wait for
        // the app bar, which is present either way, and let the query land.
        device.wait(Until.hasObject(By.text("PaceViking - Sesiones")), 5_000)
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE_NAME = "com.example.paceviking"
    }
}
