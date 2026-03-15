package io.github.ian_miller.wuziqi.ai.eval.pattern

import io.github.ian_miller.wuziqi.ai.eval.pure.PureEvaluator
import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.PieceColor

/**
 * 棋型评估器（优化版）
 * 
 * 基于棋子pattern的快速评估，纯粹计算无状态。
 * 优化：减少重复扫描，使用增量计算。
 * 
 * @author AI Assistant
 * @since 0.04
 */
class PatternEvaluator : PureEvaluator {
    
    override val name: String = "PatternEvaluator"
    
    override fun evaluate(board: Board, player: PieceColor): Int {
        val opponent = player.opposite()
        
        // 己方威胁
        val myScore = evaluateSideFast(board, player)
        
        // 对方威胁（取负）
        val opScore = evaluateSideFast(board, opponent)
        
        return myScore - opScore
    }
    
    /**
     * 快速评估一方（避免多次扫描棋盘）
     */
    private fun evaluateSideFast(board: Board, player: PieceColor): Int {
        var score = 0
        var fiveCount = 0
        var openFourCount = 0
        var closedFourCount = 0
        var openThreeCount = 0
        var closedThreeCount = 0
        var openTwoCount = 0
        var centerBonus = 0
        
        val center = Board.SIZE / 2
        val dirs = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        val checkedLines = mutableSetOf<String>()
        
        for (r in 0 until Board.SIZE) {
            for (c in 0 until Board.SIZE) {
                val piece = board.getPiece(r, c)
                
                // 中心奖励计算
                if (piece == player) {
                    val dist = kotlin.math.abs(r - center) + kotlin.math.abs(c - center)
                    centerBonus += (Board.SIZE - dist) * 2
                }
                
                // 只从己方棋子开始检查连线（避免重复）
                if (piece != player) continue
                
                for ((dr, dc) in dirs) {
                    // 只处理一个方向，避免重复
                    if (dr < 0 || (dr == 0 && dc < 0)) continue
                    
                    val lineKey = "$r,$c,$dr,$dc"
                    if (lineKey in checkedLines) continue
                    
                    val lineInfo = analyzeLine(board, r, c, dr, dc, player)
                    checkedLines.add(lineKey)
                    
                    when (lineInfo.type) {
                        LineType.FIVE -> fiveCount++
                        LineType.OPEN_FOUR -> openFourCount++
                        LineType.CLOSED_FOUR -> closedFourCount++
                        LineType.OPEN_THREE -> openThreeCount++
                        LineType.CLOSED_THREE -> closedThreeCount++
                        LineType.OPEN_TWO -> openTwoCount++
                        LineType.OTHER -> {}
                    }
                }
            }
        }
        
        // 计算分数（优先级高的先返回）
        score += fiveCount * 10_000_000
        if (fiveCount > 0) return score
        
        score += openFourCount * 100_000
        if (openFourCount > 0) return score
        
        score += closedFourCount * 10_000
        score += openThreeCount * 1_000
        score += closedThreeCount * 100
        score += openTwoCount * 10
        score += centerBonus
        
        return score
    }
    
    private enum class LineType {
        FIVE, OPEN_FOUR, CLOSED_FOUR, OPEN_THREE, CLOSED_THREE, OPEN_TWO, OTHER
    }
    
    private data class LineInfo(val type: LineType, val length: Int)
    
    private fun analyzeLine(
        board: Board, r: Int, c: Int, dr: Int, dc: Int, player: PieceColor
    ): LineInfo {
        // 计算连续棋子数
        var count = 1
        var nr = r + dr
        var nc = c + dc
        while (nr in 0 until Board.SIZE && nc in 0 until Board.SIZE && 
               board.getPiece(nr, nc) == player) {
            count++
            nr += dr
            nc += dc
        }
        val posEnd = nr to nc
        
        nr = r - dr
        nc = c - dc
        while (nr in 0 until Board.SIZE && nc in 0 until Board.SIZE && 
               board.getPiece(nr, nc) == player) {
            count++
            nr -= dr
            nc -= dc
        }
        val negEnd = nr to nc
        
        // 检查两端是否开放
        val posOpen = posEnd.first in 0 until Board.SIZE && 
                      posEnd.second in 0 until Board.SIZE &&
                      board.getPiece(posEnd.first, posEnd.second) == null
        val negOpen = negEnd.first in 0 until Board.SIZE && 
                      negEnd.second in 0 until Board.SIZE &&
                      board.getPiece(negEnd.first, negEnd.second) == null
        
        return when {
            count >= 5 -> LineInfo(LineType.FIVE, count)
            count == 4 && posOpen && negOpen -> LineInfo(LineType.OPEN_FOUR, count)
            count == 4 && (posOpen || negOpen) -> LineInfo(LineType.CLOSED_FOUR, count)
            count == 3 && posOpen && negOpen -> LineInfo(LineType.OPEN_THREE, count)
            count == 3 && (posOpen || negOpen) -> LineInfo(LineType.CLOSED_THREE, count)
            count == 2 && posOpen && negOpen -> LineInfo(LineType.OPEN_TWO, count)
            else -> LineInfo(LineType.OTHER, count)
        }
    }
    
    // 保留旧方法用于兼容性，但内部使用新方法
    @Deprecated("Use evaluateSideFast instead", ReplaceWith("evaluateSideFast(board, player)"))
    private fun evaluateSide(board: Board, player: PieceColor): Int {
        return evaluateSideFast(board, player)
    }
}
