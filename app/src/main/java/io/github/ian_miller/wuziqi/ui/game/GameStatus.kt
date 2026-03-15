package io.github.ian_miller.wuziqi.ui.game

/**
 * 游戏阶段枚举（UI 层使用）
 * 从 State（ADT）派生，用于简化 UI 条件判断
 */
enum class GameStatus {
    NOT_STARTED,  // 未开始（Initializing / Idle）
    PLAYING,      // 进行中（WaitingForPlayer / WaitingForAi / Pausing / Paused）
    FINISHED      // 已结束（GameOver）
}
