package dev.vvideo.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import dev.vvideo.R
import dev.vvideo.data.settings.Connection
import dev.vvideo.ui.component.ActionIcon
import dev.vvideo.ui.component.VVButton
import dev.vvideo.ui.theme.LocalVVColors
import dev.vvideo.ui.theme.ServerGradient

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionForm(vm: SettingsViewModel, connection: Connection, onDone: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = LocalVVColors.current
    var address by rememberSaveable(connection.address) { mutableStateOf(connection.address) }
    // Credentials stay out of saved-instance state.
    var token by remember(connection.token) { mutableStateOf(connection.token) }
    var tokenVisible by remember { mutableStateOf(false) }
    val initial = remember { state.connectedSerial }
    LaunchedEffect(Unit) { vm.dismissMessage() }
    LaunchedEffect(state.connectedSerial) { if (state.connectedSerial > initial) onDone() }
    val addressError = when (state.message) {
        SettingsMessage.InvalidAddress -> R.string.invalid_address
        SettingsMessage.ConnectionFailed -> R.string.address_unreachable
        else -> null
    }
    val tokenError = state.message == SettingsMessage.AuthenticationFailed
    Scaffold(
        containerColor = colors.bgPage,
        topBar = {
            if (connection.configured) TopAppBar(
                expandedHeight = 56.dp,
                title = { Text(stringResource(R.string.edit_connection)) },
                navigationIcon = {
                    ActionIcon(
                        R.drawable.ic_back,
                        R.string.back,
                        onDone,
                        enabled = !state.busy
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bgSurface)
            )
        }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).statusBarsPadding().navigationBarsPadding()
                .imePadding().padding(horizontal = 24.dp)
        ) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!connection.configured) {
                    Spacer(Modifier.height(40.dp))
                    Box(
                        Modifier.size(104.dp).align(Alignment.CenterHorizontally)
                            .background(ServerGradient, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_play),
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(52.dp)
                        )
                    }
                    Text(
                        stringResource(R.string.connect_title),
                        Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        stringResource(R.string.connect_body),
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))
                }
                Text(
                    stringResource(R.string.server_address),
                    color = colors.accentText,
                    style = MaterialTheme.typography.labelSmall
                )
                OutlinedTextField(
                    address,
                    {
                        address = it
                        vm.dismissMessage()
                    },
                    Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    singleLine = true,
                    enabled = !state.busy,
                    isError = addressError != null,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = colors.bgInput,
                        focusedContainerColor = colors.bgInput
                    )
                )
                if (addressError != null) Text(
                    stringResource(addressError),
                    color = colors.error,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    stringResource(R.string.access_token),
                    color = colors.accentText,
                    style = MaterialTheme.typography.labelSmall
                )
                OutlinedTextField(
                    token,
                    {
                        token = it
                        vm.dismissMessage()
                    },
                    Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    singleLine = true,
                    enabled = !state.busy,
                    isError = tokenError,
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (tokenVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    trailingIcon = {
                        ActionIcon(
                            R.drawable.ic_eye,
                            R.string.show_token,
                            { tokenVisible = !tokenVisible })
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = colors.bgInput,
                        focusedContainerColor = colors.bgInput
                    )
                )
                if (tokenError) Text(
                    stringResource(R.string.token_wrong),
                    color = colors.error,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    stringResource(R.string.connection_hint),
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            VVButton(
                stringResource(if (connection.configured) R.string.save_reconnect else R.string.connect),
                { vm.connect(address, token) },
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                enabled = address.isNotBlank() && token.isNotBlank(),
                busy = state.busy
            )
        }
    }
}
