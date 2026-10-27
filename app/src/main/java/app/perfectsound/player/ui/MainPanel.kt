package app.perfectsound.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perfectsound.player.remote.RemoteSessions
import app.perfectsound.player.ui.components.HSlider
import app.perfectsound.player.ui.components.IconButton
import app.perfectsound.player.ui.components.Icons
import app.perfectsound.player.ui.components.LabelStyle
import app.perfectsound.player.ui.components.LcdTextStyle
import app.perfectsound.player.ui.components.LedToggle
import app.perfectsound.player.ui.components.SevenSegment
import app.perfectsound.player.ui.components.TitleStrip
import app.perfectsound.player.ui.theme.PsColors

/** Actions the main panel can trigger; implemented by the app shell. */
interface MainPanelActions {
    fun play()
    fun pause()
    fun stop()
    fun next()
    fun previous()
    fun open()
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Float)
    fun toggleShuffle()
    fun cycleRepeat()
    fun toggleEqualizer()
    fun togglePlaylist()
    fun selectSource(source: Source)
    fun seekBy(deltaMs: Long)
    fun changeVolume(delta: Float)
    fun addFolder()
}

/** A source button: [available] is false when the app isn't installed. */
data class SourceOption(val source: Source, val label: String, val available: Boolean)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainPanel(
    state: DisplayState,
    source: Source,
    sources: List<SourceOption>,
    levels: FloatArray,
    actions: MainPanelActions,
    modifier: Modifier = Modifier,
) {
    // While dragging the seek bar, show the drag position instead of the playback position.
    var seeking by remember { mutableStateOf(false) }
    var seekFraction by remember { mutableFloatStateOf(0f) }
    var showRemaining by remember { mutableStateOf(false) }

    Column(modifier.background(PsColors.Panel)) {
        TitleStrip("PERFECT SOUND")
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // LCD: status, time, mini analyzer
                Column(
                    Modifier
                        .width(150.dp)
                        .height(78.dp)
                        .background(PsColors.LcdBackground, RoundedCornerShape(3.dp))
                        .border(1.dp, PsColors.BevelDark, RoundedCornerShape(3.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusIndicator(state, Modifier.size(12.dp))
                        Spacer(Modifier.width(8.dp))
                        val position = if (seeking) (seekFraction * state.durationMs).toLong() else state.positionMs
                        val shown = if (showRemaining && state.durationMs > 0) state.durationMs - position else position
                        SevenSegment(
                            text = formatLcdTime(shown, negative = showRemaining),
                            modifier = Modifier
                                .height(30.dp)
                                .width(110.dp)
                                .clickable(remember { MutableInteractionSource() }, null) { showRemaining = !showRemaining },
                        )
                    }
                    MiniAnalyzer(levels, Modifier.fillMaxWidth().height(20.dp))
                }
                // Track info
                Column(Modifier.weight(1f).height(78.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                            .background(PsColors.LcdBackground, RoundedCornerShape(3.dp))
                            .border(1.dp, PsColors.BevelDark, RoundedCornerShape(3.dp))
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicText(
                            state.title,
                            style = LcdTextStyle,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LcdField(state.bitrateKbps?.toString() ?: "", "kbps")
                        LcdField(state.sampleRateHz?.let { (it / 1000).toString() } ?: "", "kHz")
                        Spacer(Modifier.weight(1f))
                        BasicText("MONO", style = LabelStyle.copy(color = if (state.channels == 1) PsColors.Lcd else PsColors.LcdDim))
                        BasicText("STEREO", style = LabelStyle.copy(color = if ((state.channels ?: 0) >= 2) PsColors.Lcd else PsColors.LcdDim))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BasicText("VOL", style = LabelStyle)
                        HSlider(
                            value = state.volume,
                            onChange = actions::setVolume,
                            fill = Brush.horizontalGradient(listOf(PsColors.LcdMid, PsColors.Amber)),
                            modifier = Modifier.width(140.dp),
                        )
                    }
                }
            }

            // Seek bar
            HSlider(
                value = if (seeking) seekFraction else if (state.durationMs > 0) state.positionMs / state.durationMs.toFloat() else 0f,
                onChange = { seeking = true; seekFraction = it },
                onChangeFinished = {
                    if (seeking && state.durationMs > 0) actions.seekTo((seekFraction * state.durationMs).toLong())
                    seeking = false
                },
                enabled = state.canSeek,
                modifier = Modifier.fillMaxWidth(),
            )

            // Transport, and the source at the right end: just icons when the panel is narrow
            // (beside the playlist in a wide window).
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compact = maxWidth < 680.dp
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    IconButton(actions::previous, icon = Icons.Previous)
                    IconButton(actions::play, icon = Icons.Play)
                    IconButton(actions::pause, icon = Icons.Pause)
                    IconButton(actions::stop, icon = Icons.Stop)
                    IconButton(actions::next, icon = Icons.Next)
                    Spacer(Modifier.width(6.dp))
                    IconButton(actions::open, width = 34.dp, icon = Icons.Eject)
                    Spacer(Modifier.weight(1f))
                    // Source: the built-in player, or remote control of a streaming app
                    if (!compact) BasicText("SOURCE", style = LabelStyle, modifier = Modifier.padding(end = 4.dp))
                    sources.forEach { option ->
                        LedToggle(
                            if (compact) "" else option.label, option.source == source, { actions.selectSource(option.source) },
                            enabled = option.available, icon = sourceIcon(option.source),
                        )
                    }
                }
            }
        }
    }
}

private fun sourceIcon(source: Source) = when (source) {
    Source.Local -> Icons.Note
    Source.Plex -> Icons.Plex
    is Source.Remote -> when (source.app) {
        RemoteSessions.App.Spotify -> Icons.Waves
        RemoteSessions.App.YouTubeMusic -> Icons.PlayRing
        RemoteSessions.App.Chrome -> Icons.Globe
    }
}

@Composable
private fun LcdField(value: String, unit: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            Modifier
                .width(34.dp)
                .height(16.dp)
                .background(PsColors.LcdBackground, RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.CenterEnd,
        ) {
            BasicText(value, style = LcdTextStyle.copy(fontSize = 11.sp), modifier = Modifier.padding(end = 3.dp))
        }
        BasicText(unit, style = LabelStyle)
    }
}

@Composable
private fun StatusIndicator(state: DisplayState, modifier: Modifier) {
    Canvas(modifier) {
        when {
            state.isPlaying -> drawPath(Path().apply {
                moveTo(0f, 0f); lineTo(size.width, size.height / 2); lineTo(0f, size.height); close()
            }, PsColors.Lcd)
            !state.stopped -> {
                val w = size.width * 0.35f
                drawRect(PsColors.Amber, Offset(0f, 0f), Size(w, size.height))
                drawRect(PsColors.Amber, Offset(size.width - w, 0f), Size(w, size.height))
            }
            else -> drawRect(PsColors.TextDim, Offset(0f, 0f), size)
        }
    }
}

/** Small spectrum strip inside the LCD. */
@Composable
private fun MiniAnalyzer(levels: FloatArray, modifier: Modifier) {
    Canvas(modifier) {
        val n = levels.size.coerceAtLeast(1)
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        levels.forEachIndexed { i, v ->
            val h = (v.coerceIn(0f, 1f) * size.height).coerceAtLeast(1f)
            drawRect(
                Brush.verticalGradient(listOf(PsColors.Amber, PsColors.Lcd), startY = 0f, endY = size.height),
                Offset(i * (w + gap), size.height - h), Size(w, h),
            )
        }
    }
}

/** "MM:SS" for the seven-segment display, with a leading '-' when counting down. */
fun formatLcdTime(ms: Long, negative: Boolean = false): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1000)
    val minutes = (totalSeconds / 60).coerceAtMost(99)
    val seconds = totalSeconds % 60
    val m = minutes.toString().padStart(2, ' ')
    return (if (negative) "-" else " ") + "$m:${seconds.toString().padStart(2, '0')}"
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "-:--"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
