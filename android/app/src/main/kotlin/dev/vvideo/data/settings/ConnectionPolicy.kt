package dev.vvideo.data.settings

import java.net.URI

// Accepts an http or https server root for any host and reduces it to
// scheme://host[:port], so the same server always maps to one connection id.
object ConnectionPolicy {
    fun canonical(url: String): String {
        val uri = URI(url.trim().trimEnd('/'))
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        require(!host.isNullOrBlank())
        require(uri.userInfo == null && uri.rawQuery == null && uri.fragment == null)
        require(uri.path.isNullOrEmpty() || uri.path == "/")
        require(uri.port == -1 || uri.port in 1..65535)
        require(scheme == "https" || scheme == "http")
        return URI(scheme, null, host, uri.port, "", null, null).toString()
    }
}
