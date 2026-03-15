package io.github.ian_miller.wuziqi.ai.cache

import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.PieceColor

/**
 * 威胁检测缓存系统
 * 
 * 缓存内容：
 * 1. 各种威胁类型的位置列表（活四、冲四、活三等）
 * 2. 立即获胜点
 * 3. 局面威胁特征
 * 
 * 关键优化：
 * - 使用Zobrist哈希作为键
 * - LRU淘汰策略
 * - 区分玩家（同一棋盘对不同玩家威胁不同）
 */
class ThreatCache(private val maxSize: Int = 1000) {
    
    /**
     * 缓存条目
     */
    data class ThreatCacheEntry(
        val openFourMoves: List<Pair<Int, Int>>? = null,
        val closedFourMoves: List<Pair<Int, Int>>? = null,
        val openThreeMoves: List<Pair<Int, Int>>? = null,
        val immediateWin: Pair<Int, Int>? = null,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    private val cache = LinkedHashMap<Long, ThreatCacheEntry>(
        maxSize, 0.75f, true  // accessOrder = true for LRU
    )
    
    private var hits = 0
    private var misses = 0
    
    /**
     * 生成缓存键：使用统一的 ZobristHasher
     */
    fun generateKey(board: Board, player: PieceColor): Long {
        return ZobristHasher.generateKey(board, player)
    }
    
    /**
     * 获取缓存条目
     */
    fun get(key: Long): ThreatCacheEntry? {
        val entry = cache[key]
        if (entry != null) {
            hits++
        } else {
            misses++
        }
        return entry
    }
    
    /**
     * 存储缓存条目
     */
    fun put(key: Long, entry: ThreatCacheEntry) {
        if (cache.size >= maxSize) {
            // LRU淘汰：移除最久未访问的
            val iterator = cache.entries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        cache[key] = entry
    }
    
    /**
     * 获取或计算活四（带缓存）
     */
    inline fun getOpenFourMoves(
        key: Long,
        compute: () -> List<Pair<Int, Int>>
    ): List<Pair<Int, Int>> {
        val entry = get(key)
        if (entry?.openFourMoves != null) {
            return entry.openFourMoves
        }
        
        val result = compute()
        val newEntry = (entry ?: ThreatCacheEntry()).copy(openFourMoves = result)
        put(key, newEntry)
        return result
    }
    
    /**
     * 获取或计算冲四（带缓存）
     */
    inline fun getClosedFourMoves(
        key: Long,
        compute: () -> List<Pair<Int, Int>>
    ): List<Pair<Int, Int>> {
        val entry = get(key)
        if (entry?.closedFourMoves != null) {
            return entry.closedFourMoves
        }
        
        val result = compute()
        val newEntry = (entry ?: ThreatCacheEntry()).copy(closedFourMoves = result)
        put(key, newEntry)
        return result
    }
    
    /**
     * 获取或计算活三（带缓存）
     */
    inline fun getOpenThreeMoves(
        key: Long,
        compute: () -> List<Pair<Int, Int>>
    ): List<Pair<Int, Int>> {
        val entry = get(key)
        if (entry?.openThreeMoves != null) {
            return entry.openThreeMoves
        }
        
        val result = compute()
        val newEntry = (entry ?: ThreatCacheEntry()).copy(openThreeMoves = result)
        put(key, newEntry)
        return result
    }
    
    /**
     * 获取或计算立即获胜点（带缓存）
     */
    inline fun getImmediateWin(
        key: Long,
        compute: () -> Pair<Int, Int>?
    ): Pair<Int, Int>? {
        val entry = get(key)
        if (entry?.immediateWin != null) {
            return entry.immediateWin
        }
        
        val result = compute()
        val newEntry = (entry ?: ThreatCacheEntry()).copy(immediateWin = result)
        put(key, newEntry)
        return result
    }
    
    /**
     * 清空缓存
     */
    fun clear() {
        cache.clear()
        hits = 0
        misses = 0
    }
    
    /**
     * 获取统计信息
     */
    fun getStats(): String {
        val hitRate = if (hits + misses > 0) {
            (hits * 100 / (hits + misses))
        } else 0
        return "ThreatCache: size=${cache.size}, hits=$hits, misses=$misses, hitRate=$hitRate%"
    }
}
