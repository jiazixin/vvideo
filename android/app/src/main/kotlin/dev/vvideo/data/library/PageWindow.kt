package dev.vvideo.data.library

import dev.vvideo.model.MediaPage

// At most three server pages stay resident; stable keys preserve visible media.
class PageWindow {
 private val pages=ArrayDeque<MediaPage>()
 val items get()=pages.flatMap{it.items}
 val next get()=pages.lastOrNull()?.next.orEmpty()
 val previous get()=pages.firstOrNull()?.previous.orEmpty()
 fun reset(page:MediaPage){pages.clear();pages.add(page)}
 fun append(page:MediaPage){pages.addLast(page);while(pages.size>3)pages.removeFirst()}
 fun prepend(page:MediaPage){pages.addFirst(page);while(pages.size>3)pages.removeLast()}
 val retainedPages get()=pages.size
}
