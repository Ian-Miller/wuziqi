package io.github.ian_miller.wuziqi.ai.eval.strategic

import io.github.ian_miller.wuziqi.ai.eval.pure.PureEvaluator
import io.github.ian_miller.wuziqi.ai.ThreatDetector
import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.PieceColor

/**
 * 战略评估器（优化版）
 * 
 * 综合棋型、位置、威胁的评估器，适用于大师模式。
 * 优化：合并扫描，减少重复计算。
 * 
 * @author AI Assistant
 * @since 0.04
 */
class StrategicEvaluator : PureEvaluator {
    
    override val name: String = "StrategicEvaluator"
    
    override fun evaluate(board: Board, player: PieceColor): Int {
        val opponent = player.opposite()
        
        // 1. 立即胜负判断（快速路径）
        val winScore = evaluateWinningFast(board, player)
        if (winScore != 0) return winScore
        
        // 2. 单次扫描获取所有信息
        val myInfo = scanBoard(board, player)
        val opInfo = scanBoard(board, opponent)
        
        // 3. 战术评分
        val threatScore = calculateThreatScore(myInfo) - calculateThreatScore(opInfo) * 12 / 10
        
        // 4. 位置评分
        val positionScore = (myInfo.centerBonus - opInfo.centerBonus) * 5 +
                           (myInfo.connectivity - opInfo.connectivity) * 3
        
        return threatScore + positionScore
    }
    
    /**
     * 快速胜负判断
     */
    private fun evaluateWinningFast(board: Board, player: PieceColor): Int {
        val opponent = player.opposite()
        
        // 己方五连
        if (ThreatDetector.findImmediateWin(board, player) != null) {
            return 10_000_000
        }
        
        // 对方五连
        if (ThreatDetector.findImmediateWin(board, opponent) != null) {
            return -10_000_000
        }
        
        // 己方活四
        if (ThreatDetector.findOpenFourMoves(board, player).isNotEmpty()) {
            return 1_000_000
        }
        
        // 对方活四
        if (ThreatDetector.findOpenFourMoves(board, opponent).isNotEmpty()) {
            return -1_000_000
        }
        
        return 0
    }
    
    /**
     * 单次扫描获取所有信息
     */
    private data class BoardInfo(
        val fiveCount: Int = 0,
        val openFourCount: Int = 0,
        val closedFourCount: Int = 0,
        val openThreeCount: Int = 0,
        val closedThreeCount: Int = 0,
        val openTwoCount: Int = 0,
        val centerBonus: Int = 0,
        val connectivity: Int = 0
    )
    
    private fun scanBoard(board: Board, player: PieceColor): BoardInfo {
        var fiveCount = 0
        var openFourCount = 0
        var closedFourCount = 0
        var openThreeCount = 0
        var closedThreeCount = 0
        var openTwoCount = 0
        var centerBonus = 0
        var connectivity = 0
        
        val center = Board.SIZE / 2
        val dirs = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        val checkedLines = mutableSetOf<String>()
        val piecePositions = mutableListOf<Pair<Int, Int>>()
        
        // 第一次遍历：收集棋子位置和计算中心奖励
        for (r in 0 until Board.SIZE) {
            for (c in 0 until Board.SIZE) {
                if (board.getPiece(r, c) == player) {
                    piecePositions.add(r to c)
                    val dist = kotlin.math.abs(r - center) + kotlin.math.abs(c - center)
                    centerBonus += Board.SIZE - dist
                }
            }
        }
        
        // 计算连通性
        for ((r, c) in piecePositions) {
            for (dr in -1..1) {
                for (dc in -1..1) {
                    if (dr == 0 && dc == 0) continue
                    val nr = r + dr
                    val nc = c + dc
                    if (nr in 0 until Board.SIZE && nc in 0 until Board.SIZE && 
                        board.getPiece(nr, nc) == player) {
                        connectivity++
                    }
                }
            }
        }
        connectivity /= 2 // 每对棋子被计算两次
        
        // 分析连线
        for ((r, c) in piecePositions) {
            for ((dr, dc) in dirs) {
                // 只处理一个方向
                if (dr < 0 || (dr == 0 && dc < 0)) continue
                
                val lineKey = "$r,$c,$dr,$dc"
                if (lineKey in checkedLines) continue
                
                val lineType = analyzeLineType(board, r, c, dr, dc, player)
                checkedLines.add(lineKey)
                
                when (lineType) {
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
        
        return BoardInfo(
            fiveCount = fiveCount,
            openFourCount = openFourCount,
            closedFourCount = closedFourCount,
            openThreeCount = openThreeCount,
            closedThreeCount = closedThreeCount,
            openTwoCount = openTwoCount,
            centerBonus = centerBonus,
            connectivity = connectivity
        )
    }
    
    private enum class LineType {
        FIVE, OPEN_FOUR, CLOSED_FOUR, OPEN_THREE, CLOSED_THREE, OPEN_TWO, OTHER
    }
    
    private fun analyzeLineType(
        board: Board, r: Int, c: Int, dr: Int, dc: Int, player: PieceColor
    ): LineType {
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
        
        val posOpen = isValidEmpty(board, posEnd.first, posEnd.second)
        val negOpen = isValidEmpty(board, negEnd.first, negEnd.second)
        
        return when {
            count >= 5 -> LineType.FIVE
            count == 4 && posOpen && negOpen -> LineType.OPEN_FOUR
            count == 4 && (posOpen || negOpen) -> LineType.CLOSED_FOUR
            count == 3 && posOpen && negOpen -> LineType.OPEN_THREE
            count == 3 && (posOpen || negOpen) -> LineType.CLOSED_THREE
            count == 2 && posOpen && negOpen -> LineType.OPEN_TWO
            else -> LineType.OTHER
        }
    }
    
    private fun isValidEmpty(board: Board, r: Int, c: Int): Boolean {
        return r in 0 until Board.SIZE && c in 0 until Board.SIZE && board.getPiece(r, c) == null
    }
    
    private fun calculateThreatScore(info: BoardInfo): Int {
        var score = 0
        score += info.fiveCount * 10_000_000
        score += info.openFourCount * 1_000_000
        score += info.closedFourCount * 10_000
        score += info.openThreeCount * 1_000
        score += info.closedThreeCount * 100
        score += info.openTwoCount * 10
        return score
    }
}
