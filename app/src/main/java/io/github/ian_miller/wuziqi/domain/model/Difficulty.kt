package io.github.ian_miller.wuziqi.domain.model

/**
 * AI 难度等级
 */
enum class Difficulty {
    EASY,
    MEDIUM,
    HARD,
    MASTER;

    companion object {
        /**
         * 根据难度返回 AI 搜索深度
         */
        fun searchDepth(difficulty: Difficulty): Int = when (difficulty) {
            EASY -> 2    // 仅看2步
            MEDIUM -> 6  // 看6步
            HARD -> 12   // 尽可能深
            MASTER -> 20 // 大师级深度搜索
        }

        /**
         * 根据难度返回 AI 搜索宽度（每层扩展的最大节点数）
         */
        fun searchWidth(difficulty: Difficulty): Int = when (difficulty) {
            EASY -> 4    // 视野狭窄
            MEDIUM -> 10 // 正常视野
            HARD -> 20   // 全局视野
            MASTER -> 40 // 大师级广度搜索
        }

        /**
         * 思考时间限制 (毫秒)
         */
        fun timeLimit(difficulty: Difficulty): Long = when (difficulty) {
             EASY -> 500L
             MEDIUM -> 1500L
             HARD -> 4000L
             MASTER -> 12000L // 大师级深度思考（12秒）
        }

        /**
         * 是否启用随机扰动（使 AI 不完美）
         */
        fun enableRandomPerturbation(difficulty: Difficulty): Boolean = difficulty == EASY
    }
}