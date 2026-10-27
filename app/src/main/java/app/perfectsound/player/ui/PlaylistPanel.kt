package app.perfectsound.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perfectsound.player.playback.PlayerConnection
import app.perfectsound.player.ui.components.LabelStyle
import app.perfectsound.player.ui.components.LcdTextStyle
import app.perfectsound.player.ui.components.LedToggle
import app.perfectsound.player.ui.components.MenuItem
import app.perfectsound.player.ui.components.PopupMenu
import app.perfectsound.player.ui.components.TitleStrip
import app.perfectsound.player.ui.components.onRightClick
import app.perfectsound.player.ui.theme.PsColors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

interface PlaylistActions {
    fun playAt(index: Int)
    fun addFiles()
    fun addFolder()
    fun addPlex()
    fun remove(indices: Set<Int>)
    fun clear()
    fun move(from: Int, to: Int)
}

private const val DOUBLE_CLICK_MS = 400L

@Composable
fun PlaylistPanel(
    state: PlayerConnection.State,
    actions: PlaylistActions,
    modifier: Modifier = Modifier,
) {
    val count = state.playlist.size
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var anchor by remember { mutableIntStateOf(-1) }
    var addMenu by remember { mutableStateOf(false) }
    var contextMenu by remember { mutableStateOf<Offset?>(null) }
    var focused by remember { mutableStateOf(false) }
    // Mouse drag-to-reorder: the row being dragged and where it would land.
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragTo by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val currentState by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)

    LaunchedEffect(state.currentIndex) {
        if (state.currentIndex >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == state.currentIndex })
            listState.animateScrollToItem(state.currentIndex)
    }
    LaunchedEffect(count) { selected = selected.filter { it < count }.toSet() }

    fun select(index: Int, ctrl: Boolean, shift: Boolean) {
        selected = when {
            shift && anchor >= 0 -> (minOf(anchor, index)..maxOf(anchor, index)).toSet()
            ctrl -> if (index in selected) selected - index else selected + index
            else -> setOf(index)
        }
        if (!shift) anchor = index
    }

    fun moveSelection(delta: Int, extend: Boolean) {
        if (count == 0) return
        val from = if (anchor in 0 until count) anchor else if (delta > 0) -1 else count
        val target = (from + delta).coerceIn(0, count - 1)
        if (extend && anchor >= 0) selected = selected + target else { selected = setOf(target); anchor = target }
        scope.launch { listState.animateScrollToItem(target) }
    }

    Column(modifier.background(PsColors.Panel)) {
        TitleStrip("PLAYLIST")
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp)
                .background(PsColors.LcdBackground, RoundedCornerShape(3.dp))
                .border(1.dp, if (focused) PsColors.LcdMid else PsColors.BevelDark, RoundedCornerShape(3.dp))
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionUp -> moveSelection(-1, e.isShiftPressed)
                        Key.DirectionDown -> moveSelection(1, e.isShiftPressed)
                        Key.MoveHome -> moveSelection(-count, e.isShiftPressed)
                        Key.MoveEnd -> moveSelection(count, e.isShiftPressed)
                        Key.Enter, Key.NumPadEnter -> selected.minOrNull()?.let(actions::playAt) ?: return@onKeyEvent false
                        Key.Delete, Key.Backspace -> { actions.remove(selected); selected = emptySet() }
                        Key.A -> if (e.isCtrlPressed) selected = (0 until count).toSet() else return@onKeyEvent false
                        Key.Escape -> selected = emptySet()
                        else -> return@onKeyEvent false
                    }
                    true
                }
                .onRightClick { pos ->
                    focus.requestFocus()
                    indexAt(listState, pos.y)?.let { if (it !in selected) select(it, ctrl = false, shift = false) }
                    contextMenu = pos
                }
                .pointerInput(Unit) {
                    var lastClickIndex = -1
                    var lastClickTime = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (currentEvent.buttons.isSecondaryPressed) return@awaitEachGesture
                        focus.requestFocus()
                        val index = indexAt(listState, down.position.y)
                        if (index == null) {
                            selected = emptySet()
                            return@awaitEachGesture
                        }
                        val mods = currentEvent.keyboardModifiers
                        val keepForDrag = index in selected && !mods.isCtrlPressed && !mods.isShiftPressed
                        if (!keepForDrag) select(index, mods.isCtrlPressed, mods.isShiftPressed)

                        // Only a mouse drags rows; a finger drag scrolls the list.
                        val isMouse = down.type == PointerType.Mouse
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val dy = change.position.y - down.position.y
                            if (isMouse && !dragging && abs(dy) > viewConfiguration.touchSlop) {
                                dragging = true
                                dragFrom = index
                            }
                            if (dragging) {
                                change.consume()
                                val rowHeight = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 1
                                dragTo = (index + (dy / rowHeight).roundToInt()).coerceIn(0, currentState.playlist.size - 1)
                            }
                        }
                        if (dragging) {
                            if (dragTo >= 0 && dragTo != dragFrom) {
                                currentActions.move(dragFrom, dragTo)
                                selected = setOf(dragTo)
                                anchor = dragTo
                            }
                            dragFrom = -1
                            dragTo = -1
                            return@awaitEachGesture
                        }
                        if (keepForDrag) select(index, ctrl = false, shift = false)
                        val now = System.currentTimeMillis()
                        if (index == lastClickIndex && now - lastClickTime < DOUBLE_CLICK_MS) {
                            currentActions.playAt(index)
                            lastClickIndex = -1
                        } else {
                            lastClickIndex = index
                            lastClickTime = now
                        }
                    }
                },
        ) {
            if (state.playlist.isEmpty()) {
                BasicText(
                    "Add music with the ADD button (files, folders, or Plex), drop files here, or open files with the eject button (L).",
                    style = LcdTextStyle.copy(color = PsColors.TextDim),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(state.playlist, key = { _, t -> t.id }) { index, track ->
                    TrackRow(index, track, index == state.currentIndex, index in selected, dimmed = index == dragFrom)
                }
            }
            if (dragFrom >= 0 && dragTo >= 0) DropIndicator(listState, dragFrom, dragTo)
            contextMenu?.let { pos ->
                val hasSelection = selected.isNotEmpty()
                PopupMenu(
                    items = listOf(
                        MenuItem("Play", enabled = hasSelection) { selected.minOrNull()?.let(actions::playAt) },
                        MenuItem("Remove", enabled = hasSelection) { actions.remove(selected); selected = emptySet() },
                        MenuItem("Crop (keep selected)", enabled = hasSelection) {
                            actions.remove((0 until count).toSet() - selected); selected = emptySet()
                        },
                        MenuItem("Select all", enabled = count > 0) { selected = (0 until count).toSet() },
                        MenuItem("Add files…", onClick = actions::addFiles),
                        MenuItem("Add folder…", onClick = actions::addFolder),
                        MenuItem("Add from Plex…", onClick = actions::addPlex),
                        MenuItem("Clear playlist", enabled = count > 0) { actions.clear(); selected = emptySet() },
                    ),
                    offset = IntOffset(pos.x.roundToInt(), pos.y.roundToInt()),
                    onDismiss = { contextMenu = null },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box {
                LedToggle("ADD", addMenu, { addMenu = true })
                if (addMenu) PopupMenu(
                    listOf(
                        MenuItem("Add files…", onClick = actions::addFiles),
                        MenuItem("Add folder…", onClick = actions::addFolder),
                        MenuItem("Add from Plex…", onClick = actions::addPlex),
                    ),
                    offset = IntOffset(0, -210),
                    onDismiss = { addMenu = false },
                )
            }
            LedToggle("REM", false, { actions.remove(selected); selected = emptySet() }, enabled = selected.isNotEmpty())
            LedToggle("CLR", false, { actions.clear(); selected = emptySet() }, enabled = count > 0)
            Spacer(Modifier.weight(1f))
            BasicText(
                (if (selected.size > 1) "${selected.size} selected · " else "") +
                    "$count tracks · ${formatTime(state.totalDurationMs)}",
                style = LabelStyle.copy(color = PsColors.Text, fontSize = 11.sp, letterSpacing = 0.sp),
            )
        }
    }
}

@Composable
private fun TrackRow(index: Int, track: PlayerConnection.Track, isCurrent: Boolean, isSelected: Boolean, dimmed: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isSelected) PsColors.Selection else PsColors.LcdBackground)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        val color = when {
            dimmed -> PsColors.TextDim
            isCurrent -> PsColors.Current
            else -> PsColors.Lcd
        }
        BasicText(
            "${index + 1}. ${track.displayName}",
            style = LcdTextStyle.copy(color = color, fontSize = 13.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        BasicText(formatTime(track.durationMs), style = LcdTextStyle.copy(color = color, fontSize = 13.sp))
    }
}

/** Amber line where a dragged row will be inserted. */
@Composable
private fun DropIndicator(listState: LazyListState, from: Int, to: Int) {
    Canvas(Modifier.fillMaxSize()) {
        val items = listState.layoutInfo.visibleItemsInfo
        val target = items.firstOrNull { it.index == to } ?: return@Canvas
        val y = (if (to > from) target.offset + target.size else target.offset).toFloat()
        drawLine(PsColors.Amber, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
    }
}

private fun indexAt(listState: LazyListState, y: Float): Int? =
    listState.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.index
