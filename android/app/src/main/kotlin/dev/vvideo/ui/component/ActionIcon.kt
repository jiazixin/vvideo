package dev.vvideo.ui.component

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource

@Composable
fun ActionIcon(icon:Int,label:Int,onClick:()->Unit,modifier:Modifier=Modifier,tint:Color=LocalContentColor.current,enabled:Boolean=true) {
 IconButton(onClick=onClick,modifier=modifier,enabled=enabled) {
  Icon(painterResource(icon),stringResource(label),tint=tint)
 }
}
