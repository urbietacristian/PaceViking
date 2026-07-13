package com.example.paceviking

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.paceviking.data.*
import com.example.paceviking.ui.theme.PaceVikingTheme
import kotlinx.coroutines.launch

enum class Screen {
    SESSION_LIST, SESSION_EDITOR, WORKOUT
}

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
    var currentScreen by remember { mutableStateOf(Screen.SESSION_LIST) }
    var editingSession by remember { mutableStateOf<Pair<WorkoutSession, List<WorkoutPhase>>?>(null) }
    val scope = rememberCoroutineScope()

    val sessions by viewModel.sessions.collectAsState()

    // Add default session if empty
    LaunchedEffect(sessions) {
        if (sessions.isEmpty()) {
            val defaultSession = WorkoutSession(title = "Protocolo Nórdico (4x4)")
            val defaultPhases = listOf(
                WorkoutPhase(sessionId = 0, type = PhaseType.WARM_UP, durationSeconds = 600, targetHrZone = HrZone.ZONE_2, orderIndex = 0),
                WorkoutPhase(sessionId = 0, type = PhaseType.WORK, durationSeconds = 240, targetHrZone = HrZone.ZONE_4, orderIndex = 1),
                WorkoutPhase(sessionId = 0, type = PhaseType.RECOVERY, durationSeconds = 180, targetHrZone = HrZone.ZONE_2, orderIndex = 2),
                WorkoutPhase(sessionId = 0, type = PhaseType.WORK, durationSeconds = 240, targetHrZone = HrZone.ZONE_4, orderIndex = 3),
                WorkoutPhase(sessionId = 0, type = PhaseType.RECOVERY, durationSeconds = 180, targetHrZone = HrZone.ZONE_2, orderIndex = 4),
                WorkoutPhase(sessionId = 0, type = PhaseType.WORK, durationSeconds = 240, targetHrZone = HrZone.ZONE_4, orderIndex = 5),
                WorkoutPhase(sessionId = 0, type = PhaseType.RECOVERY, durationSeconds = 180, targetHrZone = HrZone.ZONE_2, orderIndex = 6),
                WorkoutPhase(sessionId = 0, type = PhaseType.WORK, durationSeconds = 240, targetHrZone = HrZone.ZONE_4, orderIndex = 7),
                WorkoutPhase(sessionId = 0, type = PhaseType.COOL_DOWN, durationSeconds = 300, targetHrZone = HrZone.ZONE_1, orderIndex = 8)
            )
            viewModel.saveSession(defaultSession, defaultPhases)
        }
    }

    when (currentScreen) {
        Screen.SESSION_LIST -> SessionListScreen(
            sessions = sessions,
            onStartSession = { 
                viewModel.startWorkout(it.id)
                currentScreen = Screen.WORKOUT 
            },
            onEditSession = { session ->
                scope.launch {
                    val phases = viewModel.getPhasesForSession(session.id)
                    editingSession = session to phases
                    currentScreen = Screen.SESSION_EDITOR
                }
            },
            onDeleteSession = { viewModel.deleteSession(it) },
            onAddSession = {
                editingSession = WorkoutSession(title = "Nueva Sesión") to emptyList()
                currentScreen = Screen.SESSION_EDITOR
            }
        )
        Screen.SESSION_EDITOR -> SessionEditorScreen(
            sessionData = editingSession,
            onSave = { session, phases ->
                viewModel.saveSession(session, phases)
                currentScreen = Screen.SESSION_LIST
            },
            onCancel = { currentScreen = Screen.SESSION_LIST }
        )
        Screen.WORKOUT -> WorkoutScreen(
            viewModel = viewModel,
            onFinish = { currentScreen = Screen.SESSION_LIST }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    sessions: List<WorkoutSession>,
    onStartSession: (WorkoutSession) -> Unit,
    onEditSession: (WorkoutSession) -> Unit,
    onDeleteSession: (WorkoutSession) -> Unit,
    onAddSession: () -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("PaceViking - Sesiones") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddSession) {
                Icon(Icons.Default.Add, contentDescription = "Añadir Sesión")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(horizontal = 16.dp)) {
            if (sessions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Cargando sesiones...")
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
                                IconButton(onClick = { onDeleteSession(session) }) {
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
fun SessionEditorScreen(
    sessionData: Pair<WorkoutSession, List<WorkoutPhase>>?,
    onSave: (WorkoutSession, List<WorkoutPhase>) -> Unit,
    onCancel: () -> Unit
) {
    var title by remember { mutableStateOf(sessionData?.first?.title ?: "") }
    var phases by remember { mutableStateOf(sessionData?.second ?: emptyList()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editar Sesión") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    TextButton(onClick = { onSave(sessionData!!.first.copy(title = title), phases) }) {
                        Text("GUARDAR", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Título de la Sesión") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )

            Text("Fases", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            
            phases.forEachIndexed { index, phase ->
                PhaseItem(
                    phase = phase,
                    onRemove = { phases = phases.filterIndexed { i, _ -> i != index } },
                    onUpdate = { updated -> 
                        phases = phases.mapIndexed { i, p -> if (i == index) updated else p }
                    }
                )
            }

            Button(
                onClick = { 
                    phases = phases + WorkoutPhase(
                        sessionId = sessionData?.first?.id ?: 0, 
                        type = PhaseType.WORK, 
                        durationSeconds = 60, 
                        targetHrZone = HrZone.NONE, 
                        orderIndex = phases.size
                    ) 
                },
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
    Card(modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Phase Type Toggle
                Text(
                    text = phase.type.name, 
                    modifier = Modifier.weight(1f).clickable {
                        val nextType = PhaseType.values()[(phase.type.ordinal + 1) % PhaseType.values().size]
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
                    value = (phase.durationSeconds / 60).toString(),
                    onValueChange = { val m = it.toIntOrNull() ?: 0; onUpdate(phase.copy(durationSeconds = m * 60)) },
                    label = { Text("Min") },
                    modifier = Modifier.width(100.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(16.dp))
                // HR Zone selector
                Column(modifier = Modifier.clickable { 
                    val nextZone = HrZone.values()[(phase.targetHrZone.ordinal + 1) % HrZone.values().size]
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
fun WorkoutScreen(viewModel: WorkoutViewModel, onFinish: () -> Unit) {
    val phase by viewModel.currentPhase
    val timeLeft by viewModel.timeLeftSeconds
    val isPaused by viewModel.isPaused
    val currentIdx by viewModel.currentPhaseIndex
    val totalPhases = viewModel.currentPhases.value.size

    if (currentIdx == -1 && phase == null) {
        onFinish()
        return
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
        modifier = Modifier.fillMaxSize().background(backgroundColor).padding(24.dp),
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
            text = String.format("%02d:%02d", timeLeft / 60, timeLeft % 60),
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
                onClick = { viewModel.resetWorkout() },
                modifier = Modifier.weight(1f).height(64.dp),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color.Red))
            ) {
                Text("DETENER", color = Color.Red, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
