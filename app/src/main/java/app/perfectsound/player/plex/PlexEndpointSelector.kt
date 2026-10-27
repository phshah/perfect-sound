package app.perfectsound.player.plex

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Selects the best reachable [PlexEndpoint] across local LAN, remote direct, and Plex Relay.
 *
 * Priority order:
 * 1. Local LAN (`local = true, relay = false`) — fastest, zero WAN usage on the same network
 * 2. Remote Direct (`local = false, relay = false`) — direct port-forwarded connection on the go
 * 3. Plex Relay (`relay = true`) — fallback relay when behind strict NAT/CGNAT on the go
 */
object PlexEndpointSelector {

    suspend fun selectBest(
        endpoints: List<PlexEndpoint>,
        probe: suspend (PlexEndpoint) -> Boolean,
    ): PlexEndpoint? = coroutineScope {
        if (endpoints.isEmpty()) return@coroutineScope null
        // Order candidates by tier (LOCAL -> REMOTE -> RELAY), preferring HTTPS within each tier.
        val ordered = endpoints.sortedWith(
            compareBy<PlexEndpoint> {
                when (it.connectionType) {
                    ConnectionType.LOCAL -> 0
                    ConnectionType.REMOTE -> 1
                    ConnectionType.RELAY -> 2
                }
            }.thenBy { if (it.protocol == "https") 0 else 1 }
        )

        val deferredResults = ordered.map { ep ->
            ep to async { runCatching { probe(ep) }.getOrDefault(false) }
        }

        // Return the highest-priority endpoint that succeeds.
        for ((ep, deferred) in deferredResults) {
            if (deferred.await()) {
                deferredResults.forEach { (_, job) -> job.cancel() }
                return@coroutineScope ep
            }
        }
        null
    }
}
