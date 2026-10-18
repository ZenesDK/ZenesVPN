package com.zenes.vpn.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.zenes.vpn.R
import com.zenes.vpn.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppEntry(
    val pkg: String,
    val label: String,
    val isSystem: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerAppScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { Store(context) }

    var mode by remember { mutableStateOf(store.perAppMode) }
    var include by remember { mutableStateOf(store.perAppInclude) }
    var exclude by remember { mutableStateOf(store.perAppExclude) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(context) }
    }

    fun persist(currentMode: String, inc: Set<String>, exc: Set<String>) {
        store.perAppMode = currentMode
        store.perAppInclude = inc
        store.perAppExclude = exc
    }

    fun switchMode(newMode: String) {
        mode = newMode
        persist(mode, include, exclude)
    }

    fun toggle(pkg: String) {
        if (mode == Store.PER_APP_INCLUDE) {
            include = HashSet(include).apply { if (!add(pkg)) remove(pkg) }
        } else {
            exclude = HashSet(exclude).apply { if (!add(pkg)) remove(pkg) }
        }
        persist(mode, include, exclude)
    }

    fun clearSelection() {
        if (mode == Store.PER_APP_INCLUDE) include = emptySet() else exclude = emptySet()
        persist(mode, include, exclude)
    }

    val visibleApps = remember(apps, query, showSystem) {
        (apps ?: emptyList()).filter { e ->
            (showSystem || !e.isSystem) &&
                (query.isBlank() || e.label.contains(query, true) || e.pkg.contains(query, true))
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.perapp_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(16.dp))

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mode == Store.PER_APP_ALL,
                onClick = { switchMode(Store.PER_APP_ALL) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
            ) { Text(stringResource(R.string.perapp_mode_all), maxLines = 1) }
            SegmentedButton(
                selected = mode == Store.PER_APP_INCLUDE,
                onClick = { switchMode(Store.PER_APP_INCLUDE) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
            ) { Text(stringResource(R.string.perapp_mode_include), maxLines = 1) }
            SegmentedButton(
                selected = mode == Store.PER_APP_EXCLUDE,
                onClick = { switchMode(Store.PER_APP_EXCLUDE) },
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
            ) { Text(stringResource(R.string.perapp_mode_exclude), maxLines = 1) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (mode) {
                Store.PER_APP_INCLUDE -> stringResource(R.string.perapp_desc_include)
                Store.PER_APP_EXCLUDE -> stringResource(R.string.perapp_desc_exclude)
                else -> stringResource(R.string.perapp_desc_all)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = when (mode) {
                Store.PER_APP_INCLUDE ->
                    stringResource(R.string.perapp_selected_count, include.size) + " · " +
                        stringResource(R.string.perapp_apply_hint)
                Store.PER_APP_EXCLUDE ->
                    stringResource(R.string.perapp_selected_count, exclude.size) + " · " +
                        stringResource(R.string.perapp_apply_hint)
                else -> stringResource(R.string.perapp_apply_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (mode != Store.PER_APP_ALL) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.perapp_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.perapp_show_system),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = showSystem, onCheckedChange = { showSystem = it })
                TextButton(onClick = { clearSelection() }) {
                    Text(stringResource(R.string.perapp_clear))
                }
            }
        }

        when {
            apps == null -> {
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.perapp_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            mode == Store.PER_APP_ALL -> Unit
            visibleApps.isEmpty() -> {
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.perapp_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(visibleApps, key = { it.pkg }) { app ->
                        AppRow(
                            app = app,
                            checked = if (mode == Store.PER_APP_INCLUDE) {
                                app.pkg in include
                            } else {
                                app.pkg in exclude
                            },
                            onToggle = { toggle(app.pkg) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AppRow(app: AppEntry, checked: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val icon by produceState<Drawable?>(initialValue = null, app.pkg) {
        value = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(app.pkg)
            } catch (_: Throwable) {
                null
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                app.pkg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun AppIcon(drawable: Drawable?) {
    val bitmap = remember(drawable) { drawable?.toBitmap(96, 96)?.asImageBitmap() }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

private fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val self = context.packageName
    return try {
        pm.getInstalledPackages(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != self }
            .map { info ->
                val ai: ApplicationInfo? = info.applicationInfo
                AppEntry(
                    pkg = info.packageName,
                    label = try {
                        ai?.loadLabel(pm)?.toString() ?: info.packageName
                    } catch (_: Throwable) {
                        info.packageName
                    },
                    isSystem = ai != null && (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    } catch (_: Throwable) {
        emptyList()
    }
}
