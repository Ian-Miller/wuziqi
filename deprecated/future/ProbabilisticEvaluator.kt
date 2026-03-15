package io.github.ian_miller.wuziqi.ai.future

import io.github.ian_miller.wuziqi.ai.ThreatDetector
import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.PieceColor
import kotlin.math.abs
import kotlin.math.min

/**
 * 概率博弈评估器：使用概率论和统计学评估棋局。
 * 
 * 核心概念：
 * 1. 威胁实现概率：基于棋盘空间和对弈历史
 * 2. 期望收益：概率 × 收益
 * 3. 风险价值：考虑对手威胁的期望损失
 * 
 * 数学工具：
 * - 贝叶斯概率：更新威胁实现概率
 * - 期望值计算：E[X] = Σ P(x) × value(x)
 * - 蒙特卡洛模拟：快速评估复杂局面（简化版）
 */
class ProbabilisticEvaluator {
    
    // 概率模型参数
    private val baseThreatProb = 0.3      // 基础威胁实现概率
    private val spaceFactor = 0.15        // 空间影响因子
    private val defenseFactor = 0.2       // 防守影响因子
    
    /**
     * 威胁实现预测
     */
    data class ThreatRealization(
        val level: ThreatLevel,
        val position: Pair<Int, Int>,
        val probability: Double,
        val expectedMoves: Int,       // 预计实现所需步数
        val blockingProbability: Double  // 被阻挡概率
    )
    
    /**
     * 简化的快速评估
     */
    fun quickAssess(
        board: Board,
        player: PieceColor,
        opponent: PieceColor
    ): Double {
        // 获取威胁信息
        val myOpenFour = ThreatDetector.findOpenFourMoves(board, player).size
        val myClosedFour = ThreatDetector.findClosedFourMoves(board, player).size
        val myOpenThree = ThreatDetector.findOpenThreeMoves(board, player).size
        
        val opOpenFour = ThreatDetector.findOpenFourMoves(board, opponent).size
        val opClosedFour = ThreatDetector.findClosedFourMoves(board, opponent).size
        val opOpenThree = ThreatDetector.findOpenThreeMoves(board, opponent).size
        
        var score = 0.0
        
        // 己方威胁
        score += myOpenFour * 50_000.0
        score += myClosedFour * 10_000.0
        score += myOpenThree * 2_000.0
        
        // 对方威胁（负向）
        score -= opOpenFour * 50_000.0
        score -= opClosedFour * 10_000.0
        score -= opOpenThree * 2_000.0
        
        // 概率调整：如果双方都有高级威胁，调整分数
        if (myOpenFour > 0 && opOpenFour > 0) {
            // 竞速局面，回合优势很重要
            score += 5_000.0  // 假设AI能把握先手优势
        }
        
        return score
    }
    
    /**
     * 计算威胁实现概率
     */
    fun calculateThreatProbabilities(
        board: Board,
        player: PieceColor,
        opponent: PieceColor
    ): List<ThreatRealization> {
        val realizations = mutableListOf<ThreatRealization>()
        
        // 分析己方威胁
        val myOpenFours = ThreatDetector.findOpenFourMoves(board, player)
        for ((r, c) in myOpenFours) {
            realizations.add(
                ThreatRealization(
                    level = ThreatLevel.OPEN_FOUR,
                    position = r to c,
                    probability = 0.95,
                    expectedMoves = 1,
                    blockingProbability = 0.1
                )
            )
        }
        
        val myClosedFours = ThreatDetector.findClosedFourMoves(board, player)
        for ((r, c) in myClosedFours) {
            val spaceBonus = calculateSpaceBonus(board, r, c)
            realizations.add(
                ThreatRealization(
                    level = ThreatLevel.CLOSED_FOUR,
                    position = r to c,
                    probability = 0.7 * (1 + spaceBonus * spaceFactor),
                    expectedMoves = 2,
                    blockingProbability = 0.3
                )
            )
        }
        
        // 分析对方威胁（用于风险评估）
        val opOpenFours = ThreatDetector.findOpenFourMoves(board, opponent)
        for ((r, c) in opOpenFours) {
            realizations.add(
                ThreatRealization(
                    level = ThreatLevel.OPEN_FOUR,
                    position = r to c,
                    probability = -0.95,  // 负值表示风险
                    expectedMoves = 1,
                    blockingProbability = 0.9  // 我们需要高概率阻挡
                )
            )
        }
        
        return realizations
    }
    
    /**
     * 计算空间奖励（周围空位比例）
     */
    private fun calculateSpaceBonus(board: Board, row: Int, col: Int): Double {
        var emptyCount = 0
        var totalCount = 0
        
        for (dr in -2..2) {
            for (dc in -2..2) {
                val nr = row + dr
                val nc = col + dc
                if (nr in 0 until Board.SIZE && nc in 0 until Board.SIZE) {
                    totalCount++
                    if (board.getPiece(nr, nc) == null) {
                        emptyCount++
                    }
                }
            }
        }
        
        return if (totalCount > 0) emptyCount.toDouble() / totalCount else 0.0
    }
    
    /**
     * 计算获胜概率
     */
    fun calculateWinProbability(
        board: Board,
        player: PieceColor,
        opponent: PieceColor
    ): Double {
        val realizations = calculateThreatProbabilities(board, player, opponent)
        
        var winProb = 0.05  // 基础概率
        
        // 累加己方威胁
        for (r in realizations) {
            if (r.probability > 0) {
                winProb += r.probability * (1 - r.blockingProbability)
            }
        }
        
        // 考虑对方威胁的防守压力
        val opponentThreats = realizations.filter { it.probability < 0 }
        for (threat in opponentThreats) {
            // 对方威胁降低我方获胜概率
            winProb *= (1 - abs(threat.probability) * 0.5)
        }
        
        return min(0.99, winProb)
    }
}
