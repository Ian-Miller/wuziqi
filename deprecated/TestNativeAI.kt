package io.github.ian_miller.wuziqi.ai

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * C++ AI 实例的 Kotlin 封装
 * 
 * 设计原则：
 * 1. 纯封装，不管理协程调度（由调用方控制）
 * 2. suspend 函数仅标记耗时操作，不切换调度器
 * 3. 所有调度决策在调用方最外层确定（Dispatcher.Default）
 */
class TestNativeAI private constructor(private val ptr: Long) {

    companion object {
        private const val TAG = "TestNativeAI"

        /**
         * 创建 AI 实例
         * 
         * @param maxDepth 最大搜索深度
         * @param timeLimitMs 每步时间限制（毫秒）
         * @param ttSizeMB 置换表大小（MB）
         * @param useTT 是否使用置换表
         * @return AI 实例，创建失败返回 null
         */
        fun create(
            maxDepth: Int = 6,
            timeLimitMs: Int = 5000,
            ttSizeMB: Int = 64,
            useTT: Boolean = true
        ): TestNativeAI? {
            val ptr = nativeCreate(maxDepth, timeLimitMs, ttSizeMB, useTT)
            return if (ptr != 0L) {
                Log.d(TAG, "Created AI instance at $ptr")
                TestNativeAI(ptr)
            } else {
                Log.e(TAG, "Failed to create AI instance")
                null
            }
        }

        // JNI 方法声明
        @JvmStatic
        private external fun nativeCreate(
            maxDepth: Int,
            timeLimitMs: Int,
            ttSizeMB: Int,
            useTT: Boolean
        ): Long

        @JvmStatic
        private external fun nativeDestroy(ptr: Long)

        @JvmStatic
        private external fun nativeStart(ptr: Long)

        @JvmStatic
        @Deprecated("使用 nativeThinkWithCallback 替代")
        private external fun nativeThink(
            ptr: Long,
            blackBits: LongArray,
            whiteBits: LongArray,
            player: Int,
            progressPtr: Long = 0
        ): ThinkResult

        @JvmStatic
        private external fun nativeStop(ptr: Long)

        @JvmStatic
        private external fun nativeReset(ptr: Long)

        @JvmStatic
        private external fun nativeGetStats(
            ptr: Long,
            outNodes: LongArray?,
            outHitRate: IntArray?,
            outSize: IntArray?,
            outCapacity: IntArray?
        )

        @JvmStatic
        private external fun nativeIsThinking(ptr: Long): Boolean

        @JvmStatic
        @Deprecated("对局记录已移至 ViewModel 层，不再使用")
        private external fun nativeGetGameCSV(ptr: Long): String

        @JvmStatic
        @Deprecated("对局记录已移至 ViewModel 层，不再使用")
        private external fun nativeGetGameLog(ptr: Long): String

        @JvmStatic
        @Deprecated("对局记录已移至 ViewModel 层，不再使用")
        private external fun nativeAddMoveRecord(
            ptr: Long,
            moveNumber: Int,
            player: Int,
            row: Int,
            col: Int,
            score: Int,
            depth: Int,
            nodes: Int,
            timeMs: Int
        )

        @JvmStatic
        @Deprecated("对局记录已移至 ViewModel 层，不再使用")
        private external fun nativeClearMoveRecords(ptr: Long)

        @JvmStatic
        private external fun nativeThinkWithCallback(
            ptr: Long,
            blackBits: LongArray,
            whiteBits: LongArray,
            player: Int,
            callback: ProgressCallback?
        ): ThinkResult

        init {
            System.loadLibrary("gomoku-ai")
        }
    }

    private val isDestroyed = AtomicBoolean(false)

    // ThinkResult 现在定义为独立的类（见 ThinkResult.kt）

    /**
     * AI 统计信息
     */
    data class Stats(
        val totalNodesSearched: Long,
        val ttHitRate: Int,
        val ttSize: Int,
        val ttCapacity: Int
    )

    // ========================================================================
    // 同步方法（立即返回，非阻塞）
    // ========================================================================

    /**
     * 准备开始思考
     * 
     * 重置 stopFlag 和统计信息，必须在 think 前调用
     * 同步方法，立即返回
     */
    fun start() {
        checkNotDestroyed()
        Log.d(TAG, "Starting AI at $ptr")
        nativeStart(ptr)
    }

    /**
     * 请求停止思考
     * 
     * 立即设置停止标志，AI 会在下一个检查点退出
     * 同步方法，立即返回，不等待实际停止
     */
    fun stop() {
        if (!isDestroyed()) {
            nativeStop(ptr)
        }
    }

    /**
     * 重置 AI 状态
     * 
     * 清空置换表，准备新局
     * 同步方法，立即返回
     */
    fun reset() {
        checkNotDestroyed()
        nativeReset(ptr)
    }

    /**
     * 获取统计信息
     * 
     * 同步方法，立即返回
     */
    fun getStats(): Stats {
        checkNotDestroyed()

        val nodesArray = LongArray(1)
        val hitRateArray = IntArray(1)
        val sizeArray = IntArray(1)
        val capacityArray = IntArray(1)

        nativeGetStats(ptr, nodesArray, hitRateArray, sizeArray, capacityArray)

        return Stats(
            totalNodesSearched = nodesArray[0],
            ttHitRate = hitRateArray[0],
            ttSize = sizeArray[0],
            ttCapacity = capacityArray[0]
        )
    }

    /**
     * 检查是否正在思考
     * 
     * 同步方法，立即返回
     */
    fun isThinking(): Boolean {
        return !isDestroyed() && nativeIsThinking(ptr)
    }

    /**
     * 检查是否已被销毁
     */
    fun isDestroyed(): Boolean = isDestroyed.get()

    /**
     * 获取 CSV 格式的对局记录
     * 
     * **已废弃**：对局记录已移至 ViewModel 层，请使用 ViewModel 生成 CSV。
     * 
     * 同步方法，立即返回
     */
    @Deprecated(
        message = "对局记录已移至 ViewModel 层，请使用 ViewModel 生成 CSV",
        replaceWith = ReplaceWith("viewModel.generateGameCSV()")
    )
    fun getGameCSV(): String {
        checkNotDestroyed()
        return nativeGetGameCSV(ptr)
    }

    /**
     * 获取详细日志文本
     * 
     * **已废弃**：对局记录已移至 ViewModel 层，请使用 ViewModel 生成日志。
     * 
     * 同步方法，立即返回
     */
    @Deprecated(
        message = "对局记录已移至 ViewModel 层，请使用 ViewModel 生成日志",
        replaceWith = ReplaceWith("viewModel.generateGameLog()")
    )
    fun getGameLog(): String {
        checkNotDestroyed()
        return nativeGetGameLog(ptr)
    }

    /**
     * 添加走法记录（自我对弈时使用）
     * 
     * **已废弃**：对局记录已移至 ViewModel 层，不再需要向 AI 层添加记录。
     * 
     * 同步方法，立即返回
     */
    @Deprecated(
        message = "对局记录已移至 ViewModel 层，不再需要向 AI 层添加记录",
        replaceWith = ReplaceWith("")
    )
    fun addMoveRecord(
        moveNumber: Int,
        player: Int,
        row: Int,
        col: Int,
        score: Int,
        depth: Int,
        nodes: Int,
        timeMs: Int
    ) {
        checkNotDestroyed()
        nativeAddMoveRecord(ptr, moveNumber, player, row, col, score, depth, nodes, timeMs)
    }

    /**
     * 清空走法记录（新对局前调用）
     * 
     * **已废弃**：对局记录已移至 ViewModel 层，不再需要管理 AI 层记录。
     * 
     * 同步方法，立即返回
     */
    @Deprecated(
        message = "对局记录已移至 ViewModel 层，不再需要管理 AI 层记录",
        replaceWith = ReplaceWith("")
    )
    fun clearMoveRecords() {
        checkNotDestroyed()
        nativeClearMoveRecords(ptr)
    }

    // ========================================================================
    // 耗时操作（suspend 函数）
    // 
    // 注意：这些函数仅在调用方的协程上下文中执行，不切换调度器
    // 调用方应确保在 Dispatcher.Default 上调用（CPU 密集型计算）
    // ========================================================================

    /**
     * 执行思考（suspend 函数）
     * 
     * **已废弃**：请使用 [thinkWithCallback] 替代，提供更好的实时进度反馈。
     * 
     * 注意：此函数不会切换调度器，调用方应确保在合适的调度器上调用
     * 
     * @param blackBits 黑子位棋盘（4 个 Long）
     * @param whiteBits 白子位棋盘（4 个 Long）
     * @param player 当前玩家（0=黑，1=白）
     * @param progressPtr 可选的共享内存指针
     * @return 思考结果
     */
    @Deprecated(
        message = "使用 thinkWithCallback 替代，提供实时进度回调",
        replaceWith = ReplaceWith("thinkWithCallback(blackBits, whiteBits, player, onProgress)")
    )
    suspend fun think(
        blackBits: LongArray,
        whiteBits: LongArray,
        player: Int,
        progressPtr: Long = 0
    ): ThinkResultInternal {
        checkNotDestroyed()
        checkBitsArray(blackBits)
        checkBitsArray(whiteBits)

        // 注册取消回调，确保协程取消时停止 C++ 搜索
        val job = coroutineContext.job
        val cancellationHandle = job.invokeOnCompletion { cause ->
            if (cause != null) {  // 协程被取消或异常完成
                Log.d(TAG, "Think cancelled, stopping AI at $ptr")
                nativeStop(ptr)
            }
        }

        return try {
            // 不切换调度器，直接使用调用方的协程上下文
            val result = nativeThink(ptr, blackBits, whiteBits, player, progressPtr)
            result.toInternal()
        } finally {
            // 清理取消回调
            cancellationHandle.dispose()
        }
    }

    /**
     * 执行思考（带实时进度回调）
     * 
     * 通过 JNI 回调每 100ms 更新一次进度，无需轮询共享内存
     * 
     * @param blackBits 黑子位棋盘（4 个 Long）
     * @param whiteBits 白子位棋盘（4 个 Long）
     * @param player 当前玩家（0=黑，1=白）
     * @param onProgress 进度回调（在 C++ 线程调用，实现方需切换到主线程）
     * @return 思考结果
     */
    suspend fun thinkWithCallback(
        blackBits: LongArray,
        whiteBits: LongArray,
        player: Int,
        onProgress: (ProgressData) -> Unit
    ): ThinkResultInternal {
        checkNotDestroyed()
        checkBitsArray(blackBits)
        checkBitsArray(whiteBits)

        // 注册取消回调
        val job = currentCoroutineContext().job
        val cancellationHandle = job.invokeOnCompletion { cause ->
            if (cause != null) {
                Log.d(TAG, "ThinkWithCallback cancelled, stopping AI at $ptr")
                nativeStop(ptr)
            }
        }

        return try {
            // 创建回调接口实现
            val callback = object : ProgressCallback {
                override fun onProgress(data: ProgressData) {
                    // 线程由kotlin协程提供，无需C++线程切换
                    onProgress(data)
                }
            }

            val result = nativeThinkWithCallback(
                ptr, blackBits, whiteBits, player, callback
            )
            result.toInternal()
        } finally {
            cancellationHandle.dispose()
        }
    }
    
    /**
     * 内部使用的思考结果（使用 Int 便于 Kotlin 计算）
     */
    data class ThinkResultInternal(
        val row: Int,
        val col: Int,
        val score: Int,
        val nodes: Int,
        val timeMs: Int,
        val completedDepth: Int,
        val success: Boolean
    )
    
    private fun ThinkResult.toInternal(): ThinkResultInternal {
        return ThinkResultInternal(
            row = rowInt,
            col = colInt,
            score = scoreInt,
            nodes = nodes,
            timeMs = timeMsInt,
            completedDepth = completedDepthInt,
            success = success
        )
    }

    /**
     * 停止并等待实际结束（suspend 函数）
     * 
     * 发送停止信号后，等待 AI 实际停止
     * 使用 yield 让出协程，不阻塞线程
     * 
     * 注意：此函数不会切换调度器，调用方应确保在合适的调度器上调用
     * 
     * @param timeoutMs 最大等待时间（毫秒），默认 5000ms
     * @return true 表示成功停止，false 表示超时
     */
    suspend fun stopAndWait(timeoutMs: Long = 5000): Boolean {
        if (isDestroyed()) return true

        stop()  // 发送停止信号

        return withTimeoutOrNull(timeoutMs) {
            while (isThinking()) {
                yield()  // 让出协程，不阻塞线程
            }
            true
        } ?: false
    }

    // ========================================================================
    // 生命周期管理
    // ========================================================================

    /**
     * 销毁 AI 实例
     * 
     * 释放 C++ 资源，销毁后不能再使用
     * 同步方法，立即返回
     */
    fun destroy() {
        if (isDestroyed.compareAndSet(false, true)) {
            Log.d(TAG, "Destroying AI instance at $ptr")
            nativeDestroy(ptr)
        }
    }

    /**
     * 用于 try-finally 或 use 函数
     */
    inline fun <T> use(block: (TestNativeAI) -> T): T {
        return try {
            block(this)
        } finally {
            destroy()
        }
    }

    private fun checkNotDestroyed() {
        check(!isDestroyed()) { "AI instance has been destroyed" }
    }

    private fun checkBitsArray(bits: LongArray) {
        require(bits.size == 4) { "Bits array must have exactly 4 elements" }
    }

    protected fun finalize() {
        if (!isDestroyed()) {
            Log.w(TAG, "Memory leak! AI instance at $ptr was not destroyed")
            destroy()
        }
    }
}

/**
 * 棋盘扩展函数：转换为位棋盘
 */
//fun Array<IntArray>.toBitBoard(): Pair<LongArray, LongArray> {
//    require(this.size == 15 && this.all { it.size == 15 }) {
//        "Board must be 15x15"
//    }
//
//    val black = LongArray(4)
//    val white = LongArray(4)
//
//    for (row in 0 until 15) {
//        for (col in 0 until 15) {
//            val pos = row * 15 + col
//            val index = pos / 64
//            val bit = pos % 64
//
//            when (this[row][col]) {
//                1 -> black[index] = black[index] or (1L shl bit)  // 黑子
//                2 -> white[index] = white[index] or (1L shl bit)  // 白子
//            }
//        }
//    }
//
//    return Pair(black, white)
//}

// ============================================================================
// 使用示例
// ============================================================================

/*
// 正确用法：调用方在最外层确定调度器为 Dispatcher.Default

class GameViewModel : ViewModel() {
    
    fun onPlayerMove(row: Int, col: Int) {
        // 在最外层指定调度器，后续所有 suspend 函数都在此调度器执行
        viewModelScope.launch(Dispatchers.Default) {
            val ai = TestNativeAI.create(depth = 6, timeLimitMs = 5000)
                ?: return@launch
            
            try {
                ai.start()
                
                val (blackBits, whiteBits) = board.toBitBoard()
                val result = ai.think(blackBits, whiteBits, player = 1)
                // think 不会切换调度器，仍在 Default 上执行
                
                withContext(Dispatchers.Main) {
                    placeStone(result.row, result.col)
                }
            } finally {
                ai.destroy()
            }
        }
    }
    
    fun onUndo() {
        viewModelScope.launch(Dispatchers.Default) {
            // 取消当前思考
            ai.stopAndWait()  // 在 Default 上等待，使用 yield 不阻塞
            ai.start()
            val newResult = ai.think(newBoardBits, player)
        }
    }
}

// 错误用法：在主线程直接调用 suspend 函数
viewModelScope.launch {  // 默认 Main 调度器
    ai.think(...)  // 会阻塞主线程！因为 think 不切换调度器
}

// 替代方案：调用方使用 withContext 包裹
viewModelScope.launch {
    val result = withContext(Dispatchers.Default) {
        ai.think(...)  // 显式切换到 Default
    }
}
*/
