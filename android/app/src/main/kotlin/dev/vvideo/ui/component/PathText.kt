package dev.vvideo.ui.component

import dev.vvideo.model.Directory

/** Separates folder names in a displayed path. */
private const val PATH_SEPARATOR = " › "

/** Ancestors shown under a folder's name. */
private const val PATH_LABEL_ANCESTORS = 2

/** Every folder name of [path], outermost first. */
fun pathText(path: List<Directory>): String = path.joinToString(PATH_SEPARATOR) { it.name }

/** The nearest ancestors of the last folder in [path], shown under its name. */
fun pathLabel(path: List<Directory>): String =
    pathText(path.dropLast(1).takeLast(PATH_LABEL_ANCESTORS))
