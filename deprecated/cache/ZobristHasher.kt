package io.github.ian_miller.wuziqi.ai.cache

import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.PieceColor
import kotlin.random.Random

/**
 * Zobrist 哈希生成器
 * 
 * 使用随机数表为棋盘状态生成唯一哈希值，用于缓存键。
 * 确保相同棋盘状态产生相同哈希，不同状态产生不同哈希。
 */
object ZobristHasher {
    
    // 随机数表：[row][col][colorIndex]，colorIndex: 0=BLACK, 1=WHITE
    private val table = Array(Board.SIZE) { row ->
        Array(Board.SIZE) { col ->
            LongArray(2).apply {
                this[0] = Random.nextLong()  // BLACK
                this[1] = Random.nextLong()  // WHITE
            }
        }
    }
    
    // 当前玩家的哈希偏移量（区分轮到谁下）
    private val playerHash = Random.nextLong()
    
    /**
     * 生成棋盘状态的哈希键
     */
    fun generateKey(board: Board, player: PieceColor): Long {
        var hash = 0L
        
        for (r in 0 until Board.SIZE) {
            for (c in 0 until Board.SIZE) {
                val piece = board.getPiece(r, c)
                if (piece != null) {
                    val colorIndex = if (piece == PieceColor.BLACK) 0 else 1
                    hash = hash xor table[r][c][colorIndex]
                }
            }
        }
        
        // 加入当前玩家信息
        if (player == PieceColor.WHITE) {
            hash = hash xor playerHash
        }
        
        return hash
    }
    
    /**
     * 仅生成棋盘状态的哈希（不包含当前玩家）
     * 用于与玩家无关的缓存
     */
    fun generateBoardKey(board: Board): Long {
        var hash = 0L
        
        for (r in 0 until Board.SIZE) {
            for (c in 0 until Board.SIZE) {
                val piece = board.getPiece(r, c)
                if (piece != null) {
                    val colorIndex = if (piece == PieceColor.BLACK) 0 else 1
                    hash = hash xor table[r][c][colorIndex]
                }
            }
        }
        
        return hash
    }
}
