package app.perfectsound.player.library

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Builds playlist items from files and folders the user picked with the system file picker. */
class TrackImporter(private val context: Context) {

    suspend fun fromDocuments(uris: List<Uri>): List<MediaItem> = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri ->
            keepAccess(uri)
            runCatching { toMediaItem(uri) }.getOrNull()
        }
    }

    /** Dropped items may be anything; keep the audio files. Access lasts only while the app runs. */
    suspend fun fromDropped(uris: List<Uri>): List<MediaItem> = withContext(Dispatchers.IO) {
        uris.filter { uri ->
            val type = context.contentResolver.getType(uri).orEmpty()
            type.startsWith("audio/") || type == "application/ogg" ||
                displayName(uri)?.substringAfterLast('.', "")?.lowercase() in AUDIO_EXTENSIONS
        }.mapNotNull { runCatching { toMediaItem(it) }.getOrNull() }
    }

    /** All audio files in a picked folder and its subfolders, sorted by path. */
    suspend fun fromTree(treeUri: Uri): List<MediaItem> = withContext(Dispatchers.IO) {
        keepAccess(treeUri)
        val files = mutableListOf<Pair<String, Uri>>()
        collectAudio(treeUri, DocumentsContract.getTreeDocumentId(treeUri), "", files)
        files.sortedBy { it.first.lowercase() }.mapNotNull { runCatching { toMediaItem(it.second) }.getOrNull() }
    }

    private fun collectAudio(tree: Uri, docId: String, path: String, out: MutableList<Pair<String, Uri>>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        context.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                when {
                    mime == DocumentsContract.Document.MIME_TYPE_DIR -> collectAudio(tree, id, "$path/$name", out)
                    mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS ->
                        out += "$path/$name" to DocumentsContract.buildDocumentUriUsingTree(tree, id)
                }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun toMediaItem(uri: Uri): MediaItem {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var durationMs: Long? = null
        MediaMetadataRetriever().apply {
            try {
                setDataSource(context, uri)
                title = extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                artist = extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                album = extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                durationMs = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } catch (_: RuntimeException) {
                // unreadable tags: fall back to the file name below
            } finally {
                release()
            }
        }
        val fileName = displayName(uri)
        return MediaItem.Builder()
            .setMediaId(UUID.randomUUID().toString())
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(title?.takeIf { it.isNotBlank() } ?: fileName?.substringBeforeLast('.') ?: uri.lastPathSegment)
                .setArtist(artist?.takeIf { it.isNotBlank() })
                .setAlbumTitle(album)
                .setDurationMs(durationMs)
                .build())
            .build()
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** Keeps read access to picked files across restarts, so saved playlists still play. */
    private fun keepAccess(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "wav", "wma", "mka", "alac", "aiff", "aif")
    }
}
