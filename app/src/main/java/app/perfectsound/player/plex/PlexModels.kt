package app.perfectsound.player.plex

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

enum class ConnectionType(val badge: String) {
    LOCAL("LOCAL"),
    REMOTE("REMOTE"),
    RELAY("RELAY"),
}

/** Remote/cellular streaming quality. LOCAL connections always stream original files directly. */
enum class StreamQuality(val label: String, val kbps: Int?) {
    Original("ORIG", null),
    Kbps320("320K", 320),
    Kbps192("192K", 192),
    Kbps128("128K", 128);

    fun next(): StreamQuality = entries[(ordinal + 1) % entries.size]
}

data class PlexEndpoint(
    val uri: String,
    val local: Boolean,
    val relay: Boolean,
    val protocol: String = "https",
) {
    val connectionType: ConnectionType
        get() = when {
            relay -> ConnectionType.RELAY
            local -> ConnectionType.LOCAL
            else -> ConnectionType.REMOTE
        }
}

data class PlexServer(
    val name: String,
    val machineIdentifier: String,
    val accessToken: String,
    val owned: Boolean = true,
    val endpoints: List<PlexEndpoint>,
)

data class PlexPin(
    val id: Long,
    val code: String,
)

data class PlexMusicSection(
    val key: String,
    val title: String,
)

data class PlexArtist(
    val ratingKey: String,
    val title: String,
    val summary: String? = null,
    val thumbPath: String? = null,
)

data class PlexAlbum(
    val ratingKey: String,
    val title: String,
    val artist: String? = null,
    val year: Int? = null,
    val thumbPath: String? = null,
)

data class PlexPlaylist(
    val ratingKey: String,
    val title: String,
    val trackCount: Int = 0,
    val durationMs: Long = 0,
    val thumbPath: String? = null,
)

data class PlexTrack(
    val machineIdentifier: String,
    val ratingKey: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val trackNumber: Int = 0,
    val partKey: String?,
    val thumbPath: String? = null,
    val bitrateKbps: Int? = null,
) {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun toMediaItem(): MediaItem {
        val trackUriStr = PlexUri.buildTrackUri(this)
        val trackUri = Uri.parse(trackUriStr)
        val artUri = thumbPath?.takeIf { it.isNotBlank() }?.let {
            Uri.parse(PlexUri.buildArtUri(machineIdentifier, it))
        }
        return MediaItem.Builder()
            .setMediaId("plex:$machineIdentifier:$ratingKey:${UUID.randomUUID()}")
            .setUri(trackUri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(trackUri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setDurationMs(durationMs.takeIf { it > 0 })
                    .setArtworkUri(artUri)
                    .build()
            )
            .build()
    }
}

data class PlexSearchResults(
    val artists: List<PlexArtist> = emptyList(),
    val albums: List<PlexAlbum> = emptyList(),
    val tracks: List<PlexTrack> = emptyList(),
)

/**
 * Logical `plex://` URIs stored in the playlist queue so tracks seamlessly switch between
 * local LAN and remote/relay endpoints when the user moves between networks.
 */
object PlexUri {
    const val SCHEME = "plex"
    private const val HOST_TRACK = "track"
    private const val HOST_ART = "art"

    data class TrackRef(
        val machineIdentifier: String,
        val ratingKey: String,
        val partKey: String?,
        val thumbPath: String?,
        val bitrateKbps: Int?,
    )

    data class ArtRef(
        val machineIdentifier: String,
        val thumbPath: String,
    )

    fun isPlexUri(uri: Uri?): Boolean = uri?.scheme == SCHEME

    fun buildTrackUri(track: PlexTrack): String {
        val params = buildList {
            track.partKey?.takeIf { it.isNotBlank() }?.let { add("part=${encode(it)}") }
            track.thumbPath?.takeIf { it.isNotBlank() }?.let { add("thumb=${encode(it)}") }
            track.bitrateKbps?.takeIf { it > 0 }?.let { add("bitrate=$it") }
        }.joinToString("&")
        val query = if (params.isEmpty()) "" else "?$params"
        return "$SCHEME://$HOST_TRACK/${encode(track.machineIdentifier)}/${encode(track.ratingKey)}$query"
    }

    fun parseTrackUri(uriString: String): TrackRef? = runCatching {
        val u = URI(uriString)
        if (u.scheme != SCHEME || u.host != HOST_TRACK) return null
        val segments = u.rawPath.trim('/').split('/')
        if (segments.size < 2) return null
        val machineId = decode(segments[0])
        val ratingKey = decode(segments[1])
        val queryMap = parseQuery(u.rawQuery)
        TrackRef(
            machineIdentifier = machineId,
            ratingKey = ratingKey,
            partKey = queryMap["part"],
            thumbPath = queryMap["thumb"],
            bitrateKbps = queryMap["bitrate"]?.toIntOrNull(),
        )
    }.getOrNull()

    fun buildArtUri(machineIdentifier: String, thumbPath: String): String =
        "$SCHEME://$HOST_ART/${encode(machineIdentifier)}?path=${encode(thumbPath)}"

    fun parseArtUri(uriString: String): ArtRef? = runCatching {
        val u = URI(uriString)
        if (u.scheme != SCHEME || u.host != HOST_ART) return null
        val machineId = decode(u.rawPath.trim('/'))
        val path = parseQuery(u.rawQuery)["path"] ?: return null
        if (machineId.isBlank() || path.isBlank()) return null
        ArtRef(machineId, path)
    }.getOrNull()

    /**
     * Resolves a logical [TrackRef] to an HTTP(S) URL for the currently reachable [baseUrl].
     * On local LAN or when [remoteQuality] is [StreamQuality.Original], streams the original file
     * directly; on remote/relay with a bitrate cap, uses Plex's Universal Audio Transcoder.
     */
    fun buildPlaybackHttpUrl(
        baseUrl: String,
        accessToken: String,
        clientId: String,
        ref: TrackRef,
        connectionType: ConnectionType,
        remoteQuality: StreamQuality,
    ): String {
        val cleanBase = baseUrl.trimEnd('/')
        val useDirectStream = (connectionType == ConnectionType.LOCAL || remoteQuality == StreamQuality.Original) &&
            !ref.partKey.isNullOrBlank()

        if (useDirectStream) {
            val part = if (ref.partKey!!.startsWith("/")) ref.partKey else "/${ref.partKey}"
            val sep = if ('?' in part) "&" else "?"
            return "$cleanBase$part${sep}X-Plex-Token=${encode(accessToken)}"
        }

        val maxKbps = remoteQuality.kbps ?: 320
        val metaPath = "/library/metadata/${ref.ratingKey}"
        return "$cleanBase/music/:/transcode/universal/start.mp3" +
            "?hasMDE=1" +
            "&path=${encode(metaPath)}" +
            "&mediaIndex=0" +
            "&partIndex=0" +
            "&protocol=http" +
            "&directPlay=0" +
            "&directStream=0" +
            "&maxAudioBitrate=$maxKbps" +
            "&audioBoost=100" +
            "&session=${encode("$clientId-${ref.ratingKey}")}" +
            "&X-Plex-Platform=Android" +
            "&X-Plex-Client-Identifier=${encode(clientId)}" +
            "&X-Plex-Product=${encode("Perfect Sound")}" +
            "&X-Plex-Token=${encode(accessToken)}"
    }

    fun buildArtworkHttpUrl(
        baseUrl: String,
        accessToken: String,
        thumbPath: String,
        maxPx: Int = 640,
    ): String {
        val cleanBase = baseUrl.trimEnd('/')
        val cleanThumb = if (thumbPath.startsWith("/")) thumbPath else "/$thumbPath"
        return "$cleanBase/photo/:/transcode" +
            "?width=$maxPx&height=$maxPx&minSize=1&upscale=1" +
            "&url=${encode(cleanThumb)}" +
            "&X-Plex-Token=${encode(accessToken)}"
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else decode(pair.substring(0, idx)) to decode(pair.substring(idx + 1))
        }.toMap()
    }

    private fun encode(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name())
    private fun decode(s: String): String = URLDecoder.decode(s, Charsets.UTF_8.name())
}
