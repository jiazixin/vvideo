package dev.vvideo.feature.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import dev.vvideo.R
import dev.vvideo.data.settings.Connection
import dev.vvideo.ui.component.*
import dev.vvideo.ui.theme.*

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(vm:SettingsViewModel,connection:Connection,onDone:()->Unit,onStorage:()->Unit={},onConnection:()->Unit={}){
 val state by vm.state.collectAsStateWithLifecycle();val theme by vm.theme.collectAsStateWithLifecycle(initialValue="system")
 LaunchedEffect(connection.id,connection.token){vm.verify(connection);vm.usage()}
 var themeOpen by remember{mutableStateOf(false)}
 val c=LocalVV.current
 val host=remember(connection.address){runCatching{java.net.URI(connection.address).rawAuthority}.getOrNull().orEmpty()}
 val context=androidx.compose.ui.platform.LocalContext.current
 val version=remember{context.packageManager.getPackageInfo(context.packageName,0).versionName.orEmpty()}
 if(themeOpen)VVBottomSheet(stringResource(R.string.theme),{themeOpen=false}){
  listOf("system" to R.string.theme_system,"light" to R.string.theme_light,"dark" to R.string.theme_dark).forEach{(key,label)->Row(Modifier.fillMaxWidth().clickable{vm.theme(key);themeOpen=false}.heightIn(min=52.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(theme==key,{vm.theme(key);themeOpen=false});Text(stringResource(label))}}
 }
 Scaffold(containerColor=c.bgPage,topBar={TopAppBar(expandedHeight=56.dp,title={Text(stringResource(R.string.settings))},navigationIcon={ActionIcon(R.drawable.ic_back,R.string.back,onDone)},colors=TopAppBarDefaults.topAppBarColors(containerColor=c.bgSurface))}){padding->Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
  Row(Modifier.fillMaxWidth().background(c.bgSurface).padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
   Box(Modifier.size(64.dp).background(AvatarGradients[5],CircleShape),contentAlignment=Alignment.Center){Icon(painterResource(R.drawable.ic_server),null,tint=androidx.compose.ui.graphics.Color.White,modifier=Modifier.size(32.dp))}
   Column{Text(host,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis);Text(if(state.connectionStatus==R.string.connection_ok)stringResource(R.string.connection_count,state.libraryCount)else stringResource(state.connectionStatus),color=if(state.connectionStatus==R.string.connection_ok)c.online else c.textSecondary,style=MaterialTheme.typography.bodyMedium)}
  }
  SettingsSection(R.string.connection_section){
   SettingsRow(R.drawable.ic_globe,R.string.server_address,host,onConnection)
   SettingsRow(R.drawable.ic_key,R.string.access_token,"••••••"+connection.token.takeLast(4),onConnection)
   SettingsRow(R.drawable.ic_refresh,R.string.verify_connection,onClick={vm.verify(connection)})
  }
  SettingsSection(R.string.storage_section){SettingsRow(R.drawable.ic_disk,R.string.storage_usage,state.usage?.let{bytes(it.video+it.images+it.overhead)+" / "+bytes(connection.budget)}?:stringResource(R.string.calculating),onStorage)}
  SettingsSection(R.string.display_section){SettingsRow(R.drawable.ic_moon,R.string.theme,stringResource(when(theme){"light"->R.string.theme_light;"dark"->R.string.theme_dark;else->R.string.theme_system}),{themeOpen=true})}
  Text(stringResource(R.string.app_version,version),Modifier.align(Alignment.CenterHorizontally).padding(16.dp),style=MaterialTheme.typography.bodySmall,color=c.textTertiary)
 }}
}
@Composable
internal fun SettingsSection(title:Int,content:@Composable ColumnScope.()->Unit){Column(Modifier.fillMaxWidth().background(LocalVV.current.bgSurface)){Text(stringResource(title),Modifier.padding(start=20.dp,top=16.dp,bottom=8.dp),color=LocalVV.current.accentText,style=MaterialTheme.typography.labelLarge);content()}}
@Composable
internal fun SettingsRow(icon:Int,label:Int,value:String?=null,onClick:()->Unit){val c=LocalVV.current
 Row(Modifier.fillMaxWidth().clickable(onClick=onClick).heightIn(min=52.dp).padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
  Icon(painterResource(icon),null,tint=c.textSecondary,modifier=Modifier.size(24.dp));Text(stringResource(label),Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
  if(value!=null)Text(value,Modifier.widthIn(max=180.dp),color=c.textSecondary,style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
 }
}
internal fun bytes(value:Long):String=when{value>=1_000_000_000->"%.2f GB".format(value/1_000_000_000.0);value>=1_000_000->"%.1f MB".format(value/1_000_000.0);else->"%.1f KB".format(value/1_000.0)}
