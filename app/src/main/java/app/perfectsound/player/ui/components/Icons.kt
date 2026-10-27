package app.perfectsound.player.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Transport and source icons, drawn to fill the canvas they are given. */
object Icons {
    private fun DrawScope.triangle(left: Float, right: Float, color: Color) {
        drawPath(Path().apply {
            moveTo(left, 0f); lineTo(right, size.height / 2); lineTo(left, size.height); close()
        }, color)
    }

    private fun DrawScope.triangleLeft(left: Float, right: Float, color: Color) {
        drawPath(Path().apply {
            moveTo(right, 0f); lineTo(left, size.height / 2); lineTo(right, size.height); close()
        }, color)
    }

    val Play: DrawScope.(Color) -> Unit = { c -> triangle(size.width * 0.15f, size.width * 0.9f, c) }

    val Pause: DrawScope.(Color) -> Unit = { c ->
        val w = size.width * 0.28f
        drawRect(c, Offset(size.width * 0.12f, 0f), Size(w, size.height))
        drawRect(c, Offset(size.width * 0.88f - w, 0f), Size(w, size.height))
    }

    val Stop: DrawScope.(Color) -> Unit = { c ->
        val s = minOf(size.width, size.height)
        drawRect(c, Offset((size.width - s) / 2, (size.height - s) / 2), Size(s, s))
    }

    val Previous: DrawScope.(Color) -> Unit = { c ->
        drawRect(c, Offset(0f, 0f), Size(size.width * 0.12f, size.height))
        triangleLeft(size.width * 0.12f, size.width * 0.56f, c)
        triangleLeft(size.width * 0.56f, size.width, c)
    }

    val Next: DrawScope.(Color) -> Unit = { c ->
        triangle(0f, size.width * 0.44f, c)
        triangle(size.width * 0.44f, size.width * 0.88f, c)
        drawRect(c, Offset(size.width * 0.88f, 0f), Size(size.width * 0.12f, size.height))
    }

    val Eject: DrawScope.(Color) -> Unit = { c ->
        drawPath(Path().apply {
            moveTo(size.width / 2, 0f); lineTo(size.width, size.height * 0.62f); lineTo(0f, size.height * 0.62f); close()
        }, c)
        drawRect(c, Offset(0f, size.height * 0.78f), Size(size.width, size.height * 0.22f))
    }

    /** Local files: a quaver. */
    val Note: DrawScope.(Color) -> Unit = { c ->
        val w = size.width
        val h = size.height
        drawOval(c, Offset(w * 0.08f, h * 0.66f), Size(w * 0.46f, h * 0.34f))
        drawRect(c, Offset(w * 0.44f, 0f), Size(w * 0.1f, h * 0.84f))
        drawPath(Path().apply {
            moveTo(w * 0.54f, 0f)
            cubicTo(w * 0.62f, h * 0.2f, w * 0.95f, h * 0.22f, w * 0.82f, h * 0.55f)
            cubicTo(w * 0.84f, h * 0.34f, w * 0.7f, h * 0.3f, w * 0.54f, h * 0.28f)
            close()
        }, c)
    }

    /** Spotify: a round badge with three sound waves. */
    val Waves: DrawScope.(Color) -> Unit = { c ->
        val r = minOf(size.width, size.height) / 2
        val cx = size.width / 2
        val cy = size.height / 2
        drawCircle(c, r, Offset(cx, cy))
        val stroke = Stroke(r * 0.17f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        for ((i, y) in listOf(-0.32f, 0.02f, 0.32f).withIndex()) {
            val half = r * (0.62f - i * 0.1f)
            drawPath(Path().apply {
                moveTo(cx - half, cy + r * y)
                quadraticTo(cx, cy + r * (y - 0.26f + i * 0.04f), cx + half, cy + r * (y + 0.1f))
            }, Color.Black.copy(alpha = 0.75f), style = stroke)
        }
    }

    /** YouTube Music: a play triangle in a ring. */
    val PlayRing: DrawScope.(Color) -> Unit = { c ->
        val r = minOf(size.width, size.height) / 2
        val cx = size.width / 2
        val cy = size.height / 2
        val line = Stroke(r * 0.16f)
        drawCircle(c, r - line.width / 2, Offset(cx, cy), style = line)
        drawPath(Path().apply {
            moveTo(cx - r * 0.3f, cy - r * 0.42f); lineTo(cx + r * 0.46f, cy); lineTo(cx - r * 0.3f, cy + r * 0.42f); close()
        }, c)
    }

    /** A web browser: a globe. */
    val Globe: DrawScope.(Color) -> Unit = { c ->
        val r = minOf(size.width, size.height) / 2
        val center = Offset(size.width / 2, size.height / 2)
        val line = Stroke(1.2.dp.toPx())
        drawCircle(c, r - line.width / 2, center, style = line)
        drawOval(c, Offset(center.x - r * 0.42f, center.y - r + line.width / 2), Size(r * 0.84f, 2 * r - line.width), style = line)
        drawLine(c, Offset(center.x - r, center.y), Offset(center.x + r, center.y), line.width)
        drawLine(c, Offset(center.x, center.y - r), Offset(center.x, center.y + r), line.width)
    }

    /** Plex: a bold right-pointing chevron inside a server frame. */
    val Plex: DrawScope.(Color) -> Unit = { c ->
        val w = size.width
        val h = size.height
        drawPath(Path().apply {
            moveTo(w * 0.22f, h * 0.08f)
            lineTo(w * 0.52f, h * 0.08f)
            lineTo(w * 0.82f, h * 0.5f)
            lineTo(w * 0.52f, h * 0.92f)
            lineTo(w * 0.22f, h * 0.92f)
            lineTo(w * 0.52f, h * 0.5f)
            close()
        }, c)
    }
}
