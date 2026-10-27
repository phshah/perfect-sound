package app.perfectsound.player.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Perfect Sound palette: brushed dark metal panels with a green LCD. */
object PsColors {
    val Background = Color(0xFF07080A)
    val PanelTop = Color(0xFF2A2F37)
    val PanelBottom = Color(0xFF191C21)
    val BevelLight = Color(0xFF4A515C)
    val BevelDark = Color(0xFF0C0E11)
    val TitleBar = Color(0xFF11141A)
    val TitleText = Color(0xFFC9D2DC)

    val LcdBackground = Color(0xFF030604)
    val Lcd = Color(0xFF39FF6A)
    val LcdDim = Color(0xFF0F2A16)
    val LcdMid = Color(0xFF1FB84A)
    val Amber = Color(0xFFFFC640)

    val Text = Color(0xFFD7DEE6)
    val TextDim = Color(0xFF7A8490)
    val Selection = Color(0xFF1B3A6B)
    val Current = Color(0xFFFFFFFF)

    val Panel: Brush = Brush.verticalGradient(listOf(PanelTop, PanelBottom))
    val ButtonFace: Brush = Brush.verticalGradient(listOf(Color(0xFF3A414B), Color(0xFF22262D)))
    val ButtonFaceHover: Brush = Brush.verticalGradient(listOf(Color(0xFF4A5360), Color(0xFF2C323B)))
    val ButtonFacePressed: Brush = Brush.verticalGradient(listOf(Color(0xFF1A1D22), Color(0xFF2A2F37)))
}
