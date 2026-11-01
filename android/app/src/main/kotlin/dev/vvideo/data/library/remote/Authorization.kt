package dev.vvideo.data.library.remote

import dev.vvideo.data.settings.Connection
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

private const val AUTHORIZATION_HEADER = "Authorization"

/**
 * Adds the bearer token to requests for the connection's server. A request may
 * carry its own [Connection] as a tag, as one being tried before it is saved
 * does; other requests use the [saved] connection. Requests to any other origin
 * go out without the header.
 */
internal class Authorization(private val saved: () -> Connection?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val connection = request.tag(Connection::class.java) ?: saved()
        val server = connection?.address?.toHttpUrlOrNull()
        if (connection == null || !connection.configured || server == null || !sameOrigin(request.url, server)) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder().header(AUTHORIZATION_HEADER, "Bearer ${connection.token}").build())
    }

    private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
}
