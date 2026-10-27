package app.perfectsound.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perfectsound.player.audio.AudioLevels
import app.perfectsound.player.audio.SpectrumAnalyzer
import app.perfectsound.player.ui.components.LabelStyle
import app.perfectsound.player.ui.components.LedToggle
import app.perfectsound.player.ui.components.VSlider
import app.perfectsound.player.ui.components.WithTooltip
import app.perfectsound.player.ui.theme.PsColors

/**
 * Whether the equalizer shows its tuning controls (preamp, band sliders, auto, presets). Off for now:
 * the equalizer is a visualization only. The settings are still kept and saved.
 */
private const val EQ_TUNING_ENABLED = false

/** Equalizer settings: preamp and ten bands, each -1..+1 (maps to ±12 dB). */
data class EqSettings(
    val enabled: Boolean = false,
    val preamp: Float = 0f,
    val bands: List<Float> = List(SpectrumAnalyzer.BAND_CENTERS_HZ.size) { 0f },
)

/**
 * The visualizer, in whichever [visMode] is chosen (click it or MODE to switch), plus the preamp and
 * ten band sliders over the BANDS view when tuning is enabled. Sits inside the now-playing panel.
 * [onToggle] overrides what ON does: in remote mode it starts capturing audio so the levels bounce,
 * since Android doesn't let us process another app's sound.
 */
@Composable
fun EqSection(
    settings: EqSettings,
    onSettingsChange: (EqSettings) -> Unit,
    levels: AudioLevels.Snapshot,
    visMode: VisMode,
    onVisModeChange: (VisMode) -> Unit,
    visTheme: VisTheme,
    onVisThemeChange: (VisTheme) -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean = settings.enabled,
    onToggle: (() -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(visMode.label, Modifier.height(20.dp).wrapContentHeight(), style = LabelStyle.copy(letterSpacing = 2.sp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LedToggle(
                    "COLOR",
                    false,
                    { onVisThemeChange(visTheme.next()) },
                    led = false,
                    tooltip = "Color theme: ${visTheme.name} — Click to switch to ${visTheme.next().name}",
                )
                ThemeDots(visTheme, Modifier.padding(top = 4.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LedToggle(
                    "MODE",
                    false,
                    { onVisModeChange(visMode.next()) },
                    led = false,
                    tooltip = "Visualizer mode: ${visMode.label} — Click to switch to ${visMode.next().label}",
                )
                ModeDots(visMode, Modifier.padding(top = 4.dp))
            }
            // Local files bounce without a switch; in remote mode ON starts the audio capture.
            if (onToggle != null) {
                LedToggle(
                    "ON",
                    on,
                    onToggle,
                    tooltip = if (on) "Audio capture active — Click to stop capturing playback" else "Capture app audio so the visualizer bounces to remote playback",
                )
            }
            if (EQ_TUNING_ENABLED) {
                if (onToggle == null) LedToggle("ON", on, { onSettingsChange(settings.copy(enabled = !settings.enabled)) }, enabled = false, tooltip = "Equalizer ON / OFF")
                LedToggle("AUTO", false, {}, enabled = false, tooltip = "Auto equalizer")
                LedToggle("PRESETS", false, {}, enabled = false, tooltip = "Equalizer presets")
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            // Preamp
            if (EQ_TUNING_ENABLED) Column(Modifier.width(28.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                VSlider(settings.preamp, { onSettingsChange(settings.copy(preamp = it)) }, Modifier.weight(1f).fillMaxWidth(), tooltip = "Preamp")
                BasicText("PRE", style = LabelStyle)
            }
            if (EQ_TUNING_ENABLED) Spacer(Modifier.width(6.dp))
            // The visualizer, with the band sliders over it
            Column(Modifier.weight(1f).fillMaxHeight()) {
                WithTooltip(
                    tooltip = "Visualizer (${visMode.label}) — Click to switch to ${visMode.next().label}",
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { hovered ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            // The VU meters are their own little windows on the panel, so they get no LCD screen behind them.
                            .then(if (visMode == VisMode.Vu) Modifier else Modifier
                                .background(PsColors.LcdBackground, RoundedCornerShape(3.dp))
                                .border(1.dp, if (hovered) PsColors.BevelLight else PsColors.BevelDark, RoundedCornerShape(3.dp)))
                            .clip(RoundedCornerShape(3.dp))
                            .clickable(remember { MutableInteractionSource() }, indication = null) { onVisModeChange(visMode.next()) },
                    ) {
                        Visualizer(visMode, levels, Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 3.dp), visTheme)
                        if (EQ_TUNING_ENABLED && visMode == VisMode.Bands) Row(Modifier.fillMaxSize()) {
                            settings.bands.forEachIndexed { i, v ->
                                VSlider(v, { nv ->
                                    onSettingsChange(settings.copy(bands = settings.bands.toMutableList().also { it[i] = nv }))
                                }, Modifier.weight(1f).fillMaxHeight(), tooltip = "${SpectrumAnalyzer.BAND_LABELS[i]} Hz")
                            }
                        }
                    }
                }
                if (visMode == VisMode.Bands) Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    SpectrumAnalyzer.BAND_LABELS.forEach {
                        BasicText(it, Modifier.weight(1f), style = LabelStyle.copy(textAlign = TextAlign.Center, fontSize = 9.sp, letterSpacing = 0.sp))
                    }
                }
            }
        }
    }
}

/** One tiny dot per visualizer mode, the current one lit, so it's clear where MODE is in the cycle. */
@Composable
private fun ModeDots(current: VisMode, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        VisMode.entries.forEach {
            Box(Modifier.size(3.dp).background(if (it == current) PsColors.Lcd else PsColors.TextDim.copy(alpha = 0.35f), CircleShape))
        }
    }
}

/** One tiny dot per colour theme, each in its own colours, the current one bright. */
@Composable
private fun ThemeDots(current: VisTheme, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        VisTheme.entries.forEach {
            Box(Modifier.size(3.dp).alpha(if (it == current) 1f else 0.3f).background(it.swatch(), CircleShape))
        }
    }
}
