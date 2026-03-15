package io.github.ian_miller.wuziqi.domain.logic

import io.github.ian_miller.wuziqi.ai.ModernAi
import io.github.ian_miller.wuziqi.ai.think.budget.InMemoryFactorHistory
import io.github.ian_miller.wuziqi.domain.model.*

/**
 * 游戏引擎，协调游戏流程
 *
 * @deprecated 已由 GameViewModelV2 直接调用 RustAi 取代，此文件通过 sourceSets 排除，不参与编译。
 */
@Deprecated(
    message = "已由 GameViewModelV2 + RustAi 取代。此文件通过 sourceSets 排除，不参与编译。",
    level = DeprecationLevel.ERROR
)
class GameEngine(
    private var gameState: GameState = GameState.initial(),
    private var gameMode: GameMode = GameMode.VS_HUMAN,
    private var difficulty: Difficulty = Difficulty.MEDIUM,
    private var aiPlayer: PieceColor = PieceColor.WHITE
) {
    private val history = mutableListOf<GameState>()
    private val moveHistory = mutableListOf<Piece>()
    private var ai: ModernAi = createAi()

    init {
        history.add(gameState)
    }

    /**
     * 获取移动历史
     */
    fun getMoveHistory(): List<Piece> = moveHistory.toList()

    /**
     * 恢复游戏
     */
    fun restoreGame(moves: List<Piece>, mode: GameMode, diff: Difficulty, aiColor: PieceColor) {
        reset()
        this.gameMode = mode
        this.difficulty = diff
        this.aiPlayer = aiColor
        this.ai = createAi()

        for (move in moves) {
            try {
                // 直接应用移动，绕过校验
                val newState = gameState.placePiece(move.row, move.col)
                gameState = newState
                history.add(newState)
                moveHistory.add(move)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 当前游戏状态
     */
    fun getCurrentState(): GameState = gameState

    /**
     * 获取游戏模式
     */
    fun getGameMode(): GameMode = gameMode

    /**
     * 获取难度
     */
    fun getDifficulty(): Difficulty = difficulty

    /**
     * 获取 AI 玩家颜色
     */
    fun getAiPlayer(): PieceColor = aiPlayer

    /**
     * 玩家落子
     * @return 如果落子成功并更新状态则返回 true
     */
    fun placePiece(row: Int, col: Int): Boolean {
        if (gameState.isGameOver()) return false

        // 如果是人机对战且当前玩家是 AI，则不允许玩家落子
        if (gameMode == GameMode.VS_AI && gameState.currentPlayer == aiPlayer) {
            return false
        }

        try {
            // 记录此次移动的颜色（即当前执子方）
            val moveColor = gameState.currentPlayer
            val newState = gameState.placePiece(row, col)
            updateState(newState)
            moveHistory.add(Piece(row, col, moveColor))
            return true
        } catch (e: IllegalArgumentException) {
            // 无效落子
            return false
        }
    }

    /**
     * 请求 AI 落子（如果当前轮到 AI）
     * @return AI 落子的位置，如果 AI 无法落子则返回 null
     */
    suspend fun aiMove(): Pair<Int, Int>? {
        if (gameState.isGameOver()) return null
        if (gameMode != GameMode.VS_AI) return null
        if (gameState.currentPlayer != aiPlayer) return null

        // 使用 Minimax AI 计算最佳落子
        val (row, col) = try {
            val move = ai.findBestMove(gameState.board, aiPlayer)
            // 安全校验：如果AI发疯返回了已占用的格子
            if (gameState.board.getPiece(move.first, move.second) != null) {
                throw IllegalStateException("AI returned occupied position: $move")
            }
            move
        } catch (e: Exception) {
            e.printStackTrace()
            // 回退：随机选择一个空位
            val emptyCells = mutableListOf<Pair<Int, Int>>()
            for (r in 0 until Board.SIZE) {
                for (c in 0 until Board.SIZE) {
                    if (gameState.board.getPiece(r, c) == null) {
                        emptyCells.add(r to c)
                    }
                }
            }
            if (emptyCells.isEmpty()) return null
            emptyCells.random()
        }
        
        try {
            // 记录 AI 移动
            val moveColor = gameState.currentPlayer
            // 直接更新游戏状态（绕过 placePiece 的玩家校验）
            val newState = gameState.placePiece(row, col)
            updateState(newState)
            moveHistory.add(Piece(row, col, moveColor))
            return row to col
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * 撤销上一步
     * @return 如果撤销成功则返回 true
     */
    fun undo(): Boolean {
        if (history.size <= 1) return false
        history.removeAt(history.size - 1)
        if (moveHistory.isNotEmpty()) {
            moveHistory.removeAt(moveHistory.size - 1)
        }
        gameState = history.last()
        return true
    }

    /**
     * 重做（如果支持）
     */
    fun redo(): Boolean {
        // 简化版本：不支持重做
        return false
    }

    /**
     * 重置游戏
     */
    fun reset() {
        gameState = GameState.initial()
        history.clear()
        moveHistory.clear()
        history.add(gameState)
    }

    /**
     * 切换游戏模式
     */
    fun setGameMode(mode: GameMode) {
        gameMode = mode
        // 如果切换到人机对战，AI 玩家颜色保持不变
    }

    /**
     * 设置难度
     */
    fun setDifficulty(difficulty: Difficulty) {
        this.difficulty = difficulty
        ai = createAi()
    }

    /**
     * 设置 AI 玩家颜色
     */
    fun setAiPlayer(color: PieceColor) {
        aiPlayer = color
    }

    /**
     * 计算辅助提示（不改变游戏状态）
     * 使用大师模式提供最强分析
     * @return 最佳落子位置
     */
    suspend fun calculateAssistMove(): Pair<Int, Int>? {
        if (gameState.isGameOver()) return null

        // 使用新架构的 ModernAi，大师模式给13秒
        val hintAi = ModernAi(
            difficulty = Difficulty.MASTER,
            factorHistory = InMemoryFactorHistory()
        )

        return try {
            hintAi.findBestMove(gameState.board, gameState.currentPlayer)
        } catch (e: Exception) {
            e.printStackTrace()
            // 如果大师模式失败，回退到困难模式
            fallbackHint()
        }
    }
    
    /**
     * 提示计算失败时的回退方案
     */
    private suspend fun fallbackHint(): Pair<Int, Int>? {
        return try {
            val fallbackAi = ModernAi(
                difficulty = Difficulty.HARD
            )
            fallbackAi.findBestMove(gameState.board, gameState.currentPlayer)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun updateState(newState: GameState) {
        gameState = newState
        history.add(newState)
    }

    private fun createAi(): ModernAi {
        return ModernAi(difficulty = difficulty)
    }
}