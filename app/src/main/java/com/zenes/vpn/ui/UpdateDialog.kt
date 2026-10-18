package com.zenes.vpn.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenes.vpn.R
import com.zenes.vpn.core.Updater
import com.zenes.vpn.util.toast
import io.nekohasekai.libbox.Libbox

/**
 * Global self-update popup. Rendered at the app root so an available update
 * pops up on any tab (including the automatic check on app start).
 */
@Composable
fun UpdateDialog() {
    val context = LocalContext.current
    val state by Updater.state.collectAsStateWithLifecycle()

    when (val s = state) {
        is Updater.State.Available -> AlertDialog(
            onDismissRequest = { Updater.dismiss() },
            title = { Text(stringResource(R.string.update_available_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.update_available_text,
                            s.version,
                            Libbox.formatBytes(s.sizeBytes),
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.update_available_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { Updater.downloadAsync(context) }) {
                    Text(stringResource(R.string.update_action_update))
                }
            },
            dismissButton = {
                TextButton(onClick = { Updater.dismiss() }) {
                    Text(stringResource(R.string.update_action_later))
                }
            },
        )

        is Updater.State.Downloading -> AlertDialog(
            onDismissRequest = { /* let the download finish — it is small */ },
            title = { Text(stringResource(R.string.update_downloading_title)) },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { s.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.update_downloading_percent, s.percent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
        )

        is Updater.State.Ready -> AlertDialog(
            onDismissRequest = { Updater.dismiss() },
            title = { Text(stringResource(R.string.update_ready_title)) },
            text = { Text(stringResource(R.string.update_ready_text, s.version)) },
            confirmButton = {
                TextButton(onClick = {
                    if (!Updater.startInstall(context)) {
                        // "Install unknown apps" permission missing — the system
                        // settings screen was opened; the user comes back and taps again.
                        toast(context, context.getString(R.string.update_install_permission_hint))
                    }
                }) {
                    Text(stringResource(R.string.update_action_install))
                }
            },
            dismissButton = {
                TextButton(onClick = { Updater.dismiss() }) {
                    Text(stringResource(R.string.update_action_later))
                }
            },
        )

        else -> {}
    }
}
