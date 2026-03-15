package io.github.ian_miller.wuziqi.ai

import io.github.ian_miller.wuziqi.ai.eval.composite.CompositeEvaluator
import io.github.ian_miller.wuziqi.ai.eval.pattern.PatternEvaluator
import io.github.ian_miller.wuziqi.ai.eval.pure.PureEvaluator
import io.github.ian_miller.wuziqi.ai.eval.strategic.StrategicEvaluator
import io.github.ian_miller.wuziqi.ai.movegen.MoveGenerator
import io.github.ian_miller.wuziqi.ai.think.algorithm.minimax.MinimaxSearch
import io.github.ian_miller.wuziqi.ai.think.budget.FactorHistory
import io.github.ian_miller.wuziqi.ai.think.budget.InMemoryFactorHistory
import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.Difficulty
import io.github.ian_miller.wuziqi.domain.model.PieceColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Deprecated(
    message = "旧版 Kotlin AI 引擎，已由 RustAi.kt（Rust via JNI）完全取代。此文件通过 sourceSets 排除，不参与编译。",
    level = DeprecationLevel.ERROR
)
/**
 * 现代 AI（新架构）
 *
 * 使用新架构的 AI：
 * - PureEvaluator: 纯粹评估，无时间管理
 * - MinimaxSearch: 搜索层时间管理，深度动态调整
 * - FactorHistory: 跨落子历史学习
 * 
 * @param difficulty 难度等级
 * @param factorHistory 深度历史（跨落子共享，可选）
 * @author AI Assistant
 * @since 0.04
 */
class ModernAi(
    private val difficulty: Difficulty = Difficulty.MEDIUM,
    private val factorHistory: FactorHistory = InMemoryFactorHistory()
) {
    
    // 根据难度选择评估器
    private val evaluator: PureEvaluator = when (difficulty) {
        Difficulty.EASY -> PatternEvaluator()
        Difficulty.MEDIUM -> PatternEvaluator()
        Difficulty.HARD -> CompositeEvaluator.Builder()
            .add(PatternEvaluator(), 0.7)
            .add(StrategicEvaluator(), 0.3)
            .build()
        Difficulty.MASTER -> CompositeEvaluator.Builder()
            .add(StrategicEvaluator(), 0.7)
            .add(PatternEvaluator(), 0.3)
            .build()
    }
    
    // 根据难度设置总时间
    private val totalTimeMs: Long = when (difficulty) {
        Difficulty.EASY -> 1000
        Difficulty.MEDIUM -> 3000
        Difficulty.HARD -> 5000
        Difficulty.MASTER -> 10000
    }
    
    // 走法生成器
    private val moveGenerator = MoveGenerator()
    
    // 搜索实例（每次搜索创建新的，但共享历史）
    private val search: MinimaxSearch
        get() = MinimaxSearch(
            evaluator = evaluator,
            moveGenerator = moveGenerator,
            totalTimeMs = totalTimeMs,
            factorHistory = factorHistory
        )
    
    /**
     * 查找最佳走法
     * 
     * @param board 当前棋盘
     * @param player 当前玩家
     * @return 最佳走法 (row, col)
     */
    suspend fun findBestMove(board: Board, player: PieceColor): Pair<Int, Int> = withContext(Dispatchers.Default) {
        search.findBestMove(board, player)
    }
}