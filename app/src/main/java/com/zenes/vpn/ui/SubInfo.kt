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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenes.vpn.R
import com.zenes.vpn.data.Subscription
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
 */
@Composable
fun SubInfoContent(sub: Subscription, compact: Boolean, modifier: Modifier = Modifier) {
    val bodyStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (sub.total > 0) {
            WavyProgressBar(progress = { (sub.used.toDouble() / sub.total).toFloat() })
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
                    color = if (daysLeft <= 3) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = stringResource(R.string.sub_info_expired),
                    style = bodyStyle,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
