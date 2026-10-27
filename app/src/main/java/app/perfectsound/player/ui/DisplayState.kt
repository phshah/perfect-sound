package app.perfectsound.player.ui

import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.common.Player
import app.perfectsound.player.playback.PlayerConnection
import app.perfectsound.player.remote.RemoteSessions

/** Where playback comes from: the built-in player (local files or Plex server) or a streaming app in remote mode. */
sealed interface Source {
    data object Local : Source
    data object Plex : Source
    data class Remote(val app: RemoteSessions.App) : Source

    val isBuiltIn: Boolean get() = this == Local || this == Plex
}

enum class Repeat { Off, All, One }

/** What the main panel shows, independent of the source. */
data class DisplayState(
    val title: String,
    val hasTrack: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val stopped: Boolean,
    val canSeek: Boolean,
    val volume: Float,
    val shuffle: Boolean,
    val repeat: Repeat,
    val canShuffle: Boolean = true,
    val canRepeat: Boolean = true,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
)

fun PlayerConnection.State.toDisplay(): DisplayState {
    val track = currentTrack
    return DisplayState(
        title = error ?: track?.let { "${currentIndex + 1}. ${it.displayName} (${formatTime(it.durationMs)})" } ?: "Perfect Sound",
        hasTrack = track != null,
        positionMs = positionMs,
        durationMs = durationMs,
        isPlaying = isPlaying,
        stopped = stopped,
        canSeek = track != null && durationMs > 0,
        volume = volume,
        shuffle = shuffle,
        repeat = when (repeatMode) {
            Player.REPEAT_MODE_ALL -> Repeat.All
            Player.REPEAT_MODE_ONE -> Repeat.One
            else -> Repeat.Off
        },
        bitrateKbps = bitrateKbps,
        sampleRateHz = sampleRateHz,
        channels = channels,
    )
}

fun RemoteSessions.NowPlaying?.toDisplay(app: RemoteSessions.App, positionMs: Long, volume: Float): DisplayState {
    val np = this
    val name = listOfNotNull(np?.artist?.takeIf { it.isNotBlank() }, np?.title?.takeIf { it.isNotBlank() }).joinToString(" - ")
    return DisplayState(
        title = if (np?.title == null) "${app.label}: nothing playing" else "${app.label}: $name" +
            (if ((np.durationMs) > 0) " (${formatTime(np.durationMs)})" else ""),
        hasTrack = np?.title != null,
        positionMs = positionMs,
        durationMs = np?.durationMs ?: 0,
        isPlaying = np?.isPlaying == true,
        stopped = np?.title == null,
        canSeek = np?.canSeek == true && (np.durationMs) > 0,
        volume = volume,
        shuffle = np?.shuffle == true,
        repeat = when (np?.repeatMode) {
            PlaybackStateCompat.REPEAT_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_GROUP -> Repeat.All
            PlaybackStateCompat.REPEAT_MODE_ONE -> Repeat.One
            else -> Repeat.Off
        },
        canShuffle = np?.canShuffle == true,
        canRepeat = np?.canRepeat == true,
        bitrateKbps = app.typicalKbps.takeIf { np?.title != null },
        sampleRateHz = app.typicalSampleRateHz.takeIf { np?.title != null },
        channels = if (np?.title != null) 2 else null,
    )
}
