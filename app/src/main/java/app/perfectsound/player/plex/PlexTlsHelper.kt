package app.perfectsound.player.plex

import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession

/**
 * Allows HTTPS connections to a Plex Media Server by literal IPv4 address (e.g. when a home router's
 * DNS rebinding protection blocks `192-168-1-x.<id>.plex.direct`) as long as the server's CA-trusted
 * certificate carries a valid `*.plex.direct` Subject Alternative Name.
 */
object PlexTlsHelper {
    private val IPV4_REGEX = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
    private val defaultVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
    @Volatile
    private var installed = false

    val verifier = HostnameVerifier { hostname, session ->
        if (defaultVerifier.verify(hostname, session)) return@HostnameVerifier true
        if (!IPV4_REGEX.matches(hostname)) return@HostnameVerifier false
        hasValidPlexDirectSan(session)
    }

    fun installIfNeeded() {
        if (installed) return
        synchronized(this) {
            if (!installed) {
                HttpsURLConnection.setDefaultHostnameVerifier(verifier)
                installed = true
            }
        }
    }

    private fun hasValidPlexDirectSan(session: SSLSession): Boolean = runCatching {
        val cert = session.peerCertificates.firstOrNull() as? X509Certificate ?: return false
        val sans = cert.subjectAlternativeNames ?: return false
        sans.any { entry ->
            val type = entry.getOrNull(0) as? Int
            val name = (entry.getOrNull(1) as? String)?.lowercase() ?: return@any false
            type == 2 && (name.endsWith(".plex.direct") || name == "plex.direct")
        }
    }.getOrDefault(false)
}
