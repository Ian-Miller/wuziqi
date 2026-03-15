package io.github.ian_miller.wuziqi.ai.cache

import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.PieceColor

/**
 * 统一缓存管理器
 * 
 * 集中管理所有AI缓存，提供：
 * 1. 统一的缓存访问接口
 * 2. 统一的缓存清理
 * 3. 缓存统计信息
 */
object CacheManager {
    
    // 各类缓存实例
    private val boardCache = BoardCache(10000)
    private val threatCache = ThreatCache(1000)
    
    // 统计信息
    private var hitCount = 0
    private var missCount = 0
    
    /**
     * 获取评估分数缓存
     */
    fun getScore(board: Board, player: PieceColor): Int? {
        val key = boardCache.generateKey(board, player)
        val value = boardCache.getScore(key)
        if (value != null) recordHit() else recordMiss()
        return value
    }
    
    /**
     * 存储评估分数缓存
     */
    fun putScore(board: Board, player: PieceColor, score: Int) {
        val key = boardCache.generateKey(board, player)
        boardCache.putScore(key, score)
    }
    
    /**
     * 获取威胁缓存
     */
    fun getThreatCache(): ThreatCache = threatCache
    
    /**
     * 生成棋盘缓存键（Zobrist哈希）
     */
    fun generateBoardKey(board: Board, player: PieceColor): Long {
        return ZobristHasher.generateKey(board, player)
    }
    
    /**
     * 计算Zobrist哈希（用于外部模块）
     */
    fun computeZobristHash(board: Board, player: PieceColor): Long {
        return ZobristHasher.generateKey(board, player)
    }
    
    /**
     * 清空所有缓存
     */
    fun clearAll() {
        boardCache.clear()
        threatCache.clear()
        hitCount = 0
        missCount = 0
    }
    
    /**
     * 获取缓存统计
     */
    fun getStats(): CacheStats {
        return CacheStats(
            boardCacheSize = boardCache.getSize(),
            threatCacheSize = threatCache.getStats(),
            totalHits = hitCount,
            totalMisses = missCount
        )
    }
    
    private fun recordHit() { hitCount++ }
    private fun recordMiss() { missCount++ }
    
    data class CacheStats(
        val boardCacheSize: Int,
        val threatCacheSize: String,
        val totalHits: Int,
        val totalMisses: Int
    ) {
        val hitRate: Float
            get() = if (totalHits + totalMisses > 0) {
                totalHits.toFloat() / (totalHits + totalMisses)
            } else 0f
        
        override fun toString(): String {
            return "CacheStats(board=$boardCacheSize, hits=$totalHits, misses=$totalMisses, hitRate=${"%.2f".format(hitRate)})"
        }
    }
}
