package app.perfectsound.player

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.DragEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import app.perfectsound.player.audio.AudioLevels
import app.perfectsound.player.capture.PlaybackCaptureService
import app.perfectsound.player.library.TrackImporter
import app.perfectsound.player.library.loadEmbeddedArt
import app.perfectsound.player.playback.PlayerConnection
import app.perfectsound.player.plex.PlexConnectionManager
import app.perfectsound.player.remote.RemoteSessions
import app.perfectsound.player.ui.EqSettings
import app.perfectsound.player.ui.MainPanelActions
import app.perfectsound.player.ui.NowPlayingInfo
import app.perfectsound.player.ui.NowPlayingPanel
import app.perfectsound.player.ui.PanelButton
import app.perfectsound.player.ui.PanelMessage
import app.perfectsound.player.ui.PerfectSoundApp
import app.perfectsound.player.ui.PlaylistActions
import app.perfectsound.player.ui.PlaylistPanel
import app.perfectsound.player.ui.PlexBrowserDialog
import app.perfectsound.player.ui.Source
import app.perfectsound.player.ui.SourceOption
import app.perfectsound.player.ui.UiPrefs
import app.perfectsound.player.ui.toDisplay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var player: PlayerConnection
    private lateinit var remote: RemoteSessions
    private lateinit var importer: TrackImporter
    private lateinit var plexManager: PlexConnectionManager
    private lateinit var prefs: UiPrefs
    private lateinit var eq: MutableStateFlow<EqSettings>
    private lateinit var source: MutableStateFlow<Source>
    /** Eject/open replaces the playlist and plays; ADD appends. */
    private var replaceOnImport = false
    private val showPlexDialog = MutableStateFlow(false)

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> import(uris) }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) lifecycleScope.launch { addItems(importer.fromTree(uri)) }
    }

    private val projectionConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null)
            PlaybackCaptureService.start(this, result.resultCode, result.data!!)
    }
    private val capturePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) requestProjection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = PlayerConnection(applicationContext, lifecycleScope)
        remote = RemoteSessions(applicationContext)
        importer = TrackImporter(applicationContext)
        plexManager = PlexConnectionManager.getInstance(applicationContext)
        prefs = UiPrefs(applicationContext)
        eq = MutableStateFlow(prefs.eq)
        source = MutableStateFlow(prefs.source)
        player.connect()
        handleOpenIntent(intent)

        setContent {
            val local by player.state.collectAsStateWithLifecycle()
            val remoteState by remote.state.collectAsStateWithLifecycle()
            val plexState by plexManager.state.collectAsStateWithLifecycle()
            val plexDialogOpen by showPlexDialog.collectAsStateWithLifecycle()
            val levels by AudioLevels.state.collectAsStateWithLifecycle()
            val eqSettings by eq.collectAsStateWithLifecycle()
            val currentSource by source.collectAsStateWithLifecycle()
            var equalizerVisible by remember { mutableStateOf(prefs.equalizerVisible) }
            var playlistVisible by remember { mutableStateOf(prefs.playlistVisible) }
            var visMode by remember { mutableStateOf(prefs.visMode) }
            var visTheme by remember { mutableStateOf(prefs.visTheme) }
            val capturing = levels.source == AudioLevels.Source.Capture

            // Remote apps report position occasionally; extrapolate it and read the system volume.
            var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
            var systemVolume by remember { mutableFloatStateOf(remote.systemVolume()) }
            LaunchedEffect(currentSource) {
                while (currentSource is Source.Remote) {
                    now = SystemClock.elapsedRealtime()
                    systemVolume = remote.systemVolume()
                    delay(100)
                }
            }

            val display = when (val s = currentSource) {
                Source.Local, Source.Plex -> local.toDisplay()
                is Source.Remote -> remoteState.nowPlaying[s.app].let { np ->
                    np.toDisplay(s.app, np?.positionAt(now) ?: 0, systemVolume)
                }
            }
            val sources = listOf(
                SourceOption(Source.Local, "LOCAL", true),
                SourceOption(Source.Plex, "PLEX", true),
            ) + RemoteSessions.App.entries.map { app ->
                SourceOption(Source.Remote(app), app.button, available = app in remoteState.installed)
            }
            val toggleCapture = { if (capturing) PlaybackCaptureService.stop(this) else startCapture() }

            PerfectSoundApp(
                display = display,
                source = currentSource,
                sources = sources,
                levels = levels,
                equalizerVisible = equalizerVisible,
                playlistVisible = playlistVisible,
                mainActions = object : MainPanelActions {
                    override fun play() = route({ player.play() }) { remote.play(it) }
                    override fun pause() = route({ player.pause() }) { remote.togglePause(it) }
                    override fun stop() = route({ player.stop() }) { remote.stop(it) }
                    override fun next() = route({ player.next() }) { remote.next(it) }
                    override fun previous() = route({ player.previous() }) { remote.previous(it) }
                    override fun open() = if (currentSource == Source.Plex) {
                        showPlexDialog.value = true
                    } else {
                        openFiles(replace = true)
                    }
                    override fun seekTo(positionMs: Long) = route({ player.seekTo(positionMs) }) { remote.seekTo(it, positionMs) }
                    override fun setVolume(volume: Float) = route({ player.setVolume(volume) }) {
                        remote.setSystemVolume(volume)
                        systemVolume = remote.systemVolume()
                    }
                    override fun toggleShuffle() = route({ player.toggleShuffle() }) { remote.toggleShuffle(it) }
                    override fun cycleRepeat() = route({ player.cycleRepeat() }) { remote.cycleRepeat(it) }
                    override fun toggleEqualizer() { equalizerVisible = !equalizerVisible; prefs.equalizerVisible = equalizerVisible }
                    override fun togglePlaylist() { playlistVisible = !playlistVisible; prefs.playlistVisible = playlistVisible }
                    // Picking a streaming app also brings it up, so there's something to play.
                    // Clicking it again while selected brings it back to the front.
                    override fun selectSource(source: Source) {
                        val wasAlreadySelected = source == this@MainActivity.source.value
                        switchSource(source)
                        if (source is Source.Remote) remote.launch(source.app)
                        if (source == Source.Plex && (wasAlreadySelected || !plexState.isConnected || local.currentTrack == null)) {
                            showPlexDialog.value = true
                        }
                    }
                    override fun seekBy(deltaMs: Long) = route({ player.seekBy(deltaMs) }) { app ->
                        val np = remote.state.value.nowPlaying[app]
                        if (np?.canSeek == true) remote.seekTo(app, (np.positionAt() + deltaMs).coerceAtLeast(0))
                    }
                    override fun changeVolume(delta: Float) = setVolume(display.volume + delta)
                    override fun addFolder() = pickFolder.launch(null)
                },
                nowPlaying = { modifier ->
                    val panel = when (val s = currentSource) {
                        Source.Local -> localNowPlaying(local, playlistVisible) {
                            playlistVisible = !playlistVisible; prefs.playlistVisible = playlistVisible
                        }
                        Source.Plex -> plexNowPlaying(local, plexState, playlistVisible) {
                            playlistVisible = !playlistVisible; prefs.playlistVisible = playlistVisible
                        }
                        is Source.Remote -> remoteNowPlaying(s.app, remoteState, capturing, toggleCapture)
                    }
                    NowPlayingPanel(
                        label = panel.label,
                        info = panel.info,
                        message = panel.message,
                        buttons = panel.buttons,
                        hint = panel.hint,
                        equalizerVisible = equalizerVisible,
                        eq = eqSettings,
                        onEqChange = { eq.value = it; prefs.eq = it },
                        levels = levels,
                        visMode = visMode,
                        onVisModeChange = { visMode = it; prefs.visMode = it },
                        visTheme = visTheme,
                        onVisThemeChange = { visTheme = it; prefs.visTheme = it },
                        modifier = modifier,
                        eqOn = if (panel.onEqToggle != null) panel.eqOn else eqSettings.enabled,
                        onEqToggle = panel.onEqToggle,
                    )
                },
                playlist = if (currentSource.isBuiltIn) { modifier -> PlaylistPanel(local, playlistActions, modifier) } else null,
                onDrop = ::onFilesDropped,
            )

            if (plexDialogOpen) {
                PlexBrowserDialog(
                    plexState = plexState,
                    plexManager = plexManager,
                    onOpenBrowserUrl = ::openBrowserUrl,
                    onPlayTracks = { items, replace -> addPlexItems(items, replace) },
                    onDismiss = { showPlexDialog.value = false },
                )
            }
        }
    }

    /** What the now-playing panel shows for one source. */
    private class NowPlayingContent(
        val label: String,
        val info: NowPlayingInfo,
        val message: PanelMessage? = null,
        val buttons: List<PanelButton> = emptyList(),
        val hint: String? = null,
        /** Remote mode: ON captures the app's audio so the equalizer bounces. */
        val eqOn: Boolean = false,
        val onEqToggle: (() -> Unit)? = null,
    )

    @Composable
    private fun localNowPlaying(
        state: PlayerConnection.State,
        playlistVisible: Boolean,
        onTogglePlaylist: () -> Unit,
    ): NowPlayingContent {
        val track = state.currentTrack
        val art by produceState<ImageBitmap?>(null, track?.uri, track?.artworkUri) {
            value = track?.uri?.let {
                loadEmbeddedArt(applicationContext, it, artworkUri = track.artworkUri)?.asImageBitmap()
            }
        }
        val buttons = listOf(
            PanelButton("OPEN FILES", tooltip = "Open audio files and replace the current playlist (L)") { openFiles(replace = true) },
            PanelButton("ADD FOLDER", tooltip = "Add all audio files from a folder to the playlist (Shift+L)") { pickFolder.launch(null) },
            PanelButton(
                "PLAYLIST",
                on = playlistVisible,
                tooltip = if (playlistVisible) "Hide the playlist (Alt+E)" else "Show the playlist (Alt+E)",
                onClick = onTogglePlaylist,
            ),
        )
        return if (track == null) NowPlayingContent(
            label = "NOW PLAYING",
            info = NowPlayingInfo(),
            message = PanelMessage("Nothing playing", "Open some music files, add a folder, or drop files onto the window."),
            buttons = buttons,
        ) else NowPlayingContent(
            label = "NOW PLAYING",
            info = NowPlayingInfo(track.title, track.artist, track.album, art),
            buttons = buttons,
        )
    }

    @Composable
    private fun plexNowPlaying(
        state: PlayerConnection.State,
        plexState: PlexConnectionManager.State,
        playlistVisible: Boolean,
        onTogglePlaylist: () -> Unit,
    ): NowPlayingContent {
        val track = state.currentTrack
        val art by produceState<ImageBitmap?>(null, track?.uri, track?.artworkUri) {
            value = track?.uri?.let {
                loadEmbeddedArt(applicationContext, it, artworkUri = track.artworkUri)?.asImageBitmap()
            }
        }
        val label = when {
            plexState.isConnected -> {
                val srv = plexState.selectedServer?.name?.uppercase() ?: "PLEX"
                val mode = plexState.connectionType?.badge ?: "ONLINE"
                "PLEX · $srv [$mode]"
            }
            plexState.connecting -> "PLEX · CONNECTING…"
            else -> "PLEX MEDIA SERVER"
        }
        val buttons = listOf(
            PanelButton(
                "BROWSE PLEX",
                on = plexState.isConnected,
                tooltip = "Open Plex Music Browser (Artists, Albums, Playlists, Search)",
            ) { showPlexDialog.value = true },
            PanelButton(
                "ON-THE-GO: ${plexState.remoteQuality.label}",
                on = plexState.remoteQuality.kbps != null,
                tooltip = "Remote / Relay streaming quality (${plexState.remoteQuality.label}) — Click to cycle ORIG / 320K / 192K / 128K",
                onClick = plexManager::cycleRemoteQuality,
            ),
            PanelButton(
                "PLAYLIST",
                on = playlistVisible,
                tooltip = if (playlistVisible) "Hide the playlist (Alt+E)" else "Show the playlist (Alt+E)",
                onClick = onTogglePlaylist,
            ),
        )
        val connectionHint = if (plexState.isConnected) {
            "Connected via ${plexState.connectionType?.badge ?: "PLEX"} (${plexState.selectedServer?.name.orEmpty()}) · On-the-go quality: ${plexState.remoteQuality.label}"
        } else null

        return when {
            !plexState.isSignedIn -> NowPlayingContent(
                label = label,
                info = NowPlayingInfo(),
                message = PanelMessage(
                    "Connect your Plex Media Server",
                    "Stream your music library directly in Perfect Sound — automatically uses Local LAN at home and Remote Direct or Plex Relay on the go.",
                    listOf(
                        PanelButton(
                            "CONNECT PLEX",
                            on = true,
                            tooltip = "Sign in with your Plex account or enter your server URL and X-Plex-Token",
                        ) { showPlexDialog.value = true }
                    ),
                ),
                buttons = buttons,
            )
            track == null -> NowPlayingContent(
                label = label,
                info = NowPlayingInfo(),
                message = PanelMessage(
                    if (plexState.isConnected) "Connected to ${plexState.selectedServer?.name}" else (plexState.error ?: "Connecting to Plex…"),
                    if (plexState.isConnected) {
                        "Mode: ${plexState.connectionType?.badge ?: "ONLINE"} · Browse your artists, albums, or playlists to start streaming."
                    } else {
                        "Check your network or open Plex Setup to refresh server connections."
                    },
                    listOf(
                        PanelButton(
                            "BROWSE PLEX",
                            on = true,
                            tooltip = "Open Plex Music Browser to browse or search your library",
                        ) { showPlexDialog.value = true }
                    ),
                ),
                buttons = buttons,
            )
            else -> NowPlayingContent(
                label = label,
                info = NowPlayingInfo(track.title, track.artist, track.album, art),
                buttons = buttons,
                hint = connectionHint,
            )
        }
    }

    @Composable
    private fun remoteNowPlaying(
        app: RemoteSessions.App,
        state: RemoteSessions.State,
        capturing: Boolean,
        toggleCapture: () -> Unit,
    ): NowPlayingContent {
        val np = state.nowPlaying[app]
        val art = remember(np?.art) { np?.art?.asImageBitmap() }
        val name = app.label.uppercase()
        val buttons = emptyList<PanelButton>()
        return when {
            !state.hasAccess -> NowPlayingContent(
                label = name,
                info = NowPlayingInfo(),
                message = PanelMessage(
                    "Allow Perfect Sound to see what's playing",
                    "To show and control ${app.label}, Android needs you to turn on notification access " +
                        "for Perfect Sound. It's only used to read and control media playback." +
                        // Android's own steps for lifting the restriction; an app can't lift it itself.
                        (if (!state.accessRestricted) "" else "\n\nIs the switch greyed out? Android restricts " +
                            "apps installed from a download:\n1. Click the switch, then OK.\n" +
                            "2. Click APP INFO, open ⋮ at the top right and choose Allow restricted settings.\n" +
                            "3. Click GRANT ACCESS again and turn it on."),
                    listOfNotNull(
                        PanelButton("GRANT ACCESS", tooltip = "Open Android Notification Access settings for Perfect Sound") { startActivity(remote.accessSettingsIntent()) },
                        if (state.accessRestricted) PanelButton("APP INFO", tooltip = "Open App Info to allow restricted settings for Perfect Sound") { startActivity(remote.appInfoIntent()) } else null,
                    ),
                ),
                buttons = buttons,
                eqOn = capturing,
                onEqToggle = toggleCapture,
            )
            np?.title == null -> NowPlayingContent(
                label = name,
                info = NowPlayingInfo(),
                message = PanelMessage(
                    "Nothing playing in ${app.label}",
                    "Start something in ${app.label}, then control it from here. Click ${app.button} above to bring ${app.label} up.",
                ),
                buttons = buttons,
                eqOn = capturing,
                onEqToggle = toggleCapture,
            )
            else -> NowPlayingContent(
                label = name,
                info = NowPlayingInfo(np.title, np.artist, np.album, art),
                buttons = buttons,
                hint = if (capturing) null else "Turn the equalizer ON to make it bounce to ${app.label}. Android will ask to capture audio each time.",
                eqOn = capturing,
                onEqToggle = toggleCapture,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenIntent(intent)
    }

    /** "Open with Perfect Sound": add the file to the playlist and play it. */
    private fun handleOpenIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        intent.action = null // don't re-import on configuration changes
        lifecycleScope.launch {
            val items = importer.fromDocuments(listOf(uri))
            if (items.isEmpty()) return@launch
            player.state.first { it.connected } // on a cold start the player may still be connecting
            switchSource(Source.Local)
            val first = player.state.value.playlist.size
            player.addTracks(items)
            player.playAt(first)
        }
    }

    override fun onStart() {
        super.onStart()
        remote.start() // re-checks notification access, which may have just been granted
    }

    /**
     * On a desktop our window stays visible beside Settings, so onStart doesn't run again after
     * access is granted there: check again when the user comes back to the window.
     */
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (isTopResumedActivity) remote.start()
    }

    override fun onStop() {
        remote.stop()
        super.onStop()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    private val playlistActions = object : PlaylistActions {
        override fun playAt(index: Int) { player.playAt(index) }
        override fun addFiles() = openFiles(replace = false)
        override fun addFolder() = pickFolder.launch(null)
        override fun addPlex() { showPlexDialog.value = true }
        override fun remove(indices: Set<Int>) { player.removeTracks(indices) }
        override fun clear() { player.clear() }
        override fun move(from: Int, to: Int) { player.moveTrack(from, to) }
    }

    /** Files dragged in from the Files app (or another window) are added to the playlist. */
    private fun onFilesDropped(event: DragEvent): Boolean {
        val clip = event.clipData ?: return false
        requestDragAndDropPermissions(event) // read access to the dropped URIs
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        if (uris.isEmpty()) return false
        lifecycleScope.launch { addItems(importer.fromDropped(uris)) }
        return true
    }

    /** Changes source, pausing the old one first when switching between built-in and remote apps. */
    private fun switchSource(new: Source) {
        val old = source.value
        if (new == old) return
        if (old.isBuiltIn && new is Source.Remote) player.pause()
        else if (old is Source.Remote) remote.pause(old.app)
        source.value = new
        prefs.source = new
    }

    /** Sends a command to the built-in player (Local / Plex) or to the streaming app being remote-controlled. */
    private inline fun route(local: () -> Unit, remoteCommand: (RemoteSessions.App) -> Unit) {
        when (val s = source.value) {
            Source.Local, Source.Plex -> local()
            is Source.Remote -> remoteCommand(s.app)
        }
    }

    private fun openFiles(replace: Boolean) {
        replaceOnImport = replace
        pickFiles.launch(arrayOf("audio/*"))
    }

    private fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        lifecycleScope.launch { addItems(importer.fromDocuments(uris)) }
    }

    private fun addItems(items: List<MediaItem>) {
        if (items.isEmpty()) return
        if (!source.value.isBuiltIn) switchSource(Source.Local)
        if (replaceOnImport) {
            replaceOnImport = false
            player.clear()
            player.addTracks(items)
            player.playAt(0)
        } else {
            player.addTracks(items)
        }
    }

    private fun addPlexItems(items: List<MediaItem>, replace: Boolean) {
        if (items.isEmpty()) return
        switchSource(Source.Plex)
        if (replace) {
            player.clear()
            player.addTracks(items)
            player.playAt(0)
        } else {
            val wasEmpty = player.state.value.playlist.isEmpty()
            player.addTracks(items)
            if (wasEmpty) player.playAt(0)
        }
    }

    private fun openBrowserUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun startCapture() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) requestProjection() else capturePermissions.launch(needed.toTypedArray())
    }

    private fun requestProjection() {
        projectionConsent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }
}
