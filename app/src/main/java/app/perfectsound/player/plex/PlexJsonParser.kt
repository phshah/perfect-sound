package app.perfectsound.player.plex

import org.json.JSONArray
import org.json.JSONObject

/** Parses JSON responses from plex.tv and Plex Media Server endpoints. */
object PlexJsonParser {

    private val IPV4_REGEX = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")

    fun parsePin(jsonStr: String): PlexPin {
        val o = JSONObject(jsonStr)
        return PlexPin(
            id = o.getLong("id"),
            code = o.getString("code"),
        )
    }

    fun parsePinAuthToken(jsonStr: String): String? {
        val o = JSONObject(jsonStr)
        if (o.isNull("authToken")) return null
        return o.optString("authToken").takeIf { it.isNotBlank() && it != "null" }
    }

    fun parseResources(jsonStr: String): List<PlexServer> {
        val arr = JSONArray(jsonStr)
        val servers = mutableListOf<PlexServer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val provides = o.optString("provides", "").split(',').map { it.trim() }
            if ("server" !in provides) continue
            val machineId = o.optString("clientIdentifier").takeIf { it.isNotBlank() } ?: continue
            val token = o.optString("accessToken").takeIf { it.isNotBlank() } ?: continue
            val name = o.optString("name").ifBlank { "Plex Media Server" }
            val owned = o.optBoolean("owned", true)
            val conns = o.optJSONArray("connections") ?: JSONArray()
            val endpoints = mutableListOf<PlexEndpoint>()
            for (j in 0 until conns.length()) {
                val c = conns.optJSONObject(j) ?: continue
                val uri = c.optString("uri").trimEnd('/').takeIf { it.isNotBlank() } ?: continue
                val protocol = c.optString("protocol", "https")
                val address = c.optString("address")
                val port = c.optInt("port", 32400)
                val local = c.optBoolean("local", false)
                val relay = c.optBoolean("relay", false)
                endpoints += PlexEndpoint(uri = uri, local = local, relay = relay, protocol = protocol)
                // If the router has DNS rebinding protection blocking *.plex.direct for LAN IPs,
                // add a direct IP HTTPS fallback endpoint as well.
                if (local && !relay && IPV4_REGEX.matches(address)) {
                    val directHttps = "https://$address:$port"
                    if (endpoints.none { it.uri == directHttps }) {
                        endpoints += PlexEndpoint(uri = directHttps, local = true, relay = false, protocol = "https")
                    }
                }
            }
            if (endpoints.isNotEmpty()) {
                servers += PlexServer(
                    name = name,
                    machineIdentifier = machineId,
                    accessToken = token,
                    owned = owned,
                    endpoints = endpoints,
                )
            }
        }
        return servers
    }

    fun parseIdentity(jsonStr: String): Pair<String, String?> {
        val root = JSONObject(jsonStr)
        val mc = root.optJSONObject("MediaContainer") ?: root
        val machineId = mc.optString("machineIdentifier").ifBlank { "manual-server" }
        val friendlyName = mc.optString("friendlyName").takeIf { it.isNotBlank() }
        return machineId to friendlyName
    }

    fun parseMusicSections(jsonStr: String): List<PlexMusicSection> {
        val mc = JSONObject(jsonStr).optJSONObject("MediaContainer") ?: return emptyList()
        val dirs = mc.optJSONArray("Directory") ?: return emptyList()
        val out = mutableListOf<PlexMusicSection>()
        for (i in 0 until dirs.length()) {
            val d = dirs.optJSONObject(i) ?: continue
            if (d.optString("type") == "artist") {
                val key = d.optString("key")
                val title = d.optString("title", "Music")
                if (key.isNotBlank()) out += PlexMusicSection(key, title)
            }
        }
        return out
    }

    fun parseArtists(jsonStr: String): List<PlexArtist> {
        val meta = metadataArray(jsonStr) ?: return emptyList()
        val out = mutableListOf<PlexArtist>()
        for (i in 0 until meta.length()) {
            val m = meta.optJSONObject(i) ?: continue
            val ratingKey = m.optString("ratingKey").takeIf { it.isNotBlank() } ?: continue
            out += PlexArtist(
                ratingKey = ratingKey,
                title = m.optString("title", "Unknown Artist"),
                summary = m.optString("summary").takeIf { it.isNotBlank() },
                thumbPath = m.optString("thumb").takeIf { it.isNotBlank() },
            )
        }
        return out
    }

    fun parseAlbums(jsonStr: String): List<PlexAlbum> {
        val meta = metadataArray(jsonStr) ?: return emptyList()
        val out = mutableListOf<PlexAlbum>()
        for (i in 0 until meta.length()) {
            val m = meta.optJSONObject(i) ?: continue
            val ratingKey = m.optString("ratingKey").takeIf { it.isNotBlank() } ?: continue
            out += PlexAlbum(
                ratingKey = ratingKey,
                title = m.optString("title", "Unknown Album"),
                artist = m.optString("parentTitle").takeIf { it.isNotBlank() },
                year = if (m.has("year")) m.optInt("year").takeIf { it > 0 } else null,
                thumbPath = m.optString("thumb").ifBlank { m.optString("parentThumb") }.takeIf { it.isNotBlank() },
            )
        }
        return out
    }

    fun parsePlaylists(jsonStr: String): List<PlexPlaylist> {
        val meta = metadataArray(jsonStr) ?: return emptyList()
        val out = mutableListOf<PlexPlaylist>()
        for (i in 0 until meta.length()) {
            val m = meta.optJSONObject(i) ?: continue
            val ratingKey = m.optString("ratingKey").takeIf { it.isNotBlank() } ?: continue
            out += PlexPlaylist(
                ratingKey = ratingKey,
                title = m.optString("title", "Playlist"),
                trackCount = m.optInt("leafCount", 0),
                durationMs = m.optLong("duration", 0L),
                thumbPath = m.optString("composite").ifBlank { m.optString("thumb") }.takeIf { it.isNotBlank() },
            )
        }
        return out
    }

    fun parseTracks(jsonStr: String, machineIdentifier: String): List<PlexTrack> {
        val meta = metadataArray(jsonStr) ?: return emptyList()
        return parseTrackArray(meta, machineIdentifier)
    }

    fun parseSearchHubs(jsonStr: String, machineIdentifier: String): PlexSearchResults {
        val mc = JSONObject(jsonStr).optJSONObject("MediaContainer") ?: return PlexSearchResults()
        val hubs = mc.optJSONArray("Hub") ?: return PlexSearchResults()
        val artists = mutableListOf<PlexArtist>()
        val albums = mutableListOf<PlexAlbum>()
        val tracks = mutableListOf<PlexTrack>()
        for (i in 0 until hubs.length()) {
            val hub = hubs.optJSONObject(i) ?: continue
            val type = hub.optString("type")
            val meta = hub.optJSONArray("Metadata") ?: continue
            when (type) {
                "artist" -> for (j in 0 until meta.length()) {
                    val m = meta.optJSONObject(j) ?: continue
                    val key = m.optString("ratingKey").takeIf { it.isNotBlank() } ?: continue
                    artists += PlexArtist(
                        ratingKey = key,
                        title = m.optString("title", "Unknown Artist"),
                        summary = m.optString("summary").takeIf { it.isNotBlank() },
                        thumbPath = m.optString("thumb").takeIf { it.isNotBlank() },
                    )
                }
                "album" -> for (j in 0 until meta.length()) {
                    val m = meta.optJSONObject(j) ?: continue
                    val key = m.optString("ratingKey").takeIf { it.isNotBlank() } ?: continue
                    albums += PlexAlbum(
                        ratingKey = key,
                        title = m.optString("title", "Unknown Album"),
                        artist = m.optString("parentTitle").takeIf { it.isNotBlank() },
                        year = if (m.has("year")) m.optInt("year").takeIf { it > 0 } else null,
                        thumbPath = m.optString("thumb").takeIf { it.isNotBlank() },
                    )
                }
                "track" -> tracks += parseTrackArray(meta, machineIdentifier)
            }
        }
        return PlexSearchResults(artists = artists, albums = albums, tracks = tracks)
    }

    private fun parseTrackArray(meta: JSONArray, machineIdentifier: String): List<PlexTrack> {
        val out = mutableListOf<PlexTrack>()
        for (i in 0 until meta.length()) {
            val m = meta.optJSONObject(i) ?: continue
            if (m.has("type") && m.optString("type") != "track") continue
            val ratingKey = m.optString("ratingKey").takeIf { it.isNotBlank() } ?: continue
            val title = m.optString("title", "Unknown Track")
            val artist = m.optString("originalTitle").ifBlank { m.optString("grandparentTitle") }.takeIf { it.isNotBlank() }
            val album = m.optString("parentTitle").takeIf { it.isNotBlank() }
            val durationMs = m.optLong("duration", 0L).coerceAtLeast(0L)
            val trackNumber = m.optInt("index", 0)
            val thumb = m.optString("thumb")
                .ifBlank { m.optString("parentThumb") }
                .ifBlank { m.optString("grandparentThumb") }
                .takeIf { it.isNotBlank() }

            var partKey: String? = null
            var bitrate: Int? = null
            val mediaArr = m.optJSONArray("Media")
            if (mediaArr != null && mediaArr.length() > 0) {
                val mediaObj = mediaArr.optJSONObject(0)
                bitrate = mediaObj?.optInt("bitrate", 0)?.takeIf { it > 0 }
                val partArr = mediaObj?.optJSONArray("Part")
                if (partArr != null && partArr.length() > 0) {
                    partKey = partArr.optJSONObject(0)?.optString("key")?.takeIf { it.isNotBlank() }
                }
            }
            out += PlexTrack(
                machineIdentifier = machineIdentifier,
                ratingKey = ratingKey,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                trackNumber = trackNumber,
                partKey = partKey,
                thumbPath = thumb,
                bitrateKbps = bitrate,
            )
        }
        return out
    }

    private fun metadataArray(jsonStr: String): JSONArray? =
        JSONObject(jsonStr).optJSONObject("MediaContainer")?.optJSONArray("Metadata")
}
