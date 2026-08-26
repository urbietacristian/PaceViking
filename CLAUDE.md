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
- **The timer runs off one session-wide schedule on `SystemClock.elapsedRealtime()`** — `phaseEndElapsed` is the current phase's deadline, and each new phase's deadline is `phaseEndElapsed + duration`, never `now + duration`. Remaining time is recomputed from that deadline on every tick, so late ticks (backgrounding, doze) neither lose time nor push the session's total end further out — a phase that ends a few ms late gives those ms back inside the next phase. Do not re-anchor a phase to "now": that is exactly what made long sessions drift past the treadmill/watch clock. Pause records the instant and resume shifts `phaseEndElapsed` by the paused interval (so a pause costs its own length and nothing more); a skip deliberately shortens the session, so it re-anchors `phaseEndElapsed` to the present.
- **The phase change is announced with a background flash** — `flash` in `WorkoutScreen`: a warning pulse at each of `PRE_FLASH_SECONDS` (5, 2 and 1 seconds left) in the *incoming* phase's color, then a full-strength flash fading over the first second of the new one. A pulse whose second falls outside the phase is skipped, so short phases only get the change flash. It is a tint drawn behind every child (`drawBehind` on the root `Box`, after `background`), never an overlay — nothing on screen gets covered. Two more `Animatable`s carry the emphasis, and which one runs says where to look: `previewFlash` brightens the "Siguiente" preview during the warning pulses (until the change lands, the VELOCIDAD card still shows the *old* speed, so brightening it there would point at the wrong number), and `speedFlash` brightens the VELOCIDAD label on the change itself and again alone — no background tint — at each of `SPEED_REMINDER_SECONDS` (1 and 2 seconds into the phase). Both speed flashes are gated on `currentIdx > 0`: the first phase of a workout changed from nothing, so it starts with no emphasis at all. The emphasis is an inversion: `Modifier.flashInvert(block, ink)` fills a rounded slab behind the text while its glyphs fade to `ink`, so at full strength font and ground have swapped. The pair is always one phase's own (`phaseTypeColor` over `phaseBackgroundColor`) — the current phase's for the speed, the **incoming** phase's for the preview, so the warning already wears the colours of what is arriving. It paints both halves (block in `drawBehind`, glyphs recoloured by blending `inverted` `SrcAtop` inside an offscreen layer) instead of passing a colour down — a colour parameter would recompose *and* re-measure the text every frame. Both targets are `PhaseMetricCard`s and invert as a whole card through its `contentModifier`, slab squared off to the card's own corner — the VELOCIDAD card in the current phase's pair, the SIGUIENTE card (the next-phase preview, which is a metric card for exactly this reason) in the incoming one's. Note it must go on the card's *content*, never on the `Surface`: the card's translucent background would be recoloured too and would swallow the slab painted behind it. Both values are read in draw lambdas only; do not read them in `WorkoutScreen` itself, which would recompose the whole screen per frame.
- **Nothing heavy runs on the main thread at a phase change.** The engine's beep/vibration (`alertPhaseChange`) and every notification the service posts run on `Dispatchers.Default` — the engine and service scopes are `Main.immediate`, so each of those `launch`es names the dispatcher explicitly. Constructing a `ToneGenerator` blocks for as long as the audio path takes to wake up and used to freeze the UI exactly when the new phase's clock appeared. Only service-lifecycle calls (`stopForeground`/`stopSelf`) hop back to main. The ongoing notification's `PendingIntent` is built once (it is a binder call, and the notification is rebuilt every tick) and the content flow is `distinctUntilChanged`.
- **Default session seeding is manual** — the session list's empty state shows a button that calls `viewModel.createDefaultSession()`. Nothing is auto-inserted; the user can keep the list empty.
- **`startWorkout` guards against empty sessions and double-starts**: the engine moves IDLE → LOADING → READY → RUNNING. Tapping a session only loads it (READY shows phase 1 with an INICIAR button); the timer and the foreground service start on `beginWorkout()`. A session with no phases bounces back to IDLE with a `userMessage` (shown as a snackbar on the session list).
- **`saveEditor` rejects phases of 0 seconds** (snackbar in the editor). `PhaseItem` keeps raw text in local state so duration fields can be emptied while typing; the phase keeps its last valid value until a new number is entered.
- **The session editor is a `LazyColumn`** (title field, "Fases" header, one item per block, action buttons). Top-level drag-reorder uses `rememberReorderableLazyListState`, whose `onMove` reports *lazy item* indices — subtract `EDITOR_HEADER_ITEMS` to get the block index the ViewModel expects. Phases inside a block still use the non-lazy `ReorderableColumn`.
- **Editor create/delete animations read their state only inside `graphicsLayer`/`layout`/`drawBehind` lambdas** (`deleteExit`, `editorFlash`), so each frame invalidates draw/layout but never recomposition — a phase card is full of text fields and is far too heavy to rebuild per frame. `EditorState`/`EditorBlock` are `@Immutable` for the same reason: they hold `List`s, which would otherwise make every card recompose on each keystroke.
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
