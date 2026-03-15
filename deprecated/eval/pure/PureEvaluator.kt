package io.github.ian_miller.wuziqi.ai.eval.pure

import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.PieceColor

/**
 * 纯粹评估器接口
 * 
 * 与旧架构 PositionEvaluator 的关键区别：
 * 1. 同步函数（非 suspend），快速返回
 * 2. 返回简单 Int（分数），无复杂对象
 * 3. 无时间管理，无状态，纯函数
 * 
 * 时间管理由 Search 层统一控制。
 * 
 * @author AI Assistant
 * @since 0.04
 */
interface PureEvaluator {
    
    /**
     * 评估器名称
     */
    val name: String
    
    /**
     * 评估棋盘分数
     * 
     * @param board 当前棋盘状态
     * @param player 评估视角（该玩家的有利程度）
     * @return 分数，正值表示对 player 有利
     */
    fun evaluate(board: Board, player: PieceColor): Int
}
