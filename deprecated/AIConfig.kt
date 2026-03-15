package io.github.ian_miller.wuziqi.ai

// ⚠️ DEPRECATED: C++ 数据结构桥接层，已由 RustAi.kt 取代。
// 此文件通过 sourceSets 排除，不参与编译。

/**
 * 棋子颜色（与 C++ Color 枚举对应）
 */
object Color {
    const val EMPTY = 0
    const val BLACK = 1
    const val WHITE = 2
}

/**
 * AI 每步计算的动态配置（传入 C++）
 * 
 * 字段顺序和类型必须与 C++ AI_CONFIG 结构匹配
 */
data class AIConfig(
    // 棋盘状态（位棋盘表示）
    val blackBits: LongArray = LongArray(4) { 0L },
    val whiteBits: LongArray = LongArray(4) { 0L },
    
    // 当前玩家（需要下子的一方）
    val currentPlayer: Int = Color.BLACK,
    
    // 最后一步棋的信息
    val lastMoveColor: Int = Color.EMPTY,
    val lastMoveRow: Byte = -1,
    val lastMoveCol: Byte = -1,
    
    // 时间控制（截止时间戳，毫秒，Unix时间戳）
    // 0 表示使用 timeLimitMs
    val deadlineTimestampMs: Long = 0L,
    
    // 备选：相对时间限制（毫秒）
    val timeLimitMs: Int = 5000,
    
    // 搜索配置
    val maxDepth: Int = 6
) {
    companion object {
        fun fromBoard(
            board: Array<IntArray>,
            currentPlayer: Int,
            lastMoveColor: Int = Color.EMPTY,
            lastMoveRow: Int = -1,
            lastMoveCol: Int = -1,
            timeLimitMs: Int = 5000,
            deadlineTimestampMs: Long = 0L,
            maxDepth: Int = 6
        ): AIConfig {
            val (blackBits, whiteBits) = board.toBitBoard()
            return AIConfig(
                blackBits = blackBits,
                whiteBits = whiteBits,
                currentPlayer = currentPlayer,
                lastMoveColor = lastMoveColor,
                lastMoveRow = lastMoveRow.toByte(),
                lastMoveCol = lastMoveCol.toByte(),
                deadlineTimestampMs = deadlineTimestampMs,
                timeLimitMs = timeLimitMs,
                maxDepth = maxDepth
            )
        }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AIConfig) return false
        return currentPlayer == other.currentPlayer &&
               lastMoveColor == other.lastMoveColor &&
               lastMoveRow == other.lastMoveRow &&
               lastMoveCol == other.lastMoveCol &&
               deadlineTimestampMs == other.deadlineTimestampMs &&
               timeLimitMs == other.timeLimitMs &&
               maxDepth == other.maxDepth &&
               blackBits.contentEquals(other.blackBits) &&
               whiteBits.contentEquals(other.whiteBits)
    }
    
    override fun hashCode(): Int {
        var result = blackBits.contentHashCode()
        result = 31 * result + whiteBits.contentHashCode()
        result = 31 * result + currentPlayer
        result = 31 * result + lastMoveColor
        result = 31 * result + lastMoveRow
        result = 31 * result + lastMoveCol
        result = 31 * result + deadlineTimestampMs.hashCode()
        result = 31 * result + timeLimitMs
        result = 31 * result + maxDepth
        return result
    }
}

/**
 * AI 思考结果（与 C++ AI_RESULT 对应）
 */
data class AIResult(
    val row: Byte,
    val col: Byte,
    val score: Short,
    val nodes: Int,
    val timeMs: Short,
    val completedDepth: Byte,
    val success: Boolean,
    val wasTimeout: Boolean = false,
    val wasUserStop: Boolean = false,
    val iterationsCompleted: Byte = 0,
    val startDepth: Byte = 0,
    val endDepth: Byte = 0,
    val errorMsg: String = ""
) {
    val rowInt: Int get() = row.toInt()
    val colInt: Int get() = col.toInt()
    val scoreInt: Int get() = score.toInt()
    val timeMsInt: Int get() = timeMs.toInt()
    val completedDepthInt: Int get() = completedDepth.toInt()
    
    val hasError: Boolean get() = errorMsg.isNotEmpty()
    
    companion object {
        fun invalid(): AIResult = AIResult(
            row = -1,
            col = -1,
            score = 0,
            nodes = 0,
            timeMs = 0,
            completedDepth = 0,
            success = false
        )
    }
}
