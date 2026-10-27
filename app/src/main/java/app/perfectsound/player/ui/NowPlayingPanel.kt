package app.perfectsound.player.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perfectsound.player.audio.AudioLevels
import app.perfectsound.player.ui.components.LabelStyle
import app.perfectsound.player.ui.components.LcdTextStyle
import app.perfectsound.player.ui.components.LedToggle
import app.perfectsound.player.ui.components.TitleStrip
import app.perfectsound.player.ui.theme.PsColors

/** The current track's details, whichever source is playing. */
data class NowPlayingInfo(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val art: ImageBitmap? = null,
)

/** Shown in place of the track details, e.g. when Android access is missing or nothing is playing. */
data class PanelMessage(val title: String, val body: String, val buttons: List<PanelButton> = emptyList())

/** A button under the album art, or under a [PanelMessage]. */
data class PanelButton(
    val label: String,
    val on: Boolean = false,
    val tooltip: String? = null,
    val onClick: () -> Unit,
)

/** Height of each [LedToggle] under the art, and the gap between them. */
private val ButtonHeight = 20.dp
private val ButtonSpacing = 4.dp

/** Smaller than this the album art is hidden so its buttons stay fully visible. */
private val MinArtSize = 48.dp

/** The buttons under the art never get narrower than this, so their labels stay readable. */
private val MinButtonWidth = 132.dp

/**
 * Album art with its buttons below, and to its right the track details above the visualizer.
 * Used for every source: local files and the streaming apps in remote mode.
 */
@Composable
fun NowPlayingPanel(
    label: String,
    info: NowPlayingInfo,
    message: PanelMessage?,
    buttons: List<PanelButton>,
    hint: String?,
    equalizerVisible: Boolean,
    eq: EqSettings,
    onEqChange: (EqSettings) -> Unit,
    levels: AudioLevels.Snapshot,
    visMode: VisMode,
    onVisModeChange: (VisMode) -> Unit,
    visTheme: VisTheme,
    onVisThemeChange: (VisTheme) -> Unit,
    modifier: Modifier = Modifier,
    /** In remote mode: whether audio is being captured for the levels, and how to toggle it. */
    eqOn: Boolean = eq.enabled,
    onEqToggle: (() -> Unit)? = null,
) {
    Column(modifier.background(PsColors.Panel)) {
        TitleStrip(label)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(10.dp)) {
            // Computed from the fixed button height rather than measured: a measurement taken while the
            // buttons are squeezed reports the clipped size, so the art would never shrink to make room.
            val buttonsHeight = ButtonHeight * buttons.size + ButtonSpacing * (buttons.size - 1).coerceAtLeast(0)
            val belowArt = if (buttons.isEmpty()) 0.dp else buttonsHeight + 8.dp
            // The art takes the height left over by its buttons, but leaves most of the width to the EQ.
            // The buttons always come first: when the panel is short (e.g. beside the playlist) the art
            // shrinks, and is dropped entirely rather than pushing the buttons out of view.
            val artSize = minOf(maxHeight - belowArt, maxWidth * 0.4f, 320.dp)
            val showArt = artSize >= MinArtSize || buttons.isEmpty()
            val columnWidth = when {
                buttons.isEmpty() -> artSize.coerceAtLeast(MinArtSize)
                else -> maxOf(artSize, MinButtonWidth).coerceAtMost(maxWidth * 0.45f)
            }
            // The row is as tall as the art and its buttons, so the visualizer ends level with them.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    Modifier.width(columnWidth),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (showArt) AlbumArt(info.art, Modifier.size(artSize.coerceAtLeast(MinArtSize)))
                    // Each button is as wide as the art.
                    if (buttons.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(ButtonSpacing)) {
                        buttons.forEach {
                            LedToggle(
                                label = it.label,
                                on = it.on,
                                onClick = it.onClick,
                                modifier = Modifier.fillMaxWidth(),
                                tooltip = it.tooltip ?: it.label,
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (message != null) Message(message) else TrackDetails(info, hint)
                    if (equalizerVisible) EqSection(eq, onEqChange, levels, visMode, onVisModeChange, visTheme, onVisThemeChange, Modifier.padding(top = 10.dp).weight(1f).fillMaxWidth(), eqOn, onEqToggle)
                }
            }
        }
    }
}

@Composable
private fun AlbumArt(art: ImageBitmap?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(PsColors.LcdBackground), contentAlignment = Alignment.Center) {
        if (art != null) Image(art, contentDescription = "Album art", contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High, modifier = Modifier.fillMaxSize())
        else BasicText("♪", style = LcdTextStyle.copy(fontSize = 40.sp, color = PsColors.LcdDim))
    }
}

@Composable
private fun TrackDetails(info: NowPlayingInfo, hint: String?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(info.title.orEmpty(), style = LcdTextStyle.copy(color = PsColors.Current, fontSize = 18.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        val byline = buildAnnotatedString {
            info.artist?.let { withStyle(SpanStyle(color = PsColors.Lcd)) { append(it) } }
            if (info.artist != null && info.album != null) withStyle(SpanStyle(color = PsColors.TextDim)) { append("  ·  ") }
            info.album?.let { withStyle(SpanStyle(color = PsColors.TextDim)) { append(it) } }
        }
        if (byline.isNotEmpty()) BasicText(byline, style = LcdTextStyle.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        hint?.let { BasicText(it, style = LabelStyle.copy(color = PsColors.TextDim, fontSize = 11.sp, letterSpacing = 0.sp, fontWeight = null)) }
    }
}

@Composable
private fun Message(message: PanelMessage) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(message.title, style = LcdTextStyle.copy(color = PsColors.Current, fontSize = 15.sp))
        BasicText(message.body, style = LcdTextStyle.copy(color = PsColors.TextDim, fontSize = 12.sp))
        if (message.buttons.isNotEmpty()) Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            message.buttons.forEach { LedToggle(it.label, it.on, it.onClick, tooltip = it.tooltip ?: it.label) }
        }
    }
}
