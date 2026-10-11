package dev.vvideo.feature.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.*
import androidx.compose.ui.text.input.*
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
fun ConnectionForm(vm:SettingsViewModel,connection:Connection,onDone:()->Unit){
 val state by vm.state.collectAsStateWithLifecycle();val c=LocalVV.current
 var address by rememberSaveable(connection.address){mutableStateOf(connection.address)}
 // Credentials stay out of saved-instance state.
 var token by remember(connection.token){mutableStateOf(connection.token)};var show by remember{mutableStateOf(false)}
 val initial=remember{state.connectedSerial}
 LaunchedEffect(Unit){vm.dismissResult()}
 LaunchedEffect(state.connectedSerial){if(state.connectedSerial>initial)onDone()}
 val addressError=when(state.result){R.string.invalid_address->R.string.invalid_address;R.string.error_connection->R.string.address_unreachable;else->0}
 val tokenError=state.result==R.string.authentication_failed
 Scaffold(containerColor=c.bgPage,topBar={if(connection.configured)TopAppBar(expandedHeight=56.dp,title={Text(stringResource(R.string.edit_connection))},navigationIcon={ActionIcon(R.drawable.ic_back,R.string.back,onDone,enabled=!state.busy)},colors=TopAppBarDefaults.topAppBarColors(containerColor=c.bgSurface))}){padding->
  Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal=24.dp)){
   Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
    if(!connection.configured){Spacer(Modifier.height(40.dp));Box(Modifier.size(104.dp).align(Alignment.CenterHorizontally).background(AvatarGradients[5],CircleShape),contentAlignment=Alignment.Center){Icon(painterResource(R.drawable.ic_play),null,tint=Color.White,modifier=Modifier.size(52.dp))};Text(stringResource(R.string.connect_title),Modifier.align(Alignment.CenterHorizontally).padding(top=16.dp),style=MaterialTheme.typography.headlineSmall);Text(stringResource(R.string.connect_body),color=c.textSecondary,style=MaterialTheme.typography.bodyMedium);Spacer(Modifier.height(16.dp))}
    Text(stringResource(R.string.server_address),color=c.accentText,style=MaterialTheme.typography.labelSmall)
    OutlinedTextField(address,{address=it;vm.dismissResult()},Modifier.fillMaxWidth().heightIn(min=52.dp),singleLine=true,enabled=!state.busy,isError=addressError!=0,shape=RoundedCornerShape(12.dp),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri,imeAction=ImeAction.Next),colors=OutlinedTextFieldDefaults.colors(unfocusedContainerColor=c.bgInput,focusedContainerColor=c.bgInput))
    if(addressError!=0)Text(stringResource(addressError),color=c.error,style=MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.access_token),color=c.accentText,style=MaterialTheme.typography.labelSmall)
    OutlinedTextField(token,{token=it;vm.dismissResult()},Modifier.fillMaxWidth().heightIn(min=52.dp),singleLine=true,enabled=!state.busy,isError=tokenError,shape=RoundedCornerShape(12.dp),visualTransformation=if(show)VisualTransformation.None else PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,imeAction=ImeAction.Done),trailingIcon={ActionIcon(R.drawable.ic_eye,R.string.show_token,{show=!show})},colors=OutlinedTextFieldDefaults.colors(unfocusedContainerColor=c.bgInput,focusedContainerColor=c.bgInput))
    if(tokenError)Text(stringResource(R.string.token_wrong),color=c.error,style=MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.connection_hint),color=c.textSecondary,style=MaterialTheme.typography.bodySmall)
   }
   VVButton(stringResource(if(connection.configured)R.string.save_reconnect else R.string.connect),{vm.connect(address,token)},Modifier.fillMaxWidth().padding(vertical=16.dp),enabled=address.isNotBlank()&&token.isNotBlank(),busy=state.busy)
  }
 }
}
