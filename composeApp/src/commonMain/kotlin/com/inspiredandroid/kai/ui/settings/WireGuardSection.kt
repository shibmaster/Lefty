package com.inspiredandroid.kai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.inspiredandroid.kai.tunnel.TunnelState
import com.inspiredandroid.kai.ui.KaiOutlinedTextField
import com.inspiredandroid.kai.ui.handCursor
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.readBytes
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.settings_wireguard
import kai.composeapp.generated.resources.settings_wireguard_allowed_ips
import kai.composeapp.generated.resources.settings_wireguard_connect_now
import kai.composeapp.generated.resources.settings_wireguard_covers_everything
import kai.composeapp.generated.resources.settings_wireguard_description
import kai.composeapp.generated.resources.settings_wireguard_disconnect
import kai.composeapp.generated.resources.settings_wireguard_endpoint
import kai.composeapp.generated.resources.settings_wireguard_idle
import kai.composeapp.generated.resources.settings_wireguard_ignored
import kai.composeapp.generated.resources.settings_wireguard_import
import kai.composeapp.generated.resources.settings_wireguard_import_failed
import kai.composeapp.generated.resources.settings_wireguard_invalid_routes
import kai.composeapp.generated.resources.settings_wireguard_paste
import kai.composeapp.generated.resources.settings_wireguard_paste_hint
import kai.composeapp.generated.resources.settings_wireguard_paste_use
import kai.composeapp.generated.resources.settings_wireguard_remove
import kai.composeapp.generated.resources.settings_wireguard_replace
import kai.composeapp.generated.resources.settings_wireguard_routes
import kai.composeapp.generated.resources.settings_wireguard_routes_hint
import kai.composeapp.generated.resources.settings_wireguard_status_connecting
import kai.composeapp.generated.resources.settings_wireguard_status_error
import kai.composeapp.generated.resources.settings_wireguard_status_off
import kai.composeapp.generated.resources.settings_wireguard_status_up
import kai.composeapp.generated.resources.settings_wireguard_status_waiting
import kai.composeapp.generated.resources.settings_wireguard_testing
import kai.composeapp.generated.resources.settings_wireguard_your_address
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Settings → General: import a WireGuard client config and route the home server through it. */
@Composable
internal fun WireGuardSection(state: WireGuardUiState, actions: SettingsActions) {
    var pasting by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleableHeadline(
            title = stringResource(Res.string.settings_wireguard),
            description = stringResource(Res.string.settings_wireguard_description),
            checked = state.enabled && state.hasConfig,
            onCheckedChange = { if (state.hasConfig) actions.onToggleWireGuard(it) },
        )

        if (state.hasConfig) {
            InfoLine(Res.string.settings_wireguard_your_address, state.addresses)
            InfoLine(Res.string.settings_wireguard_endpoint, state.endpoints)
            InfoLine(Res.string.settings_wireguard_allowed_ips, state.allowedIps)
            if (state.ignoredKeys.isNotEmpty()) {
                SmallNote(stringResource(Res.string.settings_wireguard_ignored, state.ignoredKeys))
            }
            RoutesField(state, actions)
            IdleField(state.idleMinutes, actions.onChangeWireGuardIdleMinutes)
            StatusRow(state, actions)
        }

        state.importError?.let {
            SmallNote(stringResource(Res.string.settings_wireguard_import_failed, it), isError = true)
        }
        if (pasting) {
            PasteField(
                onUse = {
                    actions.onImportWireGuardConf(it)
                    pasting = false
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ImportButton(if (state.hasConfig) Res.string.settings_wireguard_replace else Res.string.settings_wireguard_import, actions.onImportWireGuardConf)
            if (!pasting) {
                TextButton(onClick = { pasting = true }, modifier = Modifier.handCursor()) {
                    Text(stringResource(Res.string.settings_wireguard_paste))
                }
            }
            if (state.hasConfig) {
                TextButton(onClick = actions.onRemoveWireGuard, modifier = Modifier.handCursor()) {
                    Text(stringResource(Res.string.settings_wireguard_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ImportButton(labelRes: StringResource, onImport: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    // A .conf has no registered MIME type on Android, so accept any file and let the parser decide.
    val launcher = if (!LocalInspectionMode.current) {
        rememberFilePickerLauncher(type = FileKitType.File()) { file ->
            if (file != null) scope.launch { onImport(file.readBytes().decodeToString()) }
        }
    } else {
        null
    }
    OutlinedButton(onClick = { launcher?.launch() }, modifier = Modifier.handCursor()) {
        Text(stringResource(labelRes))
    }
}

@Composable
private fun PasteField(onUse: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    KaiOutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(Res.string.settings_wireguard_paste_hint), color = MaterialTheme.colorScheme.onSurfaceVariant) },
        minLines = 4,
        maxLines = 12,
    )
    OutlinedButton(onClick = { onUse(text) }, enabled = text.isNotBlank(), modifier = Modifier.handCursor()) {
        Text(stringResource(Res.string.settings_wireguard_paste_use))
    }
}

@Composable
private fun RoutesField(state: WireGuardUiState, actions: SettingsActions) {
    var text by remember { mutableStateOf(state.customRoutes) }
    LaunchedEffect(state.customRoutes) {
        if (text.trim() != state.customRoutes.trim()) text = state.customRoutes
    }
    KaiOutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            actions.onChangeWireGuardRoutes(it)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(Res.string.settings_wireguard_routes), color = MaterialTheme.colorScheme.onBackground) },
        placeholder = { Text(state.allowedIps, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        singleLine = true,
    )
    SmallNote(stringResource(Res.string.settings_wireguard_routes_hint))
    if (state.invalidRoutes.isNotEmpty()) {
        SmallNote(stringResource(Res.string.settings_wireguard_invalid_routes, state.invalidRoutes), isError = true)
    }
    if (state.routesCoverEverything) {
        SmallNote(stringResource(Res.string.settings_wireguard_covers_everything), isError = true)
    }
}

@Composable
private fun IdleField(minutes: Int, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(minutes.toString()) }
    KaiOutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() }.take(3)
            text.toIntOrNull()?.let(onChange)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(Res.string.settings_wireguard_idle), color = MaterialTheme.colorScheme.onBackground) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun StatusRow(state: WireGuardUiState, actions: SettingsActions) {
    val status = state.status
    val (text, isError) = when (status) {
        TunnelState.Off -> stringResource(Res.string.settings_wireguard_status_off) to false

        TunnelState.Connecting -> stringResource(Res.string.settings_wireguard_status_connecting) to false

        is TunnelState.Up -> if (status.handshakeAgoSec == null) {
            stringResource(Res.string.settings_wireguard_status_waiting) to false
        } else {
            stringResource(Res.string.settings_wireguard_status_up, status.handshakeAgoSec, formatBytes(status.rxBytes), formatBytes(status.txBytes)) to false
        }

        is TunnelState.Error -> stringResource(Res.string.settings_wireguard_status_error, status.message) to true
    }
    SmallNote(if (state.testing) stringResource(Res.string.settings_wireguard_testing) else text, isError = isError && !state.testing)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = actions.onConnectWireGuardNow,
            enabled = state.enabled && !state.testing,
            modifier = Modifier.handCursor(),
        ) {
            Text(stringResource(Res.string.settings_wireguard_connect_now))
        }
        if (status is TunnelState.Up || status == TunnelState.Connecting) {
            TextButton(onClick = actions.onDisconnectWireGuard, modifier = Modifier.handCursor()) {
                Text(stringResource(Res.string.settings_wireguard_disconnect))
            }
        }
    }
}

@Composable
private fun InfoLine(labelRes: StringResource, value: String) {
    if (value.isEmpty()) return
    Text(
        text = "${stringResource(labelRes)}: $value",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun SmallNote(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${(bytes * 10 / (1024 * 1024)) / 10.0} MB"
}
