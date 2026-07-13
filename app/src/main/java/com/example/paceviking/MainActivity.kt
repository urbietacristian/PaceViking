package com.example.paceviking

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.example.paceviking.ui.theme.PaceVikingTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: WorkoutViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
        workoutStatus == WorkoutStatus.RUNNING -> WorkoutScreen(viewModel)
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
                viewModel.startWorkout(session.id)
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
                }) { Text("ELIMINAR", color = Color.Red, fontWeight = FontWeight.Bold) }
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
                                    Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = Color.Red)
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

            editor.phases.forEachIndexed { index, phase ->
                PhaseItem(
                    phase = phase,
                    onRemove = { viewModel.removeEditorPhase(index) },
                    onUpdate = { updated -> viewModel.updateEditorPhase(index, updated) }
                )
            }

            Button(
                onClick = { viewModel.addEditorPhase() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Añadir Fase")
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
        }
    }
}

@Composable
fun WorkoutScreen(viewModel: WorkoutViewModel) {
    val phase by viewModel.currentPhase.collectAsStateWithLifecycle()
    val timeLeft by viewModel.timeLeftSeconds.collectAsStateWithLifecycle()
    val isPaused by viewModel.isPaused.collectAsStateWithLifecycle()
    val currentIdx by viewModel.currentPhaseIndex.collectAsStateWithLifecycle()
    val phases by viewModel.currentPhases.collectAsStateWithLifecycle()
    val totalPhases = phases.size
    var showStopDialog by remember { mutableStateOf(false) }
    var autoPausedForDialog by remember { mutableStateOf(false) }

    // Keep the screen on while the workout is running
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Asking to stop auto-pauses; declining resumes only if we auto-paused,
    // so a workout the user paused manually stays paused.
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

    BackHandler { requestStop() }

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
                }) { Text("DETENER", color = Color.Red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = dismissStop) { Text("Continuar") }
            }
        )
    }

    val backgroundColor = when (phase?.type) {
        PhaseType.WORK -> Color(0xFFFFEBEE)
        PhaseType.RECOVERY -> Color(0xFFE8F5E9)
        PhaseType.WARM_UP -> Color(0xFFE3F2FD)
        PhaseType.COOL_DOWN -> Color(0xFFF3E5F5)
        else -> MaterialTheme.colorScheme.background
    }

    val phaseColor = when (phase?.type) {
        PhaseType.WORK -> Color(0xFFD32F2F)
        PhaseType.RECOVERY -> Color(0xFF388E3C)
        PhaseType.WARM_UP -> Color(0xFF1976D2)
        PhaseType.COOL_DOWN -> Color(0xFF7B1FA2)
        else -> MaterialTheme.colorScheme.onBackground
    }

    Column(
        modifier = Modifier.fillMaxSize().background(backgroundColor).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Fase ${currentIdx + 1} / $totalPhases",
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

        Spacer(modifier = Modifier.height(64.dp))

        Text(
            text = String.format(Locale.US, "%02d:%02d", timeLeft / 60, timeLeft % 60),
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 120.sp),
            fontWeight = FontWeight.Thin
        )

        Spacer(modifier = Modifier.height(80.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = { if (isPaused) viewModel.resumeWorkout() else viewModel.pauseWorkout() },
                colors = ButtonDefaults.buttonColors(containerColor = if (isPaused) Color(0xFF4CAF50) else Color(0xFFFFA000)),
                modifier = Modifier.weight(1f).height(64.dp)
            ) {
                Text(if (isPaused) "REANUDAR" else "PAUSA", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = requestStop,
                modifier = Modifier.weight(1f).height(64.dp),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color.Red))
            ) {
                Text("DETENER", color = Color.Red, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
