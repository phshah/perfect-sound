package app.perfectsound.player.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import app.perfectsound.player.plex.PlexConnectionManager
import app.perfectsound.player.plex.PlexTlsHelper
import app.perfectsound.player.plex.PlexUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

private val plexArtCache = LruCache<String, Bitmap>(32)

/**
 * Reads the cover picture embedded in a local audio file's tags, or fetches and caches the album art
 * from a Plex Media Server when [uri] or [artworkUri] is a `plex://` URI.
 */
suspend fun loadEmbeddedArt(
    context: Context,
    uri: Uri,
    maxPx: Int = 640,
    artworkUri: Uri? = null,
): Bitmap? = withContext(Dispatchers.IO) {
    val plexTarget = when {
        PlexUri.isPlexUri(artworkUri) -> artworkUri!!
        PlexUri.isPlexUri(uri) -> uri
        else -> null
    }
    if (plexTarget != null) {
        val cacheKey = "$plexTarget:$maxPx"
        plexArtCache.get(cacheKey)?.let { return@withContext it }
        val httpUrl = PlexConnectionManager.getInstance(context).resolveArtworkHttpUrl(plexTarget, maxPx)
            ?: return@withContext null
        val bytes = runCatching {
            val conn = (URL(httpUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 6_000
                if (this is HttpsURLConnection) hostnameVerifier = PlexTlsHelper.verifier
            }
            try {
                if (conn.responseCode in 200..299) conn.inputStream.use { it.readBytes() } else null
            } finally {
                conn.disconnect()
            }
        }.getOrNull() ?: return@withContext null
        return@withContext decodeScaled(bytes, maxPx)?.also { plexArtCache.put(cacheKey, it) }
    }

    val bytes = MediaMetadataRetriever().run {
        try {
            setDataSource(context, uri)
            embeddedPicture
        } catch (_: RuntimeException) {
            null // unreadable or missing file
        } finally {
            release()
        }
    } ?: return@withContext null
    decodeScaled(bytes, maxPx)
}

private fun decodeScaled(bytes: ByteArray, maxPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
