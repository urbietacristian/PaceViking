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

- **Screen state** is managed as a `var currentScreen by remember { mutableStateOf(Screen.SESSION_LIST) }` enum inside `MainNavigation()` (`MainActivity.kt`). Navigation is plain `when` branching — no NavHost.
- **ViewModel** (`WorkoutViewModel`) is the only ViewModel, instantiated via `by viewModels()` in `MainActivity`. It owns both database access (Room DAO) and workout timer state (coroutine `Job`).
- **Data layer** is in `data/`: `WorkoutModels.kt` (entities + enums), `WorkoutDatabase.kt` (DAO + singleton Room database). There is no repository layer — the ViewModel calls the DAO directly.

### Key design decisions to be aware of

- **`updateSessionWithPhases` is a `@Transaction` DAO method** (not a `suspend` annotated `@Update`) — it deletes all existing phases for the session and re-inserts them. This means phase `id`s are always regenerated on save.
- **Timer runs as a coroutine `Job`** in `viewModelScope`. Pause cancels the job; resume starts a new one from the current `_timeLeftSeconds`. There is no clock-based correction — if the process is backgrounded mid-second the tick will be late.
- **Default session injection** happens inside a `LaunchedEffect(sessions)` in `MainNavigation` — it fires whenever the sessions `StateFlow` emits, but only inserts if the list is empty. This is the only seeding mechanism.
- **Phase duration UI** is minutes-only (`durationSeconds / 60`). Seconds are always zeroed out when editing — a phase set to 90 seconds will display as "1" minute and be saved back as 60 seconds if re-saved without changes.

### Data model

```
WorkoutSession  (id, title)
    └── WorkoutPhase  (id, sessionId FK→CASCADE, type, durationSeconds, targetHrZone, orderIndex)
```

`PhaseType`: WARM_UP, WORK, RECOVERY, COOL_DOWN  
`HrZone`: ZONE_1, ZONE_2, ZONE_3, ZONE_4, ZONE_5, NONE

### UI screens (all in `MainActivity.kt`)

| Screen (enum) | Composable | Purpose |
|---|---|---|
| `SESSION_LIST` | `SessionListScreen` | List, start, edit, delete sessions |
| `SESSION_EDITOR` | `SessionEditorScreen` | Edit title + phases; `PhaseItem` handles per-phase editing |
| `WORKOUT` | `WorkoutScreen` | Real-time countdown; auto-navigates back when `currentPhaseIndex == -1` |
