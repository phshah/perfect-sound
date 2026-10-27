package app.perfectsound.player.plex

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexClientAndRoutingTest {

    @Test
    fun parseServers_extractsLocalRemoteAndRelayEndpoints() {
        val json = """
            [
              {
                "name": "Home Media Server",
                "product": "Plex Media Server",
                "provides": "server",
                "clientIdentifier": "abc123def456",
                "accessToken": "token-xyz",
                "owned": true,
                "connections": [
                  {
                    "protocol": "https",
                    "address": "192.168.1.42",
                    "port": 32400,
                    "uri": "https://192-168-1-42.abc123def456.plex.direct:32400",
                    "local": true,
                    "relay": false
                  },
                  {
                    "protocol": "https",
                    "address": "98.210.11.22",
                    "port": 21455,
                    "uri": "https://98-210-11-22.abc123def456.plex.direct:21455",
                    "local": false,
                    "relay": false
                  },
                  {
                    "protocol": "https",
                    "address": "139.162.100.5",
                    "port": 8443,
                    "uri": "https://139-162-100-5.abc123def456.plex.direct:8443",
                    "local": false,
                    "relay": true
                  }
                ]
              },
              {
                "name": "Plex Web",
                "product": "Plex Web",
                "provides": "client",
                "clientIdentifier": "web-client-1",
                "accessToken": "ignored",
                "connections": []
              }
            ]
        """.trimIndent()

        val servers = PlexJsonParser.parseResources(json)
        assertEquals(1, servers.size)
        val server = servers.first()
        assertEquals("Home Media Server", server.name)
        assertEquals("abc123def456", server.machineIdentifier)
        assertEquals("token-xyz", server.accessToken)
        assertTrue(server.endpoints.any { it.local && !it.relay && it.uri == "https://192-168-1-42.abc123def456.plex.direct:32400" })
        // Also includes direct IP HTTPS fallback for routers with DNS rebinding protection
        assertTrue(server.endpoints.any { it.local && !it.relay && it.uri == "https://192.168.1.42:32400" })
        assertTrue(server.endpoints.any { !it.local && !it.relay && it.uri == "https://98-210-11-22.abc123def456.plex.direct:21455" })
        assertTrue(server.endpoints.any { !it.local && it.relay && it.uri == "https://139-162-100-5.abc123def456.plex.direct:8443" })
    }

    @Test
    fun parseMusicLibrary_sectionsArtistsAlbumsTracksAndPlaylists() {
        val sectionsJson = """
            {
              "MediaContainer": {
                "Directory": [
                  { "key": "1", "title": "Movies", "type": "movie" },
                  { "key": "2", "title": "Hi-Fi Music", "type": "artist" }
                ]
              }
            }
        """.trimIndent()
        val sections = PlexJsonParser.parseMusicSections(sectionsJson)
        assertEquals(listOf(PlexMusicSection("2", "Hi-Fi Music")), sections)

        val tracksJson = """
            {
              "MediaContainer": {
                "Metadata": [
                  {
                    "ratingKey": "555",
                    "title": "Digital Love",
                    "grandparentTitle": "Daft Punk",
                    "parentTitle": "Discovery",
                    "duration": 301000,
                    "index": 3,
                    "thumb": "/library/metadata/555/thumb/111",
                    "Media": [
                      {
                        "bitrate": 1024,
                        "audioChannels": 2,
                        "audioCodec": "flac",
                        "Part": [
                          {
                            "key": "/library/parts/901/111/Digital%20Love.flac",
                            "container": "flac"
                          }
                        ]
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
        val tracks = PlexJsonParser.parseTracks(tracksJson, "abc123def456")
        assertEquals(1, tracks.size)
        val t = tracks.first()
        assertEquals("555", t.ratingKey)
        assertEquals("Digital Love", t.title)
        assertEquals("Daft Punk", t.artist)
        assertEquals("Discovery", t.album)
        assertEquals(301_000L, t.durationMs)
        assertEquals("/library/parts/901/111/Digital%20Love.flac", t.partKey)
        assertEquals(1024, t.bitrateKbps)
    }

    @Test
    fun selectBestEndpoint_prefersLocalOnSameNetwork_andFallsBackToRemoteOrRelayOnTheGo() = runTest {
        val localEp = PlexEndpoint("https://192-168-1-42.srv1.plex.direct:32400", local = true, relay = false)
        val remoteEp = PlexEndpoint("https://98-210-11-22.srv1.plex.direct:21455", local = false, relay = false)
        val relayEp = PlexEndpoint("https://139-162-100-5.srv1.plex.direct:8443", local = false, relay = true)
        val endpoints = listOf(localEp, remoteEp, relayEp)

        // Case 1: Same network (Local LAN is reachable) -> picks Local even if Relay is also reachable
        val pickedLan = PlexEndpointSelector.selectBest(endpoints) { ep ->
            ep.uri == localEp.uri || ep.uri == relayEp.uri
        }
        assertEquals(localEp, pickedLan)
        assertEquals(ConnectionType.LOCAL, pickedLan?.connectionType)

        // Case 2: On the go (Local LAN fails, Remote Direct is reachable) -> picks Remote Direct
        val pickedRemote = PlexEndpointSelector.selectBest(endpoints) { ep ->
            ep.uri == remoteEp.uri || ep.uri == relayEp.uri
        }
        assertEquals(remoteEp, pickedRemote)
        assertEquals(ConnectionType.REMOTE, pickedRemote?.connectionType)

        // Case 3: On the go behind CGNAT/firewall (only Relay reachable) -> picks Relay
        val pickedRelay = PlexEndpointSelector.selectBest(endpoints) { ep ->
            ep.uri == relayEp.uri
        }
        assertEquals(relayEp, pickedRelay)
        assertEquals(ConnectionType.RELAY, pickedRelay?.connectionType)

        // Case 4: Offline (none reachable) -> returns null
        val pickedNone = PlexEndpointSelector.selectBest(endpoints) { false }
        assertNull(pickedNone)
    }

    @Test
    fun plexUri_roundTripAndStreamUrlResolution_handlesDirectAndTranscode() {
        val track = PlexTrack(
            machineIdentifier = "srv1",
            ratingKey = "555",
            title = "Digital Love",
            artist = "Daft Punk",
            album = "Discovery",
            durationMs = 301_000L,
            trackNumber = 3,
            partKey = "/library/parts/901/111/Digital%20Love.flac",
            thumbPath = "/library/metadata/555/thumb/111",
            bitrateKbps = 1024,
        )

        val logicalUri = PlexUri.buildTrackUri(track)
        assertTrue(logicalUri.startsWith("plex://track/srv1/555"))
        val parsed = PlexUri.parseTrackUri(logicalUri)
        assertNotNull(parsed)
        assertEquals("srv1", parsed!!.machineIdentifier)
        assertEquals("555", parsed.ratingKey)
        assertEquals("/library/parts/901/111/Digital%20Love.flac", parsed.partKey)
        assertEquals(1024, parsed.bitrateKbps)

        // Direct Stream when on LOCAL network (even if remote quality is 192 kbps)
        val lanUrl = PlexUri.buildPlaybackHttpUrl(
            baseUrl = "https://192-168-1-42.srv1.plex.direct:32400",
            accessToken = "tok123",
            clientId = "client-uuid",
            ref = parsed,
            connectionType = ConnectionType.LOCAL,
            remoteQuality = StreamQuality.Kbps192,
        )
        assertEquals(
            "https://192-168-1-42.srv1.plex.direct:32400/library/parts/901/111/Digital%20Love.flac?X-Plex-Token=tok123",
            lanUrl,
        )

        // Transcode Stream when on REMOTE network and remoteQuality is 192 kbps
        val remoteTranscodeUrl = PlexUri.buildPlaybackHttpUrl(
            baseUrl = "https://98-210-11-22.srv1.plex.direct:21455",
            accessToken = "tok123",
            clientId = "client-uuid",
            ref = parsed,
            connectionType = ConnectionType.REMOTE,
            remoteQuality = StreamQuality.Kbps192,
        )
        assertTrue(remoteTranscodeUrl.startsWith("https://98-210-11-22.srv1.plex.direct:21455/music/:/transcode/universal/start.mp3?"))
        assertTrue(remoteTranscodeUrl.contains("maxAudioBitrate=192"))
        assertTrue(remoteTranscodeUrl.contains("X-Plex-Token=tok123"))

        // Direct Stream when on REMOTE network and remoteQuality is Original
        val remoteOriginalUrl = PlexUri.buildPlaybackHttpUrl(
            baseUrl = "https://98-210-11-22.srv1.plex.direct:21455",
            accessToken = "tok123",
            clientId = "client-uuid",
            ref = parsed,
            connectionType = ConnectionType.REMOTE,
            remoteQuality = StreamQuality.Original,
        )
        assertEquals(
            "https://98-210-11-22.srv1.plex.direct:21455/library/parts/901/111/Digital%20Love.flac?X-Plex-Token=tok123",
            remoteOriginalUrl,
        )

        // Artwork URI round-trip and transcode URL
        val artUri = PlexUri.buildArtUri("srv1", "/library/metadata/555/thumb/111")
        val parsedArt = PlexUri.parseArtUri(artUri)
        assertNotNull(parsedArt)
        assertEquals("srv1", parsedArt!!.machineIdentifier)
        assertEquals("/library/metadata/555/thumb/111", parsedArt.thumbPath)
        val artHttp = PlexUri.buildArtworkHttpUrl("https://192.168.1.42:32400", "tok123", parsedArt.thumbPath, 640)
        assertTrue(artHttp.startsWith("https://192.168.1.42:32400/photo/:/transcode?width=640&height=640"))

        // StreamQuality cycling
        assertEquals(StreamQuality.Kbps320, StreamQuality.Original.next())
        assertEquals(StreamQuality.Kbps192, StreamQuality.Kbps320.next())
        assertEquals(StreamQuality.Kbps128, StreamQuality.Kbps192.next())
        assertEquals(StreamQuality.Original, StreamQuality.Kbps128.next())
    }

    @Test
    fun parseSearchHubsAndPlaylists_extractsArtistsAlbumsTracksAndAudioPlaylists() {
        val searchJson = """
            {
              "MediaContainer": {
                "Hub": [
                  {
                    "type": "artist",
                    "Metadata": [
                      { "ratingKey": "10", "title": "Boards of Canada" }
                    ]
                  },
                  {
                    "type": "album",
                    "Metadata": [
                      { "ratingKey": "20", "title": "Music Has the Right to Children", "parentTitle": "Boards of Canada", "year": 1998 }
                    ]
                  },
                  {
                    "type": "track",
                    "Metadata": [
                      { "ratingKey": "30", "type": "track", "title": "Roygbiv", "grandparentTitle": "Boards of Canada", "parentTitle": "Music Has the Right to Children", "duration": 149000 }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
        val results = PlexJsonParser.parseSearchHubs(searchJson, "srv1")
        assertEquals(1, results.artists.size)
        assertEquals("Boards of Canada", results.artists.first().title)
        assertEquals(1, results.albums.size)
        assertEquals(1998, results.albums.first().year)
        assertEquals(1, results.tracks.size)
        assertEquals("Roygbiv", results.tracks.first().title)

        val playlistsJson = """
            {
              "MediaContainer": {
                "Metadata": [
                  { "ratingKey": "99", "title": "Road Trip", "leafCount": 42, "duration": 9800000 }
                ]
              }
            }
        """.trimIndent()
        val playlists = PlexJsonParser.parsePlaylists(playlistsJson)
        assertEquals(1, playlists.size)
        assertEquals("Road Trip", playlists.first().title)
        assertEquals(42, playlists.first().trackCount)
    }

    @Test
    fun buildBrowserAuthUrl_handlesStrongOAuthPinAndShortLinkPin() {
        val client = PlexApiClient("client-uuid-123")
        val strongPin = PlexJsonParser.parsePin("""{"id":564964751,"code":"8lzjqnq8lye02n52jq3fqxf8e"}""")
        assertEquals(564964751L, strongPin.id)
        assertEquals("8lzjqnq8lye02n52jq3fqxf8e", strongPin.code)

        val oauthUrl = client.buildBrowserAuthUrl(strongPin)
        assertTrue(oauthUrl.startsWith("https://app.plex.tv/auth#?clientID=client-uuid-123&code=8lzjqnq8lye02n52jq3fqxf8e"))
        assertTrue(oauthUrl.contains("context%5Bdevice%5D%5Bproduct%5D=Perfect%20Sound"))
        assertTrue(oauthUrl.contains("context%5Bdevice%5D%5Bplatform%5D=Android"))
        assertTrue(!oauthUrl.contains("+"))

        val shortPin = PlexPin(id = 42L, code = "ABCD")
        assertEquals("https://plex.tv/link/?pin=ABCD", client.buildBrowserAuthUrl(shortPin))

        assertNull(PlexJsonParser.parsePinAuthToken("""{"id":564964751,"code":"8lzjqnq8lye02n52jq3fqxf8e","authToken":null}"""))
        assertEquals("my-plex-token", PlexJsonParser.parsePinAuthToken("""{"id":564964751,"authToken":"my-plex-token"}"""))
    }
}
