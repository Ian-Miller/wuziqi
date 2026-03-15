package io.github.ian_miller.wuziqi.ai.think.control

import io.github.ian_miller.wuziqi.ai.debug.DebugManager
import io.github.ian_miller.wuziqi.ai.think.budget.BudgetFactor
import io.github.ian_miller.wuziqi.ai.think.budget.TimeBudget

/**
 * 思考控制器
 * 
 * 协调时间预算和因子控制，决定是否继续思考。
 * 
 * 这是时间管理和搜索控制的核心枢纽：
 * - 时间够不够？→ TimeBudget
 * - 因子要不要增加？→ BudgetFactor  
 * - 要不要继续？→ ThinkController 综合判断
 * 
 * @author AI Assistant
 * @since 0.04
 */
interface ThinkController {
    
    /**
     * 时间预算
     */
    val timeBudget: TimeBudget
    
    /**
     * 预算因子（深度/迭代次数等）
     */
    val factor: BudgetFactor
    
    /**
     * 开始思考
     */
    fun start()
    
    /**
     * 是否应该继续思考
     * 
     * 综合时间和因子判断
     */
    fun shouldContinue(): Boolean
    
    /**
     * 获取下一个因子值
     * 
     * @return 下一个值（如深度），null 表示应该停止
     */
    fun nextValue(): Int?
    
    /**
     * 记录本次思考结果
     */
    fun recordIteration(value: Int, actualTimeMs: Long, nodesEvaluated: Int)
    
    /**
     * 获取统计信息
     */
    fun stats(): ThinkStats
}

/**
 * 思考统计
 */
data class ThinkStats(
    val iterations: Int,
    val totalNodes: Int,
    val avgTimePerIteration: Long,
    val timeUsedMs: Long,
    val timeRemainingMs: Long,
    val currentFactorValue: Int,
    val predictedMaxFactor: Int
)

/**
 * 自适应思考控制器（默认实现）
 */
class AdaptiveThinkController(
    override val timeBudget: TimeBudget,
    override val factor: BudgetFactor
) : ThinkController {
    
    private var iterations = 0
    private var totalNodes = 0
    
    override fun start() {
        timeBudget.start()
        DebugManager.d(DebugManager.Module.THINK, "ThinkController启动，时间预算=${timeBudget.remainingMs()}ms")
    }
    
    override fun shouldContinue(): Boolean {
        // 1. 检查时间预算
        if (!timeBudget.hasBudget()) {
            DebugManager.v(DebugManager.Module.THINK, "时间预算耗尽，停止搜索")
            return false
        }
        
        // 2. 检查因子是否建议继续
        val remaining = timeBudget.remainingMs()
        val canContinue = factor.suggestNext(remaining) != null
        if (!canContinue) {
            DebugManager.v(DebugManager.Module.THINK, "因子建议停止: 当前=${factor.currentValue}, 剩余=${remaining}ms")
        }
        return canContinue
    }
    
    override fun nextValue(): Int? {
        val remaining = timeBudget.remainingMs()
        return factor.suggestNext(remaining)
    }
    
    override fun recordIteration(value: Int, actualTimeMs: Long, nodesEvaluated: Int) {
        iterations++
        totalNodes += nodesEvaluated
        factor.recordExecution(value, actualTimeMs)
        DebugManager.v(DebugManager.Module.THINK, 
            "记录迭代: 深度=$value, 耗时=${actualTimeMs}ms, 累计节点=$totalNodes")
    }
    
    override fun stats(): ThinkStats {
        val elapsed = timeBudget.elapsedMs()
        val remaining = timeBudget.remainingMs()
        val avgTime = if (iterations > 0) elapsed / iterations else 0
        
        // 预测最大能达到的因子值
        val predictedMax = generateSequence(factor.currentValue) { 
            factor.suggestNext(remaining - factor.estimateTime(it)) 
        }.lastOrNull() ?: factor.currentValue
        
        return ThinkStats(
            iterations = iterations,
            totalNodes = totalNodes,
            avgTimePerIteration = avgTime,
            timeUsedMs = elapsed,
            timeRemainingMs = remaining,
            currentFactorValue = factor.currentValue,
            predictedMaxFactor = predictedMax
        )
    }
    
    companion object {
        /**
         * 创建默认控制器
         */
        fun create(
            totalTimeMs: Long,
            factor: BudgetFactor,
            emergencyReserveMs: Long = 200
        ): ThinkController {
            return AdaptiveThinkController(
                timeBudget = io.github.ian_miller.wuziqi.ai.think.budget.StandardTimeBudget(
                    totalTimeMs, 
                    emergencyReserveMs
                ),
                factor = factor
            )
        }
    }
}