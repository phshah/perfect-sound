package app.perfectsound.player.plex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * Lightweight coroutine client for plex.tv authentication/discovery and Plex Media Server music APIs.
 */
class PlexApiClient(private val clientIdentifier: String) {

    init {
        PlexTlsHelper.installIfNeeded()
    }

    /**
     * Creates a PIN on plex.tv.
     * - [strong] = true: 25-character OAuth code required by `https://app.plex.tv/auth#?`.
     * - [strong] = false: 4-character link PIN used at `https://plex.tv/link/?pin=<code>`.
     */
    suspend fun createPin(strong: Boolean = true): PlexPin = withContext(Dispatchers.IO) {
        val body = request(
            url = "https://plex.tv/api/v2/pins?strong=$strong",
            method = "POST",
            token = null,
            timeoutMs = 8_000,
        )
        PlexJsonParser.parsePin(body)
    }

    /** URL to open in the browser so the user can approve the PIN in one tap. */
    fun buildBrowserAuthUrl(pin: PlexPin): String =
        if (pin.code.length <= 6) {
            "https://plex.tv/link/?pin=${encode(pin.code)}"
        } else {
            "https://app.plex.tv/auth#?clientID=${encode(clientIdentifier)}" +
                "&code=${encode(pin.code)}" +
                "&context%5Bdevice%5D%5Bproduct%5D=${encode("Perfect Sound")}" +
                "&context%5Bdevice%5D%5Bversion%5D=${encode("0.5.0")}" +
                "&context%5Bdevice%5D%5Bplatform%5D=${encode("Android")}" +
                "&context%5Bdevice%5D%5Bdevice%5D=${encode("Android")}" +
                "&context%5Bdevice%5D%5BdeviceName%5D=${encode("Perfect Sound")}"
        }

    /** Checks whether the user has authorized [pin] yet; returns the plex.tv authToken when ready. */
    suspend fun pollPin(pin: PlexPin): String? = withContext(Dispatchers.IO) {
        val body = request(
            url = "https://plex.tv/api/v2/pins/${pin.id}?code=${encode(pin.code)}",
            method = "GET",
            token = null,
            timeoutMs = 6_000,
        )
        PlexJsonParser.parsePinAuthToken(body)
    }

    /** Discovers all Plex Media Servers (local LAN, remote direct, and relay endpoints) for [authToken]. */
    suspend fun fetchServers(authToken: String): List<PlexServer> = withContext(Dispatchers.IO) {
        val body = request(
            url = "https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1&includeIPv6=0",
            method = "GET",
            token = authToken,
            timeoutMs = 10_000,
        )
        PlexJsonParser.parseResources(body)
    }

    /** Connects to a manually entered server URL (and optional remote URL) with an X-Plex-Token. */
    suspend fun verifyManualServer(
        primaryUrlInput: String,
        remoteUrlInput: String?,
        tokenInput: String,
    ): PlexServer = withContext(Dispatchers.IO) {
        val token = tokenInput.trim()
        val candidates = buildManualEndpoints(primaryUrlInput, remoteUrlInput)
        if (candidates.isEmpty()) throw IOException("Enter a valid server URL")

        var lastError: Exception? = null
        var machineId = "manual-server"
        var serverName = "Plex Server"
        for (ep in candidates) {
            try {
                val idBody = request("${ep.uri}/identity", "GET", token, timeoutMs = 4_000)
                val (id, name) = PlexJsonParser.parseIdentity(idBody)
                machineId = id
                // Also fetch root '/' to verify the token and read friendlyName
                val rootBody = request("${ep.uri}/", "GET", token, timeoutMs = 4_000)
                val (_, rootName) = PlexJsonParser.parseIdentity(rootBody)
                serverName = rootName ?: name ?: "Plex Server"
                break
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (machineId == "manual-server" && lastError != null) throw lastError

        // Upgrade any IPv4 endpoints to also include https://<a-b-c-d>.<machineId>.plex.direct:<port>
        val enriched = enrichManualEndpointsWithPlexDirect(candidates, machineId)
        PlexServer(
            name = serverName,
            machineIdentifier = machineId,
            accessToken = token,
            owned = true,
            endpoints = enriched,
        )
    }

    /** Fast health/auth probe against an endpoint (`/identity` or `/library/sections`). */
    suspend fun probeEndpoint(endpoint: PlexEndpoint, accessToken: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val timeout = if (endpoint.local) 2_000 else 3_500
            request("${endpoint.uri}/identity", "GET", accessToken, timeoutMs = timeout)
            true
        }.getOrDefault(false)
    }

    suspend fun fetchMusicSections(baseUrl: String, token: String): List<PlexMusicSection> = withContext(Dispatchers.IO) {
        val body = request("${baseUrl.trimEnd('/')}/library/sections", "GET", token)
        PlexJsonParser.parseMusicSections(body)
    }

    suspend fun fetchArtists(baseUrl: String, token: String, sectionKey: String): List<PlexArtist> = withContext(Dispatchers.IO) {
        val body = request("${baseUrl.trimEnd('/')}/library/sections/$sectionKey/all?type=8&sort=titleSort:asc", "GET", token)
        PlexJsonParser.parseArtists(body)
    }

    suspend fun fetchAlbums(baseUrl: String, token: String, sectionKey: String): List<PlexAlbum> = withContext(Dispatchers.IO) {
        val body = request("${baseUrl.trimEnd('/')}/library/sections/$sectionKey/all?type=9&sort=addedAt:desc", "GET", token)
        PlexJsonParser.parseAlbums(body)
    }

    suspend fun fetchArtistAlbums(baseUrl: String, token: String, artistRatingKey: String): List<PlexAlbum> = withContext(Dispatchers.IO) {
        val body = request("${baseUrl.trimEnd('/')}/library/metadata/$artistRatingKey/children", "GET", token)
        PlexJsonParser.parseAlbums(body)
    }

    suspend fun fetchAlbumTracks(baseUrl: String, token: String, albumRatingKey: String, machineIdentifier: String): List<PlexTrack> =
        withContext(Dispatchers.IO) {
            val body = request("${baseUrl.trimEnd('/')}/library/metadata/$albumRatingKey/children", "GET", token)
            PlexJsonParser.parseTracks(body, machineIdentifier)
        }

    suspend fun fetchAllSectionTracks(baseUrl: String, token: String, sectionKey: String, machineIdentifier: String): List<PlexTrack> =
        withContext(Dispatchers.IO) {
            val body = request(
                "${baseUrl.trimEnd('/')}/library/sections/$sectionKey/all?type=10&sort=addedAt:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=250",
                "GET",
                token,
            )
            PlexJsonParser.parseTracks(body, machineIdentifier)
        }

    suspend fun fetchPlaylists(baseUrl: String, token: String): List<PlexPlaylist> = withContext(Dispatchers.IO) {
        val body = request("${baseUrl.trimEnd('/')}/playlists?playlistType=audio", "GET", token)
        PlexJsonParser.parsePlaylists(body)
    }

    suspend fun fetchPlaylistTracks(baseUrl: String, token: String, playlistRatingKey: String, machineIdentifier: String): List<PlexTrack> =
        withContext(Dispatchers.IO) {
            val body = request("${baseUrl.trimEnd('/')}/playlists/$playlistRatingKey/items", "GET", token)
            PlexJsonParser.parseTracks(body, machineIdentifier)
        }

    suspend fun search(
        baseUrl: String,
        token: String,
        sectionKey: String?,
        query: String,
        machineIdentifier: String,
    ): PlexSearchResults = withContext(Dispatchers.IO) {
        val q = encode(query.trim())
        val secParam = sectionKey?.let { "&sectionId=${encode(it)}" }.orEmpty()
        val body = request("${baseUrl.trimEnd('/')}/hubs/search?query=$q$secParam&limit=30", "GET", token)
        PlexJsonParser.parseSearchHubs(body, machineIdentifier)
    }

    private fun request(
        url: String,
        method: String,
        token: String?,
        timeoutMs: Int = 8_000,
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            if (this is HttpsURLConnection) {
                hostnameVerifier = PlexTlsHelper.verifier
            }
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Plex-Client-Identifier", clientIdentifier)
            setRequestProperty("X-Plex-Product", "Perfect Sound")
            setRequestProperty("X-Plex-Version", "0.5.0")
            setRequestProperty("X-Plex-Platform", "Android")
            setRequestProperty("X-Plex-Device", "Android")
            setRequestProperty("X-Plex-Device-Name", "Perfect Sound")
            if (!token.isNullOrBlank()) {
                setRequestProperty("X-Plex-Token", token)
            }
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("HTTP $code from $url")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun buildManualEndpoints(primaryInput: String, remoteInput: String?): List<PlexEndpoint> {
        val list = mutableListOf<PlexEndpoint>()
        normalizeServerUrl(primaryInput)?.let { url ->
            list += PlexEndpoint(uri = url, local = isPrivateHost(url), relay = false, protocol = url.substringBefore("://"))
        }
        if (!remoteInput.isNullOrBlank()) {
            normalizeServerUrl(remoteInput)?.let { url ->
                if (list.none { it.uri == url }) {
                    list += PlexEndpoint(uri = url, local = false, relay = false, protocol = url.substringBefore("://"))
                }
            }
        }
        return list
    }

    private fun normalizeServerUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) return null
        val withScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> "https://" + trimmed.removePrefix("http://").removePrefix("HTTP://")
            else -> "https://$trimmed"
        }
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 32400
        return "https://$host:$port"
    }

    private fun enrichManualEndpointsWithPlexDirect(endpoints: List<PlexEndpoint>, machineId: String): List<PlexEndpoint> {
        if (machineId.isBlank() || machineId == "manual-server") return endpoints
        val out = mutableListOf<PlexEndpoint>()
        val ipv4 = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
        for (ep in endpoints) {
            val u = runCatching { URI(ep.uri) }.getOrNull()
            val host = u?.host
            val port = u?.port?.takeIf { it > 0 } ?: 32400
            if (host != null && ipv4.matches(host)) {
                val dashed = host.replace('.', '-')
                out += PlexEndpoint(
                    uri = "https://$dashed.$machineId.plex.direct:$port",
                    local = ep.local,
                    relay = ep.relay,
                    protocol = "https",
                )
            }
            out += ep
        }
        return out.distinctBy { it.uri }
    }

    private fun isPrivateHost(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        return host.startsWith("192.168.") || host.startsWith("10.") ||
            host.startsWith("172.16.") || host.startsWith("172.17.") ||
            host.startsWith("172.18.") || host.startsWith("172.19.") ||
            host.startsWith("172.2") || host.startsWith("172.30.") ||
            host.startsWith("172.31.") || host.startsWith("192-168-") ||
            host.startsWith("10-") || host.endsWith(".local")
    }

    private fun encode(s: String): String =
        URLEncoder.encode(s, Charsets.UTF_8.name()).replace("+", "%20")
}
