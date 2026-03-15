package io.github.ian_miller.wuziqi.ai.mcts

import io.github.ian_miller.wuziqi.ai.ThreatDetector
import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.PieceColor
import kotlinx.coroutines.yield
import kotlin.random.Random

/**
 * 局部蒙特卡洛树搜索 (LMCTS)
 * 
 * 解决什么问题：
 * 1. 深度搜索不够时，用随机模拟补充
 * 2. 评估复杂杀棋局面（如双活三竞速）
 * 3. 验证某个威胁是否"真实"（能否转化为胜利）
 * 
 * 性能控制：
 * - 只在候选点周围3x3区域模拟
 * - 最多100次模拟（约50ms）
 * - 只在深度≥4且分数接近时触发
 */
class LocalMCTS(
    private val maxSimulations: Int = 50,
    private val explorationConstant: Double = 1.414
) {
    
    /**
     * 对关键局面进行局部MCTS验证
     * 
     * @param board 当前棋盘
     * @param move 要验证的走法
     * @param player 当前玩家
     * @return 模拟胜率 [-1, 1]
     */
    suspend fun evaluateMove(
        board: Board,
        move: Pair<Int, Int>,
        player: PieceColor
    ): Double {
        val (r, c) = move
        
        // 1. 快速验证：如果这步直接赢，返回1.0
        val newBoard = board.placePiece(r, c, player)
        if (ThreatDetector.findImmediateWin(newBoard, player) != null) {
            return 1.0
        }
        
        // 2. 生成局部候选点（只考虑原move周围2格，限制数量）
        val localCandidates = generateLocalCandidates(newBoard, r, c, range = 2)
        if (localCandidates.isEmpty()) return 0.0
        
        // 限制候选点数量，避免计算爆炸
        val limitedCandidates = if (localCandidates.size > 10) {
            localCandidates.shuffled().take(10)
        } else localCandidates
        
        // 3. 运行简化版MCTS
        var wins = 0
        var simulations = 0
        
        val startTime = System.currentTimeMillis()
        
        repeat(maxSimulations) { iteration ->
            // 每10次模拟让出协程，避免阻塞
            if (iteration % 10 == 0) yield()
            
            val result = simulate(newBoard, player.opposite(), limitedCandidates, depth = 0)
            wins += if (result > 0) 1 else if (result < 0) 0 else 0
            simulations++
            
            // 提前终止：如果胜率极高或极低
            if (simulations >= 20) {
                val winRate = wins.toDouble() / simulations
                if (winRate > 0.9) return 0.9
                if (winRate < 0.1) return -0.9
            }
        }
        
        return (2.0 * wins / simulations - 1.0)
    }
    
    /**
     * 生成局部候选点
     */
    private fun generateLocalCandidates(
        board: Board,
        centerR: Int,
        centerC: Int,
        range: Int = 3
    ): List<Pair<Int, Int>> {
        val candidates = mutableListOf<Pair<Int, Int>>()
        
        for (dr in -range..range) {
            for (dc in -range..range) {
                val nr = centerR + dr
                val nc = centerC + dc
                if (nr in 0 until Board.SIZE && 
                    nc in 0 until Board.SIZE && 
                    board.getPiece(nr, nc) == null) {
                    candidates.add(nr to nc)
                }
            }
        }
        
        return candidates
    }
    
    /**
     * 简化版模拟
     * 特点：
     * 1. 不使用完整MCTS树，只随机走到底
     * 2. 优先选择有威胁的走法
     * 3. 最多15步终止
     */
    private fun simulate(
        board: Board,
        currentPlayer: PieceColor,
        candidates: List<Pair<Int, Int>>,
        depth: Int
    ): Int {
        if (depth > 15) return 0  // 平局
        
        // 检查终局
        ThreatDetector.findImmediateWin(board, currentPlayer)?.let { return 1 }
        ThreatDetector.findImmediateWin(board, currentPlayer.opposite())?.let { return -1 }
        
        if (candidates.isEmpty()) return 0
        
        // 智能随机：优先选择能形成威胁的走法
        val move = selectSmartMove(board, candidates, currentPlayer)
        val newBoard = board.placePiece(move.first, move.second, currentPlayer)
        
        // 更新候选点
        val newCandidates = candidates.filter { it != move }
        
        return -simulate(newBoard, currentPlayer.opposite(), newCandidates, depth + 1)
    }
    
    /**
     * 智能选择走法
     * 80%概率选择威胁走法，20%完全随机
     * 
     * 优化：只检查前5个候选点，避免计算爆炸
     */
    private fun selectSmartMove(
        board: Board,
        candidates: List<Pair<Int, Int>>,
        player: PieceColor
    ): Pair<Int, Int> {
        // 限制检查数量，避免计算爆炸
        val checkLimit = minOf(candidates.size, 5)
        val candidatesToCheck = candidates.shuffled().take(checkLimit)
        
        // 找能形成四连的点（只检查部分候选）
        val threats = candidatesToCheck.filter { (r, c) ->
            val testBoard = board.placePiece(r, c, player)
            ThreatDetector.findImmediateWin(testBoard, player) != null ||
            ThreatDetector.findOpenFourMoves(testBoard, player).isNotEmpty()
        }
        
        return when {
            threats.isNotEmpty() && Random.nextDouble() < 0.8 -> threats.random()
            else -> candidates.random()
        }
    }
    
    /**
     * 批量验证多个走法，返回排序结果
     */
    suspend fun rankMoves(
        board: Board,
        moves: List<Pair<Int, Int>>,
        player: PieceColor
    ): List<Pair<Pair<Int, Int>, Double>> {
        return moves.map { move ->
            val score = evaluateMove(board, move, player)
            yield() // 每个走法后让出协程
            move to score
        }.sortedByDescending { it.second }
    }
}

/**
 * 使用场景示例：
 * 
 * 在MinimaxSearch中，当：
 * 1. 当前深度完成（如深度4）
 * 2. 最佳分数和次佳分数差距 < 10000
 * 3. 对手有活三或我方有攻击机会
 * 
 * 则触发LMCTS对前3个候选走法进行验证
 */
