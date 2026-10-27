package app.perfectsound.player.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves the play queue, position and player modes so Perfect Sound resumes where it left off. */
class QueueStore(context: Context) {

    data class Saved(
        val items: List<MediaItem>,
        val index: Int,
        val positionMs: Long,
        val volume: Float,
        val shuffle: Boolean,
        val repeatMode: Int,
    )

    private val file = File(context.filesDir, "queue.json")

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun save(player: Player) {
        val items = JSONArray()
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri ?: continue
            val m = item.mediaMetadata
            items.put(JSONObject()
                .put("id", item.mediaId)
                .put("uri", uri.toString())
                .putOpt("title", m.title?.toString())
                .putOpt("artist", m.artist?.toString())
                .putOpt("album", m.albumTitle?.toString())
                .putOpt("durationMs", m.durationMs)
                .putOpt("artworkUri", m.artworkUri?.toString()))
        }
        val json = JSONObject()
            .put("items", items)
            .put("index", player.currentMediaItemIndex)
            .put("positionMs", player.currentPosition)
            .put("volume", player.volume.toDouble())
            .put("shuffle", player.shuffleModeEnabled)
            .put("repeatMode", player.repeatMode)
        // Write-then-rename so a crash mid-write never leaves a corrupt queue.
        val tmp = File(file.parentFile, "queue.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun load(): Saved? = runCatching {
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        val array = json.getJSONArray("items")
        val items = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val uri = Uri.parse(o.getString("uri"))
            val artworkUri = o.optString("artworkUri").ifEmpty { null }?.let(Uri::parse)
            MediaItem.Builder()
                .setMediaId(o.getString("id"))
                .setUri(uri)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(o.optString("title").ifEmpty { null })
                    .setArtist(o.optString("artist").ifEmpty { null })
                    .setAlbumTitle(o.optString("album").ifEmpty { null })
                    .setDurationMs(if (o.has("durationMs")) o.getLong("durationMs") else null)
                    .setArtworkUri(artworkUri)
                    .build())
                .build()
        }
        Saved(
            items = items,
            index = json.optInt("index", 0).coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            positionMs = json.optLong("positionMs", 0),
            volume = json.optDouble("volume", 1.0).toFloat(),
            shuffle = json.optBoolean("shuffle", false),
            repeatMode = json.optInt("repeatMode", Player.REPEAT_MODE_OFF),
        )
    }.getOrNull()
}
