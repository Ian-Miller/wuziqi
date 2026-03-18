package io.github.ian_miller.wuziqi.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue // Added
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState

import androidx.compose.ui.draw.clip

@Composable
fun GlassyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable RowScope.() -> Unit
) {
    // 1. 胶囊按钮状态变化动画 (Disabled/Enabled)
    // 使用 animateColorAsState 替代原本的 alpha 动画，以解决颜色突变问题
    // 当 disabled 时，应该使用标准的 onSurface 12% (灰色)，而不是原颜色的淡化版本 (如淡蓝色)
    // 这避免了色相变化带来的视觉跳跃。
    
    val disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    val animatedContainerColor by animateColorAsState(
        targetValue = if (enabled) containerColor else disabledContainerColor,
        animationSpec = tween(durationMillis = 300),
        label = "ButtonContainerColor"
    )
    
    val animatedContentColor by animateColorAsState(
        targetValue = if (enabled) contentColor else disabledContentColor,
        animationSpec = tween(durationMillis = 300),
        label = "ButtonContentColor"
    )
    
    val elevation by animateDpAsState(
        targetValue = if (enabled) 6.dp else 0.dp,
        animationSpec = tween(durationMillis = 300),
        label = "ButtonElevation"
    )
    
    Surface(
        onClick = onClick,
        enabled = enabled,
        // 关键修复：添加 clip(CircleShape) 以确保波纹效果（Ripple）被限制在胶囊形状内，
        // 而不是扩散成矩形。这是 Material3 Surface 的常见行为，
        // 显式添加 clip 可以强制修正波纹范围。
        modifier = modifier.clip(CircleShape),
        shape = CircleShape,
        color = animatedContainerColor,
        contentColor = animatedContentColor,
        shadowElevation = elevation,
        tonalElevation = 0.dp // 禁用 tonalElevation 动画，防止颜色叠加导致的视觉突变
    ) {
        // 使用 Start 对齐配合 clip = false (在父级动画中) 实现 "左对齐展开" 的视觉效果
        // 但通常 Button 内容居中好看。用户特别要求 "Inside text aligned to left edge" 可能是配合动画。
        // 为通用性，我们这里保持 Center，但在动画时外部控制容器裁剪。
        // 如果要完全满足 "内容左对齐"，这里可以改为 Start。
        // 鉴于用户 explicit request: "Inside text is aligned to left edge", let's try Start or keep convenient.
        // Actually, if we Expand from Start, Center alignment will make content 'slide'.
        // Let's create a specialized 'AnimatedGlassyButton' logic or just Start here if it doesn't break other things.
        // Or simpler: The user says "Inside text is aligned to left edge...".
        // Let's change this to Arrangement.Start to see if it fits.
        // But spacing might look weird if text is short.
        // Let's stick to modifying the Animation Container in SymmetricalPlayerArea first.
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center, // Keeping Center for general aesthetics unless forced

            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            content()
        }
    }
}

@Composable
fun ControlButtons(
    undoEnabled: Boolean,
    showUndo: Boolean,
    isPlaying: Boolean,
    onUndo: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    onStats: () -> Unit
) {
    // Legacy implementation - unused in new UI
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        if (showUndo) {
            IconButton(onClick = onUndo, enabled = undoEnabled) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "撤销")
            }
        }
        // 第二个按钮：始终显示为停止，仅在游戏进行时可用
        IconButton(onClick = onStop, enabled = isPlaying) {
             Icon(Icons.Filled.Stop, contentDescription = "停止")
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Default.Settings, contentDescription = "设置")
        }
        IconButton(onClick = onStats) {
            Icon(Icons.Default.BarChart, contentDescription = "统计")
        }
    }
}
