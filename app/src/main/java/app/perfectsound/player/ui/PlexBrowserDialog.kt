package app.perfectsound.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import app.perfectsound.player.plex.ConnectionType
import app.perfectsound.player.plex.PlexAlbum
import app.perfectsound.player.plex.PlexArtist
import app.perfectsound.player.plex.PlexConnectionManager
import app.perfectsound.player.plex.PlexPlaylist
import app.perfectsound.player.plex.PlexTrack
import app.perfectsound.player.ui.components.LabelStyle
import app.perfectsound.player.ui.components.LcdTextStyle
import app.perfectsound.player.ui.components.LedToggle
import app.perfectsound.player.ui.components.TitleStrip
import app.perfectsound.player.ui.theme.PsColors
import kotlinx.coroutines.launch

private enum class BrowseTab(val label: String) {
    Artists("ARTISTS"),
    Albums("ALBUMS"),
    Playlists("PLAYLISTS"),
    Tracks("TRACKS"),
    Search("SEARCH"),
}

private sealed interface DrillDown {
    data object Root : DrillDown
    data class ArtistAlbums(val artist: PlexArtist) : DrillDown
    data class AlbumTracks(val album: PlexAlbum, val fromArtist: PlexArtist? = null) : DrillDown
    data class PlaylistTracks(val playlist: PlexPlaylist) : DrillDown
}

@Composable
fun PlexBrowserDialog(
    plexState: PlexConnectionManager.State,
    plexManager: PlexConnectionManager,
    onOpenBrowserUrl: (String) -> Unit,
    onPlayTracks: (List<MediaItem>, replace: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var showSetup by remember { mutableStateOf(!plexState.isSignedIn) }
    LaunchedEffect(plexState.isConnected) {
        if (plexState.isConnected) showSetup = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .widthIn(min = 420.dp, max = 760.dp)
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
                .background(PsColors.Panel)
                .border(1.dp, PsColors.BevelLight),
        ) {
            Box(Modifier.fillMaxWidth()) {
                TitleStrip("PLEX MEDIA SERVER")
                Row(
                    Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    LedToggle("CLOSE", on = false, onClick = onDismiss, led = false)
                }
            }

            // Connection & Quality status bar
            ConnectionHeaderBar(
                state = plexState,
                showSetup = showSetup,
                onToggleSetup = { showSetup = !showSetup },
                onRefresh = { plexManager.refreshConnection(refreshResources = true) },
                onCycleQuality = plexManager::cycleRemoteQuality,
                onCycleServer = {
                    val list = plexState.servers
                    if (list.size > 1) {
                        val idx = list.indexOfFirst { it.machineIdentifier == plexState.selectedServer?.machineIdentifier }
                        plexManager.selectServer(list[(idx + 1) % list.size])
                    }
                },
                onCycleSection = {
                    val secs = plexState.sections
                    if (secs.size > 1) {
                        val idx = secs.indexOfFirst { it.key == plexState.selectedSection?.key }
                        plexManager.selectSection(secs[(idx + 1) % secs.size])
                    }
                },
            )

            if (showSetup || !plexState.isSignedIn) {
                PlexSetupPanel(
                    state = plexState,
                    onSignInClick = { plexManager.startPinSignIn(onOpenBrowserUrl) },
                    onCancelPin = plexManager::cancelPinSignIn,
                    onConnectManual = { localUrl, remoteUrl, token ->
                        plexManager.connectManual(localUrl, remoteUrl, token)
                    },
                    onSignOut = plexManager::signOut,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
                )
            } else {
                PlexLibraryContent(
                    state = plexState,
                    plexManager = plexManager,
                    onPlayTracks = onPlayTracks,
                    onDismiss = onDismiss,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(10.dp),
                )
            }
        }
    }
}

@Composable
private fun ConnectionHeaderBar(
    state: PlexConnectionManager.State,
    showSetup: Boolean,
    onToggleSetup: () -> Unit,
    onRefresh: () -> Unit,
    onCycleQuality: () -> Unit,
    onCycleServer: () -> Unit,
    onCycleSection: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(PsColors.TitleBar)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val badgeColor = when (state.connectionType) {
            ConnectionType.LOCAL -> PsColors.Lcd
            ConnectionType.REMOTE, ConnectionType.RELAY -> PsColors.Amber
            null -> PsColors.TextDim
        }
        Box(Modifier.size(8.dp).background(badgeColor, CircleShape))
        val statusText = when {
            state.connecting -> "CONNECTING…"
            state.isConnected -> {
                val srv = state.selectedServer?.name ?: "PLEX"
                val mode = state.connectionType?.badge ?: "ONLINE"
                "$srv [$mode]"
            }
            state.isSignedIn -> "OFFLINE"
            else -> "NOT SIGNED IN"
        }
        BasicText(
            statusText,
            style = LcdTextStyle.copy(color = badgeColor, fontSize = 12.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (state.servers.size > 1) {
            LedToggle("SERVER ⟳", on = false, onClick = onCycleServer, led = false)
        }
        if (state.sections.size > 1 && state.selectedSection != null) {
            LedToggle("LIB: ${state.selectedSection.title.uppercase()}", on = false, onClick = onCycleSection, led = false)
        }
        LedToggle(
            label = "ON-THE-GO: ${state.remoteQuality.label}",
            on = state.remoteQuality.kbps != null,
            onClick = onCycleQuality,
        )
        if (state.isSignedIn) {
            LedToggle("⟳", on = state.connecting, onClick = onRefresh, led = false)
        }
        LedToggle("SETUP", on = showSetup, onClick = onToggleSetup)
    }
}

@Composable
private fun PlexSetupPanel(
    state: PlexConnectionManager.State,
    onSignInClick: () -> Unit,
    onCancelPin: () -> Unit,
    onConnectManual: (String, String?, String) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var manualUrl by remember { mutableStateOf("") }
    var manualRemoteUrl by remember { mutableStateOf("") }
    var manualToken by remember { mutableStateOf("") }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.error?.let { err ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(PsColors.LcdBackground, RoundedCornerShape(3.dp))
                    .border(1.dp, PsColors.Amber, RoundedCornerShape(3.dp))
                    .padding(8.dp),
            ) {
                BasicText(err, style = LcdTextStyle.copy(color = PsColors.Amber, fontSize = 12.sp))
            }
        }

        // 1. Plex.tv Automatic Discovery (Local LAN + Remote + Relay)
        Column(
            Modifier
                .fillMaxWidth()
                .background(PsColors.LcdBackground, RoundedCornerShape(4.dp))
                .border(1.dp, PsColors.BevelDark, RoundedCornerShape(4.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText("AUTOMATIC PLEX SIGN-IN (RECOMMENDED)", style = LabelStyle.copy(color = PsColors.Lcd))
            BasicText(
                "Signs in via plex.tv to automatically discover your server's Local LAN, Remote Direct, and Plex Relay addresses so streaming works at home and on the go.",
                style = LcdTextStyle.copy(color = PsColors.TextDim, fontSize = 12.sp),
            )
            if (state.pinCode != null) {
                Row(
                    Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BasicText(
                        "LINK CODE: ${state.pinCode}",
                        style = LcdTextStyle.copy(color = PsColors.Current, fontSize = 18.sp),
                    )
                    BasicText(
                        "Approve in browser or at plex.tv/link…",
                        style = LcdTextStyle.copy(color = PsColors.Amber, fontSize = 12.sp),
                    )
                    Spacer(Modifier.weight(1f))
                    LedToggle("CANCEL", on = false, onClick = onCancelPin, led = false)
                }
            } else {
                Row(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LedToggle(
                        label = if (state.connecting) "CONNECTING…" else "SIGN IN WITH PLEX",
                        on = !state.connecting,
                        enabled = !state.connecting,
                        onClick = onSignInClick,
                    )
                    if (state.isSignedIn) {
                        LedToggle("SIGN OUT", on = false, onClick = onSignOut, led = false)
                    }
                }
            }
        }

        // 2. Manual Server URL + Token fallback
        Column(
            Modifier
                .fillMaxWidth()
                .background(PsColors.LcdBackground, RoundedCornerShape(4.dp))
                .border(1.dp, PsColors.BevelDark, RoundedCornerShape(4.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText("MANUAL SERVER CONNECTION", style = LabelStyle.copy(color = PsColors.TitleText))
            LcdTextField(
                label = "LOCAL / PRIMARY URL",
                value = manualUrl,
                placeholder = "https://192.168.1.50:32400",
                onValueChange = { manualUrl = it },
            )
            LcdTextField(
                label = "REMOTE URL (OPTIONAL, ON THE GO)",
                value = manualRemoteUrl,
                placeholder = "https://plex.example.com:32400",
                onValueChange = { manualRemoteUrl = it },
            )
            LcdTextField(
                label = "X-PLEX-TOKEN",
                value = manualToken,
                placeholder = "Your X-Plex-Token",
                onValueChange = { manualToken = it },
            )
            Row(Modifier.padding(top = 4.dp)) {
                LedToggle(
                    label = "CONNECT MANUAL SERVER",
                    on = manualUrl.isNotBlank() && manualToken.isNotBlank(),
                    enabled = manualUrl.isNotBlank() && manualToken.isNotBlank() && !state.connecting,
                    onClick = { onConnectManual(manualUrl, manualRemoteUrl.takeIf { it.isNotBlank() }, manualToken) },
                )
            }
        }
    }
}

@Composable
private fun PlexLibraryContent(
    state: PlexConnectionManager.State,
    plexManager: PlexConnectionManager,
    onPlayTracks: (List<MediaItem>, replace: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(BrowseTab.Artists) }
    var drillDown by remember { mutableStateOf<DrillDown>(DrillDown.Root) }
    var searchQuery by remember { mutableStateOf("") }

    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var artists by remember { mutableStateOf<List<PlexArtist>>(emptyList()) }
    var albums by remember { mutableStateOf<List<PlexAlbum>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<PlexPlaylist>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<PlexTrack>>(emptyList()) }

    val scope = rememberCoroutineScope()
    val server = state.selectedServer
    val endpoint = state.activeEndpoint
    val sectionKey = state.selectedSection?.key

    // Load data when tab, drillDown, server, endpoint, or section changes.
    LaunchedEffect(server?.machineIdentifier, endpoint?.uri, sectionKey, tab, drillDown) {
        if (server == null || endpoint == null) return@LaunchedEffect
        loading = true
        errorMsg = null
        try {
            when (val d = drillDown) {
                is DrillDown.ArtistAlbums -> {
                    albums = plexManager.api.fetchArtistAlbums(endpoint.uri, server.accessToken, d.artist.ratingKey)
                }
                is DrillDown.AlbumTracks -> {
                    tracks = plexManager.api.fetchAlbumTracks(
                        endpoint.uri, server.accessToken, d.album.ratingKey, server.machineIdentifier
                    )
                }
                is DrillDown.PlaylistTracks -> {
                    tracks = plexManager.api.fetchPlaylistTracks(
                        endpoint.uri, server.accessToken, d.playlist.ratingKey, server.machineIdentifier
                    )
                }
                DrillDown.Root -> when (tab) {
                    BrowseTab.Artists -> if (sectionKey != null) {
                        artists = plexManager.api.fetchArtists(endpoint.uri, server.accessToken, sectionKey)
                    }
                    BrowseTab.Albums -> if (sectionKey != null) {
                        albums = plexManager.api.fetchAlbums(endpoint.uri, server.accessToken, sectionKey)
                    }
                    BrowseTab.Playlists -> {
                        playlists = plexManager.api.fetchPlaylists(endpoint.uri, server.accessToken)
                    }
                    BrowseTab.Tracks -> if (sectionKey != null) {
                        tracks = plexManager.api.fetchAllSectionTracks(
                            endpoint.uri, server.accessToken, sectionKey, server.machineIdentifier
                        )
                    }
                    BrowseTab.Search -> {
                        if (searchQuery.isNotBlank()) {
                            val res = plexManager.api.search(
                                endpoint.uri, server.accessToken, sectionKey, searchQuery, server.machineIdentifier
                            )
                            artists = res.artists
                            albums = res.albums
                            tracks = res.tracks
                        }
                    }
                }
            }
        } catch (e: Exception) {
            errorMsg = e.message ?: "Failed to load library"
        } finally {
            loading = false
        }
    }

    fun playAlbumNow(album: PlexAlbum, replace: Boolean) {
        val srv = server ?: return
        val ep = endpoint ?: return
        scope.launch {
            loading = true
            runCatching {
                plexManager.api.fetchAlbumTracks(ep.uri, srv.accessToken, album.ratingKey, srv.machineIdentifier)
            }.onSuccess { list ->
                if (list.isNotEmpty()) {
                    onPlayTracks(list.map { it.toMediaItem() }, replace)
                    if (replace) onDismiss()
                }
            }.onFailure { errorMsg = it.message }
            loading = false
        }
    }

    fun playPlaylistNow(playlist: PlexPlaylist, replace: Boolean) {
        val srv = server ?: return
        val ep = endpoint ?: return
        scope.launch {
            loading = true
            runCatching {
                plexManager.api.fetchPlaylistTracks(ep.uri, srv.accessToken, playlist.ratingKey, srv.machineIdentifier)
            }.onSuccess { list ->
                if (list.isNotEmpty()) {
                    onPlayTracks(list.map { it.toMediaItem() }, replace)
                    if (replace) onDismiss()
                }
            }.onFailure { errorMsg = it.message }
            loading = false
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Tab strip
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrowseTab.entries.forEach { t ->
                LedToggle(
                    label = t.label,
                    on = tab == t,
                    onClick = {
                        tab = t
                        drillDown = DrillDown.Root
                    },
                )
            }
            Spacer(Modifier.weight(1f))
            if (drillDown != DrillDown.Root) {
                LedToggle(
                    label = "← BACK",
                    on = false,
                    led = false,
                    onClick = {
                        drillDown = when (val d = drillDown) {
                            is DrillDown.AlbumTracks -> d.fromArtist?.let { DrillDown.ArtistAlbums(it) } ?: DrillDown.Root
                            else -> DrillDown.Root
                        }
                    },
                )
            }
        }

        // Search input bar when on Search tab
        if (tab == BrowseTab.Search && drillDown == DrillDown.Root) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    LcdTextField(
                        label = "SEARCH",
                        value = searchQuery,
                        placeholder = "Artist, album, or track…",
                        onValueChange = { searchQuery = it },
                        onDone = {
                            if (server != null && endpoint != null && searchQuery.isNotBlank()) {
                                scope.launch {
                                    loading = true
                                    runCatching {
                                        plexManager.api.search(
                                            endpoint.uri, server.accessToken, sectionKey, searchQuery, server.machineIdentifier
                                        )
                                    }.onSuccess { res ->
                                        artists = res.artists
                                        albums = res.albums
                                        tracks = res.tracks
                                    }.onFailure { errorMsg = it.message }
                                    loading = false
                                }
                            }
                        },
                    )
                }
                LedToggle(
                    label = "FIND",
                    on = searchQuery.isNotBlank(),
                    enabled = searchQuery.isNotBlank() && !loading,
                    onClick = {
                        if (server != null && endpoint != null && searchQuery.isNotBlank()) {
                            scope.launch {
                                loading = true
                                runCatching {
                                    plexManager.api.search(
                                        endpoint.uri, server.accessToken, sectionKey, searchQuery, server.machineIdentifier
                                    )
                                }.onSuccess { res ->
                                    artists = res.artists
                                    albums = res.albums
                                    tracks = res.tracks
                                }.onFailure { errorMsg = it.message }
                                loading = false
                            }
                        }
                    },
                )
            }
        }

        // Breadcrumb / Bulk actions header when viewing tracks
        val showingTracks = drillDown is DrillDown.AlbumTracks ||
            drillDown is DrillDown.PlaylistTracks ||
            (drillDown == DrillDown.Root && tab == BrowseTab.Tracks)
        if (showingTracks && tracks.isNotEmpty()) {
            val heading = when (val d = drillDown) {
                is DrillDown.AlbumTracks -> listOfNotNull(d.album.artist, d.album.title).joinToString(" — ")
                is DrillDown.PlaylistTracks -> d.playlist.title
                else -> "ALL TRACKS (${tracks.size})"
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicText(
                    heading,
                    style = LcdTextStyle.copy(color = PsColors.Current, fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                LedToggle("PLAY ALL", on = true, onClick = {
                    onPlayTracks(tracks.map { it.toMediaItem() }, true)
                    onDismiss()
                })
                LedToggle("+ QUEUE ALL", on = false, led = false, onClick = {
                    onPlayTracks(tracks.map { it.toMediaItem() }, false)
                })
            }
        }

        // Main LCD list area
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(PsColors.LcdBackground, RoundedCornerShape(3.dp))
                .border(1.dp, PsColors.BevelDark, RoundedCornerShape(3.dp)),
        ) {
            when {
                state.connecting || loading -> {
                    BasicText(
                        "Loading from Plex server…",
                        style = LcdTextStyle.copy(color = PsColors.Lcd),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                errorMsg != null || state.error != null -> {
                    BasicText(
                        errorMsg ?: state.error.orEmpty(),
                        style = LcdTextStyle.copy(color = PsColors.Amber),
                        modifier = Modifier.align(Alignment.Center).padding(20.dp),
                    )
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                        when (val d = drillDown) {
                            is DrillDown.ArtistAlbums -> {
                                items(albums, key = { it.ratingKey }) { album ->
                                    AlbumRow(
                                        album = album,
                                        onOpen = { drillDown = DrillDown.AlbumTracks(album, fromArtist = d.artist) },
                                        onPlay = { playAlbumNow(album, replace = true) },
                                        onQueue = { playAlbumNow(album, replace = false) },
                                    )
                                }
                            }
                            is DrillDown.AlbumTracks, is DrillDown.PlaylistTracks -> {
                                itemsIndexed(tracks, key = { idx, t -> "${t.ratingKey}_$idx" }) { idx, track ->
                                    PlexTrackRow(
                                        index = idx,
                                        track = track,
                                        onPlay = {
                                            // Play starting from this track within the album/playlist
                                            val slice = tracks.subList(idx, tracks.size).map { it.toMediaItem() }
                                            onPlayTracks(slice, true)
                                            onDismiss()
                                        },
                                        onQueue = { onPlayTracks(listOf(track.toMediaItem()), false) },
                                    )
                                }
                            }
                            DrillDown.Root -> when (tab) {
                                BrowseTab.Artists -> {
                                    items(artists, key = { it.ratingKey }) { artist ->
                                        ArtistRow(artist = artist) {
                                            drillDown = DrillDown.ArtistAlbums(artist)
                                        }
                                    }
                                }
                                BrowseTab.Albums -> {
                                    items(albums, key = { it.ratingKey }) { album ->
                                        AlbumRow(
                                            album = album,
                                            onOpen = { drillDown = DrillDown.AlbumTracks(album) },
                                            onPlay = { playAlbumNow(album, replace = true) },
                                            onQueue = { playAlbumNow(album, replace = false) },
                                        )
                                    }
                                }
                                BrowseTab.Playlists -> {
                                    items(playlists, key = { it.ratingKey }) { pl ->
                                        PlaylistRow(
                                            playlist = pl,
                                            onOpen = { drillDown = DrillDown.PlaylistTracks(pl) },
                                            onPlay = { playPlaylistNow(pl, replace = true) },
                                            onQueue = { playPlaylistNow(pl, replace = false) },
                                        )
                                    }
                                }
                                BrowseTab.Tracks -> {
                                    itemsIndexed(tracks, key = { idx, t -> "${t.ratingKey}_$idx" }) { idx, track ->
                                        PlexTrackRow(
                                            index = idx,
                                            track = track,
                                            onPlay = {
                                                onPlayTracks(listOf(track.toMediaItem()), true)
                                                onDismiss()
                                            },
                                            onQueue = { onPlayTracks(listOf(track.toMediaItem()), false) },
                                        )
                                    }
                                }
                                BrowseTab.Search -> {
                                    items(artists, key = { "a_${it.ratingKey}" }) { artist ->
                                        ArtistRow(artist = artist) { drillDown = DrillDown.ArtistAlbums(artist) }
                                    }
                                    items(albums, key = { "al_${it.ratingKey}" }) { album ->
                                        AlbumRow(
                                            album = album,
                                            onOpen = { drillDown = DrillDown.AlbumTracks(album) },
                                            onPlay = { playAlbumNow(album, replace = true) },
                                            onQueue = { playAlbumNow(album, replace = false) },
                                        )
                                    }
                                    itemsIndexed(tracks, key = { idx, t -> "tr_${t.ratingKey}_$idx" }) { idx, track ->
                                        PlexTrackRow(
                                            index = idx,
                                            track = track,
                                            onPlay = {
                                                onPlayTracks(listOf(track.toMediaItem()), true)
                                                onDismiss()
                                            },
                                            onQueue = { onPlayTracks(listOf(track.toMediaItem()), false) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistRow(artist: PlexArtist, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            "▸ ${artist.title}",
            style = LcdTextStyle.copy(color = PsColors.Lcd, fontSize = 13.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AlbumRow(
    album: PlexAlbum,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onQueue: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val yearSuffix = album.year?.let { " ($it)" }.orEmpty()
        val artistPrefix = album.artist?.takeIf { it.isNotBlank() }?.let { "$it — " }.orEmpty()
        BasicText(
            "▸ $artistPrefix${album.title}$yearSuffix",
            style = LcdTextStyle.copy(color = PsColors.Lcd, fontSize = 13.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        LedToggle("PLAY", on = false, led = false, onClick = onPlay)
        LedToggle("+Q", on = false, led = false, onClick = onQueue)
    }
}

@Composable
private fun PlaylistRow(
    playlist: PlexPlaylist,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onQueue: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(
            "▸ ${playlist.title} (${playlist.trackCount} tracks)",
            style = LcdTextStyle.copy(color = PsColors.Lcd, fontSize = 13.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        LedToggle("PLAY", on = false, led = false, onClick = onPlay)
        LedToggle("+Q", on = false, led = false, onClick = onQueue)
    }
}

@Composable
private fun PlexTrackRow(
    index: Int,
    track: PlexTrack,
    onPlay: () -> Unit,
    onQueue: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val label = if (track.artist.isNullOrBlank()) track.title else "${track.artist} - ${track.title}"
        BasicText(
            "${index + 1}. $label",
            style = LcdTextStyle.copy(color = PsColors.Lcd, fontSize = 13.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        BasicText(
            formatTime(track.durationMs),
            style = LcdTextStyle.copy(color = PsColors.TextDim, fontSize = 12.sp),
        )
        LedToggle("+Q", on = false, led = false, onClick = onQueue)
    }
}

@Composable
private fun LcdTextField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onDone: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            label,
            style = LabelStyle.copy(color = PsColors.TextDim),
            modifier = Modifier.width(150.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(26.dp)
                .background(PsColors.Background, RoundedCornerShape(3.dp))
                .border(1.dp, PsColors.BevelLight, RoundedCornerShape(3.dp))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                BasicText(placeholder, style = LcdTextStyle.copy(color = PsColors.TextDim, fontSize = 12.sp))
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = LcdTextStyle.copy(color = PsColors.Lcd, fontSize = 12.sp),
                cursorBrush = SolidColor(PsColors.Lcd),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
