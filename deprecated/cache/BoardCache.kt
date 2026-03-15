package io.github.ian_miller.wuziqi.ai.cache

import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.PieceColor

/**
 * 棋盘状态缓存系统：使用LRU缓存策略，存储昂贵的计算结果。
 * 
 * 缓存内容：
 * 1. 评估分数
 * 2. 威胁分析结果
 * 3. 棋型统计
 * 
 * 数学基础：利用五子棋的局部性原理，相邻棋盘状态有大量共享子结构
 */
class BoardCache(private val maxSize: Int = 10000) {
    
    // LRU缓存：使用LinkedHashMap实现O(1)访问和更新
    private val scoreCache = object : LinkedHashMap<Long, Int>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Int>?): Boolean {
            return size > maxSize
        }
    }
    
    private val threatCache = object : LinkedHashMap<Long, ThreatCacheEntry>(maxSize / 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ThreatCacheEntry>?): Boolean {
            return size > maxSize / 2
        }
    }
    
    private val patternCache = object : LinkedHashMap<Long, PatternCacheEntry>(maxSize / 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, PatternCacheEntry>?): Boolean {
            return size > maxSize / 2
        }
    }
    
    // 访问统计
    var hitCount = 0
        private set
    var missCount = 0
        private set
    
    /**
     * 生成缓存键：使用统一的 ZobristHasher
     */
    fun generateKey(board: Board, player: PieceColor): Long {
        return ZobristHasher.generateKey(board, player)
    }
    
    /**
     * 清空缓存
     */
    fun reset() {
        clear()
    }
    
    /**
     * 获取缓存的评估分数
     */
    fun getScore(key: Long): Int? {
        return scoreCache[key]?.also { hitCount++ } ?: run { missCount++; null }
    }
    
    /**
     * 存储评估分数
     */
    fun putScore(key: Long, score: Int) {
        scoreCache[key] = score
    }
    
    /**
     * 获取缓存的威胁分析
     */
    fun getThreatAnalysis(key: Long): ThreatCacheEntry? {
        return threatCache[key]?.also { hitCount++ } ?: run { missCount++; null }
    }
    
    /**
     * 存储威胁分析
     */
    fun putThreatAnalysis(key: Long, entry: ThreatCacheEntry) {
        threatCache[key] = entry
    }
    
    /**
     * 获取缓存的棋型统计
     */
    fun getPatterns(key: Long): PatternCacheEntry? {
        return patternCache[key]?.also { hitCount++ } ?: run { missCount++; null }
    }
    
    /**
     * 存储棋型统计
     */
    fun putPatterns(key: Long, entry: PatternCacheEntry) {
        patternCache[key] = entry
    }
    
    /**
     * 清空所有缓存
     */
    fun clear() {
        scoreCache.clear()
        threatCache.clear()
        patternCache.clear()
        hitCount = 0
        missCount = 0
    }
    
    /**
     * 获取缓存命中率
     */
    fun getHitRate(): Double {
        val total = hitCount + missCount
        return if (total > 0) hitCount.toDouble() / total else 0.0
    }
    
    /**
     * 获取缓存统计信息
     */
    fun getStats(): String {
        return "Cache Stats: score=${scoreCache.size}, threat=${threatCache.size}, " +
               "pattern=${patternCache.size}, hitRate=${String.format("%.2f%%", getHitRate() * 100)}"
    }
    
    /**
     * 获取当前缓存大小
     */
    fun getSize(): Int = scoreCache.size + threatCache.size + patternCache.size
    
    /**
     * 威胁分析缓存条目
     */
    data class ThreatCacheEntry(
        val myThreats: List<Pair<Int, Int>>,
        val opponentThreats: List<Pair<Int, Int>>,
        val networkScore: Int,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * 棋型缓存条目
     */
    data class PatternCacheEntry(
        val five: Int,
        val openFour: Int,
        val closedFour: Int,
        val openThree: Int,
        val closedThree: Int,
        val openTwo: Int,
        val timestamp: Long = System.currentTimeMillis()
    )
}
