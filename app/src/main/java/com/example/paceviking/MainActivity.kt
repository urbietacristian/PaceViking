package com.example.paceviking

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.paceviking.data.*
import com.example.paceviking.ui.theme.CreateFlash
import com.example.paceviking.ui.theme.DangerRed
import com.example.paceviking.ui.theme.DeleteFlash
import com.example.paceviking.ui.theme.PaceVikingTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableColumn
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        // Bounds of the list viewport — needed to centre a duplicated phase.
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

        // Scrolls the list so the given card sits as close to the middle of the
        // viewport as the list's own bounds allow.
        val centerOnPhase: (LayoutCoordinates) -> Unit = { coords ->
            val viewport = viewportCoords
            if (viewport != null && viewport.isAttached && coords.isAttached) {
                val relativeY = viewport.localPositionOf(coords, Offset.Zero).y
                val delta = relativeY - (viewport.size.height - coords.size.height) / 2f
                scope.launch { listState.animateScrollBy(delta) }
            }
            viewModel.clearPendingScroll()
        }

        // Cumulative start offset of each block on the session timeline, so
        // every phase can show when it begins and ends. A block's span counts
        // all its repetitions.
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

        // A phase created off-screen is not composed, so it can never report its
        // position: bring its block into view first and let centerOnPhase
        // fine-tune the centring once it exists.
        LaunchedEffect(scrollTargetId) {
            if (scrollTargetId != null) {
                val blockIndex = editor.blocks.indexOfFirst { b -> b.phases.any { it.id == scrollTargetId } }
                val key = editor.blocks.getOrNull(blockIndex)?.key
                if (key != null && listState.layoutInfo.visibleItemsInfo.none { it.key == key }) {
                    listState.scrollToItem(blockIndex + EDITOR_HEADER_ITEMS)
                }
            }
        }

        // With edge-to-edge the window doesn't resize for the keyboard, so the
        // list is padded by the IME inset instead: that shrinks its viewport and
        // lets Compose's built-in bring-into-view lift the focused field above
        // the keyboard. The extra bottom padding gives the *last* card room to
        // be scrolled up — without it there is nothing below to scroll into.
        val imeVisible = WindowInsets.isImeVisible
        var savedIndex by remember { mutableIntStateOf(0) }
        var savedOffset by remember { mutableIntStateOf(0) }
        var keyboardWasOpen by remember { mutableStateOf(false) }
        // While the keyboard is closed the current position is tracked, so what
        // gets restored is exactly where the list stood before it opened.
        LaunchedEffect(imeVisible, listState) {
            if (!imeVisible) {
                snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                    .collect { (index, offset) ->
                        savedIndex = index
                        savedOffset = offset
                    }
            }
        }
        LaunchedEffect(imeVisible) {
            if (imeVisible) keyboardWasOpen = true
            else if (keyboardWasOpen) {
                keyboardWasOpen = false
                listState.animateScrollToItem(savedIndex, savedOffset)
            }
        }

        val haptics = LocalHapticFeedback.current
        val reorderState = rememberReorderableLazyListState(listState) { from, to ->
            // Item indices include the header items above the block list.
            viewModel.moveBlock(from.index - EDITOR_HEADER_ITEMS, to.index - EDITOR_HEADER_ITEMS)
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(padding)
                // Scaffold's padding already covers the navigation bar; consuming
                // it keeps imePadding from stacking that inset a second time.
                .consumeWindowInsets(padding)
                .padding(horizontal = 16.dp)
                .imePadding()
                .onGloballyPositioned { viewportCoords = it },
            contentPadding = PaddingValues(
                top = 16.dp,
                bottom = if (imeVisible) 16.dp + KEYBOARD_SCROLL_HEADROOM else 16.dp
            )
        ) {
            item(key = EDITOR_KEY_TITLE, contentType = "header") {
                OutlinedTextField(
                    value = editor.title,
                    onValueChange = { viewModel.updateEditorTitle(it) },
                    label = { Text("Título de la Sesión") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                )
            }
            item(key = EDITOR_KEY_SUBHEADER, contentType = "header") {
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
            }

            itemsIndexed(
                items = editor.blocks,
                key = { _, block -> block.key },
                contentType = { _, block -> if (block.isBlock) "block" else "phase" }
            ) { blockIndex, block ->
                ReorderableItem(reorderState, key = block.key) { _ ->
                    // draggableHandle() resolves against this ReorderableItem's
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

            item(key = EDITOR_KEY_ACTIONS, contentType = "footer") {
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
}

// The editor list starts with the title field and the "Fases" header, so lazy
// item indices are two ahead of the block indices the ViewModel works with.
private const val EDITOR_HEADER_ITEMS = 2

// Room added below the last card while the keyboard is open, so even the final
// phase can be scrolled up clear of it.
private val KEYBOARD_SCROLL_HEADROOM = 260.dp
private const val EDITOR_KEY_TITLE = "editor-title"
private const val EDITOR_KEY_SUBHEADER = "editor-subheader"
private const val EDITOR_KEY_ACTIONS = "editor-actions"

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
            .deleteExit(isDeleting)
    ) {
        Column(modifier = Modifier.editorFlash(isNew, isDeleting).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                dragHandle?.invoke()
                // Phase Type Toggle, in the same per-type accent the workout
                // screen uses, so a card is identifiable by colour alone.
                val typeColor = phaseTypeColor(phase.type)
                Box(modifier = Modifier.weight(1f)) {
                    Surface(
                        color = typeColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable {
                            val nextType = PhaseType.entries[(phase.type.ordinal + 1) % PhaseType.entries.size]
                            onUpdate(phase.copy(type = nextType))
                        }
                    ) {
                        Text(
                            text = phase.type.name,
                            fontWeight = FontWeight.Bold,
                            color = typeColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
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
                    // Same zone ramp as the workout screen, so the colour the
                    // user picks here is the one they will see while running.
                    val zoneColor = hrZoneIndex(phase.targetHrZone)?.let(::hrZoneColor)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (zoneColor != null) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(zoneColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = phase.targetHrZone.name,
                            fontWeight = FontWeight.Medium,
                            color = zoneColor ?: LocalContentColor.current
                        )
                    }
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
    ) {
        val roundSeconds = block.phases.sumOf { it.durationSeconds }
        Column(modifier = Modifier.editorFlash(isNew, isDeleting).padding(12.dp)) {
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
    // The animation value is only read inside the graphicsLayer/layout lambdas,
    // so each frame invalidates draw and layout but never recomposition — a
    // card full of text fields would be far too heavy to rebuild per frame.
    return this
        .graphicsLayer {
            val slide = (progress.value / DELETE_SLIDE_FRACTION).coerceAtMost(1f)
            translationX = size.width * slide
            alpha = 1f - slide
        }
        .layout { measurable, constraints ->
            val collapse = ((progress.value - DELETE_COLLAPSE_START) / (1f - DELETE_COLLAPSE_START))
                .coerceIn(0f, 1f)
            val placeable = measurable.measure(constraints)
            layout(placeable.width, (placeable.height * (1f - collapse)).roundToInt()) {
                placeable.place(0, 0)
            }
        }
}

/**
 * Creation/deletion tint, painted behind the card's content. Like [deleteExit]
 * the animation values are read in the draw lambda only, so the flash costs a
 * redraw per frame instead of a recomposition.
 */
@Composable
private fun Modifier.editorFlash(isNew: Boolean, isDeleting: Boolean): Modifier {
    val createFlash = remember { Animatable(0f) }
    LaunchedEffect(isNew) {
        if (isNew) {
            createFlash.snapTo(1f)
            createFlash.animateTo(0f, tween(CREATE_FLASH_MS.toInt(), easing = LinearEasing))
        }
    }
    val deleteTint = remember { Animatable(0f) }
    LaunchedEffect(isDeleting) {
        if (isDeleting) deleteTint.animateTo(1f, tween(DELETE_TINT_MS))
        else deleteTint.snapTo(0f)
    }
    return drawBehind {
        val create = createFlash.value
        if (create > 0f) drawRect(CreateFlash, alpha = create * 0.8f)
        val deleting = deleteTint.value
        if (deleting > 0f) drawRect(DeleteFlash, alpha = deleting)
    }
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
// The dark ground each phase type is shown on, paired with [phaseTypeColor].
private fun phaseBackgroundColor(type: PhaseType): Color = when (type) {
    PhaseType.WORK -> Color(0xFF33120F)
    PhaseType.RECOVERY -> Color(0xFF0E2415)
    PhaseType.WARM_UP -> Color(0xFF0D1F30)
    PhaseType.COOL_DOWN -> Color(0xFF261230)
}

private fun phaseTypeColor(type: PhaseType): Color = when (type) {
    PhaseType.WORK -> Color(0xFFFF8A80)
    PhaseType.RECOVERY -> Color(0xFF81C784)
    PhaseType.WARM_UP -> Color(0xFF64B5F6)
    PhaseType.COOL_DOWN -> Color(0xFFCE93D8)
}

// Heart-rate zone ramp, matching the usual sports-watch code: cyan, green,
// yellow, orange, red. Indexed 1..5 like [hrZoneIndex].
private fun hrZoneColor(index: Int): Color = when (index) {
    1 -> Color(0xFF4FC3F7)
    2 -> Color(0xFF66BB6A)
    3 -> Color(0xFFFDD835)
    4 -> Color(0xFFFFA726)
    else -> Color(0xFFEF5350)
}

// 1..5 for the numbered zones, null for NONE — a null zone hides its card.
private fun hrZoneIndex(zone: HrZone): Int? = when (zone) {
    HrZone.ZONE_1 -> 1
    HrZone.ZONE_2 -> 2
    HrZone.ZONE_3 -> 3
    HrZone.ZONE_4 -> 4
    HrZone.ZONE_5 -> 5
    HrZone.NONE -> null
}

private val METRIC_CARD_CORNER = 16.dp

// One of the two metric cards under the phase type: small label on top, the
// value below. SpaceBetween + fillMaxHeight keeps both cards' labels and
// values aligned even though their contents have different heights.
@Composable
private fun PhaseMetricCard(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    // The zone card labels itself in its own zone color instead of the phase
    // accent, so the card and the scale below it read as one thing.
    labelColor: Color = accent.copy(alpha = 0.75f),
    // Goes on the card's contents rather than on the surface, which is where a
    // whole-card [flashInvert] belongs: the card's own translucent background
    // must not be part of what the flash recolours, or it would swallow the
    // block painted behind it. It covers the padding, so it spans the card.
    contentModifier: Modifier = Modifier,
    value: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = accent.copy(alpha = 0.12f),
        shape = RoundedCornerShape(METRIC_CARD_CORNER),
        modifier = modifier.fillMaxHeight()
    ) {
        Column(
            modifier = contentModifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = labelColor
            )
            value()
        }
    }
}

// Phase-change flash. One early warning five seconds out, then a pulse in each
// of the last two seconds, all in the incoming phase's colour; the change itself
// lands as one longer flash that fades over the first second of the new phase. A
// pulse is skipped when its second is not inside the phase (a five-second phase
// does not warn about its own start), so short phases only get the change flash.
private val PRE_FLASH_SECONDS = setOf(5, 2, 1)
private const val PRE_FLASH_UP_MS = 130
private const val PRE_FLASH_DOWN_MS = 470
private const val CHANGE_FLASH_MS = 1000
private const val FLASH_TINT_ALPHA = 0.42f
// Seconds into the phase at which the speed label pulses on its own — a
// reminder of the number the treadmill should be at, with no background flash
// behind it. The first lands as the change flash finishes fading, the second a
// beat later, for the eyes that came back to the screen too late.
private val SPEED_REMINDER_SECONDS = setOf(1, 2)

// The muted grey of the secondary lines under the clock.
private val PreviewGrey = Color(0xFFBDBDBD)

/** A short warning pulse: up fast, back down over the rest of the second. */
private suspend fun Animatable<Float, AnimationVector1D>.pulse() {
    animateTo(1f, tween(PRE_FLASH_UP_MS, easing = LinearEasing))
    animateTo(0f, tween(PRE_FLASH_DOWN_MS, easing = LinearEasing))
}

/** Full strength at once, then a slow fade — how the change itself lands. */
private suspend fun Animatable<Float, AnimationVector1D>.flashOut() {
    snapTo(1f)
    animateTo(0f, tween(CHANGE_FLASH_MS, easing = LinearEasing))
}

@Composable
fun WorkoutScreen(viewModel: WorkoutViewModel) {
    val currentEntry by viewModel.currentPhase.collectAsStateWithLifecycle()
    val phase = currentEntry?.phase
    // Held as State and never read here. The countdown changes once a second,
    // so reading it in this scope would put the whole screen — every metric
    // card, the preview, all the segments of the session bar — in that state's
    // restart scope and rebuild it at that rate, which is exactly the per-frame
    // work the flash animations below go to such lengths to avoid. Only the two
    // leaves that show the time ([PhaseClock], [SessionProgress]) read the value.
    val timeLeftState = viewModel.timeLeftSeconds.collectAsStateWithLifecycle()
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
    val backgroundColor = phase?.type?.let(::phaseBackgroundColor) ?: MaterialTheme.colorScheme.background

    val phaseColor = phase?.type?.let { phaseTypeColor(it) } ?: MaterialTheme.colorScheme.onBackground

    // Both progress bars are computed inside the leaves that read the clock —
    // the phase fill in [PhaseClock], the session bar in [SessionProgress].
    val phaseDuration = phase?.durationSeconds ?: 0

    // Three parallel timelines, each read in a draw lambda only — never in this
    // composable, which is full of text that must not recompose once per frame.
    // [flash] tints the background on every event; the other two carry the
    // emphasis, and which one runs says where to look: before the change it is
    // the preview of what is coming, after it the speed to set right now.
    val flash = remember { Animatable(0f) }
    var flashColor by remember { mutableStateOf(phaseColor) }
    val previewFlash = remember { Animatable(0f) }
    val speedFlash = remember { Animatable(0f) }
    val isRunning = status == WorkoutStatus.RUNNING

    // Warning pulses before the change, in the colour of the phase that is
    // coming, with the emphasis on its preview.
    //
    // The seconds arrive as a snapshotFlow rather than as an effect key: keying
    // on them restarted this effect (cancelling the coroutine and launching a
    // new one) on every tick, whether or not the new second was one that
    // pulses. collectLatest keeps the behaviour that made that work — the pulse
    // still in flight is cancelled when the next second lands.
    //
    // Everything the block captures is a key or derived from one: [nextPhase]
    // from the phase list and the index, [phaseDuration] from [phase]. Without
    // the per-second restart there is nothing else to refresh a stale capture.
    LaunchedEffect(phases, currentIdx, phase, isPaused, isRunning) {
        if (!isRunning || isPaused) return@LaunchedEffect
        snapshotFlow { timeLeftState.value }.collectLatest { timeLeft ->
            if (timeLeft !in PRE_FLASH_SECONDS) return@collectLatest
            if (nextPhase == null || phaseDuration <= timeLeft) return@collectLatest
            flashColor = phaseTypeColor(nextPhase.phase.type)
            coroutineScope {
                launch { flash.pulse() }
                launch { previewFlash.pulse() }
            }
        }
    }

    // The change itself, now in the new phase's colour and on its speed. The
    // first phase is excluded by the index guard: nothing has changed yet when
    // the workout starts, so its speed is not flashed either.
    LaunchedEffect(currentIdx) {
        if (currentIdx <= 0 || !isRunning) return@LaunchedEffect
        flashColor = phaseColor
        coroutineScope {
            launch { flash.flashOut() }
            launch { speedFlash.flashOut() }
        }
    }

    // Once the change flash has faded, the speed alone pulses again, twice —
    // these land on a screen that is otherwise calm.
    LaunchedEffect(phases, currentIdx, phase, isPaused, isRunning) {
        if (!isRunning || isPaused || currentIdx <= 0 || phase?.speedKmh == null) return@LaunchedEffect
        snapshotFlow { timeLeftState.value }.collectLatest { timeLeft ->
            if (phaseDuration - timeLeft !in SPEED_REMINDER_SECONDS || timeLeft < 1) return@collectLatest
            speedFlash.pulse()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            // The flash is a tint over the background, behind every child, so
            // nothing on screen is covered while it plays.
            .drawBehind { drawRect(color = flashColor, alpha = flash.value * FLASH_TINT_ALPHA) }
            .systemBarsPadding()
    ) {
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
        
        // Speed and target zone get the same visual weight as the phase type:
        // two equal cards side by side. Either one disappears when it has no
        // value, and the survivor then takes the full width on its own.
        val zoneIndex = phase?.targetHrZone?.let(::hrZoneIndex)
        if (phase?.speedKmh != null || zoneIndex != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                phase?.speedKmh?.let { speed ->
                    PhaseMetricCard(
                        label = "VELOCIDAD",
                        accent = phaseColor,
                        modifier = Modifier.weight(1f),
                        // The whole card inverts with the flash — label, number
                        // and unit as one — so the phase change puts the eye on
                        // the speed to set without showing anything the phase
                        // does not already show. The pulses *before* a change
                        // deliberately skip it: until the change lands this is
                        // still the old speed, and the preview below is what the
                        // user should be reading.
                        contentModifier = Modifier.flashInvert(
                            block = phaseColor,
                            ink = backgroundColor,
                            paddingX = 0.dp,
                            paddingY = 0.dp,
                            corner = METRIC_CARD_CORNER
                        ) { speedFlash.value }
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = String.format(Locale.US, "%.1f", speed),
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                color = phaseColor
                            )
                            Text(
                                text = " km/h",
                                style = MaterialTheme.typography.labelLarge,
                                color = phaseColor.copy(alpha = 0.75f),
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                    }
                }
                if (zoneIndex != null) {
                    PhaseMetricCard(
                        label = "ZONA $zoneIndex",
                        accent = phaseColor,
                        labelColor = hrZoneColor(zoneIndex),
                        modifier = Modifier.weight(1f)
                    ) {
                        // Position on the 1..5 scale reads at a glance while
                        // running — no text to parse on a moving treadmill.
                        // Every segment keeps its own zone color, dimmed except
                        // the target one, so the ramp stays recognisable.
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            repeat(5) { i ->
                                val zoneColor = hrZoneColor(i + 1)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(14.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            if (i + 1 == zoneIndex) zoneColor
                                            else zoneColor.copy(alpha = 0.13f)
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        PhaseClock(
            timeLeftState = timeLeftState,
            phaseDurationSeconds = phaseDuration,
            phaseColor = phaseColor
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (!isReady) {
            if (nextPhase != null) {
                // The preview is a metric card of its own, dressed in the
                // incoming phase's colours: the phase type on top, every number
                // (duration, serie, speed) below. The card takes exactly the
                // width the button leaves over (weight), so SALTAR always keeps
                // its intrinsic size, and IntrinsicSize.Min keeps the card the
                // height of its own contents instead of the whole row.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                ) {
                    val nextColor = phaseTypeColor(nextPhase.phase.type)
                    PhaseMetricCard(
                        label = "SIGUIENTE",
                        accent = nextColor,
                        modifier = Modifier.weight(1f),
                        // Inverted by the warning pulses exactly like the speed
                        // card is by the change, and in the arriving phase's own
                        // pair — before a word is read the colours already say
                        // what is coming.
                        contentModifier = Modifier.flashInvert(
                            block = nextColor,
                            ink = phaseBackgroundColor(nextPhase.phase.type),
                            paddingX = 0.dp,
                            paddingY = 0.dp,
                            corner = METRIC_CARD_CORNER
                        ) { previewFlash.value }
                    ) {
                        Text(
                            text = nextPhase.phase.type.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = nextColor
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
                            color = nextColor.copy(alpha = 0.75f)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    // In the incoming phase's colour too: the button and the
                    // card beside it do the same thing, get there sooner.
                    TextButton(onClick = requestSkip) {
                        Text("SALTAR", fontWeight = FontWeight.Bold, color = nextColor, maxLines = 1)
                    }
                }
            } else {
                Text(
                    text = "Última fase",
                    style = MaterialTheme.typography.titleMedium,
                    color = PreviewGrey
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

        SessionProgress(
            timeLeftState = timeLeftState,
            phases = phases,
            currentIdx = currentIdx,
            phaseDurationSeconds = phaseDuration,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 24.dp, vertical = 8.dp)
        )
    }
}

/**
 * The countdown and the fill that tracks the current phase behind it.
 *
 * Split out of [WorkoutScreen] for one reason: this is where the seconds are
 * read, so a tick invalidates this leaf instead of the whole screen. What is
 * left recomposing once a second is a Box and the clock's own text, which has
 * to be re-measured anyway because its glyphs changed.
 */
@Composable
private fun PhaseClock(
    timeLeftState: State<Int>,
    phaseDurationSeconds: Int,
    phaseColor: Color
) {
    val timeLeft = timeLeftState.value
    val phaseProgress = if (phaseDurationSeconds > 0) {
        ((phaseDurationSeconds - timeLeft).toFloat() / phaseDurationSeconds).coerceIn(0f, 1f)
    } else 0f
    // The engine ticks once per second, so the raw value moves in steps. A
    // linear tween exactly one tick long turns those steps into a continuous
    // slide; a new phase (progress back to 0) snaps instead of rewinding.
    val animatedPhaseProgress = animateFloatAsState(
        targetValue = phaseProgress,
        animationSpec = if (phaseProgress == 0f) snap() else tween(1000, easing = LinearEasing),
        label = "phaseProgress"
    )
    val fillColor = phaseColor.copy(alpha = 0.30f)
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
                        size = Size(size.width * animatedPhaseProgress.value, size.height)
                    )
                }
        )
        Text(
            text = formatMmSs(timeLeft),
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 120.sp),
            fontWeight = FontWeight.Thin,
            color = Color(0xFFF5F5F5),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(vertical = 8.dp)
        )
    }
}

/**
 * Session overview: the segmented bar and the elapsed/remaining totals under it.
 *
 * The other half of keeping the clock out of [WorkoutScreen]'s restart scope.
 * The seconds are read here, so this composable is rebuilt once a second — but
 * all that costs is the two labels, whose text really does change. The bar
 * itself is [SessionProgressSegments], whose arguments only change with the
 * session, so it skips; the animation reaches it as a [State] read inside a
 * draw lambda.
 */
@Composable
private fun SessionProgress(
    timeLeftState: State<Int>,
    phases: List<TimelinePhase>,
    currentIdx: Int,
    phaseDurationSeconds: Int,
    modifier: Modifier = Modifier
) {
    val totalSessionSeconds = remember(phases) { phases.sumOf { it.phase.durationSeconds } }
    // Cumulative start second of each phase (size + 1: last entry is the total),
    // used to map overall progress onto each segment of the bar.
    val phaseStartsSeconds = remember(phases) {
        phases.runningFold(0) { acc, p -> acc + p.phase.durationSeconds }
    }
    // Entry [currentIdx] of the running fold is already the sum of every phase
    // before this one, so the elapsed total needs no walk over the list.
    val phasesBefore = phaseStartsSeconds.getOrElse(currentIdx.coerceAtLeast(0)) { 0 }
    val elapsedSeconds = (phasesBefore + (phaseDurationSeconds - timeLeftState.value)).coerceAtLeast(0)
    val sessionProgress = if (totalSessionSeconds > 0) elapsedSeconds.toFloat() / totalSessionSeconds else 0f
    // Same one-tick linear tween as the phase fill, so both bars advance
    // continuously instead of stepping once per second.
    val animatedProgress = animateFloatAsState(
        targetValue = sessionProgress,
        animationSpec = if (sessionProgress == 0f) snap() else tween(1000, easing = LinearEasing),
        label = "sessionProgress"
    )

    Column(modifier = modifier) {
        SessionProgressSegments(
            phases = phases,
            phaseStartsSeconds = phaseStartsSeconds,
            totalSessionSeconds = totalSessionSeconds,
            progress = animatedProgress
        )
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

/**
 * One segment per phase, width proportional to its duration, filled
 * left-to-right as the whole session advances.
 *
 * [progress] is passed as a [State] and never read here: reading it would tie
 * the row to the animation and rebuild every segment's modifier chain per
 * frame. Each segment reads it inside its own drawBehind instead, so a frame of
 * the animation costs a redraw and neither a recomposition nor a re-layout —
 * and since nothing else in this composable changes while a session runs, the
 * row is built once and skipped from then on.
 */
@Composable
private fun SessionProgressSegments(
    phases: List<TimelinePhase>,
    phaseStartsSeconds: List<Int>,
    totalSessionSeconds: Int,
    progress: State<Float>
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(6.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        phases.forEachIndexed { index, p ->
            val segmentColor = phaseTypeColor(p.phase.type)
            val startFraction = if (totalSessionSeconds > 0) phaseStartsSeconds[index].toFloat() / totalSessionSeconds else 0f
            val endFraction = if (totalSessionSeconds > 0) phaseStartsSeconds[index + 1].toFloat() / totalSessionSeconds else 0f
            Box(
                modifier = Modifier
                    .weight(p.phase.durationSeconds.toFloat().coerceAtLeast(1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(segmentColor.copy(alpha = 0.25f))
                    .drawBehind {
                        val fillFraction = if (endFraction > startFraction) {
                            ((progress.value - startFraction) / (endFraction - startFraction))
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
}

// Padding and corner of the block a flash paints behind its text. The vertical
// padding stays small: the preview's two lines each get their own block and
// must not run into each other.
private val FLASH_BLOCK_PADDING_X = 8.dp
private val FLASH_BLOCK_PADDING_Y = 1.dp
private val FLASH_BLOCK_CORNER = 6.dp

/**
 * Inverts text while a flash plays: a [block]-coloured slab fills in behind it
 * as its glyphs fade to [ink], so at full strength font and ground have swapped
 * — the strongest emphasis available without moving anything on screen. The two
 * colours are a phase's own pair ([phaseTypeColor] over [phaseBackgroundColor]),
 * which for the next-phase preview means the flash arrives already wearing the
 * colours of the phase it is announcing.
 *
 * All of it is painted, never composed: the slab is drawn behind the content and
 * the glyphs are recoloured by blending [ink] over them (SrcAtop, inside an
 * offscreen layer so nothing underneath is touched), with [flash] read in the
 * draw lambdas. Passing an interpolated colour down to `Text` instead would
 * recompose *and* re-measure the text on every frame of the animation.
 */
private fun Modifier.flashInvert(
    block: Color,
    ink: Color,
    paddingX: Dp = FLASH_BLOCK_PADDING_X,
    paddingY: Dp = FLASH_BLOCK_PADDING_Y,
    corner: Dp = FLASH_BLOCK_CORNER,
    flash: () -> Float
): Modifier = this
    .drawBehind {
        val boost = flash()
        if (boost <= 0f) return@drawBehind
        val padX = paddingX.toPx()
        val padY = paddingY.toPx()
        drawRoundRect(
            color = block,
            alpha = boost,
            topLeft = Offset(-padX, -padY),
            size = Size(size.width + padX * 2, size.height + padY * 2),
            cornerRadius = CornerRadius(corner.toPx())
        )
    }
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val boost = flash()
        if (boost > 0f) {
            drawRect(color = ink, alpha = boost, blendMode = BlendMode.SrcAtop)
        }
    }
