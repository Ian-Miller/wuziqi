package io.github.ian_miller.wuziqi.ai.think.budget

/**
 * 预算因子 - 影响计算量的可变因素
 * 
 * 这是时间管理与搜索算法的桥梁。
 * 
 * 在不同算法中，BudgetFactor 可以是：
 * - Minimax: 搜索深度 (depth)
 * - MCTS: 迭代次数 (iterations)  
 * - Neural: 网络评估次数
 * 
 * 核心职责：
 * 1. 估算给定值所需时间
 * 2. 根据剩余预算建议下一个值
 * 3. 记录历史，优化估算
 * 
 * @author AI Assistant
 * @since 0.04
 */
interface BudgetFactor {
    
    /**
     * 因子名称
     */
    val name: String
    
    /**
     * 当前值（只读）
     */
    val currentValue: Int
    
    /**
     * 最大值限制
     */
    val maxValue: Int
    
    /**
     * 最小值限制
     */
    val minValue: Int
    
    /**
     * 估算给定值所需时间（毫秒）
     */
    fun estimateTime(value: Int): Long
    
    /**
     * 根据剩余时间预算，建议下一个值
     * 
     * @param remainingBudgetMs 剩余时间预算
     * @return 建议的下一个值，null 表示预算不足应停止
     */
    fun suggestNext(remainingBudgetMs: Long): Int?
    
    /**
     * 记录执行结果，优化后续估算
     */
    fun recordExecution(value: Int, actualTimeMs: Long)
}

/**
 * 因子历史记录
 */
interface FactorHistory {
    fun getAverageTime(value: Int): Long
    fun getExecutionCount(value: Int): Int
    fun record(value: Int, actualTimeMs: Long)
}

/**
 * 内存中的历史记录
 */
class InMemoryFactorHistory : FactorHistory {
    
    private data class Stats(var totalTime: Long = 0, var count: Int = 0)
    private val stats = mutableMapOf<Int, Stats>()
    
    override fun getAverageTime(value: Int): Long {
        val s = stats[value] ?: return defaultEstimate(value)
        return if (s.count > 0) s.totalTime / s.count else defaultEstimate(value)
    }
    
    override fun getExecutionCount(value: Int): Int {
        return stats[value]?.count ?: 0
    }
    
    override fun record(value: Int, actualTimeMs: Long) {
        stats.getOrPut(value) { Stats() }.apply {
            totalTime += actualTimeMs
            count++
        }
    }
    
    private fun defaultEstimate(value: Int): Long = when (value) {
        in 0..2 -> 50
        in 3..4 -> 200
        in 5..6 -> 1000
        in 7..8 -> 5000
        else -> value * value * 50L
    }
}