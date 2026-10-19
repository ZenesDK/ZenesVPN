package com.zenes.vpn.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenes.vpn.R
import com.zenes.vpn.data.Store
import com.zenes.vpn.data.StatsStore
import com.zenes.vpn.ui.theme.VividBlue
import com.zenes.vpn.ui.theme.VividCyan
import com.zenes.vpn.ui.theme.VividViolet
import com.zenes.vpn.ui.theme.cardTintBrush
import com.zenes.vpn.ui.theme.protocolColor
import io.nekohasekai.libbox.Libbox
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit) {
    var period by remember { mutableIntStateOf(14) }
    var showReset by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    val context = androidx.compose.ui.platform.LocalContext.current
    val dayList = remember(period, revision) { StatsStore.snapshot(period) }
    val topServers = remember(period, revision) { StatsStore.topServers(period) }
    val serversById = remember(revision) { Store(context).servers().associateBy { it.id } }
    val today = dayList.lastOrNull()
    val dayFormat = remember { SimpleDateFormat("dd.MM", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cancel),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.stats_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(16.dp))

        // ---------------- today hero card ----------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .background(cardTintBrush()),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                HeroCell(
                    label = stringResource(R.string.upload_speed),
                    bytes = today?.up ?: 0L,
                    tint = VividViolet,
                )
                HeroCell(
                    label = stringResource(R.string.download_speed),
                    bytes = today?.down ?: 0L,
                    tint = VividCyan,
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        // ---------------- period selector ----------------
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val periods = listOf(7, 14, 30)
            periods.forEachIndexed { index, p ->
                SegmentedButton(
                    selected = period == p,
                    onClick = { period = p },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = periods.size),
                ) {
                    Text(
                        stringResource(
                            when (p) {
                                7 -> R.string.period_7
                                14 -> R.string.period_14
                                else -> R.string.period_30
                            },
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---------------- daily bar chart ----------------
        val maxTotal = dayList.maxOfOrNull { it.up + it.down } ?: 0L
        val chartProgress by animateFloatAsState(
            targetValue = if (appeared) 1f else 0f,
            animationSpec = tween(700, easing = FastOutSlowInEasing),
            label = "chartGrow",
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(vertical = 16.dp),
        ) {
            if (maxTotal == 0L) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.BarChart,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(44.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.stats_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column {
                    Canvas(Modifier.fillMaxWidth().height(140.dp).padding(horizontal = 12.dp)) {
                        val n = dayList.size
                        if (n > 0) {
                            val slot = size.width / n
                            val barW = slot * 0.58f
                            dayList.forEachIndexed { i, day ->
                                val total = (day.up + day.down) * chartProgress
                                val h = if (maxTotal > 0) (total / maxTotal) * size.height else 0f
                                if (h > 1f) {
                                    drawRoundRect(
                                        brush = Brush.verticalGradient(
                                            listOf(VividViolet, VividBlue),
                                            startY = size.height - h,
                                            endY = size.height,
                                        ),
                                        topLeft = Offset(i * slot + (slot - barW) / 2f, size.height - h),
                                        size = Size(barW, h),
                                        cornerRadius = CornerRadius(barW / 3f),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        dayList.forEachIndexed { i, day ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                val showLabel = period == 7 || i % 3 == 0 || i == dayList.lastIndex
                                if (showLabel) {
                                    Text(
                                        text = dayFormat.format(
                                            java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
                                                .parse(day.key) ?: java.util.Date(),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (i == dayList.lastIndex) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        // ---------------- per-server breakdown ----------------
        if (topServers.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(VividViolet),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.stats_by_server),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(10.dp))
            val maxServer = topServers.maxOfOrNull { it.total } ?: 1L
            topServers.forEach { stat ->
                val profile = serversById[stat.id]
                val color = protocolColor(profile?.proto ?: "vless")
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = profile?.let { (it.flag + " ").plus(it.name) } ?: stat.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = Libbox.formatBytes(stat.total),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)),
                    ) {
                        val share = if (maxServer > 0) stat.total.toFloat() / maxServer else 0f
                        val animatedShare by animateFloatAsState(
                            targetValue = share * chartProgress,
                            animationSpec = tween(700, easing = FastOutSlowInEasing),
                            label = "share",
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedShare.coerceIn(0.02f, 1f))
                                .height(6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(color),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // ---------------- reset ----------------
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
            onClick = { showReset = true },
        ) {
            Text(
                stringResource(R.string.stats_reset),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text(stringResource(R.string.stats_reset)) },
            text = { Text(stringResource(R.string.stats_reset_text)) },
            confirmButton = {
                TextButton(onClick = {
                    StatsStore.reset()
                    revision++
                    showReset = false
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun HeroCell(label: String, bytes: Long, tint: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(tint),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            Libbox.formatBytes(bytes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}
