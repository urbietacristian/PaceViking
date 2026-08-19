# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

Run these from the project root using the Gradle wrapper:

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device/emulator
./gradlew installDebug

# Run unit tests
./gradlew test

# Run instrumented (on-device) tests
./gradlew connectedAndroidTest

# Run a single unit test class
./gradlew test --tests "com.example.paceviking.ExampleUnitTest"

# Run a single instrumented test class
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.paceviking.ExampleInstrumentedTest

# Clean build
./gradlew clean
```

On Windows use `gradlew.bat` instead of `./gradlew`.

Room code generation requires KSP — always build before expecting generated DAO/database code to resolve in the IDE.

## Architecture

**Single-Activity MVVM** — no navigation library, no fragments.

- **Screen state is derived from ViewModel state** inside `MainNavigation()` (`MainActivity.kt`): `workoutStatus == RUNNING` shows `WorkoutScreen`, `editorState != null` shows `SessionEditorScreen`, otherwise `SessionListScreen`. There is no NavHost and no screen enum — closing the editor or resetting the workout navigates implicitly, and the visible screen survives configuration changes because the ViewModel does.
- **Workout timer lives in `WorkoutEngine`**, a process-level singleton owned by `PaceVikingApplication` — NOT in the ViewModel. The engine exposes all workout state as `StateFlow`s; the ViewModel just re-exposes them to the UI. This is what lets the workout keep running when the Activity (and ViewModel) are destroyed.
- **`WorkoutService` is a foreground service** (type `specialUse`, with a partial wakelock) started by `startWorkout` and stopped by itself when the engine returns to IDLE. It only keeps the process alive and mirrors the countdown in an ongoing notification — it contains no timer logic. Swiping the app from recents (`onTaskRemoved`) resets the engine, which stops the workout, the notification, and the service. `POST_NOTIFICATIONS` is requested (API 33+) when starting a workout; the workout starts whether or not it is granted.
- **ViewModel** (`WorkoutViewModel`) is the only ViewModel, instantiated via `by viewModels()` in `MainActivity`. It owns database access (Room DAO), the session editor's in-progress state (`EditorState`), and one-shot snackbar messages (`userMessage`).
- **Data layer** is in `data/`: `WorkoutModels.kt` (entities + enums), `WorkoutDatabase.kt` (DAO + singleton Room database). There is no repository layer — the ViewModel calls the DAO directly.

### Key design decisions to be aware of

- **`updateSessionWithPhases` is a `@Transaction` DAO method** (not a `suspend` annotated `@Update`) — it deletes all existing phases for the session and re-inserts them. `saveSession` re-numbers `orderIndex` from list position before persisting, so stored indices are always contiguous.
- **Timer is anchored to `SystemClock.elapsedRealtime()`** — remaining time is recomputed from the clock on every tick, so late ticks (backgrounding, doze) never lose time. Pause cancels the job; resume starts a new one from the current remaining seconds.
- **Default session seeding is manual** — the session list's empty state shows a button that calls `viewModel.createDefaultSession()`. Nothing is auto-inserted; the user can keep the list empty.
- **`startWorkout` guards against empty sessions and double-starts**: the engine moves IDLE → LOADING → READY → RUNNING. Tapping a session only loads it (READY shows phase 1 with an INICIAR button); the timer and the foreground service start on `beginWorkout()`. A session with no phases bounces back to IDLE with a `userMessage` (shown as a snackbar on the session list).
- **`saveEditor` rejects phases of 0 seconds** (snackbar in the editor). `PhaseItem` keeps raw text in local state so duration fields can be emptied while typing; the phase keeps its last valid value until a new number is entered.
- **Deletion in the editor is deferred**: `requestRemove…` marks the card, the exit animation plays, and `commit…Deletion` (matched by phase id / block key, not index) removes it. `saveEditor` commits any pending deletion first.
- **Release builds run R8** via AGP 9's `optimization { enable = true }`, which requires `android.r8.gradual.support=true` in `gradle.properties`.
- **The UI is always night mode** — `PaceVikingTheme` ignores the system theme and dynamic color, and the workout screen's per-phase colors are dark backgrounds with bright accents. Do not reintroduce `isSystemInDarkTheme()`/light pastel phase backgrounds: the workout clock relies on this fixed dark palette for contrast. System bars are forced to light icons in `MainActivity.onCreate`.

### Data model

```
WorkoutSession  (id, title)
    └── WorkoutPhase  (id, sessionId FK→CASCADE, type, durationSeconds, targetHrZone, orderIndex)
```

`PhaseType`: WARM_UP, WORK, RECOVERY, COOL_DOWN  
`HrZone`: ZONE_1, ZONE_2, ZONE_3, ZONE_4, ZONE_5, NONE

### UI screens (all in `MainActivity.kt`)

| Shown when | Composable | Purpose |
|---|---|---|
| default | `SessionListScreen` | List, start, edit, delete sessions; empty state offers a sample-session button |
| `editorState != null` | `SessionEditorScreen` | Edit title + phases (state lives in the ViewModel); `PhaseItem` handles per-phase editing |
| `workoutStatus == READY \|\| RUNNING` | `WorkoutScreen` | READY shows phase 1 + INICIAR; RUNNING is the live countdown; returns to the list when the workout finishes or is stopped |
