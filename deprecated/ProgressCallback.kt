package io.github.ian_miller.wuziqi.ai

// ⚠️ DEPRECATED: C++ AI 进度回调接口，已由 RustAi.kt 取代。
// 此文件通过 sourceSets 排除，不参与编译。

/**
 * AI 搜索进度回调接口
 * 
 * C++ 每 100ms 调用一次，用于实时更新 UI
 */
interface ProgressCallback {
    /**
     * 进度更新回调
     * 
     * @param data 进度数据（在 C++ 线程调用，实现方需切换到主线程）
     */
    fun onProgress(data: ProgressData)
}

/**
 * 进度数据类（与 C++ ProgressData 对应）
 * 注意：字段顺序必须与 C++ 结构体完全一致
 */
data class ProgressData(
    val status: Int = 0,              // 0=IDLE, 1=RUNNING, 2=COMPLETED, 3=STOPPED
    val bestMoveRow: Int = -1,        // 当前最佳走法行
    val bestMoveCol: Int = -1,        // 当前最佳走法列
    val score: Int = 0,               // 评估分数
    val currentDepth: Int = 0,        // 当前搜索深度
    val completedDepth: Int = 0,      // 已完成深度
    val nodesVisited: Long = 0,       // 访问节点数
    val nodesPerSecond: Long = 0,     // NPS
    val elapsedTimeMs: Long = 0,      // 已用时间
    val progressPercent: Int = 0,     // 进度百分比
    val currentPlayer: Int = 0,       // 当前玩家
    val phase: Int = 0,               // 当前阶段
    val memoryUsedMB: Long = 0,       // 内存使用
    val peakMemoryMB: Long = 0,       // 峰值内存
    val evaluatorCount: Long = 0,     // 评估器调用计数
    val pvLength: Int = 0,            // PV 长度
    val pv: IntArray = IntArray(20) { -1 }  // PV 数据（10步 × 2）
) {
    /**
     * 将 PV 转换为 (row, col) 列表
     */
    fun getPvList(): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until minOf(pvLength, 10)) {
            val row = pv[i * 2]
            val col = pv[i * 2 + 1]
            if (row >= 0 && col >= 0) {
                result.add(row to col)
            }
        }
        return result
    }
    
    companion object {
        const val PV_MAX_LENGTH = 10
    }
}
