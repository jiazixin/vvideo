package dev.vvideo.data.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private const val CONNECT_TIMEOUT_SECONDS = 5L
private const val READ_TIMEOUT_SECONDS = 10L
internal const val API_TIMEOUT_SECONDS = 15L

/**
 * The app's one OkHttp client. The API, images and media use it or derive from it
 * with `newBuilder()`, sharing its connection pool and dispatcher. Redirects are
 * not followed, so the Authorization header that [authorization] adds never
 * reaches another server.
 */
internal fun baseHttpClient(authorization: Interceptor): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .followRedirects(false)
    .followSslRedirects(false)
    .addInterceptor(authorization)
    .build()
