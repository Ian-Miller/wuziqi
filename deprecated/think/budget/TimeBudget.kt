package io.github.ian_miller.wuziqi.ai.think.budget

/**
 * 时间预算管理
 * 
 * 核心职责：管理总时间预算，决定"要不要继续"
 * 
 * 与 BudgetFactor（如深度）的区别：
 * - TimeBudget: 关注"时间够不够"（物理限制）
 * - BudgetFactor: 关注"因子值如何变化"（逻辑控制）
 * 
 * @author AI Assistant
 * @since 0.04
 */
interface TimeBudget {
    
    /**
     * 总时间预算（毫秒）
     */
    val totalTimeMs: Long
    
    /**
     * 开始计时
     */
    fun start()
    
    /**
     * 已用时间
     */
    fun elapsedMs(): Long
    
    /**
     * 剩余时间
     */
    fun remainingMs(): Long
    
    /**
     * 是否还有预算继续
     * 
     * 考虑紧急预留时间
     */
    fun hasBudget(): Boolean
    
    /**
     * 紧急停止（立即返回false）
     */
    fun emergencyStop()
}

/**
 * 标准时间预算实现
 */
class StandardTimeBudget(
    override val totalTimeMs: Long,
    private val emergencyReserveMs: Long = 200
) : TimeBudget {
    
    private var startTime: Long = 0
    private var stopped: Boolean = false
    
    override fun start() {
        startTime = System.currentTimeMillis()
    }
    
    override fun elapsedMs(): Long {
        return if (startTime == 0L) 0 else System.currentTimeMillis() - startTime
    }
    
    override fun remainingMs(): Long {
        return totalTimeMs - elapsedMs()
    }
    
    override fun hasBudget(): Boolean {
        if (stopped) return false
        return remainingMs() > emergencyReserveMs
    }
    
    override fun emergencyStop() {
        stopped = true
    }
}