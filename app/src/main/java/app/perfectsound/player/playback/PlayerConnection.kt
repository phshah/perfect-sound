package app.perfectsound.player.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.perfectsound.player.plex.PlexUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** UI-side handle on [PlaybackService]: exposes player state as a flow and forwards commands. */
class PlayerConnection(private val context: Context, private val scope: CoroutineScope) {

    data class Track(
        val id: String,
        val title: String,
        val artist: String?,
        val durationMs: Long,
        val album: String? = null,
        val uri: Uri? = null,
        val artworkUri: Uri? = null,
    ) {
        val displayName: String get() = if (artist.isNullOrBlank()) title else "$artist - $title"
    }

    data class State(
        val connected: Boolean = false,
        val playlist: List<Track> = emptyList(),
        val currentIndex: Int = C.INDEX_UNSET,
        val isPlaying: Boolean = false,
        /** Winamp-style stop: paused and rewound, shown as stopped. */
        val stopped: Boolean = true,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val volume: Float = 1f,
        val shuffle: Boolean = false,
        val repeatMode: Int = Player.REPEAT_MODE_OFF,
        val bitrateKbps: Int? = null,
        val sampleRateHz: Int? = null,
        val channels: Int? = null,
        /** Shown briefly when a track can't be played (it is skipped). */
        val error: String? = null,
    ) {
        val currentTrack: Track? get() = playlist.getOrNull(currentIndex)
        val totalDurationMs: Long get() = playlist.sumOf { it.durationMs.coerceAtLeast(0) }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var ticker: Job? = null
    private var stopped = true
    private var error: String? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()

        override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
            val title = controller?.currentMediaItem?.mediaMetadata?.title ?: "track"
            error = "Can't play $title"
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) error = null
        }
    }

    fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(listener)
            stopped = !c.isPlaying
            refresh()
            ticker = scope.launch {
                while (isActive) {
                    controller?.let { if (it.isPlaying) _state.update { s -> s.copy(positionMs = it.currentPosition) } }
                    delay(100)
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        ticker?.cancel()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    fun play() = controller?.run {
        if (playbackState == Player.STATE_IDLE) prepare()
        if (playbackState == Player.STATE_ENDED) seekToDefaultPosition()
        stopped = false
        play()
    }

    fun pause() = controller?.run {
        // Winamp: pause while stopped does nothing; while paused it resumes.
        if (stopped) return@run
        if (isPlaying) pause() else play()
    }

    fun stop() = controller?.run {
        stopped = true
        pause()
        seekTo(0)
        refresh()
    }

    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPreviousMediaItem()
    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs)
    fun seekBy(deltaMs: Long) = controller?.run { seekTo((currentPosition + deltaMs).coerceAtLeast(0)) }
    fun moveTrack(from: Int, to: Int) = controller?.moveMediaItem(from, to)
    fun setVolume(volume: Float) = controller?.run { this.volume = volume.coerceIn(0f, 1f) }
    fun toggleShuffle() = controller?.run { shuffleModeEnabled = !shuffleModeEnabled }

    /** Off -> repeat playlist -> repeat track -> off. */
    fun cycleRepeat() = controller?.run {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun playAt(index: Int) = controller?.run {
        seekToDefaultPosition(index)
        if (playbackState == Player.STATE_IDLE) prepare()
        stopped = false
        play()
    }

    fun addTracks(items: List<MediaItem>) = controller?.run {
        addMediaItems(items)
        if (playbackState == Player.STATE_IDLE) prepare()
    }

    fun removeTracks(indices: Collection<Int>) = controller?.run {
        indices.sortedDescending().forEach { removeMediaItem(it) }
    }

    fun clear() = controller?.run {
        stopped = true
        clearMediaItems()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun refresh() {
        val c = controller ?: return
        val playlist = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toTrack() }
        val currentTrack = playlist.getOrNull(c.currentMediaItemIndex)
        val format = c.currentTracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.getTrackFormat(0)
        if (c.isPlaying) stopped = false
        _state.value = State(
            connected = true,
            playlist = playlist,
            currentIndex = if (c.mediaItemCount == 0) C.INDEX_UNSET else c.currentMediaItemIndex,
            isPlaying = c.isPlaying,
            stopped = stopped,
            positionMs = c.currentPosition,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: currentTrack?.durationMs ?: 0,
            volume = c.volume,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            bitrateKbps = format?.let { f ->
                (if (f.bitrate != androidx.media3.common.Format.NO_VALUE) f.bitrate else f.averageBitrate)
                    .takeIf { it > 0 }?.let { it / 1000 }
            } ?: currentTrack?.uri?.let { u ->
                PlexUri.parseTrackUri(u.toString())?.bitrateKbps
            } ?: format?.let { averageKbps(currentTrack?.uri, c.duration) },
            sampleRateHz = format?.sampleRate?.takeIf { it > 0 },
            channels = format?.channelCount?.takeIf { it > 0 },
            error = error,
        )
    }

    private val fileSizes = mutableMapOf<Uri, Long?>()

    /**
     * The average bitrate from the file's size and length, for formats that don't state one
     * (FLAC, WAV), like the classic desktop players show.
     */
    private fun averageKbps(uri: Uri?, durationMs: Long): Int? {
        if (uri == null || PlexUri.isPlexUri(uri) || durationMs <= 0 || durationMs == C.TIME_UNSET) return null
        val size = fileSizes.getOrPut(uri) {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
                }
            }.getOrNull()
        } ?: return null
        return (size * 8 / durationMs).toInt().takeIf { it > 0 }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun MediaItem.toTrack() = Track(
        id = mediaId,
        title = mediaMetadata.title?.toString() ?: requestMetadata.mediaUri?.lastPathSegment ?: "Unknown",
        artist = mediaMetadata.artist?.toString(),
        durationMs = mediaMetadata.durationMs ?: 0,
        album = mediaMetadata.albumTitle?.toString()?.takeIf { it.isNotBlank() },
        uri = requestMetadata.mediaUri ?: localConfiguration?.uri,
        artworkUri = mediaMetadata.artworkUri,
    )
}
