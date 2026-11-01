package dev.vvideo.data.settings

private const val MAX_TOKEN_LENGTH = 4096

/** The bearer token must fit an HTTP header without controls or whitespace. */
internal object TokenPolicy {
    fun valid(token: String): Boolean = token.length in 1..MAX_TOKEN_LENGTH && token.all { it in '!'..'~' }
}
