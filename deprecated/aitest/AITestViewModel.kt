@file:OptIn(kotlinx.coroutines.ObsoleteCoroutinesApi::class)

package io.github.ian_miller.wuziqi.ui.aitest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ian_miller.wuziqi.ai.RustAi
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * AI 自对弈 ViewModel - 简洁版（带中间状态）
 */
class AITestViewModel : ViewModel() {

    // ========== 状态定义（5种）==========
    sealed class State {
        data class Idle(
            val board: Array<IntArray> = Array(15) { IntArray(15) },
            val winner: String? = null  // 游戏结束时的获胜者
        ) : State() {
            companion object {
                fun create() = Idle()
            }
        }
        
        data class Running(
            val board: Array<IntArray>,
            val currentPlayer: Int,
            val moveCount: Int,
            val lastMove: Pair<Int, Int>?,
            val blackAi: RustAi,
            val whiteAi: RustAi
        ) : State()
        
        // 正在暂停：已通知 AI 停止，等待 AI 返回
        data class Pausing(
            val board: Array<IntArray>,
            val currentPlayer: Int,
            val moveCount: Int,
            val lastMove: Pair<Int, Int>?,
            val blackAi: RustAi,
            val whiteAi: RustAi
        ) : State()
        
        // 已暂停：AI 已返回，完全停止
        data class Paused(
            val board: Array<IntArray>,
            val currentPlayer: Int,
            val moveCount: Int,
            val lastMove: Pair<Int, Int>?,
            val blackAi: RustAi,
            val whiteAi: RustAi
        ) : State()
        
        // 延迟中：走子后等待，让用户看清落子
        data class Delaying(
            val board: Array<IntArray>,
            val nextPlayer: Int,        // 延迟后要切换到的玩家
            val moveCount: Int,
            val lastMove: Pair<Int, Int>,
            val blackAi: RustAi,
            val whiteAi: RustAi,
            val delayMs: Long = 500
        ) : State()
        
        // 正在停止：清理资源中
        data class Stopping(
            val board: Array<IntArray>,  // 保持棋盘显示
            val blackAi: RustAi,
            val whiteAi: RustAi,
            val waitingForAi: Boolean = false  // 是否正在等待 AI 返回
        ) : State()
    }

    sealed class Cmd {
        object Start : Cmd()
        object Pause : Cmd()
        object Resume : Cmd()
        object Stop : Cmd()
        data class AiDone(val move: Pair<Int, Int>?) : Cmd()
        object DelayComplete : Cmd()  // 延迟结束，继续下一步
    }

    // ========== 暂停协调器 ==========
    private val pauseCoordinator = PauseCoordinator(
        onPause = { actor.trySend(Cmd.Pause) },
        onResume = { actor.trySend(Cmd.Resume) }
    )

    // ========== UI 状态 ==========
    private val _state = MutableStateFlow<State>(State.Idle())
    val state: StateFlow<State> = _state

    // ========== Actor ==========
    private val actor = viewModelScope.actor<Cmd>(capacity = Channel.UNLIMITED) {
        var current: State = State.Idle()

        for (cmd in channel) {
            val next = transition(current, cmd)
            current = next
            _state.value = next
            
            // 触发副作用：Delaying 状态需要启动延迟
            if (next is State.Delaying && cmd !is Cmd.DelayComplete) {
                launchDelay(next)
            }
        }
    }

    // ========== 状态转换（纯函数）==========
    private fun transition(state: State, cmd: Cmd): State = when (state) {
        is State.Idle -> when (cmd) {
            is Cmd.Start -> {
                // 使用 MASTER 级参数（depth=20, time=12s），与人机对战最高难度一致
                val black = RustAi.create(20, 12000, 1)!!
                val white = RustAi.create(20, 12000, 2)!!
                // validate 由 launchAi 负责，无需在此处调用
                State.Running(
                    board = Array(15) { IntArray(15) },
                    currentPlayer = 1,
                    moveCount = 0,
                    lastMove = null,
                    blackAi = black,
                    whiteAi = white
                ).also { launchAi(it) }
            }
            else -> state
        }

        is State.Running -> when (cmd) {
            is Cmd.Pause -> State.Pausing(
                board = state.board,
                currentPlayer = state.currentPlayer,
                moveCount = state.moveCount,
                lastMove = state.lastMove,
                blackAi = state.blackAi,
                whiteAi = state.whiteAi
            ).also {
                // 通知当前思考的 AI 停止
                val ai = if (state.currentPlayer == 1) state.blackAi else state.whiteAi
                ai.invalidate()
            }
            
            is Cmd.Stop -> State.Stopping(
                board = state.board,
                blackAi = state.blackAi,
                whiteAi = state.whiteAi,
                waitingForAi = true  // AI 正在运行，需要等待返回
            ).also {
                // invalidate 当前思考的 AI，但不立即 destroy
                val ai = if (state.currentPlayer == 1) state.blackAi else state.whiteAi
                ai.invalidate()
            }
            
            is Cmd.AiDone -> if (cmd.move != null) {
                handleMove(state, cmd.move)
            } else state
            
            else -> state
        }

        is State.Delaying -> when (cmd) {
            // 延迟结束，切换到下一个玩家并开始思考
            is Cmd.DelayComplete -> State.Running(
                board = state.board,
                currentPlayer = state.nextPlayer,
                moveCount = state.moveCount,
                lastMove = state.lastMove,
                blackAi = state.blackAi,
                whiteAi = state.whiteAi
            ).also { launchAi(it) }
            
            // Delaying 时没有 AI 在思考，直接进 Paused，不需要等待 AiDone
            is Cmd.Pause -> State.Paused(
                board = state.board,
                currentPlayer = state.nextPlayer,  // 延迟后的下一个玩家
                moveCount = state.moveCount,
                lastMove = state.lastMove,
                blackAi = state.blackAi,
                whiteAi = state.whiteAi
            )
            
            is Cmd.Stop -> {
                // Delaying 时没有 AI 在运行，直接清理回 Idle
                state.blackAi.destroy()
                state.whiteAi.destroy()
                State.Idle(board = state.board, winner = null)
            }
            
            else -> state
        }

        is State.Pausing -> when (cmd) {
            // AI 返回后，进入真正的 Paused
            is Cmd.AiDone -> State.Paused(
                board = state.board,
                currentPlayer = state.currentPlayer,
                moveCount = state.moveCount,
                lastMove = state.lastMove,
                blackAi = state.blackAi,
                whiteAi = state.whiteAi
            )
            
            is Cmd.Stop -> State.Stopping(
                board = state.board,
                blackAi = state.blackAi,
                whiteAi = state.whiteAi,
                waitingForAi = true  // AI 可能还在运行（虽然已被 invalidate），等待返回
            )
            // Pausing 不立即 destroy，等待 AiDone
            
            else -> state
        }

        is State.Paused -> when (cmd) {
            // 恢复时先进入 Delaying，延迟后再开始思考，节奏与正常走子一致
            is Cmd.Resume -> State.Delaying(
                board = state.board,
                nextPlayer = state.currentPlayer,  // 当前玩家继续下棋
                moveCount = state.moveCount,
                lastMove = state.lastMove ?: (0 to 0),  // Paused 时一定有 lastMove
                blackAi = state.blackAi,
                whiteAi = state.whiteAi,
                delayMs = 500
            ).also {
                // 恢复前重置 AI 的停止标志
                state.blackAi.validate()
                state.whiteAi.validate()
            }
            
            is Cmd.Stop -> {
                // Paused 时没有 AI 在运行，直接清理回 Idle
                state.blackAi.destroy()
                state.whiteAi.destroy()
                State.Idle(board = state.board, winner = null)
            }
            
            else -> state
        }

        is State.Stopping -> when (cmd) {
            is Cmd.AiDone -> {
                // AI 已返回，执行 destroy 后再回到 Idle
                state.blackAi.destroy()
                state.whiteAi.destroy()
                State.Idle(board = state.board, winner = null)
            }
            else -> state
        }
    }

    // ========== 处理走法 ==========
    private fun handleMove(running: State.Running, move: Pair<Int, Int>): State {
        val newBoard = running.board.copyOf().apply {
            this[move.first][move.second] = running.currentPlayer
        }
        
        // 检查胜利
        if (checkWin(newBoard, move.first, move.second, running.currentPlayer)) {
            running.blackAi.destroy()
            running.whiteAi.destroy()
            val winner = if (running.currentPlayer == 1) "BLACK" else "WHITE"
            return State.Idle(board = newBoard, winner = winner)
        }
        
        // 进入延迟状态，让用户看清落子
        return State.Delaying(
            board = newBoard,
            nextPlayer = 3 - running.currentPlayer,
            moveCount = running.moveCount + 1,
            lastMove = move,
            blackAi = running.blackAi,
            whiteAi = running.whiteAi,
            delayMs = 500
        )
    }

    // ========== 启动 AI ==========
    private fun launchAi(running: State.Running) {
        viewModelScope.launch(Dispatchers.IO) {
            val ai = if (running.currentPlayer == 1) running.blackAi else running.whiteAi
            // validate 必须在 take_turn 之前调用：take_turn 不再自己重置 should_stop，
            // 以避免与 Kotlin 端 invalidate() 发生竞态
            ai.validate()
            val move = ai.takeTurn(running.board.toFlatByteArray())
            actor.send(Cmd.AiDone(move))
        }
    }

    // ========== 启动延迟 ==========
    private fun launchDelay(delaying: State.Delaying) {
        viewModelScope.launch(Dispatchers.IO) {
            delay(delaying.delayMs)
            actor.send(Cmd.DelayComplete)
        }
    }

    // ========== 公共 API ==========
    fun start() = actor.trySend(Cmd.Start)
    fun stop() = actor.trySend(Cmd.Stop)
    
    /**
     * 设置暂停源状态（通过 PauseCoordinator 合并多个暂停源）
     * @param source 暂停源类型
     * @param isPaused 该源是否要求暂停
     */
    fun setPauseSource(source: PauseCoordinator.Source, isPaused: Boolean) {
        pauseCoordinator.setSource(source, isPaused)
    }
    
    /**
     * 调试方法：直接 invalidate 两个 AI，不经过 Actor
     * 用于检测是否因状态机对错误 AI 进行 invalidate 导致 AI 无返回
     */
    fun debugInvalidateBothAis() {
        val current = _state.value
        when (current) {
            is State.Running -> {
                android.util.Log.d("AITest", "Debug: invalidate 黑方AI")
                current.blackAi.invalidate()
                android.util.Log.d("AITest", "Debug: invalidate 白方AI")
                current.whiteAi.invalidate()
            }
            is State.Delaying -> {
                android.util.Log.d("AITest", "Debug: invalidate 黑方AI")
                current.blackAi.invalidate()
                android.util.Log.d("AITest", "Debug: invalidate 白方AI")
                current.whiteAi.invalidate()
            }
            is State.Pausing -> {
                android.util.Log.d("AITest", "Debug: invalidate 黑方AI")
                current.blackAi.invalidate()
                android.util.Log.d("AITest", "Debug: invalidate 白方AI")
                current.whiteAi.invalidate()
            }
            is State.Paused -> {
                android.util.Log.d("AITest", "Debug: invalidate 黑方AI")
                current.blackAi.invalidate()
                android.util.Log.d("AITest", "Debug: invalidate 白方AI")
                current.whiteAi.invalidate()
            }
            else -> {
                android.util.Log.d("AITest", "Debug: 当前状态无AI可invalidate")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 清理暂停协调器
        pauseCoordinator.clear()
        // 安全清理：发送 Stop 命令让 Actor 处理
        actor.trySend(Cmd.Stop)
        viewModelScope.launch {
            delay(500)
            actor.close()
        }
    }

    // ========== 工具 ==========
    private fun checkWin(board: Array<IntArray>, r: Int, c: Int, p: Int): Boolean {
        val dirs = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        return dirs.any { (dr, dc) ->
            var count = 1
            var nr = r + dr
            var nc = c + dc
            while (nr in 0..14 && nc in 0..14 && board[nr][nc] == p) {
                count++; nr += dr; nc += dc
            }
            nr = r - dr; nc = c - dc
            while (nr in 0..14 && nc in 0..14 && board[nr][nc] == p) {
                count++; nr -= dr; nc -= dc
            }
            count >= 5
        }
    }

    private fun Array<IntArray>.toFlatByteArray(): ByteArray {
        return ByteArray(225) { i -> this[i / 15][i % 15].toByte() }
    }
}
