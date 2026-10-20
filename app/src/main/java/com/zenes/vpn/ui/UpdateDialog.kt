package com.zenes.vpn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
        is Updater.State.Available -> {
            var notesExpanded by remember { mutableStateOf(false) }
            val notes = remember(s.version) { Updater.cleanReleaseNotes(s.notes) }
            val notesScroll = rememberScrollState()
            val chevronRotation by animateFloatAsState(
                targetValue = if (notesExpanded) 180f else 0f,
                label = "whatsnew-chevron",
            )
            AlertDialog(
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
                        if (s.prerelease) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.update_prerelease_badge),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        if (notes.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { notesExpanded = !notesExpanded }
                                    .padding(vertical = 4.dp, horizontal = 2.dp),
                            ) {
                                Text(
                                    stringResource(R.string.update_whatsnew),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .rotate(chevronRotation),
                                )
                            }
                            // Single size animation source (expand/shrink here) —
                            // no animateContentSize on the parent: two competing
                            // size animations are what made this block stutter.
                            AnimatedVisibility(
                                visible = notesExpanded,
                                enter = fadeIn(tween(150)) + expandVertically(
                                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                                ),
                                exit = fadeOut(tween(120)) + shrinkVertically(
                                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                                ),
                            ) {
                                Text(
                                    notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .padding(top = 6.dp)
                                        .heightIn(max = 240.dp)
                                        .verticalScroll(notesScroll),
                                )
                            }
                        }
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
        }

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
