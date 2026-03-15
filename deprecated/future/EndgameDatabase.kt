package io.github.ian_miller.wuziqi.ai.future

import io.github.ian_miller.wuziqi.ai.ThreatDetector
import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.PieceColor

/**
 * 小规模终局数据库
 * 
 * 只存储可解决的小规模局面：
 * - 棋盘 ≤ 6×6（可解）
 * - 剩余棋子 ≤ 5个（可快速穷举）
 * - 特定杀法模式（如双活三绝杀）
 */
class EndgameDatabase {
    
    /**
     * 局面结果分类
     */
    sealed class GameResult {
        object Win : GameResult()       // 当前行动方必胜
        object Loss : GameResult()      // 当前行动方必败
        object Draw : GameResult()      // 必和（五子棋无和棋，但可表示不确定）
        data class Unknown(val confidence: Double) : GameResult()  // 不确定，confidence是置信度
    }
    
    /**
     * 特定模式识别（这是实用的部分）
     */
    fun recognizePattern(board: Board, player: PieceColor): GameResult {
        return when {
            // 模式1: 自己双活四 = 必胜
            hasDoubleOpenFour(board, player) -> GameResult.Win
            
            // 模式2: 双冲四且不在同一直线 = 必胜
            hasDoubleClosedFourNonAligned(board, player) -> GameResult.Win
            
            // 模式3: 冲四+活三且无法同时防守 = 必胜
            hasClosedFourAndOpenThree(board, player) -> GameResult.Win
            
            // 模式4: 对手有双活四 = 必败
            hasDoubleOpenFour(board, player.opposite()) -> GameResult.Loss
            
            // 模式5: 活四 vs 活四 = 竞速（先手胜）
            hasOpenFourVsOpenFour(board, player) -> 
                if (isPlayerTurn(board, player)) GameResult.Win else GameResult.Loss
            
            else -> GameResult.Unknown(0.5)
        }
    }
    
    /**
     * 检查双活四
     */
    private fun hasDoubleOpenFour(board: Board, player: PieceColor): Boolean {
        val openFours = ThreatDetector.findOpenFourMoves(board, player)
        return openFours.size >= 2
    }
    
    /**
     * 检查双冲四且不在同一直线
     */
    private fun hasDoubleClosedFourNonAligned(board: Board, player: PieceColor): Boolean {
        val closedFours = ThreatDetector.findClosedFourMoves(board, player)
        return closedFours.size >= 2
    }
    
    /**
     * 检查冲四+活三组合
     */
    private fun hasClosedFourAndOpenThree(board: Board, player: PieceColor): Boolean {
        val hasClosedFour = ThreatDetector.findClosedFourMoves(board, player).isNotEmpty()
        val hasOpenThree = ThreatDetector.findOpenThreeMoves(board, player).size >= 2
        return hasClosedFour && hasOpenThree
    }
    
    /**
     * 竞速局面检测：双方都有活四
     */
    private fun hasOpenFourVsOpenFour(board: Board, player: PieceColor): Boolean {
        val opponent = player.opposite()
        val myOpenFour = ThreatDetector.findOpenFourMoves(board, player).isNotEmpty()
        val opOpenFour = ThreatDetector.findOpenFourMoves(board, opponent).isNotEmpty()
        return myOpenFour && opOpenFour
    }
    
    /**
     * 判断是否轮到某方（简化实现）
     */
    private fun isPlayerTurn(board: Board, player: PieceColor): Boolean {
        // 简化：通过棋子数量判断
        var blackCount = 0
        var whiteCount = 0
        for (r in 0 until Board.SIZE) {
            for (c in 0 until Board.SIZE) {
                when (board.getPiece(r, c)) {
                    PieceColor.BLACK -> blackCount++
                    PieceColor.WHITE -> whiteCount++
                    else -> {}
                }
            }
        }
        // 黑子先行，黑子数=白子数时轮到黑，黑子数>白子数时轮到白
        return when (player) {
            PieceColor.BLACK -> blackCount == whiteCount
            PieceColor.WHITE -> blackCount > whiteCount
        }
    }
}
