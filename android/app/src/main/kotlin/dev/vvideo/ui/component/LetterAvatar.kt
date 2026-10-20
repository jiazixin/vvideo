package dev.vvideo.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vvideo.ui.theme.AvatarGradients

fun avatarLetter(name: String): String {
    val points = name.codePoints().toArray()
    val point = points.firstOrNull { Character.isLetter(it) }
        ?: points.firstOrNull { Character.isDigit(it) } ?: return "?"
    return String(Character.toChars(point)).uppercase(java.util.Locale.ROOT)
}

fun avatarColorIndex(id: String): Int = (kotlin.math.abs(id.hashCode().toLong()) % 7).toInt()

@Composable
fun LetterAvatar(id: String, name: String, size: Dp = 52.dp) {
    val letter = remember(name) { avatarLetter(name) }
    val brush = remember(id) { AvatarGradients[avatarColorIndex(id)] }
    Box(Modifier.size(size).background(brush, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            letter,
            color = Color.White,
            fontSize = (size.value * .4f).sp,
            fontWeight = FontWeight.Medium
        )
    }
}
