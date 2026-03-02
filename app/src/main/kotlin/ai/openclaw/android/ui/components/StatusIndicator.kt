package ai.openclaw.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.openclaw.android.ui.theme.LocalStatusColors

enum class Status { Connected, Warning, Error, Offline }

@Composable
fun StatusIndicator(
    status: Status,
    size: Dp = 10.dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalStatusColors.current
    val color = when (status) {
        Status.Connected -> colors.connected
        Status.Warning -> colors.warning
        Status.Error -> colors.error
        Status.Offline -> colors.offline
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
fun StatusDot(
    connected: Boolean,
    size: Dp = 10.dp,
    modifier: Modifier = Modifier,
) {
    StatusIndicator(
        status = if (connected) Status.Connected else Status.Error,
        size = size,
        modifier = modifier,
    )
}
