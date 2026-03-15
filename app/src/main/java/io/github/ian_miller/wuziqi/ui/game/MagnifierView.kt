package io.github.ian_miller.wuziqi.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.PieceColor
import io.github.ian_miller.wuziqi.domain.model.Piece
import kotlin.math.min

data class MagnifierState(
    val sourceCenter: Offset,
    val sourceBoardWidth: Float,
    val sourceBoardHeight: Float,
    val previewRow: Int?,
    val previewCol: Int?,
    val visible: Boolean = false
)

@Composable
fun MagnifierView(
    state: MagnifierState,
    board: Board,
    currentPlayer: PieceColor,
    lastMove: Piece?,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.clip(CircleShape).border(2.dp, Color.Black, CircleShape)) {
        val gridSize = Board.SIZE
        val canvasWidth = size.width
        val canvasHeight = size.height
        val scale = 1.2f
        val sourceRef = state.sourceCenter
        
        withTransform({
            translate(canvasWidth / 2, canvasHeight / 2)
            scale(scale, scale, Offset.Zero)
            translate(-sourceRef.x, -sourceRef.y)
        }) {
            val boardW = state.sourceBoardWidth
            val boardH = state.sourceBoardHeight
            
            val cellWidth = boardW / (gridSize + 1)
            val cellHeight = boardH / (gridSize + 1)
            val offsetX = cellWidth
            val offsetY = cellHeight

            drawRect(
                 brush = Brush.linearGradient(
                    colors = listOf(Color(0xFFE6CCB2), Color(0xFFD7B899)),
                    start = Offset(0f, 0f),
                    end = Offset(boardW, boardH)
                ),
                topLeft = Offset(0f, 0f),
                size = androidx.compose.ui.geometry.Size(boardW, boardH)
            )

            val gridColor = Color(0xFF8B7355)
            val gridStrokeWidth = 2f
            
            val gridWidth = cellWidth * (gridSize - 1)
            val gridHeight = cellHeight * (gridSize - 1)

            for (i in 0 until gridSize) {
                val x = offsetX + i * cellWidth
                val y = offsetY + i * cellHeight
                
                // Vertical lines
                drawLine(
                    color = gridColor,
                    start = Offset(x, offsetY),
                    end = Offset(x, offsetY + gridHeight),
                    strokeWidth = gridStrokeWidth
                )
                // Horizontal lines
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
                        drawCircle(color = shadowColor, center = Offset(centerX + radius * 0.15f, centerY + radius * 0.15f), radius = radius * 0.95f)
                        drawCircle(brush = mainBrush, center = Offset(centerX, centerY), radius = radius)
                        drawCircle(color = Color.White.copy(alpha = 0.3f), center = Offset(centerX - radius * 0.25f, centerY - radius * 0.25f), radius = radius * 0.3f)
                     }
                }
            }
            
            if (state.previewRow != null && state.previewCol != null) {
                 val centerX = offsetX + state.previewCol * cellWidth
                 val centerY = offsetY + state.previewRow * cellHeight
                 val radius = min(cellWidth, cellHeight) * 0.4f
                 val previewColor = currentPlayer
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
                drawCircle(color = shadowColor, center = Offset(centerX + radius * 0.15f, centerY + radius * 0.15f), radius = radius * 0.95f)
                drawCircle(brush = mainBrush, center = Offset(centerX, centerY), radius = radius)
                drawCircle(color = Color.White.copy(alpha = 0.3f), center = Offset(centerX - radius * 0.25f, centerY - radius * 0.25f), radius = radius * 0.3f)
            }
            
            if (lastMove != null) {
                 val centerX = offsetX + lastMove.col * cellWidth
                 val centerY = offsetY + lastMove.row * cellHeight
                 val radius = min(cellWidth, cellHeight) * 0.45f
                 drawCircle(color = Color.Red.copy(alpha = 0.7f), center = Offset(centerX, centerY), radius = radius, style = Stroke(width = 3f * 2 / scale))
                 drawCircle(color = Color.Yellow.copy(alpha = 0.5f), center = Offset(centerX, centerY), radius = radius * 0.7f, style = Stroke(width = 2f * 2 / scale))
            }
        }
        
        val center = Offset(canvasWidth/2, canvasHeight/2)
        val length = 20f
        drawLine(Color.Red, start = Offset(center.x - length, center.y), end = Offset(center.x + length, center.y), strokeWidth = 2f)
        drawLine(Color.Red, start = Offset(center.x, center.y - length), end = Offset(center.x, center.y + length), strokeWidth = 2f)
    }
}
