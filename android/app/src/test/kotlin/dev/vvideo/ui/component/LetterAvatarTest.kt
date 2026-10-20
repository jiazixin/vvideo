package dev.vvideo.ui.component

import org.junit.Assert.*
import org.junit.Test

class LetterAvatarTest {
    @Test
    fun skipsDigitsAndPunctuation() {
        assertEquals("京", avatarLetter("2024 京都旅行"))
        assertEquals("D", avatarLetter("DJI_Export"))
        assertEquals("2", avatarLetter("2024"))
        assertEquals("É", avatarLetter(" 12 élan"))
        assertEquals("?", avatarLetter("?!"))
    }

    @Test
    fun minimumHashIsSafe() {
        assertTrue(avatarColorIndex("polygenelubricants") in 0..6)
    }
}
