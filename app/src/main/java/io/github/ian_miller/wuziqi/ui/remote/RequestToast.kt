package io.github.ian_miller.wuziqi.ui.remote

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 请求横幅：顶部滑入，显示消息 + ✓/✗ 操作按钮，超时自动消失。
 * 样式参照难度 Toast（深棕底色 + 金色边框 + 金色文字）。
 *
 * @param visible       是否显示
 * @param message       提示文字
 * @param onAccept      点击 ✓（接受）
 * @param onReject      点击 ✗（拒绝）
 * @param autoDismissMs 自动消失时长（毫秒），<=0 则不自动消失
 * @param onTimeout     自动消失时的回调（默认 = onReject）
 */
@Composable
fun RequestToast(
    visible: Boolean,
    message: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
    autoDismissMs: Long = RemoteTiming.TOAST_AUTO_DISMISS_MS,
    onTimeout: () -> Unit = onReject,
) {
    // 自动消失计时（key = message 确保内容变化时重置计时）
    if (visible && autoDismissMs > 0) {
        LaunchedEffect(message) {
            delay(autoDismissMs)
            onTimeout()
        }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { -it }, animationSpec = tween(300)) +
                fadeIn(animationSpec = tween(300)),
        exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(300)) +
                fadeOut(animationSpec = tween(300)),
    ) {
        // 外层 Surface：深棕底色 + 胶囊形状 + 金色边框（与 DifficultyToast 保持一致）
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF5D4037),
            shadowElevation = 8.dp,
            border = BorderStroke(1.5.dp, Color(0xFFFFE082).copy(alpha = 0.6f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 装饰小圆点（仿 DifficultyToast 的棋子装饰）
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(Color(0xFFFFE082).copy(alpha = 0.85f), CircleShape),
                )
                // 消息文字
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFFFFE082),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // ✓ 接受按钮（绿色胶囊）
                Surface(
                    onClick = onAccept,
                    modifier = Modifier.size(42.dp),
                    shape = CircleShape,
                    color = Color(0xFF1B5E20),
                    shadowElevation = 4.dp,
                    border = BorderStroke(1.dp, Color(0xFF69F0AE).copy(alpha = 0.5f)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color(0xFF69F0AE),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                // ✗ 拒绝按钮（红色胶囊）
                Surface(
                    onClick = onReject,
                    modifier = Modifier.size(42.dp),
                    shape = CircleShape,
                    color = Color(0xFFB71C1C),
                    shadowElevation = 4.dp,
                    border = BorderStroke(1.dp, Color(0xFFFF8A80).copy(alpha = 0.5f)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}
