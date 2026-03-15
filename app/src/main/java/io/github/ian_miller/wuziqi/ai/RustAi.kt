package io.github.ian_miller.wuziqi.ai

/**
 * Rust AI 引擎的 Kotlin 封装
 * 
 * 管理 Rust 端 AI 对象的生命周期
 * 支持多实例（可用于 AI 自对弈）
 * 
 * 架构说明：
 * - should_stop 标志只能由 Kotlin 端通过 JNI 修改（requestStop）
 * - Rust 端只读取 should_stop，不修改
 * - co_validate 内部有调用频率控制（每 1024 次调用检查一次）
 */
class RustAi private constructor(
    private val nativePtr: Long,
    val config: RustAiConfig
) {
    private var isDestroyed = false
    
    // 进度回调接口（暂不使用，避免 JNI 开销）
    interface ProgressListener {
        fun onProgress(depth: Int, score: Int, nodes: Long)
    }
    
    companion object {
        init {
            System.loadLibrary("gomoku_rust")
        }
        
        /**
         * 创建新的 AI 实例
         * 
         * @param maxDepth 最大搜索深度
         * @param timeLimitMs 时间限制（毫秒）
         * @param player AI 执棋颜色（1=黑，2=白）
         */
        @JvmStatic
        fun create(
            maxDepth: Int = 4,
            timeLimitMs: Int = 5000,
            player: Int = 1
        ): RustAi? {
            val ptr = nativeCreate(maxDepth, timeLimitMs, player)
            return if (ptr != 0L) RustAi(ptr, RustAiConfig(maxDepth, timeLimitMs, player)) else null
        }
        
        /**
         * 创建 MCTS AI 实例（EASY / MEDIUM 难度）
         *
         * @param timeLimitMs 时间限制（毫秒）
         * @param player AI 执棋颜色（1=黑，2=白）
         * @param explorationCx100 UCB1 探索常数 × 100（EASY=200，MEDIUM=120）
         */
        @JvmStatic
        fun createMcts(
            timeLimitMs: Int,
            player: Int,
            explorationCx100: Int
        ): RustAi? {
            val ptr = nativeCreateMcts(timeLimitMs, player, explorationCx100)
            return if (ptr != 0L) RustAi(ptr, RustAiConfig(0, timeLimitMs, player)) else null
        }

        /**
         * 测试多实例支持
         */
        @JvmStatic
        fun testMultiInstance(): Boolean = nativeTestMultiInstance()
        
        // Native 方法
        @JvmStatic
        private external fun nativeCreate(maxDepth: Int, timeLimitMs: Int, player: Int): Long

        @JvmStatic
        private external fun nativeCreateMcts(timeLimitMs: Int, player: Int, explorationCx100: Int): Long
        
        @JvmStatic
        private external fun nativeTestMultiInstance(): Boolean
    }
    
    // Native 方法（仅保留核心生命周期方法）
    private external fun nativeDestroy(ptr: Long)
    private external fun nativeClear(ptr: Long)
    private external fun nativeTakeTurn(ptr: Long, boardData: ByteArray): Int
    private external fun nativeInvalidate(ptr: Long)
    private external fun nativeValidate(ptr: Long)
    
    /**
     * 执行思考（takeTurn）
     * 
     * @param board 当前棋盘（225字节数组，0=空，1=黑，2=白）
     * @return 走法（row to col），如果取消则返回 null
     */
    fun takeTurn(board: ByteArray): Pair<Int, Int>? {
        checkDestroyed()
        val result = nativeTakeTurn(nativePtr, board)
        return if (result >= 0) result.toPosition() else null
    }
    
    /**
     * 将 JNI 返回的 Int 转为坐标对
     */
    private fun Int.toPosition(): Pair<Int, Int>? {
        if (this < 0) return null
        return (this / 15) to (this % 15)
    }
    
    /**
     * 使当前计算失效（由 Kotlin 端调用）
     * 通知 AI 尽快停止当前思考，返回当前最佳结果或 null
     */
    fun invalidate() {
        if (!isDestroyed) {
            nativeInvalidate(nativePtr)
        }
    }
    
    /**
     * 恢复计算有效（由 Kotlin 端调用）
     * 重置 should_stop 标志为 false，在启动/恢复 AI 前调用
     */
    fun validate() {
        if (!isDestroyed) {
            nativeValidate(nativePtr)
        }
    }
    
    /**
     * 清理资源（重置内部状态，但不修改 should_stop）
     */
    fun clear() {
        checkDestroyed()
        nativeClear(nativePtr)
    }
    
    /**
     * 销毁对象并释放内存
     */
    fun destroy() {
        if (!isDestroyed) {
            nativeDestroy(nativePtr)
            isDestroyed = true
        }
    }
    
    private fun checkDestroyed() {
        if (isDestroyed) {
            throw IllegalStateException("RustAi instance has been destroyed")
        }
    }
    
    protected fun finalize() {
        if (!isDestroyed) {
            destroy()
        }
    }
}

/**
 * Rust AI 内部配置（不与 AIConfig.kt 冲突）
 */
data class RustAiConfig(
    val maxDepth: Int = 4,
    val timeLimitMs: Int = 5000,
    val player: Int = 1
)
