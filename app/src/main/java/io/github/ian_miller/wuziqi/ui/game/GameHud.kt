package io.github.ian_miller.wuziqi.ui.game

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import io.github.ian_miller.wuziqi.domain.model.GameMode
import io.github.ian_miller.wuziqi.ui.game.GameResult
import io.github.ian_miller.wuziqi.domain.model.PieceColor
import io.github.ian_miller.wuziqi.ui.theme.LocalStrings

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AnimatedStatusText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelSmall,
    color: Color = MaterialTheme.colorScheme.secondary
) {
    // 移除 animateContentSize 以避免与外层 HUD 尺寸动画冲突
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = text,
            transitionSpec = {
                // 行业标准优化：
                // 1. 移除 expand/shrinkHorizontally，避免文字变形和布局挤压。
                // 2. 使用纯 Fade + Scale，配合 SizeTransform 实现平滑胶囊伸缩。
                // 3. 关键：fadeIn 稍微延迟，等容器尺寸变大后再显示文字，防止溢出。
                (fadeIn(animationSpec = tween(200, delayMillis = 50)) +
                        scaleIn(initialScale = 0.9f, animationSpec = tween(200, delayMillis = 50)))
                    .togetherWith(
                        fadeOut(animationSpec = tween(150)) + 
                        scaleOut(targetScale = 0.9f, animationSpec = tween(150))
                    )
                    .using(
                        // 禁用裁剪，允许文字在过渡期间短暂溢出（反正会被 fadeOut/In 掩盖）
                        // 确保动画曲线平滑
                        SizeTransform(clip = false)
                    )
            },
            label = "StatusTextAnimation"
        ) { targetText ->
            Text(
                text = targetText,
                style = style,
                color = color,
                maxLines = 1, // Keep maxLines 1 to force single line
                // Remove ellipsis to allow container to grow if needed, or if clipped, at least not adding "..." prematurely
                overflow = TextOverflow.Clip // or Visible, to avoid premature ellipsis if constraints are tight during anim
            )
        }
    }
}

@Composable
fun SinglePlayerGameHud(
    isBlack: Boolean,
    isPlayer: Boolean,
    isActive: Boolean,
    isWinner: Boolean,
    gameStatus: GameStatus,
    modifier: Modifier = Modifier,
    rotate180: Boolean = false,
    isDraw: Boolean = false,
    showStartSelection: Boolean = false, // Show "Select Me" state
    playerName: String? = null,
    onSelect: (() -> Unit)? = null, // Select action
    aiProgress: Float = 0f,
    onPlayVictorySound: (() -> Unit)? = null // New callback
) {
    val s = LocalStrings.current
    // 旋转边框动画
    val infiniteTransition = rememberInfiniteTransition(label = "BorderRotation")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Angle"
    )

    // 最外层容器：解耦了背景光圈和前景内容的尺寸限制
    Box(
        modifier = modifier.rotate(if (rotate180) 180f else 0f),
        contentAlignment = Alignment.Center
    ) {
        // 1. 背景层：旋转光圈 (仅在 Active 时显示)
        // 使用 matchParentSize 随内容伸缩，并使用 .clip(CircleShape) 仅裁剪这一层的绘制范围，
        // 而不裁剪整个父容器，从而允许前景文字在 transition 期间短暂溢出而不被切断。
        if (isActive && gameStatus == GameStatus.PLAYING) {
             androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize().clip(CircleShape)) {
                  val maxDim = maxOf(size.width, size.height) * 1.5f
                  rotate(angle) {
                        drawRect(
                            brush = Brush.sweepGradient(
                                listOf(
                                     Color(0xFF00E5FF).copy(alpha = 0.0f),
                                     Color(0xFF00E5FF).copy(alpha = 1.0f),
                                     Color(0xFF00E5FF).copy(alpha = 0.0f),
                                )
                            ),
                            topLeft = Offset((size.width - maxDim) / 2, (size.height - maxDim) / 2),
                            size = androidx.compose.ui.geometry.Size(maxDim, maxDim)
                        )
                  }
             }
        }
        
        // 2. 前景层：内容容器 (替代 Surface)
        // 使用 Box + background + shadow(clip=false)，避免容器裁剪内部正在缩放的文字
        Box(
            modifier = Modifier
                .padding(3.dp) // 预留光圈边框
                .shadow(elevation = 8.dp, shape = CircleShape, clip = false) 
                .background(
                    color = if (showStartSelection) MaterialTheme.colorScheme.primaryContainer 
                            else MaterialTheme.colorScheme.surface.copy(alpha = 1f),
                    shape = CircleShape
                )
                // 处理点击 (等同于 Surface 的 onClick)
                // 修复：添加 clip(CircleShape) 以限制波纹(Ripple)的范围，防止其扩散为矩形。
                // 仅在交互模式下应用裁剪，避免影响游戏进行时的文字缩放动画。
                .then(if (showStartSelection && onSelect != null) Modifier.clip(CircleShape).clickable { onSelect.invoke() } else Modifier)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (showStartSelection) {
                    // Selection Mode Layout
                    // Reusing PlayerAvatar for layout consistency (size: 42.dp)
                    PlayerAvatar(
                        isBlack = true, // Force black style for "First" visual
                        isPlayer = isPlayer, // Use actual player type (AI or Human)
                        isActive = false, 
                        isWinner = false,
                        progress = 0f,
                        showLabel = false 
                    )
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column(horizontalAlignment = Alignment.Start) {
                        if (isPlayer && playerName != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = playerName,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color(0xFF1976D2), // Distinct Blue color for name
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                                Text(
                                    text = s.xFirstSuffix,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        } else {
                            Text(
                                text = if (isPlayer) s.playerFirstBlack else s.aiFirstBlack,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Text(
                            text = s.clickToStart,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    // Normal HUD Layout
                    PlayerAvatar(
                        isBlack = isBlack,
                        isPlayer = isPlayer,
                        isActive = isActive,
                        isWinner = isWinner,
                        progress = if (!isPlayer) aiProgress else 0f,
                        showLabel = false 
                    )
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column(horizontalAlignment = Alignment.Start) {
                        // 1. Label
                        Text(
                            text = if (isBlack) s.blackFirst else s.whiteSecond,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        if (gameStatus == GameStatus.PLAYING) {
                            // 关键修正：确保 AnimatedStatusText 在 if/else 切换时保持同一个 Composable 实例
                            val statusText = if (isActive) {
                                if (isPlayer) {
                                    if (playerName != null) s.playerTurnFmt(playerName) else s.yourTurn
                                } else s.aiThinking
                            } else {
                                s.waitingOpponent
                            }
                            
                            val statusColor = if (isActive) {
                                Color(0xFF2E7D32)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            }

                            AnimatedStatusText(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor
                            )
                        } else if (isWinner) {
                            AnimatedStatusText(
                                text = s.win, 
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFD32F2F) // Red
                            )
                        } else {
                            // Default placeholder
                        }
                    }
                }
            }
        }

        // 3. 胜利/平局图章层
        if (isWinner) {
            VictoryStamp(
                modifier = Modifier.align(Alignment.CenterStart),
                onPlaySound = { onPlayVictorySound?.invoke() }
            )
        } else if (isDraw && gameStatus == GameStatus.FINISHED) {
            DrawStamp(
                modifier = Modifier.align(Alignment.CenterStart),
                onPlaySound = { onPlayVictorySound?.invoke() }
            )
        }
    }
}

@Composable
fun VictoryStamp(
    modifier: Modifier = Modifier,
    onPlaySound: () -> Unit = {}
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
        onPlaySound()
    }
    
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 3f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow),
        label = "StampScale"
    )
    
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(300),
        label = "StampAlpha"
    )

    // Random rotation and position for the "stamped" look
    // Range: -45 to -25 degrees (Right side tilting up more)
    val rotation by remember { mutableStateOf((-45..-25).random().toFloat()) }
    // Offset range modified to shifting left, potentially outside capsule.
    // CenterStart is 0. Moving left means negative X.
    // Range: -20.dp to 10.dp relative to start edge
    val offsetX by remember { mutableStateOf((-20..10).random().dp) } 
    // Moving up: more negative Y
    val offsetY by remember { mutableStateOf((-25..-10).random().dp) }

    Box(
        modifier = modifier
            .offset(x = offsetX, y = offsetY)
            .rotate(rotation)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .border(4.dp, Color(0xFFD32F2F), CircleShape) // Red circle border
            .padding(vertical = 4.dp, horizontal = 8.dp)
            .zIndex(10f) // Ensure on top
    ) {
         Text(
            text = LocalStrings.current.victoryStamp,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                letterSpacing = 2.sp
            ),
            color = Color(0xFFD32F2F) // Red ink color
        )
    }
}

@Composable
fun DrawStamp(
    modifier: Modifier = Modifier,
    onPlaySound: () -> Unit = {}
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
        onPlaySound()
    }

    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 3f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow),
        label = "DrawStampScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(300),
        label = "DrawStampAlpha"
    )
    val rotation by remember { mutableStateOf((-15..15).random().toFloat()) }
    val offsetX by remember { mutableStateOf((-20..10).random().dp) }
    val offsetY by remember { mutableStateOf((-25..-10).random().dp) }

    Box(
        modifier = modifier
            .offset(x = offsetX, y = offsetY)
            .rotate(rotation)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .border(4.dp, Color(0xFFFFB300), CircleShape) // 琥珀色边框
            .padding(vertical = 4.dp, horizontal = 8.dp)
            .zIndex(10f)
    ) {
        Text(
            text = LocalStrings.current.drawStamp,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                letterSpacing = 2.sp
            ),
            color = Color(0xFFFFB300)
        )
    }
}

@Composable
fun PlayerAvatar(
    isBlack: Boolean,
    isPlayer: Boolean,
    isActive: Boolean,
    isWinner: Boolean,
    progress: Float = 0f,
    showLabel: Boolean = true
) {
    val borderColor = if (isWinner) Color.Yellow else if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent
    val borderWidth = if (isActive || isWinner) 3.dp else 0.dp
    
    // 呼吸动画
    val transition = rememberInfiniteTransition(label = "ActiveGlow")
    val alpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Alpha"
    )
    val glowColor = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = alpha) else Color.Transparent
    val targetProgress = progress.coerceIn(0f, 1f)

    // 软式进度：融合“追踪 + 惯性”，进度上报不均匀时避免卡顿感
    var displayedProgress by remember { mutableStateOf(0f) }
    var lastReported by remember { mutableStateOf(0f) }
    var emaSpeed by remember { mutableStateOf(0f) }
    val isShowingProgress = !isPlayer && (isActive || targetProgress > 0f)

    LaunchedEffect(isShowingProgress, targetProgress) {
        if (!isShowingProgress) {
            displayedProgress = 0f
            lastReported = 0f
            emaSpeed = 0f
            return@LaunchedEffect
        }

        var lastFrame = 0L
        while (isShowingProgress) {
            withFrameNanos { now ->
                if (lastFrame == 0L) {
                    lastFrame = now
                    return@withFrameNanos
                }

                val dt = ((now - lastFrame) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                lastFrame = now

                val reportDelta = (targetProgress - lastReported).coerceAtLeast(0f)
                val observedSpeed = reportDelta / dt
                emaSpeed = emaSpeed * 0.84f + observedSpeed * 0.16f
                val predictedSpeed = if (reportDelta < 0.0015f) emaSpeed * 0.92f else emaSpeed

                val catchup = (targetProgress - displayedProgress).coerceAtLeast(0f) * 0.24f
                val inertia = predictedSpeed * dt * 0.28f

                var next = displayedProgress + catchup + inertia
                if (targetProgress < 0.999f) {
                    next = minOf(next, 0.97f)
                }
                displayedProgress = next.coerceIn(0f, 1f)
                lastReported = targetProgress
            }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .shadow(elevation = if(isActive) 8.dp else 0.dp, shape = CircleShape, ambientColor = glowColor, spotColor = glowColor)
                .border(borderWidth, borderColor, CircleShape)
                .background(if (isBlack) Color.Black else Color.White, CircleShape)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isShowingProgress && displayedProgress > 0f) {
                androidx.compose.foundation.Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(1.dp)
                ) {
                    drawArc(
                        color = Color(0xFF00E5FF).copy(alpha = 0.32f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = Color(0xFF00E5FF),
                        startAngle = -90f,
                        sweepAngle = 360f * displayedProgress,
                        useCenter = false,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            Icon(
                imageVector = if (isPlayer) Icons.Default.Person else Icons.Default.Computer,
                contentDescription = null,
                tint = if (isBlack) Color.White else Color.Black
            )
        }
        if (showLabel) {
            val s = LocalStrings.current
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isBlack) s.colorBlack else s.colorWhite,
                style = MaterialTheme.typography.labelMedium,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PlayerHud(
    playerColor: PieceColor,
    isTurn: Boolean,
    gameStatus: GameStatus,
    winner: PieceColor?,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isTurn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isTurn) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = if (isTurn) MaterialTheme.colorScheme.primary else Color.Transparent

    Card(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .border(2.dp, borderColor, MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Player Icon/Color Indicator
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (playerColor == PieceColor.BLACK) Color.Black else Color.White)
                    .border(1.dp, Color.Gray, CircleShape)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(horizontalAlignment = Alignment.Start) {
                // Main Status Text
                val s = LocalStrings.current
                Text(
                    text = if (playerColor == PieceColor.BLACK) s.colorBlack else s.colorWhite,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor
                )
                
                // Secondary Info (Turn status or Win/Loss)
                val statusText: String = when (gameStatus) {
                    GameStatus.PLAYING -> if (isTurn) s.playerHudYourTurn else s.playerHudWaitOpponent
                    GameStatus.FINISHED -> {
                        if (winner == playerColor) s.playerHudWin
                        else if (winner == null) s.playerHudDraw
                        else s.playerHudLose
                    }
                    else -> s.playerHudReady
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun GameStatusBar(
    uiModel: GameViewModelV2.UiModel
) {
    val s = LocalStrings.current
    val statusText = when (uiModel.gameStatus) {
        GameStatus.NOT_STARTED -> s.statusSelectFirst
        GameStatus.PLAYING -> when (val result = uiModel.gameResult) {
            is GameResult.Win ->
                if (result.winner == PieceColor.BLACK) s.statusBlackWins else s.statusWhiteWins
            is GameResult.Draw -> s.statusDraw
            else -> {
                if (uiModel.isAiThinking) {
                    s.statusAiThinking
                } else if (uiModel.mode == GameMode.VS_HUMAN) {
                    if (uiModel.currentPlayer == PieceColor.BLACK) s.statusBlackTurn else s.statusWhiteTurn
                } else {
                    val colorText = if (uiModel.currentPlayer == PieceColor.BLACK) s.colorBlack else s.colorWhite
                    if (uiModel.currentPlayer == uiModel.aiPlayerColor)
                        s.statusAiTurnFmt(colorText)
                    else
                        s.statusPlayerTurnFmt(colorText)
                }
            }
        }
        GameStatus.FINISHED -> when (val result = uiModel.gameResult) {
            is GameResult.Win -> if (result.winner == PieceColor.BLACK) s.statusGameOverBlackWins else s.statusGameOverWhiteWins
            is GameResult.Draw -> s.statusGameOverDraw
            else -> s.statusGameOver
        }
    }
    
    // 动态变化的边框颜色（AI思考时呼吸灯效果）
    val transition = rememberInfiniteTransition(label = "AiThinking")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "AiThinkingAlpha"
    )
    val containerColor = if (uiModel.isAiThinking) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = alpha)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (uiModel.isAiThinking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            
            Text(
                text = statusText,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = if (uiModel.isAiThinking) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
