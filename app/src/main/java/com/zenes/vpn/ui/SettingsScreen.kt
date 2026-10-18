package com.zenes.vpn.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenes.vpn.BuildConfig
import com.zenes.vpn.R
import com.zenes.vpn.bg.CrashLogger
import com.zenes.vpn.core.Updater
import com.zenes.vpn.data.Store
import com.zenes.vpn.ui.theme.VividCyan
import com.zenes.vpn.ui.theme.VividPink
import com.zenes.vpn.ui.theme.cardTintBrush
import com.zenes.vpn.util.toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.nekohasekai.libbox.Libbox

@Composable
fun SettingsScreen(onOpenPerApps: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var autostart by remember { mutableStateOf(Store(context).autostart) }
    var killSwitch by remember { mutableStateOf(Store(context).killSwitchHint) }
    var crashLog by remember { mutableStateOf(CrashLogger.fullReport()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))

        // ---------------- connection ----------------
        SectionLabel(stringResource(R.string.section_connection))
        SettingSwitch(
            title = stringResource(R.string.autostart),
            subtitle = stringResource(R.string.autostart_desc),
            checked = autostart,
            onChange = {
                autostart = it
                Store(context).autostart = it
            },
        )
        SettingSwitch(
            title = stringResource(R.string.kill_switch),
            subtitle = stringResource(R.string.kill_switch_desc),
            checked = killSwitch,
            onChange = { checked ->
                killSwitch = checked
                Store(context).killSwitchHint = checked
                try {
                    // Open the system "Always-on VPN" screen; the OS dialog
                    // controls the actual kill switch behaviour.
                    context.startActivity(
                        Intent("android.net.vpn.SETTINGS")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    toast(context, context.getString(R.string.kill_switch_hint))
                } catch (t: Throwable) {
                    toast(context, t.message ?: "?")
                }
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenPerApps() }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.perapp_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    perAppSummary(context),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))

        // ---------------- diagnostics ----------------
        SectionLabel(stringResource(R.string.section_diagnostics), dot = VividCyan)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            var logExpanded by remember { mutableStateOf(false) }
            Column(
                Modifier
                    .padding(16.dp)
                    .animateContentSize(tween(220)),
            ) {
                val entries = crashLog.lines().count { it.isNotBlank() }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (crashLog.isEmpty()) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .clickable { logExpanded = !logExpanded }
                    },
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.crash_log),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        when {
                            crashLog.isEmpty() -> Text(
                                stringResource(R.string.crash_log_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            logExpanded -> Text(
                                stringResource(R.string.crash_collapse),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            else -> {
                                Text(
                                    text = crashLog.lines().lastOrNull { it.isNotBlank() } ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    stringResource(R.string.crash_log_entries, entries),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (logExpanded && crashLog.isNotEmpty()) {
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(crashLog))
                            toast(context, context.getString(R.string.crash_copied))
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.crash_copy))
                        }
                        IconButton(onClick = {
                            CrashLogger.clearAll()
                            crashLog = ""
                            logExpanded = false
                        }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.crash_clear))
                        }
                    }
                }
                if (logExpanded) {
                    Spacer(Modifier.height(8.dp))
                    if (crashLog.isEmpty()) {
                        Text(
                            stringResource(R.string.crash_log_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = if (crashLog.length > 1500) "…" + crashLog.takeLast(1500) else crashLog,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // ---------------- about ----------------
        SectionLabel(stringResource(R.string.section_about), dot = VividPink)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .background(cardTintBrush()),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.about_version),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.about_engine),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        try {
                            "sing-box " + Libbox.version()
                        } catch (_: Throwable) {
                            "sing-box"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---------------- update check ----------------
        val updateState by Updater.state.collectAsStateWithLifecycle()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .clickable {
                    if (updateState !is Updater.State.Checking &&
                        updateState !is Updater.State.Downloading
                    ) {
                        Updater.checkAsync(context, auto = false)
                    }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.SystemUpdateAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.update_check),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = updateStatusLine(updateState),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (updateState is Updater.State.Checking) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun updateStatusLine(state: Updater.State): String = when (state) {
    is Updater.State.Idle -> stringResource(R.string.update_check_sub)
    is Updater.State.Checking -> stringResource(R.string.update_checking)
    is Updater.State.UpToDate -> stringResource(R.string.update_latest)
    is Updater.State.Available -> stringResource(
        R.string.update_available_short,
        state.version,
    )
    is Updater.State.Downloading -> stringResource(R.string.update_downloading_percent, state.percent)
    is Updater.State.Ready -> stringResource(R.string.update_ready_title)
    is Updater.State.Failed -> stringResource(R.string.update_error, state.message)
}

@Composable
private fun perAppSummary(context: android.content.Context): String {
    val store = Store(context)
    return when (store.perAppMode) {
        Store.PER_APP_INCLUDE -> context.getString(R.string.perapp_summary_include, store.perAppInclude.size)
        Store.PER_APP_EXCLUDE -> context.getString(R.string.perapp_summary_exclude, store.perAppExclude.size)
        else -> context.getString(R.string.perapp_summary_all)
    }
}

@Composable
private fun SectionLabel(text: String, dot: Color = MaterialTheme.colorScheme.primary) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dot),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
