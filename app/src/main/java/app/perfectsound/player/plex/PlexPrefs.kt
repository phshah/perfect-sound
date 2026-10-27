package app.perfectsound.player.plex

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persists Plex credentials, discovered server endpoints, and streaming quality preferences. */
class PlexPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("plex", Context.MODE_PRIVATE)

    val clientIdentifier: String
        get() = prefs.getString("clientId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("clientId", it).apply()
        }

    var plexTvToken: String?
        get() = prefs.getString("plexTvToken", null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString("plexTvToken", value).apply()

    var selectedServerId: String?
        get() = prefs.getString("selectedServerId", null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString("selectedServerId", value).apply()

    var selectedSectionKey: String?
        get() = prefs.getString("selectedSectionKey", null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString("selectedSectionKey", value).apply()

    var remoteQuality: StreamQuality
        get() = prefs.getString("remoteQuality", null)
            ?.let { name -> StreamQuality.entries.firstOrNull { it.name == name } }
            ?: StreamQuality.Original
        set(value) = prefs.edit().putString("remoteQuality", value.name).apply()

    var servers: List<PlexServer>
        get() = runCatching {
            val raw = prefs.getString("serversJson", null) ?: return emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val epArr = o.optJSONArray("endpoints") ?: JSONArray()
                val endpoints = (0 until epArr.length()).mapNotNull { j ->
                    val e = epArr.optJSONObject(j) ?: return@mapNotNull null
                    PlexEndpoint(
                        uri = e.getString("uri"),
                        local = e.optBoolean("local", false),
                        relay = e.optBoolean("relay", false),
                        protocol = e.optString("protocol", "https"),
                    )
                }
                PlexServer(
                    name = o.getString("name"),
                    machineIdentifier = o.getString("machineIdentifier"),
                    accessToken = o.getString("accessToken"),
                    owned = o.optBoolean("owned", true),
                    endpoints = endpoints,
                )
            }
        }.getOrDefault(emptyList())
        set(value) {
            val arr = JSONArray()
            value.forEach { s ->
                val epArr = JSONArray()
                s.endpoints.forEach { e ->
                    epArr.put(
                        JSONObject()
                            .put("uri", e.uri)
                            .put("local", e.local)
                            .put("relay", e.relay)
                            .put("protocol", e.protocol)
                    )
                }
                arr.put(
                    JSONObject()
                        .put("name", s.name)
                        .put("machineIdentifier", s.machineIdentifier)
                        .put("accessToken", s.accessToken)
                        .put("owned", s.owned)
                        .put("endpoints", epArr)
                )
            }
            prefs.edit().putString("serversJson", arr.toString()).apply()
        }

    fun clearAll() {
        val id = clientIdentifier
        prefs.edit().clear().putString("clientId", id).apply()
    }
}
