package com.zenes.vpn.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.integration.android.IntentIntegrator
import com.zenes.vpn.R
import com.zenes.vpn.data.ServerProfile
import com.zenes.vpn.data.Store
import com.zenes.vpn.data.Subscription
import com.zenes.vpn.parser.LinkParser
import com.zenes.vpn.ui.theme.VividAmber
import com.zenes.vpn.ui.theme.VividCyan
import com.zenes.vpn.ui.theme.VividViolet
import com.zenes.vpn.ui.theme.heroBrush
import com.zenes.vpn.ui.theme.protocolColor
import com.zenes.vpn.util.Importer
import com.zenes.vpn.util.tcpPing
import com.zenes.vpn.util.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var servers by remember { mutableStateOf(Store(context).servers()) }
    var selectedId by remember { mutableStateOf(Store(context).selectedId()) }
    var showAddSheet by remember { mutableStateOf(false) }
    var showSubDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ServerProfile?>(null) }
    var deleteSubTarget by remember { mutableStateOf<Subscription?>(null) }
    var pingingAll by remember { mutableIntStateOf(0) }
    var subs by remember { mutableStateOf(Store(context).subs()) }
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    fun reload() {
        servers = Store(context).servers()
        selectedId = Store(context).selectedId()
        subs = Store(context).subs()
    }

    val qrLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val scan = IntentIntegrator.parseActivityResult(result.resultCode, result.data)
        val contents: String? = scan?.contents
        if (!contents.isNullOrBlank()) {
            scope.launch {
                val n = Importer.importText(context, contents)
                toast(context, context.getString(R.string.imported_count, n))
                reload()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.servers_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(
                    enabled = servers.isNotEmpty() && pingingAll == 0,
                    onClick = {
                        pingingAll = 1
                        val snapshot = servers
                        scope.launch {
                            snapshot.forEach { profile ->
                                val ms = tcpPing(profile.host, profile.port)
                                Store(context).updatePing(profile.id, ms ?: -2)
                                delay(30)
                            }
                            pingingAll = 0
                            reload()
                        }
                    },
                ) {
                    Text(if (pingingAll != 0) stringResource(R.string.pinging) else stringResource(R.string.ping_all))
                }
            }
            Spacer(Modifier.height(8.dp))

            if (servers.isEmpty() && subs.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Default.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(72.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.servers_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            } else {
                // The list itself keeps a comfortable 10dp gap between cards.
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (subs.isNotEmpty()) {
                        item(key = "subs_header") {
                            Text(
                                stringResource(R.string.nav_subs),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        items(subs, key = { "sub_${it.id}" }) { sub ->
                            SubscriptionCard(
                                sub = sub,
                                dateFormat = dateFormat,
                                onRefresh = {
                                    scope.launch {
                                        val result = Importer.importSubscription(context, sub.url)
                                        toast(
                                            context,
                                            if (result.isSuccess)
                                                context.getString(R.string.sub_updated_ok, result.getOrNull() ?: 0)
                                            else
                                                context.getString(R.string.sub_error, result.exceptionOrNull()?.message ?: "?"),
                                        )
                                        reload()
                                    }
                                },
                                onDelete = { deleteSubTarget = sub },
                            )
                        }
                        item(key = "subs_gap") { Spacer(Modifier.height(6.dp)) }
                    }
                    items(servers, key = { it.id }) { profile ->
                        ServerCard(
                            profile = profile,
                            selected = profile.id == selectedId,
                            onSelect = {
                                Store(context).select(profile.id)
                                selectedId = profile.id
                                toast(context, context.getString(R.string.select_server))
                            },
                            onPing = {
                                scope.launch {
                                    val ms = tcpPing(profile.host, profile.port)
                                    Store(context).updatePing(profile.id, ms ?: -2)
                                    reload()
                                }
                            },
                            onDelete = { deleteTarget = profile },
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { showAddSheet = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.add_server)) },
        )
    }

    // ---------------- import bottom sheet ----------------
    if (showAddSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.add_server),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp),
                )
                SheetItem(
                    icon = Icons.Default.ContentPaste,
                    label = stringResource(R.string.import_clipboard),
                ) {
                    showAddSheet = false
                    val text = clipboard.getText()?.text
                    if (text.isNullOrBlank()) {
                        toast(context, context.getString(R.string.clipboard_empty))
                    } else {
                        scope.launch {
                            val n = Importer.importText(context, text)
                            toast(
                                context,
                                if (n == 0) context.getString(R.string.clipboard_empty)
                                else context.getString(R.string.imported_count, n),
                            )
                            reload()
                        }
                    }
                }
                SheetItem(
                    icon = Icons.Default.QrCodeScanner,
                    label = stringResource(R.string.scan_qr),
                    iconTint = VividViolet,
                ) {
                    showAddSheet = false
                    val activity = context as? android.app.Activity
                    if (activity != null) {
                        val integrator = IntentIntegrator(activity)
                        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                        integrator.setPrompt(context.getString(R.string.qr_prompt))
                        integrator.setBeepEnabled(false)
                        integrator.setOrientationLocked(true)
                        qrLauncher.launch(integrator.createScanIntent())
                    }
                }
                SheetItem(
                    icon = Icons.Default.CloudDownload,
                    label = stringResource(R.string.from_subscription),
                    iconTint = VividAmber,
                ) {
                    showAddSheet = false
                    showSubDialog = true
                }
            }
        }
    }

    // ---------------- quick subscription dialog ----------------
    if (showSubDialog) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSubDialog = false },
            title = { Text(stringResource(R.string.add_subscription)) },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text(stringResource(R.string.sub_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = url.contains("://"),
                    onClick = {
                        showSubDialog = false
                        scope.launch {
                            val result = Importer.importSubscription(context, url.trim())
                            toast(
                                context,
                                if (result.isSuccess)
                                    context.getString(R.string.sub_updated_ok, result.getOrNull() ?: 0)
                                else
                                    context.getString(R.string.sub_error, result.exceptionOrNull()?.message ?: "?"),
                            )
                            reload()
                        }
                    },
                ) { Text(stringResource(R.string.sub_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showSubDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // ---------------- delete dialog ----------------
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_server)) },
            text = { Text((target.flag + " ").plus(target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    Store(context).deleteServer(target.id)
                    deleteTarget = null
                    reload()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // ---------------- delete subscription dialog ----------------
    deleteSubTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteSubTarget = null },
            title = { Text(stringResource(R.string.delete_subscription)) },
            text = { Text(stringResource(R.string.servers_also_removed)) },
            confirmButton = {
                TextButton(onClick = {
                    Store(context).deleteSub(target.id)
                    deleteSubTarget = null
                    reload()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteSubTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Compact subscription card shown at the top of the merged servers list. */
@Composable
private fun SubscriptionCard(
    sub: Subscription,
    dateFormat: SimpleDateFormat,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    // Vivid hero gradient makes the subscription the most colorful card on the screen.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(heroBrush()),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    sub.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subscriptionHasInfo(sub)) {
                    Spacer(Modifier.height(4.dp))
                    SubInfoContent(sub, compact = true, onHero = true)
                }
                Text(
                    stringResource(
                        R.string.sub_updated,
                        if (sub.lastUpdate > 0) dateFormat.format(Date(sub.lastUpdate))
                        else stringResource(R.string.sub_never),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.sub_refresh),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = stringResource(R.string.delete),
                    tint = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun ServerCard(
    profile: ServerProfile,
    selected: Boolean,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    // Gradient outline around the selected server.
                    Modifier.border(
                        BorderStroke(2.dp, Brush.linearGradient(listOf(VividViolet, VividCyan))),
                        shape,
                    )
                } else {
                    Modifier
                },
            )
            .clickable { onSelect() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    profile.pingMs.let { ping ->
                        if (ping >= 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "$ping ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    ping < 100 -> MaterialTheme.colorScheme.tertiary
                                    ping < 300 -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.error
                                },
                            )
                        } else if (ping == -2) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.ping_fail),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProtoChipInline(profile.proto)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${profile.host}:${profile.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onPing) {
                Icon(
                    Icons.Default.Speed,
                    contentDescription = stringResource(R.string.ping_all),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One option row inside the import sheet. Generous vertical padding keeps options apart. */
@Composable
private fun SheetItem(icon: ImageVector, label: String, iconTint: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint)
            Spacer(Modifier.width(16.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Inline protocol chip used inside server rows (tiny, non-interactive). */
@Composable
private fun ProtoChipInline(proto: String) {
    val color = protocolColor(proto)
    Text(
        text = proto.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}
