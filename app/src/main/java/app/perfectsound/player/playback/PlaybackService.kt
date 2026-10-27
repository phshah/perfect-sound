package app.perfectsound.player.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.perfectsound.player.MainActivity
import app.perfectsound.player.audio.AudioLevels
import app.perfectsound.player.audio.SpectrumAnalyzer
import app.perfectsound.player.audio.SpectrumTapProcessor
import app.perfectsound.player.plex.PlexConnectionManager
import app.perfectsound.player.plex.PlexTlsHelper
import app.perfectsound.player.plex.PlexUri
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** Plays local and Plex audio in the background with a media notification, lock-screen and media-key controls. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val tap = SpectrumTapProcessor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var levelsJob: Job? = null
    private lateinit var queueStore: QueueStore
    private lateinit var plexManager: PlexConnectionManager
    private val main = Handler(Looper.getMainLooper())
    private val saveQueue = Runnable { session?.player?.let(queueStore::save) }
    /** Tracks that failed in a row; stops skipping once every track has failed. */
    private var consecutiveErrors = 0
    /** Tracks whether we already re-probed Plex endpoints for the current media item after a network switch. */
    private var retriedPlexMediaId: String? = null

    override fun onCreate() {
        super.onCreate()
        PlexTlsHelper.installIfNeeded()
        plexManager = PlexConnectionManager.getInstance(this)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(tap)).build()
        }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("PerfectSound/0.5.0 (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(12_000)
        val baseDataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val resolvingDataSourceFactory = ResolvingDataSource.Factory(baseDataSourceFactory) { dataSpec ->
            if (PlexUri.isPlexUri(dataSpec.uri)) {
                val resolved = plexManager.resolvePlaybackUriBlocking(dataSpec.uri)
                dataSpec.buildUpon().setUri(resolved).build()
            } else {
                dataSpec
            }
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(resolvingDataSourceFactory))
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        queueStore = QueueStore(this)
        queueStore.load()?.let { saved ->
            player.setMediaItems(saved.items, saved.index, saved.positionMs)
            player.volume = saved.volume
            player.shuffleModeEnabled = saved.shuffle
            player.repeatMode = saved.repeatMode
            if (saved.items.isNotEmpty()) player.prepare()
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startLevels() else stopLevels()
                scheduleSave()
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_VOLUME_CHANGED)) scheduleSave()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    consecutiveErrors = 0
                    retriedPlexMediaId = null
                }
            }

            // On network switches (Wi-Fi <-> mobile data), re-probe Plex endpoints once before skipping.
            override fun onPlayerError(error: PlaybackException) {
                val currentItem = player.currentMediaItem
                val currentUri = currentItem?.localConfiguration?.uri ?: currentItem?.requestMetadata?.mediaUri
                if (currentItem != null && PlexUri.isPlexUri(currentUri) && retriedPlexMediaId != currentItem.mediaId) {
                    retriedPlexMediaId = currentItem.mediaId
                    val resumePos = player.currentPosition
                    scope.launch(Dispatchers.IO) {
                        plexManager.resolvePlaybackUriBlocking(currentUri!!, forceReprobe = true)
                        main.post {
                            if (player.currentMediaItem?.mediaId == currentItem.mediaId) {
                                player.seekTo(resumePos)
                                player.prepare()
                                player.play()
                            }
                        }
                    }
                    return
                }
                consecutiveErrors++
                if (consecutiveErrors < player.mediaItemCount && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })

        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                // "Resume playback" from the system media controls after the app was closed.
                override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo)
                        : ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val saved = queueStore.load()
                        ?: return Futures.immediateFailedFuture(UnsupportedOperationException("nothing to resume"))
                    return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(saved.items, saved.index, saved.positionMs))
                }

                // Items added by the UI carry their URI in requestMetadata; resolve it for the player.
                override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                             mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
                    Futures.immediateFuture(mediaItems.map { item ->
                        if (item.localConfiguration != null) item
                        else item.buildUpon().setUri(item.requestMetadata.mediaUri).build()
                    }.toMutableList())
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    private fun scheduleSave() {
        main.removeCallbacks(saveQueue)
        main.postDelayed(saveQueue, 500)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.let(queueStore::save)
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        main.removeCallbacks(saveQueue)
        main.removeCallbacks(periodicSave)
        session?.run {
            queueStore.save(player)
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /**
     * Publishes levels and the waveform ~60 times a second. The decoder writes audio ahead of the speaker in
     * bursts, so a playhead advances in real time [LATENCY_S] behind the write position.
     */
    private fun startLevels() {
        main.removeCallbacks(periodicSave)
        main.postDelayed(periodicSave, 5000) // keep the saved position fresh while playing
        if (levelsJob?.isActive == true) return
        levelsJob = scope.launch {
            val frame = FloatArray(FFT_SIZE)
            var analyzer = SpectrumAnalyzer(tap.sampleRate, FFT_SIZE)
            var playhead = -1.0
            var last = System.nanoTime()
            while (isActive) {
                val now = System.nanoTime()
                val dt = (now - last) / 1e9
                last = now
                val rate = tap.sampleRate
                if (analyzer.sampleRate != rate) analyzer = SpectrumAnalyzer(rate, FFT_SIZE)
                val target = tap.framesWritten() - LATENCY_S * rate
                playhead = if (playhead < 0 || abs(playhead - target) > rate * 0.25) target
                else playhead + dt * rate + (target - playhead - dt * rate) * 0.05
                tap.read(playhead.toLong(), frame)
                var sumSquares = 0.0
                for (s in frame) sumSquares += s * s
                val rmsDb = 20f * log10(maxOf(sqrt(sumSquares / frame.size).toFloat(), 1e-5f))
                analyzer.analyze(frame)?.let {
                    AudioLevels.publish(AudioLevels.Snapshot(AudioLevels.Source.Local, it.bands, rmsDb,
                        it.bars, SpectrumAnalyzer.scopeTrace(frame), tap.channelDb(playhead.toLong(), rate / 20)))
                }
                delay(16)
            }
        }
    }

    private val periodicSave = object : Runnable {
        override fun run() {
            session?.player?.let(queueStore::save)
            main.postDelayed(this, 5000)
        }
    }

    private fun stopLevels() {
        main.removeCallbacks(periodicSave)
        levelsJob?.cancel()
        levelsJob = null
        if (AudioLevels.state.value.source == AudioLevels.Source.Local) AudioLevels.clear()
    }

    private companion object {
        const val FFT_SIZE = 2048
        const val LATENCY_S = 0.25
    }
}
