package io.github.ian_miller.wuziqi.ai.think.algorithm.minimax

import io.github.ian_miller.wuziqi.ai.debug.DebugManager
import io.github.ian_miller.wuziqi.ai.think.budget.BudgetFactor
import io.github.ian_miller.wuziqi.ai.think.budget.FactorHistory
import io.github.ian_miller.wuziqi.ai.think.budget.InMemoryFactorHistory

/**
 * Minimax 搜索深度因子
 * 
 * BudgetFactor 的具体实现，用于控制 Minimax 搜索深度。
 * 
 * @param history 深度历史数据（跨落子共享）
 * @param initialDepth 初始深度
 * @param maxDepth 最大允许深度
 * @param minDepth 最小允许深度
 */
class DepthFactor(
    private val history: FactorHistory = InMemoryFactorHistory(),
    initialDepth: Int = 2,
    override val maxValue: Int = 12,
    override val minValue: Int = 2
) : BudgetFactor {
    
    override val name: String = "minimax_depth"
    
    override var currentValue: Int = initialDepth
        private set
    
    /** 深度步长（每次增加的深度） */
    var depthStep: Int = 1
        set(value) {
            require(value > 0) { "步长必须为正" }
            field = value
        }
    
    /** 安全因子（预估时间乘以这个因子作为安全边际） */
    var safetyFactor: Double = 1.5
        set(value) {
            require(value >= 1.0) { "安全因子必须 >= 1.0" }
            field = value
        }
    
    override fun estimateTime(value: Int): Long {
        val historical = history.getAverageTime(value)
        return if (history.getExecutionCount(value) < 3) {
            estimateHeuristic(value)
        } else {
            historical
        }
    }
    
    override fun suggestNext(remainingBudgetMs: Long): Int? {
        val nextDepth = currentValue + depthStep
        
        if (nextDepth > maxValue) {
            DebugManager.v(DebugManager.Module.THINK, "已达到最大深度=$maxValue")
            return null
        }
        
        val estimatedTime = (estimateTime(nextDepth) * safetyFactor).toLong()
        val canProceed = estimatedTime < remainingBudgetMs
        
        DebugManager.v(DebugManager.Module.THINK, 
            "深度建议: 当前=$currentValue, 建议=$nextDepth, 预估=${estimatedTime}ms, " +
            "剩余=${remainingBudgetMs}ms, 是否继续=$canProceed")
        
        return if (canProceed) nextDepth else null
    }
    
    override fun recordExecution(value: Int, actualTimeMs: Long) {
        history.record(value, actualTimeMs)
        val oldValue = currentValue
        if (value > currentValue) {
            currentValue = value
        }
        DebugManager.v(DebugManager.Module.THINK, 
            "记录执行: 深度=$value, 实际=${actualTimeMs}ms, 当前深度 $oldValue → $currentValue")
    }
    
    private fun estimateHeuristic(depth: Int): Long = when (depth) {
        2 -> 50
        4 -> 200
        6 -> 1000
        8 -> 5000
        10 -> 15000
        12 -> 45000
        else -> depth * depth * 50L
    }
    
    fun reset() {
        currentValue = minValue
    }
}