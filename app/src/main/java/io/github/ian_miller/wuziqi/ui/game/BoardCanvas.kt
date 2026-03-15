package io.github.ian_miller.wuziqi.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.PieceColor
import io.github.ian_miller.wuziqi.domain.model.Piece
import kotlin.math.min

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.getValue

@Composable
fun BoardCanvas(
    board: Board,
    enabled: Boolean,
    onPlacePiece: (Int, Int) -> Unit,
    onUpdateMagnifier: (MagnifierState?) -> Unit,
    modifier: Modifier = Modifier,
    gameStatus: GameStatus,
    isAiThinking: Boolean,
    lastMove: Piece? = null,
    currentPlayer: PieceColor? = null,
    assistMove: Pair<Int, Int>? = null,
    showAssistHint: Boolean = false,
    aiPreviewMove: Pair<Int, Int>? = null  // AI 当前最优走法预览
) {
    val gridSize = Board.SIZE
     // 2. 棋盘边框颜色随游戏状态变化的动画
    val targetBorderColor = when (gameStatus) {
        GameStatus.NOT_STARTED -> Color.Gray.copy(alpha = 0.5f)
        GameStatus.PLAYING -> if (isAiThinking) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
        GameStatus.FINISHED -> Color(0xFFFFD700) // Gold
    }
    
    val animatedBorderColor by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = tween(500),
        label = "BoardBorderColor"
    )

    // 3. 棋盘整体状态变化动画 (AI 回合时的变暗效果)
    // 移除边框宽度的动态变化，保持恒定 4dp，仅通过颜色区分状态，避免视觉跳动
    val targetBorderWidth = 4.dp
    val animatedBorderWidth = targetBorderWidth // No animation needed for constant width
    
    val targetOverlayAlpha = if (isAiThinking || !enabled) 0.05f else 0.0f
    val animatedOverlayAlpha by animateFloatAsState(
        targetValue = targetOverlayAlpha,
        animationSpec = tween(300),
        label = "BoardOverlayAlpha"
    )
    
    val targetBackgroundAlpha = if (enabled) 0.2f else 0.1f
    val animatedBackgroundAlpha by animateFloatAsState(
        targetValue = targetBackgroundAlpha,
        animationSpec = tween(300),
        label = "BoardBgAlpha"
    )

    val boardBackground = Color.LightGray.copy(alpha = animatedBackgroundAlpha)

    var previewPosition by remember { mutableStateOf<Offset?>(null) }
    var previewRow by remember { mutableStateOf<Int?>(null) }
    var previewCol by remember { mutableStateOf<Int?>(null) }
    
    // AI 预览走法闪烁动画（在 Canvas 外创建）
    val aiPreviewInfiniteTransition = rememberInfiniteTransition(label = "ai_preview_pulse")
    val aiPreviewAlpha by aiPreviewInfiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ai_preview_alpha"
    )
    
    Box(
        modifier = modifier
            .border(
                width = animatedBorderWidth,
                color = animatedBorderColor,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            )
            .padding(4.dp)
            .background(boardBackground)
            // 使用 drawWithContent 或额外的 Box 层覆盖蒙版，而不是 run { background } 这是 Compose 的最佳实践
    ) {
        // AI 思考时的蒙版层
        if (animatedOverlayAlpha > 0f) {
             Box(
                 modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = animatedOverlayAlpha))
             )
        }
        
        Canvas(modifier = Modifier
            .fillMaxSize()
            .pointerInput(enabled, board) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var offset = down.position
                    val layoutSize = size
                    
                    val cellWidth = layoutSize.width.toFloat() / (gridSize + 1)
                    val cellHeight = layoutSize.height.toFloat() / (gridSize + 1)

                    // 超出棋盘边界这个阈値就取消预览（防止边缘落子加载的进渐式取消）
                    val cancelThreshold = cellWidth * 1.5f
                    val boardLeft = cellWidth
                    val boardRight = gridSize.toFloat() * cellWidth
                    val boardTop = cellHeight
                    val boardBottom = gridSize.toFloat() * cellHeight

                    fun Offset.isWithinBoardThreshold() =
                        x >= boardLeft - cancelThreshold &&
                        x <= boardRight + cancelThreshold &&
                        y >= boardTop - cancelThreshold &&
                        y <= boardBottom + cancelThreshold

                    var row = (offset.y / cellHeight - 1).let { kotlin.math.round(it) }.toInt()
                    var col = (offset.x / cellWidth - 1).let { kotlin.math.round(it) }.toInt()

                    row = row.coerceIn(0, gridSize - 1)
                    col = col.coerceIn(0, gridSize - 1)

                    if (offset.isWithinBoardThreshold() && board.getPiece(row, col) == null) {
                        previewRow = row
                        previewCol = col
                        previewPosition = offset
                    }

                    onUpdateMagnifier(
                        MagnifierState(
                            sourceCenter = offset,
                            sourceBoardWidth = layoutSize.width.toFloat(),
                            sourceBoardHeight = layoutSize.height.toFloat(),
                            previewRow = previewRow,
                            previewCol = previewCol,
                            visible = true
                        )
                    )
                    
                    var isDragging = true
                    while (isDragging) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull()
                        
                        if (change != null && change.pressed) {
                            offset = change.position

                            if (!offset.isWithinBoardThreshold()) {
                                // 超出棋盘边界一定距离，取消预览
                                previewRow = null
                                previewCol = null
                                previewPosition = null
                            } else {
                                val newRow = (offset.y / cellHeight - 1).let { kotlin.math.round(it) }.toInt().coerceIn(0, gridSize - 1)
                                val newCol = (offset.x / cellWidth - 1).let { kotlin.math.round(it) }.toInt().coerceIn(0, gridSize - 1)

                                if (board.getPiece(newRow, newCol) == null) {
                                    previewRow = newRow
                                    previewCol = newCol
                                    previewPosition = offset
                                } else {
                                    previewRow = null
                                    previewCol = null
                                    previewPosition = null
                                }
                            }
                            
                            onUpdateMagnifier(
                                MagnifierState(
                                    sourceCenter = offset,
                                    sourceBoardWidth = layoutSize.width.toFloat(),
                                    sourceBoardHeight = layoutSize.height.toFloat(),
                                    previewRow = previewRow,
                                    previewCol = previewCol,
                                    visible = true
                                )
                            )
                        } else {
                            isDragging = false
                        }
                    }
                    
                    if (previewRow != null && previewCol != null) {
                        onPlacePiece(previewRow!!, previewCol!!)
                    }
                    
                    previewRow = null
                    previewCol = null
                    previewPosition = null
                    onUpdateMagnifier(null)
                }
            }
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            
            val cellWidth = canvasWidth / (gridSize + 1)
            val cellHeight = canvasHeight / (gridSize + 1)
            val offsetX = cellWidth
            val offsetY = cellHeight

            val backgroundBrush = Brush.linearGradient(
                colors = listOf(Color(0xFFE6CCB2), Color(0xFFD7B899)),
                start = Offset(0f, 0f),
                end = Offset(canvasWidth, canvasHeight)
            )
            drawRect(brush = backgroundBrush)

            val gridColor = if (enabled) Color(0xFF8B7355) else Color(0xFF8B7355).copy(alpha = 0.5f)
            val gridStrokeWidth = 2f
            
            val gridWidth = cellWidth * (gridSize - 1)
            val gridHeight = cellHeight * (gridSize - 1)

            for (i in 0 until gridSize) {
                // Vertical
                val x = offsetX + i * cellWidth
                drawLine(
                    color = gridColor,
                    start = Offset(x, offsetY),
                    end = Offset(x, offsetY + gridHeight),
                    strokeWidth = gridStrokeWidth
                )
                // Horizontal
                val y = offsetY + i * cellHeight
                drawLine(
                    color = gridColor,
                    start = Offset(offsetX, y),
                    end = Offset(offsetX + gridWidth, y),
                    strokeWidth = gridStrokeWidth
                )
            }

            // 绘制星位 (Hoshi)
            if (gridSize == 15) {
                val starPoints = listOf(
                    Pair(3, 3), Pair(3, 11),
                    Pair(7, 7),
                    Pair(11, 3), Pair(11, 11)
                )
                val starRadius = min(cellWidth, cellHeight) * 0.1f
                
                starPoints.forEach { (r, c) ->
                    val x = offsetX + c * cellWidth
                    val y = offsetY + r * cellHeight
                    drawCircle(
                        color = gridColor,
                        center = Offset(x, y),
                        radius = starRadius
                    )
                }
            }

            for (row in 0 until gridSize) {
                for (col in 0 until gridSize) {
                    val piece = board.getPiece(row, col)
                    if (piece != null) {
                        val centerX = offsetX + col * cellWidth
                        val centerY = offsetY + row * cellHeight
                        val radius = min(cellWidth, cellHeight) * 0.4f
                        val mainBrush: Brush
                        val shadowColor: Color
                        when (piece) {
                            PieceColor.BLACK -> {
                                mainBrush = Brush.radialGradient(
                                    colors = listOf(Color.Black, Color.DarkGray),
                                    center = Offset(centerX, centerY),
                                    radius = radius
                                )
                                shadowColor = Color.Black.copy(alpha = 0.6f)
                            }
                            PieceColor.WHITE -> {
                                mainBrush = Brush.radialGradient(
                                    colors = listOf(Color.White, Color.LightGray),
                                    center = Offset(centerX, centerY),
                                    radius = radius
                                )
                                shadowColor = Color.Gray.copy(alpha = 0.4f)
                            }
                        }
                        drawCircle(
                            color = shadowColor,
                            center = Offset(centerX + radius * 0.15f, centerY + radius * 0.15f),
                            radius = radius * 0.95f
                        )
                        drawCircle(
                            brush = mainBrush,
                            center = Offset(centerX, centerY),
                            radius = radius
                        )
                        val highlightRadius = radius * 0.3f
                        drawCircle(
                            color = Color.White.copy(alpha = 0.3f),
                            center = Offset(centerX - radius * 0.25f, centerY - radius * 0.25f),
                            radius = highlightRadius
                        )
                    }
                }
            }
            
            if (previewRow != null && previewCol != null) {
                val centerX = offsetX + previewCol!! * cellWidth
                val centerY = offsetY + previewRow!! * cellHeight
                val radius = min(cellWidth, cellHeight) * 0.4f
                val previewColor = currentPlayer ?: PieceColor.BLACK
                val mainBrush: Brush
                val shadowColor: Color
                when (previewColor) {
                    PieceColor.BLACK -> {
                        mainBrush = Brush.radialGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.6f), Color.DarkGray.copy(alpha = 0.6f)),
                            center = Offset(centerX, centerY),
                            radius = radius
                        )
                        shadowColor = Color.Black.copy(alpha = 0.4f)
                    }
                    PieceColor.WHITE -> {
                        mainBrush = Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.6f), Color.LightGray.copy(alpha = 0.6f)),
                            center = Offset(centerX, centerY),
                            radius = radius
                        )
                        shadowColor = Color.Gray.copy(alpha = 0.3f)
                    }
                }
                drawCircle(
                    color = shadowColor,
                    center = Offset(centerX + radius * 0.15f, centerY + radius * 0.15f),
                    radius = radius * 0.95f
                )
                drawCircle(
                    brush = mainBrush,
                    center = Offset(centerX, centerY),
                    radius = radius
                )
                val highlightRadius = radius * 0.3f
                drawCircle(
                    color = Color.White.copy(alpha = 0.3f),
                    center = Offset(centerX - radius * 0.25f, centerY - radius * 0.25f),
                    radius = highlightRadius
                )
            }

            if (lastMove != null) {
                val centerX = offsetX + lastMove.col * cellWidth
                val centerY = offsetY + lastMove.row * cellHeight
                val radius = min(cellWidth, cellHeight) * 0.45f
                drawCircle(
                    color = Color.Red.copy(alpha = 0.7f),
                    center = Offset(centerX, centerY),
                    radius = radius,
                    style = Stroke(width = 3f)
                )
                drawCircle(
                    color = Color.Yellow.copy(alpha = 0.5f),
                    center = Offset(centerX, centerY),
                    radius = radius * 0.7f,
                    style = Stroke(width = 2f)
                )
            }
            
            // 绘制 AI 辅助提示
            if (showAssistHint && assistMove != null) {
                val (row, col) = assistMove
                val centerX = offsetX + col * cellWidth
                val centerY = offsetY + row * cellHeight
                val radius = min(cellWidth, cellHeight) * 0.4f
                
                drawCircle(
                     color = Color.Green.copy(alpha = 0.8f),
                     center = Offset(centerX, centerY),
                     radius = radius,
                     style = Stroke(width = 4f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
                )
                drawCircle(
                    color = Color.Green,
                    center = Offset(centerX, centerY),
                    radius = radius * 0.3f
                )
            }
            
            // 绘制 AI 思考中的最优走法预览（半透明棋子）
            if (isAiThinking && aiPreviewMove != null) {
                val (row, col) = aiPreviewMove
                // 检查该位置是否为空
                if (board.getPiece(row, col) == null) {
                    val centerX = offsetX + col * cellWidth
                    val centerY = offsetY + row * cellHeight
                    val radius = min(cellWidth, cellHeight) * 0.4f
                    
                    // AI 思考中的棋子颜色（当前玩家颜色）
                    val previewColor = currentPlayer ?: PieceColor.BLACK
                    
                    // 使用外部定义的动画值 aiPreviewAlpha
                    val mainBrush: Brush
                    val shadowColor: Color
                    when (previewColor) {
                        PieceColor.BLACK -> {
                            mainBrush = Brush.radialGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = aiPreviewAlpha),
                                    Color.DarkGray.copy(alpha = aiPreviewAlpha * 0.7f)
                                ),
                                center = Offset(centerX, centerY),
                                radius = radius
                            )
                            shadowColor = Color.Black.copy(alpha = aiPreviewAlpha * 0.5f)
                        }
                        PieceColor.WHITE -> {
                            mainBrush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = aiPreviewAlpha),
                                    Color.LightGray.copy(alpha = aiPreviewAlpha * 0.7f)
                                ),
                                center = Offset(centerX, centerY),
                                radius = radius
                            )
                            shadowColor = Color.Gray.copy(alpha = aiPreviewAlpha * 0.4f)
                        }
                    }
                    
                    // 阴影
                    drawCircle(
                        color = shadowColor,
                        center = Offset(centerX + radius * 0.15f, centerY + radius * 0.15f),
                        radius = radius * 0.95f
                    )
                    // 棋子主体
                    drawCircle(
                        brush = mainBrush,
                        center = Offset(centerX, centerY),
                        radius = radius
                    )
                    // 高光
                    drawCircle(
                        color = Color.White.copy(alpha = aiPreviewAlpha * 0.4f),
                        center = Offset(centerX - radius * 0.25f, centerY - radius * 0.25f),
                        radius = radius * 0.3f
                    )
                    
                    // 添加 "AI" 标识圆圈
                    drawCircle(
                        color = Color.Cyan.copy(alpha = 0.9f),
                        center = Offset(centerX, centerY),
                        radius = radius * 0.2f
                    )
                }
            }
        }
    }
}
