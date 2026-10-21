package dev.vvideo.core.media.datasource

/** Request headers that authorize a media request with the connection's [token]. */
internal fun authorizationHeaders(token: String): Map<String, String> =
    mapOf("Authorization" to "Bearer $token")
