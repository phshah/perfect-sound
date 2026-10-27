package app.perfectsound.player.plex

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages Plex server authentication, endpoint probing across LAN / Remote Direct / Relay,
 * automatic network-roaming handoff, and `plex://` stream URL resolution.
 */
class PlexConnectionManager private constructor(context: Context) {

    data class State(
        val isSignedIn: Boolean = false,
        val connecting: Boolean = false,
        val pinCode: String? = null,
        val servers: List<PlexServer> = emptyList(),
        val selectedServer: PlexServer? = null,
        val activeEndpoint: PlexEndpoint? = null,
        val sections: List<PlexMusicSection> = emptyList(),
        val selectedSection: PlexMusicSection? = null,
        val remoteQuality: StreamQuality = StreamQuality.Original,
        val error: String? = null,
    ) {
        val isConnected: Boolean get() = selectedServer != null && activeEndpoint != null
        val connectionType: ConnectionType? get() = activeEndpoint?.connectionType
    }

    private val appContext = context.applicationContext
    val prefs = PlexPrefs(appContext)
    val api = PlexApiClient(prefs.clientIdentifier)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(
        State(
            isSignedIn = prefs.plexTvToken != null || prefs.servers.isNotEmpty(),
            servers = prefs.servers,
            selectedServer = prefs.servers.firstOrNull { it.machineIdentifier == prefs.selectedServerId }
                ?: prefs.servers.firstOrNull(),
            remoteQuality = prefs.remoteQuality,
        )
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /** Active reachable endpoint per server machineIdentifier. */
    private val activeEndpoints = ConcurrentHashMap<String, PlexEndpoint>()
    private var pinJob: Job? = null
    private var probeJob: Job? = null

    init {
        PlexTlsHelper.installIfNeeded()
        registerNetworkCallback()
        if (_state.value.servers.isNotEmpty() || prefs.plexTvToken != null) {
            refreshConnection(refreshResources = prefs.plexTvToken != null)
        }
    }

    /** Starts Plex PIN sign-in and calls [onBrowserUrl] so the caller can open the auth tab. */
    fun startPinSignIn(onBrowserUrl: (String) -> Unit) {
        pinJob?.cancel()
        pinJob = scope.launch {
            _state.update { it.copy(connecting = true, error = null, pinCode = null) }
            try {
                // 1) Create a strong=true OAuth PIN required by https://app.plex.tv/auth#?
                val browserPin = api.createPin(strong = true)
                // 2) Also create a 4-character strong=false PIN in case the user prefers typing a code at plex.tv/link
                val linkPin = runCatching { api.createPin(strong = false) }.getOrNull()
                val url = api.buildBrowserAuthUrl(browserPin)
                _state.update { it.copy(pinCode = linkPin?.code ?: "BROWSER") }
                onBrowserUrl(url)

                // Poll up to 3 minutes for the user to approve in the browser or at plex.tv/link.
                repeat(90) {
                    if (!isActive) return@launch
                    delay(2_000)
                    val token = runCatching { api.pollPin(browserPin) }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: linkPin?.let { lp -> runCatching { api.pollPin(lp) }.getOrNull()?.takeIf { it.isNotBlank() } }
                    if (!token.isNullOrBlank()) {
                        prefs.plexTvToken = token
                        _state.update { it.copy(isSignedIn = true, pinCode = null) }
                        discoverAndConnect(token)
                        return@launch
                    }
                }
                _state.update { it.copy(connecting = false, pinCode = null, error = "Sign-in timed out. Try again.") }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        connecting = false,
                        pinCode = null,
                        error = "Couldn't reach plex.tv (${e.message ?: "network error"})",
                    )
                }
            }
        }
    }

    fun cancelPinSignIn() {
        pinJob?.cancel()
        _state.update { it.copy(connecting = false, pinCode = null) }
    }

    /** Connects to a manually configured server URL + optional remote URL + X-Plex-Token. */
    fun connectManual(localOrPrimaryUrl: String, remoteUrl: String?, token: String) {
        probeJob?.cancel()
        probeJob = scope.launch {
            _state.update { it.copy(connecting = true, error = null) }
            try {
                val server = api.verifyManualServer(localOrPrimaryUrl, remoteUrl, token)
                val updatedServers = (listOf(server) + prefs.servers.filterNot { it.machineIdentifier == server.machineIdentifier })
                prefs.servers = updatedServers
                prefs.selectedServerId = server.machineIdentifier
                _state.update {
                    it.copy(
                        isSignedIn = true,
                        servers = updatedServers,
                        selectedServer = server,
                    )
                }
                probeAndLoadSections(server)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        connecting = false,
                        error = "Connection failed: ${e.message ?: "check URL and token"}",
                    )
                }
            }
        }
    }

    fun selectServer(server: PlexServer) {
        prefs.selectedServerId = server.machineIdentifier
        prefs.selectedSectionKey = null
        _state.update {
            it.copy(
                selectedServer = server,
                activeEndpoint = activeEndpoints[server.machineIdentifier],
                sections = emptyList(),
                selectedSection = null,
                error = null,
            )
        }
        probeJob?.cancel()
        probeJob = scope.launch { probeAndLoadSections(server) }
    }

    fun selectSection(section: PlexMusicSection) {
        prefs.selectedSectionKey = section.key
        _state.update { it.copy(selectedSection = section) }
    }

    fun cycleRemoteQuality() {
        val next = _state.value.remoteQuality.next()
        prefs.remoteQuality = next
        _state.update { it.copy(remoteQuality = next) }
    }

    fun signOut() {
        pinJob?.cancel()
        probeJob?.cancel()
        activeEndpoints.clear()
        prefs.clearAll()
        _state.value = State(remoteQuality = prefs.remoteQuality)
    }

    /** Re-probes endpoints (and optionally refreshes server list from plex.tv) when network changes. */
    fun refreshConnection(refreshResources: Boolean = false) {
        probeJob?.cancel()
        probeJob = scope.launch {
            val token = prefs.plexTvToken
            if (refreshResources && !token.isNullOrBlank()) {
                discoverAndConnect(token)
            } else {
                val srv = _state.value.selectedServer ?: prefs.servers.firstOrNull()
                if (srv != null) probeAndLoadSections(srv)
            }
        }
    }

    private suspend fun discoverAndConnect(plexTvToken: String) {
        _state.update { it.copy(connecting = true, error = null) }
        val discovered = runCatching { api.fetchServers(plexTvToken) }.getOrNull()
        val servers = if (!discovered.isNullOrEmpty()) {
            prefs.servers = discovered
            discovered
        } else {
            prefs.servers
        }
        if (servers.isEmpty()) {
            _state.update {
                it.copy(
                    connecting = false,
                    error = "No Plex Media Servers found on this account.",
                )
            }
            return
        }
        val selected = servers.firstOrNull { it.machineIdentifier == prefs.selectedServerId } ?: servers.first()
        prefs.selectedServerId = selected.machineIdentifier
        _state.update { it.copy(servers = servers, selectedServer = selected) }
        probeAndLoadSections(selected)
    }

    private suspend fun probeAndLoadSections(server: PlexServer) {
        _state.update { it.copy(connecting = true, error = null) }
        val best = PlexEndpointSelector.selectBest(server.endpoints) { ep ->
            api.probeEndpoint(ep, server.accessToken)
        }
        if (best == null) {
            _state.update {
                it.copy(
                    connecting = false,
                    activeEndpoint = null,
                    error = "Can't reach ${server.name} on local network or remote access.",
                )
            }
            return
        }
        activeEndpoints[server.machineIdentifier] = best
        val sections = runCatching { api.fetchMusicSections(best.uri, server.accessToken) }.getOrDefault(emptyList())
        val selectedSec = sections.firstOrNull { it.key == prefs.selectedSectionKey } ?: sections.firstOrNull()
        selectedSec?.let { prefs.selectedSectionKey = it.key }
        _state.update {
            it.copy(
                connecting = false,
                selectedServer = server,
                activeEndpoint = best,
                sections = sections,
                selectedSection = selectedSec,
                error = if (sections.isEmpty()) "No Music library found on ${server.name}." else null,
            )
        }
    }

    /**
     * Resolves a `plex://track/...` URI to a live HTTP(S) stream URL on ExoPlayer's loader thread.
     * Automatically probes endpoints if not yet connected or after a network switch.
     */
    fun resolvePlaybackUriBlocking(uri: Uri, forceReprobe: Boolean = false): Uri {
        val ref = PlexUri.parseTrackUri(uri.toString()) ?: return uri
        val server = findServer(ref.machineIdentifier) ?: return uri
        val endpoint = getOrProbeEndpointBlocking(server, forceReprobe) ?: return uri
        val httpUrl = PlexUri.buildPlaybackHttpUrl(
            baseUrl = endpoint.uri,
            accessToken = server.accessToken,
            clientId = prefs.clientIdentifier,
            ref = ref,
            connectionType = endpoint.connectionType,
            remoteQuality = _state.value.remoteQuality,
        )
        return Uri.parse(httpUrl)
    }

    /** Resolves album/track cover art for a `plex://` track or art URI. */
    suspend fun resolveArtworkHttpUrl(uri: Uri, maxPx: Int = 640): String? {
        val artRef = PlexUri.parseArtUri(uri.toString())
        val trackRef = if (artRef == null) PlexUri.parseTrackUri(uri.toString()) else null
        val machineId = artRef?.machineIdentifier ?: trackRef?.machineIdentifier ?: return null
        val thumbPath = artRef?.thumbPath ?: trackRef?.thumbPath ?: return null
        val server = findServer(machineId) ?: return null
        val endpoint = activeEndpoints[machineId] ?: PlexEndpointSelector.selectBest(server.endpoints) { ep ->
            api.probeEndpoint(ep, server.accessToken)
        }?.also { activeEndpoints[machineId] = it } ?: return null
        return PlexUri.buildArtworkHttpUrl(endpoint.uri, server.accessToken, thumbPath, maxPx)
    }

    private fun findServer(machineIdentifier: String): PlexServer? =
        _state.value.servers.firstOrNull { it.machineIdentifier == machineIdentifier }
            ?: prefs.servers.firstOrNull { it.machineIdentifier == machineIdentifier }
            ?: _state.value.selectedServer

    private fun getOrProbeEndpointBlocking(server: PlexServer, forceReprobe: Boolean): PlexEndpoint? {
        if (!forceReprobe) {
            activeEndpoints[server.machineIdentifier]?.let { return it }
        }
        return runBlocking(Dispatchers.IO) {
            var best = PlexEndpointSelector.selectBest(server.endpoints) { ep ->
                api.probeEndpoint(ep, server.accessToken)
            }
            if (best == null && prefs.plexTvToken != null) {
                // Public IP may have changed on the go; refresh server endpoints from plex.tv.
                val refreshed = runCatching { api.fetchServers(prefs.plexTvToken!!) }.getOrNull()
                if (!refreshed.isNullOrEmpty()) {
                    prefs.servers = refreshed
                    val updatedServer = refreshed.firstOrNull { it.machineIdentifier == server.machineIdentifier } ?: server
                    best = PlexEndpointSelector.selectBest(updatedServer.endpoints) { ep ->
                        api.probeEndpoint(ep, updatedServer.accessToken)
                    }
                }
            }
            if (best != null) {
                activeEndpoints[server.machineIdentifier] = best
                if (_state.value.selectedServer?.machineIdentifier == server.machineIdentifier) {
                    _state.update { it.copy(activeEndpoint = best, error = null) }
                }
            }
            best
        }
    }

    private fun registerNetworkCallback() {
        runCatching {
            val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (_state.value.selectedServer != null) {
                        refreshConnection(refreshResources = false)
                    }
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    // When switching between Wi-Fi and Cellular, re-probe so LOCAL vs REMOTE updates.
                    val srv = _state.value.selectedServer ?: return
                    scope.launch {
                        val best = PlexEndpointSelector.selectBest(srv.endpoints) { ep ->
                            api.probeEndpoint(ep, srv.accessToken)
                        }
                        if (best != null && best != activeEndpoints[srv.machineIdentifier]) {
                            activeEndpoints[srv.machineIdentifier] = best
                            _state.update { it.copy(activeEndpoint = best, error = null) }
                        }
                    }
                }
            })
        }
    }

    companion object {
        @Volatile
        private var instance: PlexConnectionManager? = null

        fun getInstance(context: Context): PlexConnectionManager =
            instance ?: synchronized(this) {
                instance ?: PlexConnectionManager(context.applicationContext).also { instance = it }
            }
    }
}
