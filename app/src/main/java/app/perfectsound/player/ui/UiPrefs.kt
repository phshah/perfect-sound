package app.perfectsound.player.ui

import android.content.Context
import app.perfectsound.player.audio.SpectrumAnalyzer
import app.perfectsound.player.remote.RemoteSessions
import org.json.JSONArray
import org.json.JSONObject

/** Remembers window-level choices between launches: source, visible panels, visualizer mode and EQ sliders. */
class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    var source: Source
        get() = when (val name = prefs.getString("source", null)) {
            "Plex" -> Source.Plex
            else -> name?.let { n -> RemoteSessions.App.entries.firstOrNull { it.name == n } }
                ?.let { Source.Remote(it) } ?: Source.Local
        }
        set(value) = prefs.edit().putString("source", when (value) {
            Source.Local -> "Local"
            Source.Plex -> "Plex"
            is Source.Remote -> value.app.name
        }).apply()

    var equalizerVisible: Boolean
        get() = prefs.getBoolean("equalizerVisible", true)
        set(value) = prefs.edit().putBoolean("equalizerVisible", value).apply()

    var playlistVisible: Boolean
        get() = prefs.getBoolean("playlistVisible", true)
        set(value) = prefs.edit().putBoolean("playlistVisible", value).apply()

    var visMode: VisMode
        get() = prefs.getString("visMode", null)?.let { name -> VisMode.entries.firstOrNull { it.name == name } } ?: VisMode.Bands
        set(value) = prefs.edit().putString("visMode", value.name).apply()

    var visTheme: VisTheme
        get() = prefs.getString("visTheme", null)?.let { name -> VisTheme.entries.firstOrNull { it.name == name } } ?: VisTheme.Green
        set(value) = prefs.edit().putString("visTheme", value.name).apply()

    var eq: EqSettings
        get() = runCatching {
            val o = JSONObject(prefs.getString("eq", null) ?: return EqSettings())
            val bands = o.getJSONArray("bands")
            EqSettings(
                enabled = o.optBoolean("enabled"),
                preamp = o.optDouble("preamp", 0.0).toFloat(),
                bands = List(SpectrumAnalyzer.BAND_CENTERS_HZ.size) { i -> bands.optDouble(i, 0.0).toFloat() },
            )
        }.getOrDefault(EqSettings())
        set(value) = prefs.edit().putString("eq", JSONObject()
            .put("enabled", value.enabled)
            .put("preamp", value.preamp.toDouble())
            .put("bands", JSONArray(value.bands.map { it.toDouble() }))
            .toString()).apply()
}
