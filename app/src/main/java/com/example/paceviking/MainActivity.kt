package com.example.paceviking

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.paceviking.data.*
import com.example.paceviking.ui.theme.DangerRed
import com.example.paceviking.ui.theme.PaceVikingTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: WorkoutViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always night mode, so force light system-bar icons even
        // when the device is in light theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            PaceVikingTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation(viewModel)
                }
            }
        }
    }
}

@Composable
fun MainNavigation(viewModel: WorkoutViewModel) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val workoutStatus by viewModel.workoutStatus.collectAsStateWithLifecycle()
    val editorState by viewModel.editorState
    val userMessage by viewModel.userMessage

    // The workout notification needs POST_NOTIFICATIONS on API 33+; the
    // foreground service runs either way, so the workout starts regardless.
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // The visible screen is derived from ViewModel state so it survives
    // configuration changes (rotation) along with the ViewModel itself.
    when {
        workoutStatus == WorkoutStatus.READY || workoutStatus == WorkoutStatus.RUNNING -> WorkoutScreen(viewModel)
        editorState != null -> SessionEditorScreen(viewModel)
        else -> SessionListScreen(
            sessions = sessions,
            userMessage = userMessage,
            onMessageShown = { viewModel.clearUserMessage() },
            onStartSession = { session ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                viewModel.startWorkout(session)
            },
            onEditSession = { viewModel.openEditor(it) },
            onDeleteSession = { viewModel.deleteSession(it) },
            onAddSession = { viewModel.openEditorForNew() },
            onCreateDefaultSession = { viewModel.createDefaultSession() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    sessions: List<WorkoutSession>?,
    userMessage: String?,
    onMessageShown: () -> Unit,
    onStartSession: (WorkoutSession) -> Unit,
    onEditSession: (WorkoutSession) -> Unit,
    onDeleteSession: (WorkoutSession) -> Unit,
    onAddSession: () -> Unit,
    onCreateDefaultSession: () -> Unit
) {
    var sessionToDelete by remember { mutableStateOf<WorkoutSession?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(userMessage) {
        userMessage?.let {
            snackbarHostState.showSnackbar(it)
            onMessageShown()
        }
    }

    sessionToDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("Eliminar sesión") },
            text = { Text("¿Eliminar \"${session.title}\" y todas sus fases? Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSession(session)
                    sessionToDelete = null
                }) { Text("ELIMINAR", color = DangerRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) { Text("Cancelar") }
            }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("PaceViking - Sesiones") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddSession) {
                Icon(Icons.Default.Add, contentDescription = "Añadir Sesión")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(horizontal = 16.dp)) {
            if (sessions == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (sessions.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("No hay sesiones. Pulsa + para crear una.")
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(onClick = onCreateDefaultSession) {
                        Text("Añadir sesión de ejemplo (Protocolo Nórdico 4x4)")
                    }
                }
            } else {
                LazyColumn {
                    items(sessions) { session ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { onStartSession(session) }
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(session.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                                IconButton(onClick = { onEditSession(session) }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Editar")
                                }
                                IconButton(onClick = { sessionToDelete = session }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = DangerRed)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionEditorScreen(viewModel: WorkoutViewModel) {
    val editor = viewModel.editorState.value ?: return
    val userMessage by viewModel.userMessage
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(userMessage) {
        userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearUserMessage()
        }
    }

    BackHandler { viewModel.cancelEditor() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Editar Sesión") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.cancelEditor() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.saveEditor() }) {
                        Text("GUARDAR", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                value = editor.title,
                onValueChange = { viewModel.updateEditorTitle(it) },
                label = { Text("Título de la Sesión") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )

            Text("Fases", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))

            editor.blocks.forEachIndexed { blockIndex, block ->
                if (!block.isBlock) {
                    block.phases.firstOrNull()?.let { phase ->
                        PhaseItem(
                            phase = phase,
                            onRemove = { viewModel.removeEditorPhase(blockIndex, 0) },
                            onUpdate = { updated -> viewModel.updateEditorPhase(blockIndex, 0, updated) }
                        )
                    }
                } else {
                    BlockItem(
                        block = block,
                        onRepetitionsChange = { viewModel.updateBlockRepetitions(blockIndex, it) },
                        onRemoveBlock = { viewModel.removeEditorBlock(blockIndex) },
                        onAddPhase = { viewModel.addPhaseToBlock(blockIndex) },
                        onUpdatePhase = { phaseIndex, updated ->
                            viewModel.updateEditorPhase(blockIndex, phaseIndex, updated)
                        },
                        onRemovePhase = { phaseIndex -> viewModel.removeEditorPhase(blockIndex, phaseIndex) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = { viewModel.addEditorPhase() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Añadir Fase")
                }
                OutlinedButton(onClick = { viewModel.addEditorBlock() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Añadir Bloque")
                }
            }
        }
    }
}

@Composable
fun PhaseItem(phase: WorkoutPhase, onRemove: () -> Unit, onUpdate: (WorkoutPhase) -> Unit) {
    val minutes = phase.durationSeconds / 60
    val seconds = phase.durationSeconds % 60
    // Raw text as local state so the fields can be emptied while typing; keyed
    // to the phase value so external changes (item reuse after a deletion)
    // re-sync the text. The phase keeps its last valid duration until a new
    // number is typed.
    var minutesText by remember(minutes) { mutableStateOf(minutes.toString()) }
    var secondsText by remember(seconds) { mutableStateOf(seconds.toString()) }
    // The speed's canonical text (whole numbers without ".0") matches what the
    // filter lets the user type, so the keyed re-sync doesn't fight the cursor.
    var speedText by remember(phase.speedKmh) {
        mutableStateOf(phase.speedKmh?.let { formatSpeedForEditor(it) } ?: "")
    }

    Card(modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Phase Type Toggle
                Text(
                    text = phase.type.name,
                    modifier = Modifier.weight(1f).clickable {
                        val nextType = PhaseType.entries[(phase.type.ordinal + 1) % PhaseType.entries.size]
                        onUpdate(phase.copy(type = nextType))
                    },
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "Eliminar fase", tint = Color.Gray)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { input ->
                        val filtered = input.filter { it.isDigit() }.take(3)
                        minutesText = filtered
                        filtered.toIntOrNull()?.let { m ->
                            onUpdate(phase.copy(durationSeconds = m * 60 + seconds))
                        }
                    },
                    label = { Text("Min") },
                    modifier = Modifier.width(80.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = secondsText,
                    onValueChange = { input ->
                        val filtered = input.filter { it.isDigit() }.take(2)
                        secondsText = filtered
                        filtered.toIntOrNull()?.let { s ->
                            onUpdate(phase.copy(durationSeconds = minutes * 60 + s.coerceIn(0, 59)))
                        }
                    },
                    label = { Text("Seg") },
                    modifier = Modifier.width(80.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(16.dp))
                // HR Zone selector
                Column(modifier = Modifier.clickable {
                    val nextZone = HrZone.entries[(phase.targetHrZone.ordinal + 1) % HrZone.entries.size]
                    onUpdate(phase.copy(targetHrZone = nextZone))
                }) {
                    Text("Zona Cardíaca", style = MaterialTheme.typography.labelSmall)
                    Text(phase.targetHrZone.name, fontWeight = FontWeight.Medium)
                }
            }
            OutlinedTextField(
                value = speedText,
                onValueChange = { input ->
                    val filtered = filterSpeedInput(input)
                    speedText = filtered
                    // Blank (or a lone ".") means "no recommendation".
                    onUpdate(phase.copy(speedKmh = filtered.toDoubleOrNull()))
                },
                label = { Text("Velocidad (km/h)") },
                placeholder = { Text("Opcional") },
                modifier = Modifier.width(160.dp).padding(top = 8.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true
            )
        }
    }
}

/**
 * Editor card for a repeatable block: a repetitions stepper in the header and
 * the block's phases nested below, each edited with the regular [PhaseItem].
 */
@Composable
fun BlockItem(
    block: EditorBlock,
    onRepetitionsChange: (Int) -> Unit,
    onRemoveBlock: () -> Unit,
    onAddPhase: () -> Unit,
    onUpdatePhase: (Int, WorkoutPhase) -> Unit,
    onRemovePhase: (Int) -> Unit
) {
    Card(
        modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Bloque",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { onRepetitionsChange(block.repetitions - 1) },
                    enabled = block.repetitions > 1
                ) {
                    Text("−", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    text = "× ${block.repetitions}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = { onRepetitionsChange(block.repetitions + 1) },
                    enabled = block.repetitions < 99
                ) {
                    Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onRemoveBlock) {
                    Icon(Icons.Default.Delete, contentDescription = "Eliminar bloque", tint = Color.Gray)
                }
            }
            block.phases.forEachIndexed { phaseIndex, phase ->
                PhaseItem(
                    phase = phase,
                    onRemove = { onRemovePhase(phaseIndex) },
                    onUpdate = { updated -> onUpdatePhase(phaseIndex, updated) }
                )
            }
            TextButton(onClick = onAddPhase, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Añadir fase al bloque")
            }
        }
    }
}

// Speed input allows up to 2 integer digits, one dot and one decimal (max 25.0).
private fun filterSpeedInput(input: String): String {
    val cleaned = input.filter { it.isDigit() || it == '.' }
    val dot = cleaned.indexOf('.')
    return if (dot == -1) cleaned.take(2)
    else cleaned.take(dot).take(2) + "." + cleaned.drop(dot + 1).filter { it.isDigit() }.take(1)
}

// Whole numbers render without ".0" so the text matches what was typed.
private fun formatSpeedForEditor(speed: Double): String =
    if (speed % 1.0 == 0.0) speed.toInt().toString() else String.format(Locale.US, "%.1f", speed)

private fun formatSpeedKmh(speed: Double): String = String.format(Locale.US, "%.1f km/h", speed)

private fun formatMmSs(totalSeconds: Int): String =
    String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)

// Bright accent per phase type, shared by the current-phase texts and the
// segmented session progress bar.
private fun phaseTypeColor(type: PhaseType): Color = when (type) {
    PhaseType.WORK -> Color(0xFFFF8A80)
    PhaseType.RECOVERY -> Color(0xFF81C784)
    PhaseType.WARM_UP -> Color(0xFF64B5F6)
    PhaseType.COOL_DOWN -> Color(0xFFCE93D8)
}

@Composable
fun WorkoutScreen(viewModel: WorkoutViewModel) {
    val currentEntry by viewModel.currentPhase.collectAsStateWithLifecycle()
    val phase = currentEntry?.phase
    val timeLeft by viewModel.timeLeftSeconds.collectAsStateWithLifecycle()
    val isPaused by viewModel.isPaused.collectAsStateWithLifecycle()
    val currentIdx by viewModel.currentPhaseIndex.collectAsStateWithLifecycle()
    val phases by viewModel.currentPhases.collectAsStateWithLifecycle()
    val status by viewModel.workoutStatus.collectAsStateWithLifecycle()
    val isReady = status == WorkoutStatus.READY
    val totalPhases = phases.size
    val nextPhase = phases.getOrNull(currentIdx + 1)
    var showStopDialog by remember { mutableStateOf(false) }
    var showSkipDialog by remember { mutableStateOf(false) }
    var autoPausedForDialog by remember { mutableStateOf(false) }

    // Keep the screen on while the workout is running
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Asking to stop or skip auto-pauses; declining resumes only if we
    // auto-paused, so a workout the user paused manually stays paused.
    // Pausing under the skip dialog also prevents the phase from changing
    // while the confirmation is on screen.
    val requestStop = {
        if (!isPaused) {
            viewModel.pauseWorkout()
            autoPausedForDialog = true
        }
        showStopDialog = true
    }
    val dismissStop = {
        showStopDialog = false
        if (autoPausedForDialog) viewModel.resumeWorkout()
        autoPausedForDialog = false
    }
    val requestSkip = {
        if (!isPaused) {
            viewModel.pauseWorkout()
            autoPausedForDialog = true
        }
        showSkipDialog = true
    }
    val dismissSkip = {
        showSkipDialog = false
        if (autoPausedForDialog) viewModel.resumeWorkout()
        autoPausedForDialog = false
    }

    // While READY nothing has started, so back just leaves without asking.
    BackHandler {
        if (isReady) viewModel.resetWorkout() else requestStop()
    }

    if (showSkipDialog) {
        AlertDialog(
            onDismissRequest = dismissSkip,
            title = { Text("Saltar fase") },
            text = { Text("¿Saltar a ${nextPhase?.phase?.type?.name}? El tiempo restante de la fase actual se descartará.") },
            confirmButton = {
                TextButton(onClick = {
                    showSkipDialog = false
                    autoPausedForDialog = false
                    viewModel.skipToNextPhase()
                }) { Text("SALTAR", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = dismissSkip) { Text("Cancelar") }
            }
        )
    }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = dismissStop,
            title = { Text("Detener entrenamiento") },
            text = { Text("¿Seguro que quieres detener el entrenamiento? Se perderá el progreso actual.") },
            confirmButton = {
                TextButton(onClick = {
                    showStopDialog = false
                    autoPausedForDialog = false
                    viewModel.resetWorkout()
                }) { Text("DETENER", color = DangerRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = dismissStop) { Text("Continuar") }
            }
        )
    }

    // Night-mode palette: dark backgrounds tinted per phase, bright accents on top.
    val backgroundColor = when (phase?.type) {
        PhaseType.WORK -> Color(0xFF33120F)
        PhaseType.RECOVERY -> Color(0xFF0E2415)
        PhaseType.WARM_UP -> Color(0xFF0D1F30)
        PhaseType.COOL_DOWN -> Color(0xFF261230)
        else -> MaterialTheme.colorScheme.background
    }

    val phaseColor = phase?.type?.let { phaseTypeColor(it) } ?: MaterialTheme.colorScheme.onBackground

    // Whole-session progress: elapsed time over total session time.
    val totalSessionSeconds = remember(phases) { phases.sumOf { it.phase.durationSeconds } }
    // Cumulative start second of each phase (size + 1: last entry is the total),
    // used to map overall progress onto each segment of the bar.
    val phaseStartsSeconds = remember(phases) { phases.runningFold(0) { acc, p -> acc + p.phase.durationSeconds } }
    val elapsedSeconds = (phases.take(currentIdx.coerceAtLeast(0)).sumOf { it.phase.durationSeconds } +
        ((phase?.durationSeconds ?: 0) - timeLeft)).coerceAtLeast(0)
    val sessionProgress = if (totalSessionSeconds > 0) elapsedSeconds.toFloat() / totalSessionSeconds else 0f
    val animatedProgress by animateFloatAsState(targetValue = sessionProgress, label = "sessionProgress")

    Box(modifier = Modifier.fillMaxSize().background(backgroundColor).systemBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
        val serieLabel = currentEntry?.takeIf { it.totalRepetitions > 1 }
            ?.let { " · Serie ${it.repetition}/${it.totalRepetitions}" } ?: ""
        Text(
            text = "Fase ${currentIdx + 1} / $totalPhases$serieLabel",
            style = MaterialTheme.typography.labelLarge,
            color = phaseColor.copy(alpha = 0.7f)
        )
        Text(
            text = phase?.type?.name ?: "",
            style = MaterialTheme.typography.headlineLarge,
            color = phaseColor,
            fontWeight = FontWeight.ExtraBold
        )
        
        Surface(
            color = phaseColor.copy(alpha = 0.1f),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text(
                text = "Zona Objetivo: ${phase?.targetHrZone?.name}",
                style = MaterialTheme.typography.titleMedium,
                color = phaseColor,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        phase?.speedKmh?.let { speed ->
            Surface(
                color = phaseColor.copy(alpha = 0.1f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(
                    text = "Velocidad: ${formatSpeedKmh(speed)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = phaseColor,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(64.dp))

        Text(
            text = String.format(Locale.US, "%02d:%02d", timeLeft / 60, timeLeft % 60),
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 120.sp),
            fontWeight = FontWeight.Thin,
            color = Color(0xFFF5F5F5)
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (!isReady) {
            if (nextPhase != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The phase type goes on its own line and every number
                    // (duration, serie, speed) on the next; the column takes
                    // exactly the width the button leaves over (weight), so
                    // SALTAR always keeps its intrinsic size.
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Siguiente: ${nextPhase.phase.type.name}",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFFBDBDBD)
                        )
                        val nextDetails = buildList {
                            add(formatMmSs(nextPhase.phase.durationSeconds))
                            if (nextPhase.totalRepetitions > 1) {
                                add("Serie ${nextPhase.repetition}/${nextPhase.totalRepetitions}")
                            }
                            nextPhase.phase.speedKmh?.let { add(formatSpeedKmh(it)) }
                        }.joinToString(" · ")
                        Text(
                            text = nextDetails,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFFBDBDBD)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = requestSkip) {
                        Text("SALTAR", fontWeight = FontWeight.Bold, color = phaseColor, maxLines = 1)
                    }
                }
            } else {
                Text(
                    text = "Última fase",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFBDBDBD)
                )
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        if (isReady) {
            Button(
                onClick = { viewModel.beginWorkout() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50),
                    contentColor = Color.Black
                ),
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) {
                Text("INICIAR", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = { if (isPaused) viewModel.resumeWorkout() else viewModel.pauseWorkout() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPaused) Color(0xFF4CAF50) else Color(0xFFFFA000),
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.weight(1f).height(64.dp)
                ) {
                    Text(if (isPaused) "REANUDAR" else "PAUSA", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = requestStop,
                    modifier = Modifier.weight(1f).height(64.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(DangerRed))
                ) {
                    Text("DETENER", color = DangerRed, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        }

        // Session overview: one segment per phase, width proportional to its
        // duration, filled left-to-right as the whole session advances.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(6.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                phases.forEachIndexed { index, p ->
                    val segmentColor = phaseTypeColor(p.phase.type)
                    val startFraction = if (totalSessionSeconds > 0) phaseStartsSeconds[index].toFloat() / totalSessionSeconds else 0f
                    val endFraction = if (totalSessionSeconds > 0) phaseStartsSeconds[index + 1].toFloat() / totalSessionSeconds else 0f
                    val fillFraction = if (endFraction > startFraction) {
                        ((animatedProgress - startFraction) / (endFraction - startFraction)).coerceIn(0f, 1f)
                    } else 0f
                    Box(
                        modifier = Modifier
                            .weight(p.phase.durationSeconds.toFloat().coerceAtLeast(1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(segmentColor.copy(alpha = 0.25f))
                    ) {
                        if (fillFraction > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(fillFraction)
                                    .background(segmentColor)
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${formatMmSs(elapsedSeconds)} / ${formatMmSs(totalSessionSeconds)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF9E9E9E)
                )
                Text(
                    text = "faltan ${formatMmSs((totalSessionSeconds - elapsedSeconds).coerceAtLeast(0))}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF9E9E9E)
                )
            }
        }
    }
}
