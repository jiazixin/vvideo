package dev.vvideo.data.cache

/** Cache operations available to the settings state holder. */
internal interface CacheSettings {
    suspend fun configure(budget: Long, clear: Boolean = false)
    suspend fun usage(): CacheUsage
}
