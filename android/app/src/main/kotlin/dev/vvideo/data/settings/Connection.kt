package dev.vvideo.data.settings

import java.security.MessageDigest

/** The media cache budgets offered in Settings, in decimal bytes. */
val CACHE_BUDGETS = listOf(1_000_000_000L, 5_000_000_000L, 10_000_000_000L)
const val DEFAULT_CACHE_BUDGET = 5_000_000_000L

data class Connection(
    val address: String = "",
    val token: String = "",
    val budget: Long = DEFAULT_CACHE_BUDGET,
    val lastDirectory: Long = 0L
) {
    val configured: Boolean get() = address.isNotBlank() && token.isNotBlank()

    // Connection is immutable. Compute its cache/bookmark namespace once rather
    // than constructing a digest and 32 formatters during UI recomposition.
    val id: String = MessageDigest.getInstance("SHA-256").digest(address.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
