package com.example.paceviking

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.paceviking.data.*
import com.example.paceviking.ui.theme.CreateFlash
import com.example.paceviking.ui.theme.DangerRed
import com.example.paceviking.ui.theme.DeleteFlash
import com.example.paceviking.ui.theme.PaceVikingTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableColumn
import java.util.Locale
import kotlin.math.roundToInt

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
        val scrollState = rememberScrollState()
        val scope = rememberCoroutineScope()
        // Bounds of the scroll viewport (captured before verticalScroll, so the
        // size is the visible one) — needed to centre a duplicated phase.
        var viewportCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        val scrollTargetId = viewModel.pendingScrollPhaseId.value
        val highlightId = viewModel.highlightPhaseId.value
        val deletingPhaseId = viewModel.deletingPhaseId.value
        val deletingBlockKey = viewModel.deletingBlockKey.value

        // Deletions are committed once the red flash has played; the flash
        // itself lives in the card, keyed to these ids.
        LaunchedEffect(deletingPhaseId) {
            if (deletingPhaseId != null) {
                delay(DELETE_EXIT_MS)
                viewModel.commitPhaseDeletion()
            }
        }
        LaunchedEffect(deletingBlockKey) {
            if (deletingBlockKey != null) {
                delay(DELETE_EXIT_MS)
                viewModel.commitBlockDeletion()
            }
        }
        LaunchedEffect(highlightId) {
            if (highlightId != null) {
                delay(CREATE_FLASH_MS + 200)
                viewModel.clearHighlight()
            }
        }

        val centerOnPhase: (LayoutCoordinates) -> Unit = { coords ->
            val viewport = viewportCoords
            if (viewport != null && viewport.isAttached && coords.isAttached) {
                val relativeY = viewport.localPositionOf(coords, Offset.Zero).y
                val centred = scrollState.value + relativeY - (viewport.size.height - coords.size.height) / 2f
                val target = centred.roundToInt().coerceIn(0, scrollState.maxValue)
                scope.launch { scrollState.animateScrollTo(target) }
            }
            viewModel.clearPendingScroll()
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .onGloballyPositioned { viewportCoords = it }
                .verticalScroll(scrollState)
        ) {
            OutlinedTextField(
                value = editor.title,
                onValueChange = { viewModel.updateEditorTitle(it) },
                label = { Text("Título de la Sesión") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )

            // Cumulative start offset of each block on the session timeline, so
            // every phase can show when it begins and ends. A block's span
            // counts all its repetitions.
            val blockStarts = remember(editor.blocks) {
                var elapsed = 0
                editor.blocks.map { block ->
                    val start = elapsed
                    elapsed += block.phases.sumOf { it.durationSeconds } * block.repetitions
                    start
                }
            }
            val totalSeconds = remember(editor.blocks) {
                editor.blocks.sumOf { block -> block.phases.sumOf { it.durationSeconds } * block.repetitions }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Fases", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    text = "Total ${formatClock(totalSeconds)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            val haptics = LocalHapticFeedback.current
            ReorderableColumn(
                list = editor.blocks,
                onSettle = { from, to -> viewModel.moveBlock(from, to) },
                onMove = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) },
                modifier = Modifier.fillMaxWidth()
            ) { blockIndex, block, _ ->
                key(block.key) {
                    // draggableHandle() resolves against this ReorderableColumn's
                    // scope; the handle reorders this item at the top level.
                    val handle: @Composable () -> Unit = {
                        IconButton(
                            onClick = {},
                            modifier = Modifier.draggableHandle(
                                onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                            )
                        ) {
                            Icon(Icons.Default.DragHandle, contentDescription = "Reordenar", tint = Color.Gray)
                        }
                    }
                    val blockStart = blockStarts.getOrElse(blockIndex) { 0 }
                    if (!block.isBlock) {
                        block.phases.firstOrNull()?.let { phase ->
                            PhaseItem(
                                phase = phase,
                                timeRange = rangeLabel(blockStart, phase.durationSeconds),
                                onRemove = { viewModel.requestRemoveEditorPhase(blockIndex, 0) },
                                onDuplicate = { viewModel.duplicateEditorPhase(blockIndex, 0) },
                                onUpdate = { updated -> viewModel.updateEditorPhase(blockIndex, 0, updated) },
                                dragHandle = handle,
                                onPlaced = if (phase.id == scrollTargetId) centerOnPhase else null,
                                isNew = phase.id == highlightId,
                                isDeleting = phase.id == deletingPhaseId
                            )
                        }
                    } else {
                        BlockItem(
                            block = block,
                            startSeconds = blockStart,
                            dragHandle = handle,
                            onRepetitionsChange = { viewModel.updateBlockRepetitions(blockIndex, it) },
                            onRemoveBlock = { viewModel.requestRemoveEditorBlock(blockIndex) },
                            onAddPhase = { viewModel.addPhaseToBlock(blockIndex) },
                            onUpdatePhase = { phaseIndex, updated ->
                                viewModel.updateEditorPhase(blockIndex, phaseIndex, updated)
                            },
                            onRemovePhase = { phaseIndex -> viewModel.requestRemoveEditorPhase(blockIndex, phaseIndex) },
                            onDuplicatePhase = { phaseIndex -> viewModel.duplicateEditorPhase(blockIndex, phaseIndex) },
                            onMovePhase = { from, to -> viewModel.movePhaseInBlock(blockIndex, from, to) },
                            scrollTargetPhaseId = scrollTargetId,
                            onTargetPlaced = centerOnPhase,
                            highlightPhaseId = highlightId,
                            deletingPhaseId = deletingPhaseId,
                            isDeleting = block.key == deletingBlockKey
                        )
                    }
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
fun PhaseItem(
    phase: WorkoutPhase,
    // "mm:ss → mm:ss": when this phase starts and ends on the session timeline.
    timeRange: String,
    onRemove: () -> Unit,
    onDuplicate: () -> Unit,
    onUpdate: (WorkoutPhase) -> Unit,
    dragHandle: (@Composable () -> Unit)? = null,
    // Set only on a just-created phase: reports the card's bounds so the
    // editor can scroll it into the middle of the screen.
    onPlaced: ((LayoutCoordinates) -> Unit)? = null,
    isNew: Boolean = false,
    isDeleting: Boolean = false
) {
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

    val haptics = LocalHapticFeedback.current
    Card(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .then(onPlaced?.let { Modifier.onGloballyPositioned(it) } ?: Modifier)
            .deleteExit(isDeleting),
        colors = CardDefaults.cardColors(containerColor = editorCardColor(isNew, isDeleting))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                dragHandle?.invoke()
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
                Text(
                    text = timeRange,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.Gray
                )
                IconButton(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onDuplicate()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Duplicar fase", tint = Color.Gray)
                }
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
                        onRemove()
                    },
                    // Ignore extra taps while the delete animation plays out.
                    enabled = !isDeleting
                ) {
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
    // Offset of the block's first repetition on the session timeline.
    startSeconds: Int,
    dragHandle: @Composable () -> Unit,
    onRepetitionsChange: (Int) -> Unit,
    onRemoveBlock: () -> Unit,
    onAddPhase: () -> Unit,
    onUpdatePhase: (Int, WorkoutPhase) -> Unit,
    onRemovePhase: (Int) -> Unit,
    onDuplicatePhase: (Int) -> Unit,
    onMovePhase: (Int, Int) -> Unit,
    scrollTargetPhaseId: Long?,
    onTargetPlaced: (LayoutCoordinates) -> Unit,
    highlightPhaseId: Long?,
    deletingPhaseId: Long?,
    isDeleting: Boolean
) {
    val haptics = LocalHapticFeedback.current
    // A block flashes green when its first (creating) phase is the new one.
    val isNew = block.phases.size == 1 && block.phases.first().id == highlightPhaseId
    Card(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .deleteExit(isDeleting),
        colors = CardDefaults.cardColors(containerColor = editorCardColor(isNew, isDeleting)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
    ) {
        val roundSeconds = block.phases.sumOf { it.durationSeconds }
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                dragHandle()
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Bloque",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = rangeLabel(startSeconds, roundSeconds * block.repetitions),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Gray
                    )
                }
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
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
                        onRemoveBlock()
                    },
                    enabled = !isDeleting
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Eliminar bloque", tint = Color.Gray)
                }
            }
            if (block.repetitions > 1) {
                Text(
                    text = "Horarios de la 1ª repetición (+${formatClock(roundSeconds)} por repetición)",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
            // Start offsets of this block's phases inside its first repetition.
            var phaseElapsed = startSeconds
            val phaseStarts = block.phases.map { phase ->
                val start = phaseElapsed
                phaseElapsed += phase.durationSeconds
                start
            }
            // Nested reorder scope: each phase's handle moves it within this block.
            ReorderableColumn(
                list = block.phases,
                onSettle = { from, to -> onMovePhase(from, to) },
                onMove = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) },
                modifier = Modifier.fillMaxWidth()
            ) { phaseIndex, phase, _ ->
                key(phase.id) {
                    PhaseItem(
                        phase = phase,
                        timeRange = rangeLabel(
                            phaseStarts.getOrElse(phaseIndex) { startSeconds },
                            phase.durationSeconds
                        ),
                        onRemove = { onRemovePhase(phaseIndex) },
                        onDuplicate = { onDuplicatePhase(phaseIndex) },
                        onUpdate = { updated -> onUpdatePhase(phaseIndex, updated) },
                        onPlaced = if (phase.id == scrollTargetPhaseId) onTargetPlaced else null,
                        // The block itself already flashes for its first phase.
                        isNew = !isNew && phase.id == highlightPhaseId,
                        isDeleting = phase.id == deletingPhaseId,
                        dragHandle = {
                            IconButton(
                                onClick = {},
                                modifier = Modifier.draggableHandle(
                                    onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                                )
                            ) {
                                Icon(Icons.Default.DragHandle, contentDescription = "Reordenar fase", tint = Color.Gray)
                            }
                        }
                    )
                }
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

// Editor feedback timings: a green flash fading out on creation; on deletion a
// red wash, then the card slides off to the right and collapses (pulling the
// next card up) before it actually leaves the list.
private const val CREATE_FLASH_MS = 700L
private const val DELETE_TINT_MS = 150
private const val DELETE_EXIT_MS = 340L
// Fractions of the exit animation spent sliding out and collapsing; they
// overlap so the card starts making room before it has fully left.
private const val DELETE_SLIDE_FRACTION = 0.6f
private const val DELETE_COLLAPSE_START = 0.45f

/**
 * Exit animation for a card being deleted: it slides to the right while fading
 * out, and its reported height shrinks to zero so the cards below travel up
 * into the freed space.
 */
@Composable
private fun Modifier.deleteExit(isDeleting: Boolean): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(isDeleting) {
        if (isDeleting) progress.animateTo(1f, tween(DELETE_EXIT_MS.toInt(), easing = FastOutLinearInEasing))
        else progress.snapTo(0f)
    }
    if (progress.value == 0f) return this
    val slide = (progress.value / DELETE_SLIDE_FRACTION).coerceAtMost(1f)
    val collapse =
        ((progress.value - DELETE_COLLAPSE_START) / (1f - DELETE_COLLAPSE_START)).coerceIn(0f, 1f)
    return this
        .graphicsLayer {
            translationX = size.width * slide
            alpha = 1f - slide
        }
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, (placeable.height * (1f - collapse)).roundToInt()) {
                placeable.place(0, 0)
            }
        }
}

/**
 * Card background for an editor card: tinted green right after creation (fading
 * back to the default) and washed red while it is being deleted.
 */
@Composable
private fun editorCardColor(isNew: Boolean, isDeleting: Boolean): Color {
    val base = CardDefaults.cardColors().containerColor
    val createFlash = remember { Animatable(0f) }
    LaunchedEffect(isNew) {
        if (isNew) {
            createFlash.snapTo(1f)
            createFlash.animateTo(0f, tween(CREATE_FLASH_MS.toInt(), easing = LinearEasing))
        }
    }
    val deleteTint by animateFloatAsState(
        targetValue = if (isDeleting) 1f else 0f,
        animationSpec = tween(DELETE_TINT_MS),
        label = "deleteTint"
    )
    return lerp(lerp(base, CreateFlash, createFlash.value * 0.8f), DeleteFlash, deleteTint)
}

// Timeline positions in the editor: mm:ss, growing to h:mm:ss past an hour.
private fun formatClock(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private fun rangeLabel(startSeconds: Int, durationSeconds: Int): String =
    "${formatClock(startSeconds)} → ${formatClock(startSeconds + durationSeconds)}"

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
    // Same one-tick linear tween as the phase fill, so both bars advance
    // continuously instead of stepping once per second.
    val animatedProgress by animateFloatAsState(
        targetValue = sessionProgress,
        animationSpec = if (sessionProgress == 0f) snap() else tween(1000, easing = LinearEasing),
        label = "sessionProgress"
    )

    // Current phase progress, drawn as the clock's own background: the tinted
    // fill starts empty and grows to the right as the phase advances.
    val phaseDuration = phase?.durationSeconds ?: 0
    val phaseProgress = if (phaseDuration > 0) {
        ((phaseDuration - timeLeft).toFloat() / phaseDuration).coerceIn(0f, 1f)
    } else 0f
    // The engine ticks once per second, so the raw value moves in steps. A
    // linear tween exactly one tick long turns those steps into a continuous
    // slide; a new phase (progress back to 0) snaps instead of rewinding.
    val animatedPhaseProgress by animateFloatAsState(
        targetValue = phaseProgress,
        animationSpec = if (phaseProgress == 0f) snap() else tween(1000, easing = LinearEasing),
        label = "phaseProgress"
    )
    val fillColor = phaseColor.copy(alpha = 0.30f)

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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(phaseColor.copy(alpha = 0.10f))
        ) {
            // Drawn instead of sized: the clock's height comes from the text,
            // so the fill has to match the parent and paint a fraction of it.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawRect(
                            color = fillColor,
                            size = Size(size.width * animatedPhaseProgress, size.height)
                        )
                    }
            )
            Text(
                text = String.format(Locale.US, "%02d:%02d", timeLeft / 60, timeLeft % 60),
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 120.sp),
                fontWeight = FontWeight.Thin,
                color = Color(0xFFF5F5F5),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(vertical = 8.dp)
            )
        }

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
                    // The fill is drawn rather than laid out: reading the
                    // animation inside drawBehind keeps the per-frame work to
                    // the draw phase, with no recomposition or re-layout.
                    Box(
                        modifier = Modifier
                            .weight(p.phase.durationSeconds.toFloat().coerceAtLeast(1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(segmentColor.copy(alpha = 0.25f))
                            .drawBehind {
                                val fillFraction = if (endFraction > startFraction) {
                                    ((animatedProgress - startFraction) / (endFraction - startFraction))
                                        .coerceIn(0f, 1f)
                                } else 0f
                                if (fillFraction > 0f) {
                                    drawRect(
                                        color = segmentColor,
                                        size = Size(size.width * fillFraction, size.height)
                                    )
                                }
                            }
                    )
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
