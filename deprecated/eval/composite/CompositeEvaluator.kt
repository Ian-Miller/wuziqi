package io.github.ian_miller.wuziqi.ai.eval.composite

import io.github.ian_miller.wuziqi.ai.eval.pure.PureEvaluator
import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.PieceColor

/**
 * 组合评估器
 * 
 * 将多个评估器按权重组合，无时间分配逻辑。
 * 时间管理由上层 Search 控制。
 * 
 * @param evaluators 评估器及其权重列表
 * @author AI Assistant
 * @since 0.04
 */
class CompositeEvaluator(
    private val evaluators: List<Pair<PureEvaluator, Double>>
) : PureEvaluator {
    
    override val name: String = "Composite(${evaluators.joinToString(",") { it.first.name }})"
    
    init {
        require(evaluators.isNotEmpty()) { "至少需要一个评估器" }
        require(evaluators.all { it.second > 0 }) { "权重必须为正" }
    }
    
    override fun evaluate(board: Board, player: PieceColor): Int {
        val totalWeight = evaluators.sumOf { it.second }
        
        return evaluators.sumOf { (evaluator, weight) ->
            evaluator.evaluate(board, player) * weight
        }.toInt() / totalWeight.toInt()
    }
    
    /**
     * 构建器
     */
    class Builder {
        private val evaluators = mutableListOf<Pair<PureEvaluator, Double>>()
        
        fun add(evaluator: PureEvaluator, weight: Double): Builder {
            evaluators.add(evaluator to weight)
            return this
        }
        
        fun build(): CompositeEvaluator {
            return CompositeEvaluator(evaluators.toList())
        }
    }
}
