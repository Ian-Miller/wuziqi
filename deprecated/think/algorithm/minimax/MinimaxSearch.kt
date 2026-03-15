package io.github.ian_miller.wuziqi.ai.think.algorithm.minimax

import io.github.ian_miller.wuziqi.ai.debug.DebugManager
import io.github.ian_miller.wuziqi.ai.eval.pure.PureEvaluator
import io.github.ian_miller.wuziqi.ai.movegen.MoveGenerator
import io.github.ian_miller.wuziqi.ai.think.budget.FactorHistory
import io.github.ian_miller.wuziqi.ai.think.budget.InMemoryFactorHistory
import io.github.ian_miller.wuziqi.ai.think.control.AdaptiveThinkController
import io.github.ian_miller.wuziqi.ai.think.control.ThinkController
import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.PieceColor
import kotlin.math.max
import kotlin.math.min

/**
 * Minimax 搜索（新架构）
 * 
 * 使用 ThinkController 协调时间预算和深度因子。
 * 
 * @param evaluator 纯粹评估器
 * @param moveGenerator 走法生成器
 * @param controller 思考控制器（可选，默认创建）
 * @author AI Assistant
 * @since 0.04
 */
class MinimaxSearch(
    private val evaluator: PureEvaluator,
    private val moveGenerator: MoveGenerator,
    private val controller: ThinkController
) {
    
    private var nodesEvaluated = 0
    
    /**
     * 次级构造函数：从总时间和历史创建
     */
    constructor(
        evaluator: PureEvaluator,
        moveGenerator: MoveGenerator,
        totalTimeMs: Long = 5000,
        factorHistory: FactorHistory = InMemoryFactorHistory()
    ) : this(
        evaluator = evaluator,
        moveGenerator = moveGenerator,
        controller = AdaptiveThinkController.create(
            totalTimeMs = totalTimeMs,
            factor = DepthFactor(history = factorHistory)
        )
    )
    
    /**
     * 搜索最佳走法
     */
    fun findBestMove(board: Board, player: PieceColor): Pair<Int, Int> {
        nodesEvaluated = 0
        controller.start()
        
        DebugManager.i(DebugManager.Module.THINK, "Minimax开始搜索，玩家=$player")
        
        // 1. 快速检查：是否有立即获胜的走法
        moveGenerator.findWinningMove(board, player)?.let { winMove ->
            DebugManager.i(DebugManager.Module.THINK, "发现立即获胜走法: $winMove")
            return winMove
        }
        
        // 2. 快速检查：是否需要防守对手的必胜走法
        moveGenerator.findBlockingMove(board, player)?.let { blockMove ->
            DebugManager.i(DebugManager.Module.THINK, "发现必须防守的走法: $blockMove")
            return blockMove
        }
        
        DebugManager.withIndent {
            var bestMove: Pair<Int, Int>? = null
            var lastScore = 0
            
            // 迭代加深
            while (controller.shouldContinue()) {
                val depth = controller.nextValue() ?: break
                
                val iterationStart = System.currentTimeMillis()
                val (move, score) = searchAtDepth(board, player, depth)
                val iterationTime = System.currentTimeMillis() - iterationStart
                
                DebugManager.d(DebugManager.Module.THINK, 
                    "深度=$depth 完成: 最佳走法=$move, 分数=$score, 耗时=${iterationTime}ms, 节点=$nodesEvaluated")
                
                if (move != null) {
                    bestMove = move
                    lastScore = score
                }
                
                controller.recordIteration(depth, iterationTime, nodesEvaluated)
                
                // 找到必胜/必败，提前终止
                if (kotlin.math.abs(score) > 9000000) {
                    DebugManager.i(DebugManager.Module.THINK, "找到必胜/必败走法，提前终止")
                    break
                }
            }
            
            val stats = controller.stats()
            DebugManager.i(DebugManager.Module.THINK, 
                "搜索完成: 最终深度=${stats.currentFactorValue}, 总节点=${stats.totalNodes}, " +
                "平均每次迭代=${stats.avgTimePerIteration}ms")
            
            return bestMove ?: (Board.SIZE / 2 to Board.SIZE / 2)
        }
    }
    
    private fun searchAtDepth(board: Board, player: PieceColor, depth: Int): Pair<Pair<Int, Int>?, Int> {
        val moves = moveGenerator.generateMoves(board, player, 0)
        if (moves.isEmpty()) return null to 0
        
        var bestMove = moves.first()
        var bestScore = Int.MIN_VALUE
        
        for (move in moves) {
            val (r, c) = move
            val newBoard = board.placePiece(r, c, player)
            
            val score = minimax(
                board = newBoard,
                depth = depth - 1,
                alpha = Int.MIN_VALUE,
                beta = Int.MAX_VALUE,
                maximizingPlayer = false,
                player = player,
                originalPlayer = player
            )
            
            if (score > bestScore) {
                bestScore = score
                bestMove = move
            }
        }
        
        return bestMove to bestScore
    }
    
    private fun minimax(
        board: Board,
        depth: Int,
        alpha: Int,
        beta: Int,
        maximizingPlayer: Boolean,
        player: PieceColor,
        originalPlayer: PieceColor,
        currentDepth: Int = 0
    ): Int {
        nodesEvaluated++
        
        // 检查时间预算（每1000个节点检查一次）
        if (nodesEvaluated % 1000 == 0 && !controller.timeBudget.hasBudget()) {
            return evaluator.evaluate(board, originalPlayer)
        }
        
        if (depth == 0) {
            return evaluator.evaluate(board, originalPlayer)
        }
        
        val currentPlayer = if (maximizingPlayer) player else player.opposite()
        val moves = moveGenerator.generateMoves(board, currentPlayer, currentDepth)
        
        if (moves.isEmpty()) {
            return evaluator.evaluate(board, originalPlayer)
        }
        
        var alphaVar = alpha
        var betaVar = beta
        
        if (maximizingPlayer) {
            var maxEval = Int.MIN_VALUE
            for ((r, c) in moves) {
                val newBoard = board.placePiece(r, c, currentPlayer)
                val eval = minimax(newBoard, depth - 1, alphaVar, betaVar, false, player, originalPlayer, currentDepth + 1)
                maxEval = max(maxEval, eval)
                alphaVar = max(alphaVar, eval)
                if (betaVar <= alphaVar) break
            }
            return maxEval
        } else {
            var minEval = Int.MAX_VALUE
            for ((r, c) in moves) {
                val newBoard = board.placePiece(r, c, currentPlayer)
                val eval = minimax(newBoard, depth - 1, alphaVar, betaVar, true, player, originalPlayer, currentDepth + 1)
                minEval = min(minEval, eval)
                betaVar = min(betaVar, eval)
                if (betaVar <= alphaVar) break
            }
            return minEval
        }
    }
}