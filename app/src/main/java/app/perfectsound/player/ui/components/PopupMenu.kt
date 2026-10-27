package app.perfectsound.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.perfectsound.player.ui.theme.PsColors

data class MenuItem(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/** A compact dark context menu. Dismisses on outside click or after an item is chosen. */
@Composable
fun PopupMenu(items: List<MenuItem>, offset: IntOffset, onDismiss: () -> Unit) {
    Popup(offset = offset, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier
                .width(IntrinsicSize.Max)
                .background(PsColors.PanelBottom)
                .border(1.dp, PsColors.BevelLight)
                .padding(vertical = 3.dp),
        ) {
            items.forEach { item ->
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(if (hovered && item.enabled) PsColors.Selection else PsColors.PanelBottom)
                        .hoverable(interaction)
                        .then(if (item.enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
                        .clickable(interaction, null, enabled = item.enabled) { onDismiss(); item.onClick() }
                        .padding(horizontal = 14.dp, vertical = 5.dp),
                ) {
                    BasicText(item.label, style = LabelStyle.copy(
                        color = if (!item.enabled) PsColors.TextDim else if (hovered) Color.White else PsColors.Text,
                        fontSize = 12.sp,
                        letterSpacing = 0.sp,
                    ))
                }
            }
        }
    }
}

/** A small read-only panel of label/value rows, e.g. keyboard shortcuts. */
@Composable
fun InfoPopup(title: String, rows: List<Pair<String, String>>, offset: IntOffset, onDismiss: () -> Unit) {
    Popup(offset = offset, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier
                .background(PsColors.PanelBottom)
                .border(1.dp, PsColors.BevelLight)
                .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)
                .padding(14.dp),
        ) {
            BasicText(title, style = LabelStyle.copy(color = PsColors.TitleText, letterSpacing = 2.sp))
            rows.forEach { (label, value) ->
                androidx.compose.foundation.layout.Row(Modifier.padding(top = 6.dp)) {
                    BasicText(label, Modifier.width(170.dp), style = LcdTextStyle.copy(fontSize = 12.sp))
                    BasicText(value, style = LabelStyle.copy(color = PsColors.Text, fontSize = 12.sp, letterSpacing = 0.sp))
                }
            }
        }
    }
}
