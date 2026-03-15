package io.github.ian_miller.wuziqi.ai

// ⚠️ DEPRECATED: 与 C++ 思考结果对应的数据类，已由 RustAi.kt 取代。
// 此文件通过 sourceSets 排除，不参与编译。

/**
 * AI 思考结果数据类
 * 
 * 用于 JNI 桥接，字段顺序和类型必须与 C++ 结构匹配
 * 
 * @param row 行坐标 (0-14)，-1 表示无效
 * @param col 列坐标 (0-14)，-1 表示无效
 * @param score 评估分数
 * @param nodes 搜索节点数
 * @param timeMs 耗时（毫秒）
 * @param completedDepth 完成的搜索深度
 * @param success 是否成功找到走法
 * @param wasTimeout 是否因超时终止（反馈信息）
 * @param wasUserStop 是否被用户停止（反馈信息）
 * @param iterationsCompleted 完成了几层迭代（反馈信息）
 * @param startDepth 本次从哪开始（反馈信息）
 * @param endDepth 本次搜到哪（反馈信息）
 */
data class ThinkResult(
    val row: Byte,
    val col: Byte,
    val score: Short,
    val nodes: Int,
    val timeMs: Short,
    val completedDepth: Byte,
    val success: Boolean,
    // 反馈信息（用于自适应策略调整）
    val wasTimeout: Boolean = false,
    val wasUserStop: Boolean = false,
    val iterationsCompleted: Byte = 0,
    val startDepth: Byte = 0,
    val endDepth: Byte = 0,
    // Debug 信息
    val errorMsg: String = ""
) {
    /**
     * 将 Byte 转为 Int，处理有符号转换
     */
    val rowInt: Int get() = row.toInt()
    val colInt: Int get() = col.toInt()
    val scoreInt: Int get() = score.toInt()
    val timeMsInt: Int get() = timeMs.toInt()
    val completedDepthInt: Int get() = completedDepth.toInt()
    val iterationsCompletedInt: Int get() = iterationsCompleted.toInt()
    val startDepthInt: Int get() = startDepth.toInt()
    val endDepthInt: Int get() = endDepth.toInt()
    
    /**
     * 是否有错误信息
     */
    val hasError: Boolean get() = errorMsg.isNotEmpty()
    
    companion object {
        /**
         * 创建无效结果（用于取消或错误情况）
         */
        fun invalid(): ThinkResult = ThinkResult(
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
