package com.zenes.vpn.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenes.vpn.R
import com.zenes.vpn.data.Subscription
import com.zenes.vpn.ui.theme.RoseSoft
import io.nekohasekai.libbox.Libbox
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/** True when the subscription carries any traffic/expire metadata worth rendering. */
fun subscriptionHasInfo(sub: Subscription): Boolean =
    sub.total > 0 || sub.expire > 0 || sub.used > 0

/**
 * Subscription metadata block: wavy usage bar (only with a traffic quota),
 * a traffic line and an expiry line (red when 3 or fewer days remain).
 *
 * With [onHero] = true the block is rendered on a violet gradient card, so all
 * text switches to white and the warning color becomes the light rose.
 */
@Composable
fun SubInfoContent(sub: Subscription, compact: Boolean, modifier: Modifier = Modifier, onHero: Boolean = false) {
    val bodyStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    val bodyColor = if (onHero) Color.White.copy(alpha = 0.92f) else Color.Unspecified

    Column(modifier = modifier.fillMaxWidth()) {
        if (sub.total > 0) {
            WavyProgressBar(
                progress = { (sub.used.toDouble() / sub.total).toFloat() },
                color = if (onHero) Color.White else MaterialTheme.colorScheme.primary,
                brush = if (onHero) {
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(Color.White, Color.White.copy(alpha = 0.75f)),
                    )
                } else {
                    null
                },
            )
            Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
        }

        val trafficText = if (sub.total > 0) {
            stringResource(
                R.string.sub_info_used,
                Libbox.formatBytes(sub.used),
                Libbox.formatBytes(sub.total),
            )
        } else if (sub.used > 0) {
            stringResource(R.string.sub_info_unlimited, Libbox.formatBytes(sub.used))
        } else {
            null
        }
        if (trafficText != null) {
            Text(
                text = trafficText,
                style = bodyStyle,
                color = bodyColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (sub.expire > 0) {
            val expireMs = sub.expire * 1000L
            val daysLeft = ceil((expireMs - System.currentTimeMillis()) / 86400000.0).toInt()
            val dateStr = dateFmt.format(Date(expireMs))
            if (daysLeft >= 0) {
                Text(
                    text = stringResource(R.string.sub_info_expires, dateStr, daysLeft),
                    style = bodyStyle,
                    color = when {
                        onHero && daysLeft <= 3 -> RoseSoft
                        onHero -> Color.White.copy(alpha = 0.85f)
                        daysLeft <= 3 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = stringResource(R.string.sub_info_expired),
                    style = bodyStyle,
                    color = if (onHero) RoseSoft else MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
