package dev.vvideo.model

/** The app theme the user picked; [key] is how it is stored. */
enum class ThemeMode(val key: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        fun fromKey(value: String?): ThemeMode = entries.firstOrNull { it.key == value } ?: System
    }
}
