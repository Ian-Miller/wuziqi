package io.github.ian_miller.wuziqi.ai.future

/**
 * 影响力图缓存
 * 
 * InfluenceGraph.analyzeInfluence计算较耗时，可以缓存结果
 */
class InfluenceCache(private val maxSize: Int = 500) {
    
    data class InfluenceEntry(
        val influenceMap: Array<DoubleArray>,
        val timestamp: Long
    )
    
    private val cache = LinkedHashMap<Long, InfluenceEntry>(maxSize, 0.75f, true)
    
    fun get(key: Long): Array<DoubleArray>? {
        return cache[key]?.influenceMap
    }
    
    fun put(key: Long, influenceMap: Array<DoubleArray>) {
        if (cache.size >= maxSize) {
            cache.remove(cache.keys.first())
        }
        cache[key] = InfluenceEntry(influenceMap, System.currentTimeMillis())
    }
    
    fun clear() {
        cache.clear()
    }
}
