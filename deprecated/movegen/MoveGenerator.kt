package io.github.ian_miller.wuziqi.ai.movegen

import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.PieceColor

/**
 * 走法生成器
 * 
 * 生成候选走法，按启发式排序。
 * 
 * @author AI Assistant
 * @since 0.04
 */
class MoveGenerator(
    private val maxMoves: Int = 10  // 最大走法数量限制
) {
    
    /**
     * 生成候选走法
     * 
     * @param board 当前棋盘
     * @param player 当前玩家
     * @param depth 当前深度（0表示根节点）
     * @return 按启发式排序的走法列表
     */
    fun generateMoves(
        board: Board,
        player: PieceColor,
        depth: Int
    ): List<Pair<Int, Int>> {
        val candidates = mutableListOf<Pair<Int, Int>>()
        
        // 如果棋盘为空，只返回中心点
        if (isBoardEmpty(board)) {
            return listOf(Board.SIZE / 2 to Board.SIZE / 2)
        }
        
        // 收集所有已有棋子周围的空点
        val neighborMoves = mutableSetOf<Pair<Int, Int>>()
        for (r in 0 until Board.SIZE) {
            for (c in 0 until Board.SIZE) {
                if (board.getPiece(r, c) != null) {
                    // 添加周围8个方向的空点
                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            if (dr == 0 && dc == 0) continue
                            val nr = r + dr
                            val nc = c + dc
                            if (nr in 0 until Board.SIZE && 
                                nc in 0 until Board.SIZE && 
                                board.getPiece(nr, nc) == null) {
                                neighborMoves.add(nr to nc)
                            }
                        }
                    }
                }
            }
        }
        
        candidates.addAll(neighborMoves)
        
        // 如果邻居点太少，添加中心区域
        if (candidates.size < 5) {
            val center = Board.SIZE / 2
            for (r in center - 2..center + 2) {
                for (c in center - 2..center + 2) {
                    if (r in 0 until Board.SIZE && 
                        c in 0 until Board.SIZE && 
                        board.getPiece(r, c) == null) {
                        candidates.add(r to c)
                    }
                }
            }
        }
        
        // 按启发式排序（优先中心，深层节点减少数量）
        val sorted = candidates.sortedBy { (r, c) ->
            val center = Board.SIZE / 2
            kotlin.math.abs(r - center) + kotlin.math.abs(c - center)
        }
        
        // 根据深度限制走法数量（根节点用maxMoves，深层减少）
        val limit = when {
            depth == 0 -> maxMoves
            depth <= 2 -> maxMoves / 2
            else -> 4
        }
        
        return sorted.take(limit.coerceAtLeast(4))
    }
    
    /**
     * 快速检查是否有立即获胜的走法（五连）
     */
    fun findWinningMove(board: Board, player: PieceColor): Pair<Int, Int>? {
        return io.github.ian_miller.wuziqi.ai.ThreatDetector.findImmediateWin(board, player)
    }
    
    /**
     * 快速检查是否需要防守对手的必胜走法（对手五连或活四）
     */
    fun findBlockingMove(board: Board, player: PieceColor): Pair<Int, Int>? {
        val opponent = player.opposite()
        // 先检查对手五连（立即获胜）
        io.github.ian_miller.wuziqi.ai.ThreatDetector.findImmediateWin(board, opponent)?.let { return it }
        // 再检查对手活四（下一步获胜）
        val opOpenFour = io.github.ian_miller.wuziqi.ai.ThreatDetector.findOpenFourMoves(board, opponent)
        return opOpenFour.firstOrNull()
    }
    
    /**
     * 获取所有有棋子的位置
     */
    fun getAllPiecePositions(board: Board): List<Pair<Int, Int>> {
        val positions = mutableListOf<Pair<Int, Int>>()
        for (r in 0 until Board.SIZE) {
            for (c in 0 until Board.SIZE) {
                if (board.getPiece(r, c) != null) {
                    positions.add(r to c)
                }
            }
        }
        return positions
    }
    
    private fun isBoardEmpty(board: Board): Boolean {
        for (r in 0 until Board.SIZE) {
            for (c in 0 until Board.SIZE) {
                if (board.getPiece(r, c) != null) {
                    return false
                }
            }
        }
        return true
    }
}