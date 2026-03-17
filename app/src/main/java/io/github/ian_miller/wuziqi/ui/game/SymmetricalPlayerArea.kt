package io.github.ian_miller.wuziqi.ui.game

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer // Add this import
import androidx.compose.ui.unit.dp
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.TransformOrigin // Add this import
import io.github.ian_miller.wuziqi.domain.model.GameMode
import io.github.ian_miller.wuziqi.ui.theme.LocalStrings

@Composable
fun SymmetricalPlayerArea(
    modifier: Modifier = Modifier,
    isTop: Boolean,
    isVsHuman: Boolean,
    hudContent: @Composable () -> Unit,
    controlsContent: @Composable () -> Unit
) {
    if (isVsHuman) {
        // PvP Symmetrical Layout
        // 关键修正：将 rotate(180f) 应用于 Box 而不是 Column，并且确保 graphicsLayer 不裁剪 (clip = false)。
        // 这样即使旋转，内容的阴影也能在 Layer 之外绘制，防止上方玩家区域的阴影被裁剪。
        Box(
            modifier = modifier
                .graphicsLayer {
                    rotationZ = if (isTop) 180f else 0f
                    clip = false // 显式禁用裁剪
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                 controlsContent()
                 Spacer(modifier = Modifier.height(16.dp))
                 hudContent()
            }
        }
    } else {
        // PvE Layout (Not necessarily symmetrical or rotated)
        // For PvE, Top is AI, Bottom is Player.
        // We generally don't rotate Top AI area in PvE unless requested, but here we likely just want 
        // to render HUD. The concept of "Controls" for AI is nonexistent (no buttons).
        // If it's the Top area in PvE, we might just show HUD.
        // If it's Bottom area in PvE, we show Controls (Top) -> Spacer -> Hud (Bottom) ?
        // Or Hud (Top) -> Controls (Bottom) ?
        // Usually PvE controls are at the very bottom.
        
        // Let's defer to the passed content's layout for PvE if needed, OR enforce a standard.
        // Since this component is designed to "abstract symmetry", it implies it manages the layout.
        
        // For PvE Top (AI): Controls are usually empty. Just HUD.
        // For PvE Bottom (Player): Controls (Undo/Stop/Menu) are usually Separated.
        
        // To be safe and flexible:
        if (isTop) {
            // Top PvE: Just HUD (usually).
            // ERROR FIX: Must apply modifier here to respect padding passed from parent!
            Box(modifier = modifier) {
                hudContent()
            }
        } else {
           // Bottom PvE: Controls then HUD (closest to edge) or HUD then Controls?
           // Current design in GameScreen was: Controls -> Spacer -> HUD.
           Column(
                modifier = modifier,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                 controlsContent()
                 Spacer(modifier = Modifier.height(16.dp))
                 hudContent()
            }
        }
    }
}

// Reusable Controls Row for Symmetry
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun GameControlsRow(
    gameStatus: GameStatus,
    isVsHuman: Boolean,
    isTurn: Boolean, // relevant for PvP
    undoEnabled: Boolean,
    showAssistButton: Boolean,
    lastMoveExists: Boolean,
    isAiThinking: Boolean,
    onUndo: () -> Unit,
    onAssist: () -> Unit,
    onMenu: (() -> Unit)?,
    debugBoardCopyVisible: Boolean = false,
    debugBoardCopied: Boolean = false,
    onCopyBoard: (() -> Unit)? = null,
    onStartAiFirst: (() -> Unit)? = null,
    onStartPlayerFirst: (() -> Unit)? = null
) {
    val s = LocalStrings.current
    // 维持固定高度以防止垂直跳动和阴影裁剪
    // 方案修正：
    // 1. Row 保持固定高度 72dp (48dp 按钮 + 24dp 阴影预留)。
    // 2. 移除 Row 的 padding，改为在 AnimatedControlItem 内部添加 padding。
    //    这里的逻辑是：让每个 Item 的"自身尺寸"包含阴影区域。
    //    这样 AnimatedVisibility 在计算布局和裁剪边界时，会把 padding 区域视为内容的一部分，
    //    从而不会裁掉其中的阴影。同时因为 Row 高度固定匹配 Item 高度，不会发生跳动。
    Row(
        modifier = Modifier.height(72.dp), 
        horizontalArrangement = Arrangement.Center, 
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 动画规格优化：
        // 1. clip = false: 关键修正！允许按钮在动画过程中超出缩小的容器边界绘制，
        //    防止 Rect 容器切断 Rounded Button 的圆角，解决"Cut"问题。
        //    配合 fadeOut，按钮会在"重影/Ghost"状态下自然消失，保留完整形状。
        val enterSpec = fadeIn(tween(300)) + 
                        expandHorizontally(
                            expandFrom = Alignment.CenterHorizontally, 
                            animationSpec = tween(300, easing = LinearOutSlowInEasing),
                            clip = false
                        )
        
        val exitSpec = fadeOut(tween(300)) + 
                       shrinkHorizontally(
                           shrinkTowards = Alignment.CenterHorizontally, 
                           animationSpec = tween(300, easing = FastOutSlowInEasing),
                           clip = false
                       )

        // 辅助函数：为每个按钮添加独立的 Padding，这样当按钮消失时，Padding 也会随之平滑消失，避免位置突变
        // 替代了 Row 的 horizontalArrangement = Arrangement.spacedBy(16.dp)
        @Composable
        fun AnimatedControlItem(
            visible: Boolean,
            content: @Composable () -> Unit
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = enterSpec,
                exit = exitSpec
            ) {
                // 使用默认 Alignment (Center) 以保持对称动画的视觉平衡
                // 关键修正：将 Vertical Padding (12dp) 移入 Item 内部。
                // 这强制 AnimatedVisibility 将这 12dp x 2 的空间视为"内容"的一部分。
                // 即使系统对 Layout Node 进行裁剪，由于阴影位于这个逻辑边界内（实际上是在 Padding 区域绘制），
                // 它是安全的。这是处理动画组件阴影裁剪最稳健的方式：Expand the bounds to include the shadow.
                Box(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
                ) {
                    content()
                }
            }
        }

        // Undo
        AnimatedControlItem(
            visible = gameStatus == GameStatus.PLAYING && undoEnabled && (!isVsHuman || isTurn)
        ) {
             GlassyButton(
                onClick = onUndo,
                enabled = lastMoveExists && !isAiThinking,
                containerColor = MaterialTheme.colorScheme.secondaryContainer
             ) {
                 Icon(Icons.Filled.Undo, null)
                 if (!isVsHuman) {
                     Spacer(Modifier.width(8.dp))
                     Text(s.undo, maxLines = 1)
                 }
             }
        }
             
        // Assist (PvP Only, if Turn)
        AnimatedControlItem(
             visible = gameStatus == GameStatus.PLAYING && isVsHuman && showAssistButton && isTurn
        ) {
             GlassyButton(
                onClick = onAssist,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
             ) {
                 Icon(Icons.Filled.Info, null)
                 Spacer(Modifier.width(8.dp))
                 Text(s.hint, maxLines = 1)
             }
        }

        AnimatedControlItem(
            visible = debugBoardCopyVisible && onCopyBoard != null
        ) {
            val copyBoardAction = onCopyBoard ?: return@AnimatedControlItem
            GlassyButton(
                onClick = copyBoardAction,
                containerColor = if (debugBoardCopied) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                }
            ) {
                Icon(if (debugBoardCopied) Icons.Filled.Check else Icons.Filled.ContentCopy, null)
                Spacer(Modifier.width(8.dp))
                Text(if (debugBoardCopied) s.copied else s.copyBoard, maxLines = 1)
            }
        }
        
        // Start Buttons (PvE Only here - PvP uses HUD selection)
        AnimatedControlItem(
            visible = (gameStatus == GameStatus.NOT_STARTED || gameStatus == GameStatus.FINISHED) && !isVsHuman && onStartAiFirst != null && onStartPlayerFirst != null
        ) {
             Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                 GlassyButton(onClick = onStartAiFirst!!, containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                     Icon(Icons.Filled.Computer, null); Spacer(Modifier.width(4.dp)); Text(s.aiGoFirst, maxLines = 1)
                 }
                 GlassyButton(onClick = onStartPlayerFirst!!, containerColor = MaterialTheme.colorScheme.primaryContainer) {
                     Icon(Icons.Filled.Person, null); Spacer(Modifier.width(4.dp)); Text(s.iGoFirst, maxLines = 1)
                 }
             }
        }
        
        // Menu Button
        if (onMenu != null) {
            // Menu 始终存在，但也需要 spacing 保持一致性
            Box(modifier = Modifier.padding(horizontal = 8.dp)) {
                GlassyButton(
                    onClick = onMenu,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Icon(Icons.Filled.Settings, "设置")
                }
            }
        }
    }
}

