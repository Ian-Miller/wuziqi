package io.github.ian_miller.wuziqi.ui.game

import android.app.Application
import android.content.Context
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.Difficulty
import io.github.ian_miller.wuziqi.domain.model.GameMode
import io.github.ian_miller.wuziqi.domain.model.Piece
import io.github.ian_miller.wuziqi.domain.model.PieceColor
import io.github.ian_miller.wuziqi.domain.repository.GameRepository
import io.github.ian_miller.wuziqi.ai.RustAi
import io.github.ian_miller.wuziqi.AppLifecycleState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * GameViewModel V2 - 游戏专用 ViewModel
 * 
 * 职责：
 * - 管理游戏流程（Actor + ADT 架构）
 * - AI 对战逻辑
 * - 音效/震动反馈
 * - 游戏存档/恢复
 * 
 * 不管：
 * - 玩家管理（MenuViewModel）
 * - 统计加载（MenuViewModel）
 * - 设置对话框状态（MenuViewModel）
 */
@OptIn(kotlinx.coroutines.ObsoleteCoroutinesApi::class)
@HiltViewModel
class GameViewModelV2 @Inject constructor(
    application: Application,
    private val repository: GameRepository
) : AndroidViewModel(application) {

    // ========================================================================
    // 设置数据类
    // ========================================================================
    
    data class GameSettings(
        val soundEnabled: Boolean = true,
        val vibrationEnabled: Boolean = true,
        val undoEnabled: Boolean = true,
        val aiAssistEnabled: Boolean = true
    )
    
    // ========================================================================
    // 游戏状态（ADT）
    // ========================================================================
    
    sealed class State {
        // 初始化中（从路由参数加载设置）
        object Initializing : State()
        
        // 游戏未开始（显示开始界面）
        data class Idle(
            val mode: GameMode,
            val difficulty: Difficulty,
            val settings: GameSettings,
            val aiPlayerColor: PieceColor?,  // VS_AI 模式下 AI 执什么颜色
            val pvpBottomIsBlack: Boolean = true  // PvP 模式下方玩家是否执黑
        ) : State()
        
        // 等待玩家落子
        data class WaitingForPlayer(
            val board: Board,
            val currentPlayer: PieceColor,
            val mode: GameMode,
            val difficulty: Difficulty,
            val settings: GameSettings,
            val moveHistory: List<Piece>,
            val aiPlayerColor: PieceColor?,  // VS_AI 模式下有效
            val aiHint: Pair<Int, Int>? = null,           // 双人模式 AI 提示
            val isCalculatingHint: Boolean = false,        // 是否正在计算提示
            val lastMove: Piece? = null,
            val pvpBottomIsBlack: Boolean = true
        ) : State()
        
        // 等待 AI 落子
        data class WaitingForAi(
            val board: Board,
            val currentPlayer: PieceColor,  // AI 的颜色
            val mode: GameMode,
            val difficulty: Difficulty,
            val settings: GameSettings,
            val moveHistory: List<Piece>,
            val aiPlayerColor: PieceColor,
            val lastMove: Piece? = null,
            val pvpBottomIsBlack: Boolean = true
        ) : State()
        
        // 暂停中（AI 思考被打断）
        data class Pausing(
            val returnState: WaitingForAi,
            val resumeRequested: Boolean = false  // 暂停期间收到 Resume 信号
        ) : State()
        
        // 已暂停
        data class Paused(
            val returnState: WaitingForAi
        ) : State()

        // 轮次切换缓冲延迟（玩家落子 → AI 开始思考之间的小暂停）
        data class Delaying(
            val nextState: WaitingForAi
        ) : State()

        // 停止中（清理资源，等待 AI 返回后转 Idle）
        data class Stopping(
            val mode: GameMode,
            val difficulty: Difficulty,
            val settings: GameSettings,
            val aiPlayerColor: PieceColor?,
            val pvpBottomIsBlack: Boolean = true
        ) : State()
        
        // 游戏结束
        data class GameOver(
            val board: Board,
            val winner: PieceColor?,  // null = 平局
            val mode: GameMode,
            val difficulty: Difficulty,
            val settings: GameSettings,
            val moveHistory: List<Piece>,
            val aiPlayerColor: PieceColor?,
            val pvpBottomIsBlack: Boolean = true
        ) : State()
    }
    
    // ========================================================================
    // 命令定义
    // ========================================================================
    
    sealed class Cmd {
        // 初始化
        data class Init(
            val mode: GameMode,
            val difficulty: Difficulty,
            val settings: GameSettings,
            val aiPlayerColor: PieceColor?,  // VS_AI 模式下 AI 执什么颜色
            val pvpBottomIsBlack: Boolean = true
        ) : Cmd()
        
        // 游戏控制
        data class Start(
            val aiFirst: Boolean = false,  // VS_AI 模式下 AI 是否先手
            val pvpBottomIsBlack: Boolean = true  // PvP 模式下方是否执黑
        ) : Cmd()
        object Restart : Cmd()
        object Stop : Cmd()
        object Pause : Cmd()
        object Resume : Cmd()
        /** AI 计算被取消（返回 None），用于清理 Stopping 状态 */
        object AiCancelled : Cmd()
        /** Delaying 状态延迟到期，转入 WaitingForAi */
        object DelayElapsed : Cmd()
        /** 从存档直接恢复到指定状态，由 initialize() 在找到存档时发送 */
        data class Restore(val state: State) : Cmd()
        
        // 游戏动作
        data class PlacePiece(val row: Int, val col: Int) : Cmd()
        object Undo : Cmd()
        data class AiDone(val piece: Piece) : Cmd()
        data class AssistReady(val move: Pair<Int, Int>, val expectedMoveCount: Int) : Cmd()
        
        // 设置更新（游戏中也可修改，影响下一局）
        data class UpdateSettings(val settings: GameSettings) : Cmd()
        data class SetDifficulty(val difficulty: Difficulty) : Cmd()
    }
    
    // ========================================================================
    // Actor 定义
    // ========================================================================
    
    private val actor = viewModelScope.actor<Cmd>(capacity = Channel.UNLIMITED) {
        var state: State = State.Initializing
        for (cmd in channel) {
            val oldState = state                      // 在覆盖前保存旧状态
            val newState = transition(state, cmd)
            state = newState
            _gameState.value = newState
            triggerSideEffects(newState, cmd, oldState)
        }
    }
    
    private fun sendCommand(cmd: Cmd) {
        viewModelScope.launch {
            actor.send(cmd)
        }
    }
    
    // ========================================================================
    // 状态流
    // ========================================================================
    
    private val _gameState = MutableStateFlow<State>(State.Initializing)
    val gameState: StateFlow<State> = _gameState
    
    // UI 状态（对话框等）
    data class UiState(
        val showSettings: Boolean = false,
        val showDifficultyToast: Boolean = false,
        val showingAssistHint: Boolean = false  // 是否正在显示 AI 提示
    )
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState
    private val progressTracker = SoftProgressTracker()
    val aiProgress: StateFlow<Float> = progressTracker.progress
    /** AI 思考中的当前最优走法（实时轮询 Rust 侧，触发 uiModel 重组） */
    private val _aiBestMoveHint = MutableStateFlow<Pair<Int, Int>?>(null)

    // ========================================================================
    // 合并后的 UI 模型（供 Compose 直接使用）
    // ========================================================================

    /**
     * 单一真相来源：将 State 和 UiState 合并为一个扁平的、可直接绑定的数据类，
     * 避免 UI 层多次调用 getter 引发重组不一致的问题。
     */
    data class UiModel(
        // 游戏进度
        val gameStatus: GameStatus,
        val board: Board,
        val currentPlayer: PieceColor?,
        val lastMove: Piece?,
        val gameResult: GameResult?,
        // 配置
        val mode: GameMode,
        val difficulty: Difficulty,
        val settings: GameSettings,
        val aiPlayerColor: PieceColor?,
        val pvpBottomIsBlack: Boolean,
        // AI
        val isAiThinking: Boolean,
        val aiProgress: Float,
        val aiHint: Pair<Int, Int>?,
        val isCalculatingHint: Boolean,
        val canUndo: Boolean,
        /** AI 思考中的当前最优走法（用于落子预览；null = 尚未确定或非 AI 思考状态） */
        val aiBestMoveHint: Pair<Int, Int>?,
        // UI 对话框
        val showSettings: Boolean,
        val showDifficultyToast: Boolean,
        val showingAssistHint: Boolean,
    ) {
        val isVsHuman: Boolean get() = mode == GameMode.VS_HUMAN
        val shouldShowAssistButton: Boolean get() = aiHint != null

        companion object {
            fun loading() = UiModel(
                gameStatus = GameStatus.NOT_STARTED,
                board = Board.empty(),
                currentPlayer = null,
                lastMove = null,
                gameResult = null,
                mode = GameMode.VS_AI,
                difficulty = Difficulty.EASY,
                settings = GameSettings(),
                aiPlayerColor = null,
                pvpBottomIsBlack = true,
                isAiThinking = false,
                aiProgress = 0f,
                aiHint = null,
                isCalculatingHint = false,
                canUndo = false,
                aiBestMoveHint = null,
                showSettings = false,
                showDifficultyToast = false,
                showingAssistHint = false,
            )
        }
    }

    val uiModel: StateFlow<UiModel> = combine(
        _gameState, _uiState, progressTracker.progress, _aiBestMoveHint
    ) { state, ui, progress, bestMove ->
        buildUiModel(state, ui, progress, bestMove)
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiModel.loading())
    
    // ========================================================================
    // 资源
    // ========================================================================
    
    private val soundPool = SoundPool.Builder().setMaxStreams(2).build()
    private var moveSoundId: Int = 0
    private var stampSoundId: Int = 0
    private val vibrator = getApplication<Application>().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val prefs = getApplication<Application>().getSharedPreferences("gomoku_prefs", Context.MODE_PRIVATE)
    
    // AI 任务
    private var aiJob: Job? = null
    private var assistJob: Job? = null
    private var assistAi: RustAi? = null  // 提升至成员，供 cancelAssistJob 调用 invalidate()
    private var delayJob: Job? = null  // 轮次切换延迟任务
    private var toastJob: Job? = null  // 难度 Toast 定时器（可取消重置）

    // ========================================================================
    // 暂停源管理
    // ========================================================================

    /**
     * 所有可能请求暂停 AI 的来源。
     * 只有集合从空→非空时才发 Cmd.Pause；从非空→空时才发 Cmd.Resume。
     * 任意一个源暂停 → 游戏暂停；所有源都解除 → 游戏恢复。
     */
    enum class PauseSource {
        LIFECYCLE,   // Activity/Fragment ON_PAUSE
        NAVIGATION,  // 导航离开页面（onDispose）
    }

    private val pauseSources = mutableSetOf<PauseSource>()

    /**
     * 添加一个暂停源。首次添加（0→1）时向 Actor 发送 Cmd.Pause。
     */
    fun addPauseSource(source: PauseSource) {
        val wasEmpty = pauseSources.isEmpty()
        pauseSources.add(source)
        if (wasEmpty) {
            // 首个暂停源：触发真正的暂停
            _doPause()
        }
    }

    /**
     * 移除一个暂停源。最后一个移除（1→0）时向 Actor 发送 Cmd.Resume。
     */
    fun removePauseSource(source: PauseSource) {
        pauseSources.remove(source)
        if (pauseSources.isEmpty()) {
            // 所有源都已解除：触发恢复
            _doResume()
        }
    }

    /** 实际执行暂停（仅由 addPauseSource 在 0→1 时调用） */
    private fun _doPause() {
        when (val state = gameState.value) {
            is State.WaitingForPlayer ->
                saveGame(state.mode, state.difficulty, state.aiPlayerColor, state.pvpBottomIsBlack, state.moveHistory)
            is State.WaitingForAi -> {
                sendCommand(Cmd.Pause)
                saveGame(state.mode, state.difficulty, state.aiPlayerColor, state.pvpBottomIsBlack, state.moveHistory)
            }
            is State.Delaying -> {
                // 延迟期间暂停：发 Pause（Actor 排队处理）并保存当前盘面
                sendCommand(Cmd.Pause)
                val ns = state.nextState
                saveGame(ns.mode, ns.difficulty, ns.aiPlayerColor, ns.pvpBottomIsBlack, ns.moveHistory)
            }
            else -> {}
        }
    }

    /** 实际执行恢复（仅由 removePauseSource 在 1→0 时调用） */
    private fun _doResume() {
        // 无论当前状态，总是发送 Resume 让 Actor 排队处理。
        // 若 Cmd.Pause 还未被处理（race condition），actor 会先处理 Pause→Pausing，
        // 再处理 Resume→Pausing(resumeRequested=true)，AI 完成后直接使用其结果。
        sendCommand(Cmd.Resume)
    }
    
    init {
        loadSoundEffects()
    }
    
    // ========================================================================
    // 状态转换（纯函数）
    // ========================================================================
    
    private fun transition(state: State, cmd: Cmd): State = when (state) {
        is State.Initializing -> when (cmd) {
            is Cmd.Init -> State.Idle(
                mode = cmd.mode,
                difficulty = cmd.difficulty,
                settings = cmd.settings,
                aiPlayerColor = cmd.aiPlayerColor,
                pvpBottomIsBlack = cmd.pvpBottomIsBlack
            )
            else -> state
        }
        
        is State.Idle -> when (cmd) {
            is Cmd.Start -> startNewGame(state, cmd)
            is Cmd.Restore -> cmd.state          // 直接跳到存档状态，副作用会自动触发 AI
            is Cmd.UpdateSettings -> state.copy(settings = cmd.settings)
            is Cmd.SetDifficulty -> state.copy(difficulty = cmd.difficulty)
            else -> state
        }
        
        is State.WaitingForPlayer -> when (cmd) {
            is Cmd.PlacePiece -> handlePlacePiece(state, cmd)
            is Cmd.Undo -> handleUndo(state)
            is Cmd.UpdateSettings -> state.copy(settings = cmd.settings)
            is Cmd.Stop -> {
                invalidateAndDestroyAi()
                State.Idle(
                    mode = state.mode,
                    difficulty = state.difficulty,
                    settings = state.settings,
                    aiPlayerColor = state.aiPlayerColor,
                    pvpBottomIsBlack = state.pvpBottomIsBlack
                )
            }
            is Cmd.AssistReady -> {
                // 校验步数：若 Undo/落子 导致盘面步数已变，丢弃过期结果
                if (state.moveHistory.size == cmd.expectedMoveCount) {
                    state.copy(aiHint = cmd.move, isCalculatingHint = false)
                } else {
                    state  // 过期结果，静默丢弃
                }
            }
            else -> state
        }
        
        is State.WaitingForAi -> when (cmd) {
            is Cmd.AiDone -> handleAiDone(state, cmd)
            is Cmd.Pause -> State.Pausing(state)
            is Cmd.Stop -> {
                invalidateAi()
                State.Stopping(
                    mode = state.mode,
                    difficulty = state.difficulty,
                    settings = state.settings,
                    aiPlayerColor = state.aiPlayerColor,
                    pvpBottomIsBlack = state.pvpBottomIsBlack
                )
            }
            else -> state
        }
        
        is State.Pausing -> when (cmd) {
            is Cmd.AiDone -> if (state.resumeRequested) {
                // 暂停期间用户已恢复，直接采用 AI 计算结果继续游戏
                handleAiDone(state.returnState, cmd)
            } else {
                destroyAi()
                State.Paused(state.returnState)
            }
            is Cmd.AiCancelled -> {
                destroyAi()
                // resumeRequested=true → 返回 WaitingForAi，副作用会重新触发 AI；否则进入 Paused
                if (state.resumeRequested) state.returnState else State.Paused(state.returnState)
            }
            is Cmd.Resume -> state.copy(resumeRequested = true)
            is Cmd.Stop -> State.Stopping(
                mode = state.returnState.mode,
                difficulty = state.returnState.difficulty,
                settings = state.returnState.settings,
                aiPlayerColor = state.returnState.aiPlayerColor,
                pvpBottomIsBlack = state.returnState.pvpBottomIsBlack
            )
            else -> state
        }
        
        is State.Paused -> when (cmd) {
            is Cmd.Resume -> {
                validateAi()
                state.returnState
            }
            is Cmd.Stop -> {
                invalidateAndDestroyAi()
                State.Idle(
                    mode = state.returnState.mode,
                    difficulty = state.returnState.difficulty,
                    settings = state.returnState.settings,
                    aiPlayerColor = state.returnState.aiPlayerColor,
                    pvpBottomIsBlack = state.returnState.pvpBottomIsBlack
                )
            }
            else -> state
        }

        is State.Delaying -> when (cmd) {
            is Cmd.DelayElapsed -> state.nextState   // 副作用自动启动 AI
            is Cmd.Pause -> State.Paused(state.nextState)  // 跳过延迟直接暂停
            is Cmd.Stop -> State.Idle(
                mode = state.nextState.mode,
                difficulty = state.nextState.difficulty,
                settings = state.nextState.settings,
                aiPlayerColor = state.nextState.aiPlayerColor,
                pvpBottomIsBlack = state.nextState.pvpBottomIsBlack
            )
            else -> state
        }

        is State.Stopping -> when (cmd) {
            is Cmd.AiDone -> {
                destroyAi()
                State.Idle(
                    mode = state.mode,
                    difficulty = state.difficulty,
                    settings = state.settings,
                    aiPlayerColor = state.aiPlayerColor,
                    pvpBottomIsBlack = state.pvpBottomIsBlack
                )
            }
            is Cmd.AiCancelled -> {
                destroyAi()
                State.Idle(
                    mode = state.mode,
                    difficulty = state.difficulty,
                    settings = state.settings,
                    aiPlayerColor = state.aiPlayerColor,
                    pvpBottomIsBlack = state.pvpBottomIsBlack
                )
            }
            else -> state
        }
        
        is State.GameOver -> when (cmd) {
            is Cmd.Restart -> restartGame(state)
            // 游戏结束后点击"选择先后手"按钮 → 用选中的参数重新开局
            is Cmd.Start -> {
                val idle = State.Idle(
                    mode = state.mode,
                    difficulty = state.difficulty,
                    settings = state.settings,
                    aiPlayerColor = if (state.mode == GameMode.VS_AI) {
                        if (cmd.aiFirst) PieceColor.BLACK else PieceColor.WHITE
                    } else null,
                    pvpBottomIsBlack = cmd.pvpBottomIsBlack
                )
                startNewGame(idle, cmd)
            }
            is Cmd.Stop -> State.Idle(
                mode = state.mode,
                difficulty = state.difficulty,
                settings = state.settings,
                aiPlayerColor = state.aiPlayerColor,
                pvpBottomIsBlack = state.pvpBottomIsBlack
            )
            // 游戏结束后允许修改难度和设置，下局开始时生效
            is Cmd.SetDifficulty -> state.copy(difficulty = cmd.difficulty)
            is Cmd.UpdateSettings -> state.copy(settings = cmd.settings)
            else -> state
        }
    }
    
    // ========================================================================
    // 状态转换辅助函数
    // ========================================================================
    
    private fun startNewGame(idle: State.Idle, cmd: Cmd.Start): State {
        val board = Board.empty()
        val isVsAi = idle.mode == GameMode.VS_AI
        
        // 确定 AI 颜色
        val aiColor = if (isVsAi) {
            if (cmd.aiFirst) PieceColor.BLACK else PieceColor.WHITE
        } else null
        
        val currentPlayer = PieceColor.BLACK
        
        return if (isVsAi && aiColor == currentPlayer) {
            // AI 先手
            State.Delaying(State.WaitingForAi(
                board = board,
                currentPlayer = currentPlayer,
                mode = idle.mode,
                difficulty = idle.difficulty,
                settings = idle.settings,
                moveHistory = emptyList(),
                aiPlayerColor = aiColor!!,
                lastMove = null,
                pvpBottomIsBlack = cmd.pvpBottomIsBlack
            ))
        } else {
            // 玩家先手
            State.WaitingForPlayer(
                board = board,
                currentPlayer = currentPlayer,
                mode = idle.mode,
                difficulty = idle.difficulty,
                settings = idle.settings,
                moveHistory = emptyList(),
                aiPlayerColor = aiColor,
                lastMove = null,
                pvpBottomIsBlack = cmd.pvpBottomIsBlack
            )
        }
    }
    
    private fun handlePlacePiece(state: State.WaitingForPlayer, cmd: Cmd.PlacePiece): State {
        if (!isValidMove(state.board, cmd.row, cmd.col)) {
            return state
        }
        
        val newBoard = state.board.placePiece(cmd.row, cmd.col, state.currentPlayer)
        val newPiece = Piece(cmd.row, cmd.col, state.currentPlayer)
        val newHistory = state.moveHistory + newPiece
        
        // 检查胜负
        if (checkWin(newBoard, cmd.row, cmd.col, state.currentPlayer)) {
            return State.GameOver(
                board = newBoard,
                winner = state.currentPlayer,
                mode = state.mode,
                difficulty = state.difficulty,
                settings = state.settings,
                moveHistory = newHistory,
                aiPlayerColor = state.aiPlayerColor,
                pvpBottomIsBlack = state.pvpBottomIsBlack
            )
        }
        
        // 检查平局
        if (newHistory.size >= 225 || newBoard.isFull()) {
            return State.GameOver(
                board = newBoard,
                winner = null,
                mode = state.mode,
                difficulty = state.difficulty,
                settings = state.settings,
                moveHistory = newHistory,
                aiPlayerColor = state.aiPlayerColor,
                pvpBottomIsBlack = state.pvpBottomIsBlack
            )
        }
        
        val nextPlayer = state.currentPlayer.opposite()
        
        return if (state.mode == GameMode.VS_AI && state.aiPlayerColor == nextPlayer) {
            State.Delaying(State.WaitingForAi(
                board = newBoard,
                currentPlayer = nextPlayer,
                mode = state.mode,
                difficulty = state.difficulty,
                settings = state.settings,
                moveHistory = newHistory,
                aiPlayerColor = state.aiPlayerColor!!,
                lastMove = newPiece,
                pvpBottomIsBlack = state.pvpBottomIsBlack
            ))
        } else {
            State.WaitingForPlayer(
                board = newBoard,
                currentPlayer = nextPlayer,
                mode = state.mode,
                difficulty = state.difficulty,
                settings = state.settings,
                moveHistory = newHistory,
                aiPlayerColor = state.aiPlayerColor,
                lastMove = newPiece,
                pvpBottomIsBlack = state.pvpBottomIsBlack
            )
        }
    }
    
    private fun handleAiDone(state: State.WaitingForAi, cmd: Cmd.AiDone): State {
        if (!isValidMove(state.board, cmd.piece.row, cmd.piece.col)) {
            return state
        }
        
        val newBoard = state.board.placePiece(cmd.piece.row, cmd.piece.col, cmd.piece.color)
        val newHistory = state.moveHistory + cmd.piece
        
        // 检查胜负
        if (checkWin(newBoard, cmd.piece.row, cmd.piece.col, cmd.piece.color)) {
            return State.GameOver(
                board = newBoard,
                winner = cmd.piece.color,
                mode = state.mode,
                difficulty = state.difficulty,
                settings = state.settings,
                moveHistory = newHistory,
                aiPlayerColor = state.aiPlayerColor,
                pvpBottomIsBlack = state.pvpBottomIsBlack
            )
        }
        
        // 检查平局
        if (newHistory.size >= 225 || newBoard.isFull()) {
            return State.GameOver(
                board = newBoard,
                winner = null,
                mode = state.mode,
                difficulty = state.difficulty,
                settings = state.settings,
                moveHistory = newHistory,
                aiPlayerColor = state.aiPlayerColor,
                pvpBottomIsBlack = state.pvpBottomIsBlack
            )
        }
        
        val nextPlayer = cmd.piece.color.opposite()
        
        return State.WaitingForPlayer(
            board = newBoard,
            currentPlayer = nextPlayer,
            mode = state.mode,
            difficulty = state.difficulty,
            settings = state.settings,
            moveHistory = newHistory,
            aiPlayerColor = state.aiPlayerColor,
            lastMove = cmd.piece,
            pvpBottomIsBlack = state.pvpBottomIsBlack
        )
    }
    
    private fun handleUndo(state: State.WaitingForPlayer): State {
        if (state.moveHistory.isEmpty()) return state
        
        return when (state.mode) {
            GameMode.VS_AI -> {
                // 人机模式撤销两步（玩家一步 + AI 一步）
                if (state.moveHistory.size >= 2) {
                    val newHistory = state.moveHistory.dropLast(2)
                    val lastPiece = newHistory.lastOrNull()
                    val newBoard = if (newHistory.isEmpty()) {
                        Board.empty()
                    } else {
                        // 从空棋盘重建
                        newHistory.fold(Board.empty()) { board, piece ->
                            board.placePiece(piece.row, piece.col, piece.color)
                        }
                    }
                    // 撤销后轮到玩家行动，玩家颜色 = AI 颜色的对立色
                    val playerColor = if (state.aiPlayerColor == PieceColor.BLACK)
                        PieceColor.WHITE else PieceColor.BLACK
                    state.copy(
                        board = newBoard,
                        moveHistory = newHistory,
                        currentPlayer = playerColor,
                        lastMove = lastPiece,
                        aiHint = null,
                        isCalculatingHint = false
                    )
                } else state
            }
            GameMode.VS_HUMAN -> {
                // 双人模式撤销一步
                val newHistory = state.moveHistory.dropLast(1)
                val lastPiece = newHistory.lastOrNull()
                val newBoard = if (newHistory.isEmpty()) {
                    Board.empty()
                } else {
                    newHistory.fold(Board.empty()) { board, piece ->
                        board.placePiece(piece.row, piece.col, piece.color)
                    }
                }
                state.copy(
                    board = newBoard,
                    moveHistory = newHistory,
                    // 撤销后轮到被撤销棋子的颜色：newHistory.size 为偶数→黑棋（先手），奇数→白棋
                    currentPlayer = if (newHistory.size % 2 == 0) PieceColor.BLACK else PieceColor.WHITE,
                    lastMove = lastPiece,
                    // 清除旧提示，避免撤销后残留上一步的提示
                    aiHint = null,
                    isCalculatingHint = false
                )
            }
        }
    }
    
    private fun restartGame(state: State.GameOver): State {
        // 重新开局，保持设置，交换先后手
        val newPvpBottomIsBlack = !state.pvpBottomIsBlack
        val board = Board.empty()
        val currentPlayer = PieceColor.BLACK  // 黑棋始终先手

        // VS_AI 模式：若 AI 执黑，则 AI 先手
        return if (state.mode == GameMode.VS_AI && state.aiPlayerColor == PieceColor.BLACK) {
            State.Delaying(State.WaitingForAi(
                board = board,
                currentPlayer = currentPlayer,
                mode = state.mode,
                difficulty = state.difficulty,
                settings = state.settings,
                moveHistory = emptyList(),
                aiPlayerColor = state.aiPlayerColor!!,
                lastMove = null,
                pvpBottomIsBlack = newPvpBottomIsBlack
            ))
        } else {
            State.WaitingForPlayer(
                board = board,
                currentPlayer = currentPlayer,
                mode = state.mode,
                difficulty = state.difficulty,
                settings = state.settings,
                moveHistory = emptyList(),
                aiPlayerColor = state.aiPlayerColor,
                lastMove = null,
                pvpBottomIsBlack = newPvpBottomIsBlack
            )
        }
    }
    
    // ========================================================================
    // 副作用
    // ========================================================================
    
    private fun triggerSideEffects(newState: State, cmd: Cmd, oldState: State) {
        // ── 落子音效（基于命令 + 状态实际变化）────────────────────────
        // 用引用相等（!==）防止 else→state 的“空命令”误触发（如过期的 AiDone 落至 WaitingForPlayer）
        if (newState !== oldState) {
            when {
                // 玩家落子：不论新状态是 Delaying 还是 WaitingForPlayer，立即播放
                cmd is Cmd.PlacePiece && oldState is State.WaitingForPlayer -> {
                    playMoveSound(); triggerVibration()
                }
                // AI 落子完成
                cmd is Cmd.AiDone && newState is State.WaitingForPlayer -> {
                    playMoveSound(); triggerVibration()
                }
            }
        }

        // ── 离开 Delaying 时取消延迟任务（非正常超时结束）────────────
        if (oldState is State.Delaying && cmd !is Cmd.DelayElapsed) {
            cancelDelayJob()
        }

        when (newState) {
            is State.Delaying -> {
                // 启动 300ms 延迟，到期后进入 WaitingForAi
                delayJob?.cancel()
                delayJob = viewModelScope.launch {
                    delay(300L)
                    sendCommand(Cmd.DelayElapsed)
                }
            }

            is State.WaitingForAi -> {
                if (cmd !is Cmd.AiDone && cmd !is Cmd.Resume) {
                    // 普通路径（新回合、Delaying 结束等）：先 validate 确保 should_stop=false
                    validateAi()
                    launchAiThinking(newState)
                } else if (cmd is Cmd.Resume) {
                    validateAi()
                    launchAiThinking(newState)
                }
            }

            is State.WaitingForPlayer -> {
                // 每次新回合开始（aiHint 为 null）时清除提示预览状态
                // AssistReady 到达时 aiHint != null，不在此处重置，避免误清除正在显示的预览
                if (newState.aiHint == null) {
                    _uiState.update { it.copy(showingAssistHint = false) }
                }

                // 双人模式：延迟 5 秒后启动 AI 辅助计算
                if (newState.mode == GameMode.VS_HUMAN &&
                    newState.settings.aiAssistEnabled &&
                    !newState.isCalculatingHint &&
                    newState.aiHint == null
                ) {
                    launchAssistCalculation(newState)
                }

                // 保存游戏
                saveGameAsync(newState)
            }

            is State.GameOver -> {
                // 仅在首次进入 GameOver 时触发（防止后续命令如 SetDifficulty 重复触发音效）
                if (newState !== oldState) {
                    saveGameRecord(newState)
                    clearSavedGame(newState.mode)
                    invalidateAndDestroyAi()
                    playStampSound()
                }
            }

            is State.Idle -> {
                // 从进行中的局面（WaitingForPlayer/WaitingForAi/Paused/Stopping 等）
                // 主动结束本局时（Cmd.Stop），清除该模式的存档，防止退出再返回时恢复已放弃的对局
                if (oldState !is State.Idle && oldState !is State.Initializing) {
                    clearSavedGame(newState.mode)
                }
            }

            else -> {}
        }
    }
    
    // ========================================================================
    // 公共 API（供 UI 调用）
    // ========================================================================
    
    fun initialize(mode: GameMode) {
        val settings = GameSettings(
            soundEnabled = prefs.getBoolean("sound_enabled", true),
            vibrationEnabled = prefs.getBoolean("vibration_enabled", true),
            undoEnabled = prefs.getBoolean("undo_enabled", true),
            aiAssistEnabled = prefs.getBoolean("ai_assist_enabled", true)
        )
        
        val difficulty = try {
            Difficulty.valueOf(prefs.getString("selected_difficulty", Difficulty.EASY.name)!!)
        } catch (e: Exception) {
            Difficulty.EASY
        }
        
        val aiColor = if (mode == GameMode.VS_AI) {
            try {
                PieceColor.valueOf(prefs.getString("ai_player_color", PieceColor.WHITE.name)!!)
            } catch (e: Exception) {
                PieceColor.WHITE
            }
        } else null
        
        val pvpBottomIsBlack = prefs.getBoolean("pvp_bottom_is_black", true)
        
        sendCommand(Cmd.Init(mode, difficulty, settings, aiColor, pvpBottomIsBlack))
        
        // 尝试恢复存档
        if (restoreSavedGame(mode)) {
            // 已恢复，不需要进入 Idle
        }
        
        // 人机模式显示难度提示
        if (mode == GameMode.VS_AI) {
            triggerDifficultyToast()
        }
    }
    
    fun startGame(aiFirst: Boolean = false, pvpBottomIsBlack: Boolean = true) {
        // 保存 PvP 先后手偏好
        if (getMode() == GameMode.VS_HUMAN) {
            prefs.edit().putBoolean("pvp_bottom_is_black", pvpBottomIsBlack).apply()
        }
        sendCommand(Cmd.Start(aiFirst, pvpBottomIsBlack))
    }
    
    fun placePiece(row: Int, col: Int) = sendCommand(Cmd.PlacePiece(row, col))
    fun undo() = sendCommand(Cmd.Undo)
    fun stopGame() = sendCommand(Cmd.Stop)
    fun restart() = sendCommand(Cmd.Restart)
    fun setDifficulty(difficulty: Difficulty) {
        // 实时持久化到 SharedPreferences，确保退出再进入后难度不丢失
        prefs.edit { putString("selected_difficulty", difficulty.name) }
        // 难度变化时销毁缓存的 AI，下次开局时创建新实例
        invalidateAndDestroyAi()
        sendCommand(Cmd.SetDifficulty(difficulty))
        // 人机模式下显示难度 Toast（若已显示则刷新持续时间）
        if (getMode() == GameMode.VS_AI) {
            triggerDifficultyToast()
        }
    }
    
    fun updateSettings(settings: GameSettings) {
        // 保存到 Prefs
        prefs.edit {
            putBoolean("sound_enabled", settings.soundEnabled)
            putBoolean("vibration_enabled", settings.vibrationEnabled)
            putBoolean("undo_enabled", settings.undoEnabled)
            putBoolean("ai_assist_enabled", settings.aiAssistEnabled)
        }
        sendCommand(Cmd.UpdateSettings(settings))
    }
    
    fun setSoundEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("sound_enabled", enabled) }
        val current = getSettings()
        updateSettings(current.copy(soundEnabled = enabled))
    }
    
    fun setVibrationEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("vibration_enabled", enabled) }
        val current = getSettings()
        updateSettings(current.copy(vibrationEnabled = enabled))
    }
    
    fun setUndoEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("undo_enabled", enabled) }
        val current = getSettings()
        updateSettings(current.copy(undoEnabled = enabled))
    }
    
    fun setAiAssistEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("ai_assist_enabled", enabled).apply()
        val current = getSettings()
        updateSettings(current.copy(aiAssistEnabled = enabled))
    }
    
    // ========================================================================
    // UiModel 构建（私有）
    // ========================================================================

    private fun buildUiModel(state: State, ui: UiState, aiProgressRaw: Float, bestMoveHint: Pair<Int, Int>?): UiModel {
        val gameStatus = when (state) {
            is State.Initializing -> GameStatus.NOT_STARTED
            is State.Idle -> GameStatus.NOT_STARTED
            is State.WaitingForPlayer, is State.WaitingForAi,
            is State.Pausing, is State.Paused, is State.Delaying -> GameStatus.PLAYING
            is State.GameOver -> GameStatus.FINISHED
            is State.Stopping -> GameStatus.NOT_STARTED
        }
        val board = when (state) {
            is State.WaitingForPlayer -> state.board
            is State.WaitingForAi -> state.board
            is State.GameOver -> state.board
            is State.Pausing -> state.returnState.board
            is State.Paused -> state.returnState.board
            is State.Delaying -> state.nextState.board
            else -> Board.empty()
        }
        val currentPlayer = when (state) {
            is State.WaitingForPlayer -> state.currentPlayer
            is State.WaitingForAi -> state.currentPlayer
            is State.Pausing -> state.returnState.currentPlayer
            is State.Paused -> state.returnState.currentPlayer
            is State.Delaying -> state.nextState.currentPlayer
            else -> null
        }
        val lastMove = when (state) {
            is State.WaitingForPlayer -> state.lastMove
            is State.WaitingForAi -> state.lastMove
            is State.GameOver -> state.moveHistory.lastOrNull()
            is State.Pausing -> state.returnState.lastMove
            is State.Paused -> state.returnState.lastMove
            is State.Delaying -> state.nextState.lastMove
            else -> null
        }
        val gameResult = when (state) {
            is State.GameOver -> state.winner?.let { GameResult.Win(it) } ?: GameResult.Draw
            else -> null
        }
        val mode = when (state) {
            is State.Idle -> state.mode
            is State.WaitingForPlayer -> state.mode
            is State.WaitingForAi -> state.mode
            is State.GameOver -> state.mode
            is State.Pausing -> state.returnState.mode
            is State.Paused -> state.returnState.mode
            is State.Delaying -> state.nextState.mode
            else -> GameMode.VS_AI
        }
        val difficulty = when (state) {
            is State.Idle -> state.difficulty
            is State.WaitingForPlayer -> state.difficulty
            is State.WaitingForAi -> state.difficulty
            is State.GameOver -> state.difficulty
            is State.Pausing -> state.returnState.difficulty
            is State.Paused -> state.returnState.difficulty
            is State.Delaying -> state.nextState.difficulty
            else -> Difficulty.EASY
        }
        val settings = when (state) {
            is State.Idle -> state.settings
            is State.WaitingForPlayer -> state.settings
            is State.WaitingForAi -> state.settings
            is State.GameOver -> state.settings
            is State.Pausing -> state.returnState.settings
            is State.Paused -> state.returnState.settings
            is State.Delaying -> state.nextState.settings
            else -> GameSettings()
        }
        val aiPlayerColor = when (state) {
            is State.Idle -> state.aiPlayerColor
            is State.WaitingForPlayer -> state.aiPlayerColor
            is State.WaitingForAi -> state.aiPlayerColor
            is State.GameOver -> state.aiPlayerColor
            is State.Pausing -> state.returnState.aiPlayerColor
            is State.Paused -> state.returnState.aiPlayerColor
            is State.Delaying -> state.nextState.aiPlayerColor
            else -> null
        }
        val pvpBottomIsBlack = when (state) {
            is State.Idle -> state.pvpBottomIsBlack
            is State.WaitingForPlayer -> state.pvpBottomIsBlack
            is State.WaitingForAi -> state.pvpBottomIsBlack
            is State.GameOver -> state.pvpBottomIsBlack
            is State.Pausing -> state.returnState.pvpBottomIsBlack
            is State.Paused -> state.returnState.pvpBottomIsBlack
            is State.Delaying -> state.nextState.pvpBottomIsBlack
            else -> true
        }
        val aiHint = (state as? State.WaitingForPlayer)?.aiHint
        val isCalculatingHint = (state as? State.WaitingForPlayer)?.isCalculatingHint ?: false
        val canUndo = (state as? State.WaitingForPlayer)?.let {
            // 人机模式需至少 2 步（玩家 1 步 + AI 1 步）才能撤销；双人模式 1 步即可
            val minMoves = if (it.mode == GameMode.VS_AI) 2 else 1
            it.moveHistory.size >= minMoves && it.settings.undoEnabled
        } ?: false

        return UiModel(
            gameStatus = gameStatus,
            board = board,
            currentPlayer = currentPlayer,
            lastMove = lastMove,
            gameResult = gameResult,
            mode = mode,
            difficulty = difficulty,
            settings = settings,
            aiPlayerColor = aiPlayerColor,
            pvpBottomIsBlack = pvpBottomIsBlack,
            isAiThinking = state is State.WaitingForAi || state is State.Pausing || state is State.Paused,
            aiProgress = when (state) {
                is State.WaitingForAi, is State.Pausing, is State.Paused -> aiProgressRaw.coerceIn(0f, 1f)
                else -> 0f
            },
            aiHint = aiHint,
            isCalculatingHint = isCalculatingHint,
            canUndo = canUndo,
            aiBestMoveHint = if (state is State.WaitingForAi || state is State.Pausing) bestMoveHint else null,
            showSettings = ui.showSettings,
            showDifficultyToast = ui.showDifficultyToast,
            showingAssistHint = ui.showingAssistHint,
        )
    }

    // ========================================================================
    // 查询方法（已被 uiModel 取代，保留供兼容）
    // ========================================================================

    fun getMode(): GameMode = when (val s = gameState.value) {
        is State.Idle -> s.mode
        is State.WaitingForPlayer -> s.mode
        is State.WaitingForAi -> s.mode
        is State.GameOver -> s.mode
        else -> GameMode.VS_AI
    }
    
    fun getDifficulty(): Difficulty = when (val s = gameState.value) {
        is State.Idle -> s.difficulty
        is State.WaitingForPlayer -> s.difficulty
        is State.WaitingForAi -> s.difficulty
        is State.GameOver -> s.difficulty
        else -> Difficulty.EASY
    }
    
    fun getSettings(): GameSettings = when (val s = gameState.value) {
        is State.Idle -> s.settings
        is State.WaitingForPlayer -> s.settings
        is State.WaitingForAi -> s.settings
        is State.GameOver -> s.settings
        else -> GameSettings()
    }
    
    fun getBoard(): Board? = when (val s = gameState.value) {
        is State.WaitingForPlayer -> s.board
        is State.WaitingForAi -> s.board
        is State.GameOver -> s.board
        else -> null
    }
    
    fun getCurrentPlayer(): PieceColor? = when (val s = gameState.value) {
        is State.WaitingForPlayer -> s.currentPlayer
        is State.WaitingForAi -> s.currentPlayer
        else -> null
    }
    
    fun getLastMove(): Piece? = when (val s = gameState.value) {
        is State.WaitingForPlayer -> s.lastMove
        is State.WaitingForAi -> s.lastMove
        is State.GameOver -> s.moveHistory.lastOrNull()
        else -> null
    }
    
    fun getAiHint(): Pair<Int, Int>? = when (val s = gameState.value) {
        is State.WaitingForPlayer -> s.aiHint
        else -> null
    }
    
    fun isCalculatingHint(): Boolean = when (val s = gameState.value) {
        is State.WaitingForPlayer -> s.isCalculatingHint
        else -> false
    }
    
    fun isAiThinking(): Boolean = gameState.value is State.WaitingForAi
    
    fun canUndo(): Boolean = when (val s = gameState.value) {
        is State.WaitingForPlayer -> s.moveHistory.isNotEmpty() && s.settings.undoEnabled
        else -> false
    }
    
    fun shouldShowAssistButton(): Boolean = 
        isCalculatingHint() || getAiHint() != null
    
    fun isVsHuman(): Boolean = getMode() == GameMode.VS_HUMAN
    
    fun getActiveAiColor(): PieceColor? = when (val s = gameState.value) {
        is State.Idle -> s.aiPlayerColor
        is State.WaitingForPlayer -> s.aiPlayerColor
        is State.WaitingForAi -> s.aiPlayerColor
        is State.GameOver -> s.aiPlayerColor
        else -> null
    }
    
    fun getPvpBottomIsBlack(): Boolean = when (val s = gameState.value) {
        is State.Idle -> s.pvpBottomIsBlack
        is State.WaitingForPlayer -> s.pvpBottomIsBlack
        is State.WaitingForAi -> s.pvpBottomIsBlack
        is State.GameOver -> s.pvpBottomIsBlack
        else -> true
    }
    
    fun getGameStatus(): GameStatus = when (gameState.value) {
        is State.Initializing -> GameStatus.NOT_STARTED
        is State.Idle -> GameStatus.NOT_STARTED
        is State.WaitingForPlayer, is State.WaitingForAi,
             is State.Pausing, is State.Paused, is State.Delaying -> GameStatus.PLAYING
        is State.GameOver -> GameStatus.FINISHED
        is State.Stopping -> GameStatus.NOT_STARTED
    }
    
    fun getGameResult(): GameResult? = when (val s = gameState.value) {
        is State.GameOver -> {
            s.winner?.let { GameResult.Win(it) } ?: GameResult.Draw
        }
        else -> null
    }
    
    // ========================================================================
    // UI 控制
    // ========================================================================
    
    fun triggerDifficultyToast() {
        // 取消上一次定时器（若 Toast 已显示则重置持续时间）
        toastJob?.cancel()
        _uiState.update { it.copy(showDifficultyToast = true) }
        toastJob = viewModelScope.launch {
            delay(1500)
            _uiState.update { it.copy(showDifficultyToast = false) }
        }
    }
    
    fun hideDifficultyToast() {
        _uiState.update { it.copy(showDifficultyToast = false) }
    }
    
    fun showSettings() {
        _uiState.update { it.copy(showSettings = true) }
    }
    
    fun hideSettings() {
        _uiState.update { it.copy(showSettings = false) }
    }
    
    fun onShowAssistHint() {
        _uiState.update { it.copy(showingAssistHint = true) }
    }
    
    fun hideAssistHint() {
        _uiState.update { it.copy(showingAssistHint = false) }
    }
    
    // ========================================================================
    // 音效和震动
    // ========================================================================
    
    private fun loadSoundEffects() {
        try {
            val context = getApplication<Application>()
            val resId = context.resources.getIdentifier("place_piece", "raw", context.packageName)
            if (resId != 0) moveSoundId = soundPool.load(context, resId, 1)
            
            val stampResId = context.resources.getIdentifier("stamp", "raw", context.packageName)
            if (stampResId != 0) stampSoundId = soundPool.load(context, stampResId, 1)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun playMoveSound() {
        if (!AppLifecycleState.isInForeground) return
        val settings = getSettings()
        if (!settings.soundEnabled || moveSoundId == 0) return
        try {
            soundPool.play(moveSoundId, 1f, 1f, 1, 0, 1f)
        } catch (e: Exception) {}
    }
    
    fun playStampSound() {
        if (!AppLifecycleState.isInForeground) return
        val settings = getSettings()
        if (!settings.soundEnabled || stampSoundId == 0) return
        try {
            soundPool.play(stampSoundId, 1f, 1f, 1, 0, 1f)
        } catch (e: Exception) {}
    }
    
    private fun triggerVibration() {
        if (!AppLifecycleState.isInForeground) return
        val settings = getSettings()
        if (!settings.vibrationEnabled) return

        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
    }
    
    // ========================================================================
    // AI 管理（接入 Rust AI）
    // ========================================================================

    /** 当前持有的 Rust AI 实例（单局内复用） */
    private var rustAi: RustAi? = null

    private fun getOrCreateRustAi(difficulty: Difficulty, aiColorInt: Int): RustAi? {
        rustAi?.let { return it }
        return when (difficulty) {
            // EASY / MEDIUM → Guided MCTS（探索常数 × 100 传给 JNI）
            Difficulty.EASY   -> RustAi.createMcts(timeLimitMs = 650,   player = aiColorInt, explorationCx100 = 220)
            Difficulty.MEDIUM -> RustAi.createMcts(timeLimitMs = 1800,  player = aiColorInt, explorationCx100 = 110)
            // HARD / MASTER → Minimax + Alpha-Beta + 迭代加深
            Difficulty.HARD   -> RustAi.create(maxDepth = 12, timeLimitMs = 4000,  player = aiColorInt)
            Difficulty.MASTER -> RustAi.create(maxDepth = 20, timeLimitMs = 12000, player = aiColorInt)
        }.also { rustAi = it }
    }

    private fun launchAiThinking(state: State.WaitingForAi) {
        cancelAiJob()
        progressTracker.start(aiTimeLimitMs(state.difficulty))   // 重置并传入 AI 时间预算
        val aiColorInt = if (state.aiPlayerColor == PieceColor.BLACK) 1 else 2
        val minAnimMs = minProgressAnimMs(state.difficulty)
        // 仅在进度明显未到位时才保留少量收尾动画预算
        val finishBudgetMs = 282L
        aiJob = viewModelScope.launch(Dispatchers.Default) {
            val thinkStart = System.currentTimeMillis()
            try {
                val ai = getOrCreateRustAi(state.difficulty, aiColorInt)
                if (ai == null) {
                    progressTracker.cancel()
                    sendCommand(Cmd.AiCancelled)
                    return@launch
                }
                val boardBytes = state.board.toByteArray()

                // 启动最优走法轮询协程（200ms 间隔），随 aiJob 自动取消
                val pollJob = launch {
                    while (isActive) {
                        delay(200L)
                        _aiBestMoveHint.value = ai.getBestMove()
                    }
                }

                val move = ai.takeTurn(boardBytes) { percent ->
                    // Rust 回调进度直通 [0, 1]；速度上限公式自动分配空间
                    progressTracker.advanceTo(percent.coerceIn(0, 100) / 100f)
                }
                pollJob.cancel()
                _aiBestMoveHint.value = null   // 落子前清除预览
                if (move != null) {
                    val currentProgress = progressTracker.progress.value
                    // 仅在进度明显未到位时才补足最小动画时长；若视觉上已接近满环则立即落子
                    val elapsed = System.currentTimeMillis() - thinkStart
                    val fillRemaining = if (currentProgress < 0.92f) {
                        (minAnimMs - elapsed - finishBudgetMs).coerceAtLeast(0L)
                    } else {
                        0L
                    }
                    if (fillRemaining > 0L) {
                        delay(fillRemaining)
                    }
                    // 仅在尚未接近满环时做短暂补满，避免已经满环后还明显等待
                    if (progressTracker.progress.value < 0.985f) {
                        progressTracker.setManualTarget(1.0f)
                        delay(120L)
                    }
                    progressTracker.complete()
                    sendCommand(Cmd.AiDone(Piece(move.first, move.second, state.aiPlayerColor)))
                } else {
                    // AI 被取消（invalidate 后返回 null）
                    progressTracker.cancel()
                    sendCommand(Cmd.AiCancelled)
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) e.printStackTrace()
                progressTracker.cancel()
                sendCommand(Cmd.AiCancelled)
            }
        }
    }

    /**
     * 软性进度追踪器——倒计时 + Rust 增量池（时间精确版）。
     *
     * 核心修复：用实际帧间隔 [dt] 替代固定 FRAME_MS 计算 baseInc 和 drain，
     * 消除主线程波动（GC、渲染峰值）导致的"明显不动"。
     *
     * 设计：
     *   1. base 速率：K 倍时限填满，始终有进度，与真实 dt 等比。
     *   2. Rust 增量池：回调增量缓存到 pendingBoost，指数衰减释放（每 16ms 15%），
     *      衰减速率也与真实 dt 等比，确保时间精度。
     *   3. 速度上限：inc ≤ (1−displayed)×dt/remaining，保留空间给后续回调。
     *   4. manualTarget（fill/finish）绕过上限，约 250ms 平滑到位再 complete()。
     */
    private inner class SoftProgressTracker {
        private val _progress = MutableStateFlow(0f)
        val progress: StateFlow<Float> = _progress

        @Volatile private var rustProgress: Float = 0f
        @Volatile private var manualTarget: Float = 0f
        @Volatile private var timeLimitMs: Long = 1000L
        @Volatile private var startTimeMs: Long = 0L

        // 以下仅在 loopJob（Main）访问
        private var displayed: Float = 0f
        private var prevRust: Float = 0f
        private var pendingBoost: Float = 0f

        private var loopJob: Job? = null

        private val K = 2.0f
        private val DRAIN_FACTOR = 0.15f   // 每 16ms 释放 15% 的 boost 池
        private val BASE_FRAME_MS = 16f    // drain 速率的参考帧时长

        fun start(aiTimeLimitMs: Long) {
            loopJob?.cancel()
            rustProgress = 0f; manualTarget = 0f
            displayed = 0f; prevRust = 0f; pendingBoost = 0f
            _progress.value = 0f
            timeLimitMs = aiTimeLimitMs.coerceAtLeast(1L)
            startTimeMs = System.currentTimeMillis()
            loopJob = viewModelScope.launch {
                var lastMs = startTimeMs
                while (isActive) {
                    val now = System.currentTimeMillis()
                    val dt = (now - lastMs).toFloat().coerceIn(1f, 150f)
                    lastMs = now
                    tick(now, dt)
                    delay(16L)
                }
            }
        }

        private fun tick(now: Long, dt: Float) {
            val elapsed = (now - startTimeMs).toFloat()
            val T = timeLimitMs.toFloat()

            /* ① 收集 Rust 增量 */
            val rp = rustProgress
            pendingBoost += (rp - prevRust).coerceAtLeast(0f)
            prevRust = rp

            /* ② 指数衰减释放（与真实 dt 等比） */
            val drainFactor = (DRAIN_FACTOR * dt / BASE_FRAME_MS).coerceIn(0f, 1f)
            val desiredDrain = pendingBoost * drainFactor

            /* ③ base 增量（时间精确） */
            val baseInc = dt / (K * T)

            /* ④ 期望增量 = base + drain */
            var inc = baseInc + desiredDrain

            /* ⑤ 速度上限：inc ≤ (1−displayed) × dt / remaining */
            val remaining = T - elapsed
            if (remaining > 0f) {
                val maxInc = (1f - displayed) * dt / remaining
                inc = inc.coerceAtMost(maxInc)
            }

            /* ⑥ 回算 boost 实际消耗量 */
            pendingBoost = (pendingBoost - (inc - baseInc).coerceAtLeast(0f)).coerceAtLeast(0f)

            /* ⑦ manualTarget 绕过速度上限 */
            val mt = manualTarget
            if (mt > displayed + 0.001f) {
                inc = maxOf(inc, (mt - displayed) * (dt / BASE_FRAME_MS * 0.20f).coerceIn(0f, 1f))
            }

            displayed = (displayed + inc).coerceIn(0f, 1f)
            _progress.value = displayed
        }

        /** Rust 回调：推进上报进度（只增不减） */
        fun advanceTo(value: Float) {
            val c = value.coerceIn(0f, 1f)
            if (c > rustProgress) rustProgress = c
        }

        /** fill/finish 专用：绕过速度上限，约 250ms 平滑填满 */
        fun setManualTarget(value: Float) {
            val c = value.coerceIn(0f, 1f)
            if (c > manualTarget) manualTarget = c
        }

        /** AI 落子前：立即居满并停止循环 */
        fun complete() {
            rustProgress = 1f; manualTarget = 1f; displayed = 1f
            _progress.value = 1f; loopJob?.cancel(); loopJob = null
        }

        /** AI 取消 / 出错：立即清零 */
        fun cancel() {
            loopJob?.cancel(); loopJob = null; rustProgress = 0f
            manualTarget = 0f; displayed = 0f; _progress.value = 0f
        }
    }
    /**
     * AI 实际时间限制（与 Rust 侧创建参数保持同步）。
     * 用于 SoftProgressTracker 计算时间基础进度。
     */
    private fun aiTimeLimitMs(difficulty: Difficulty): Long = when (difficulty) {
        Difficulty.EASY   -> 650L
        Difficulty.MEDIUM -> 1800L
        Difficulty.HARD   -> 4000L
        Difficulty.MASTER -> 12000L
    }

    /**
     * 每种难度的最小进度环动画时长。
     * AI 若提前完成（如即时赢棋），会等到此时长后才落子，
     * 让玩家看到进度环平滑填充，而不是瞬间消失。
     */
    private fun minProgressAnimMs(difficulty: Difficulty): Long = when (difficulty) {
        Difficulty.EASY   -> 550L    // 与 EASY 时间限制（650ms）接近
        Difficulty.MEDIUM -> 1200L   // MEDIUM 1800ms，留出 1.2s 最短动画
        Difficulty.HARD   -> 2500L   // HARD 4000ms，确保有足够进度感
        Difficulty.MASTER -> 4500L   // MASTER 基本总是超过此值，作为安全下限
    }

    private fun launchAssistCalculation(state: State.WaitingForPlayer) {
        cancelAssistJob()
        val currentColor = state.currentPlayer
        val expectedMoveCount = state.moveHistory.size  // 用于校验：结果返回时步数若已变则丢弃
        // 预先捕获棋盘快照，避免协程延迟期间状态被修改
        val boardSnapshot = state.board.toByteArray()
        assistJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                // 等待 5 秒：用户若在此期间落子，协程会被 cancelAssistJob() 取消
                delay(5_000L)
                // 使用比 HARD 难度（4000ms）稍多的时间限制，深度与 HARD 相当
                val ai = RustAi.create(
                    maxDepth = 20,
                    timeLimitMs = 12_000,
                    player = if (currentColor == PieceColor.BLACK) 1 else 2
                ) ?: return@launch
                assistAi = ai  // 注册到成员，使 cancelAssistJob 能协作式通知 Rust 侧停止
                val move = ai.takeTurn(boardSnapshot)
                assistAi = null
                ai.destroy()
                // isActive 检查：若 assistJob 在 JNI 计算期间被 cancel（阻塞调用无法中断），
                // 此处拦截，避免将过期局面的提示发送给 actor。
                if (move != null && isActive) {
                    sendCommand(Cmd.AssistReady(move, expectedMoveCount))
                }
            } catch (e: Exception) {
                // CancellationException（用户落子/撤销）会被静默忽略
                if (e !is kotlinx.coroutines.CancellationException) e.printStackTrace()
            } finally {
                // 确保无论正常结束还是取消，都清理成员引用并销毁 AI 实例
                assistAi?.destroy()
                assistAi = null
            }
        }
    }

    private fun randomFallback(board: io.github.ian_miller.wuziqi.domain.model.Board): Pair<Int, Int>? {
        val empty = mutableListOf<Pair<Int, Int>>()
        for (r in 0 until io.github.ian_miller.wuziqi.domain.model.Board.SIZE) {
            for (c in 0 until io.github.ian_miller.wuziqi.domain.model.Board.SIZE) {
                if (board.getPiece(r, c) == null) empty.add(r to c)
            }
        }
        return empty.randomOrNull()
    }

    private fun validateAi() { rustAi?.validate() }
    private fun invalidateAi() { rustAi?.invalidate(); progressTracker.cancel() }
    private fun destroyAi() { rustAi?.destroy(); rustAi = null; progressTracker.cancel() }
    private fun invalidateAndDestroyAi() { invalidateAi(); destroyAi() }
    private fun cancelAiJob() { aiJob?.cancel(); aiJob = null; progressTracker.cancel() }
    private fun cancelAssistJob() {
        // 先通知 Rust 侧停止（协作式取消）：即使 JNI 阻塞也能尽早跳出搜索循环
        assistAi?.invalidate()
        assistJob?.cancel()
        assistJob = null
        // assistAi 由协程 finally 块负责 destroy + 清空
    }
    private fun cancelDelayJob() { delayJob?.cancel(); delayJob = null }
    
    // ========================================================================
    // 存档管理
    // ========================================================================

    /**
     * 存档格式：DIFFICULTY;AI_COLOR_OR_NONE;PVP_BOTTOM_IS_BLACK;row,col,COLOR|row,col,COLOR|...
     * currentPlayer 由 moveHistory.size % 2 推导，无需单独存储。
     */
    private fun saveGame(
        mode: GameMode,
        difficulty: Difficulty,
        aiPlayerColor: PieceColor?,
        pvpBottomIsBlack: Boolean,
        moveHistory: List<Piece>
    ) {
        // 空局不保存，避免主菜单出现无意义的"续局"入口
        if (moveHistory.isEmpty()) return
        val sb = StringBuilder()
        sb.append(difficulty.name).append(";")
        sb.append(aiPlayerColor?.name ?: "NONE").append(";")
        sb.append(pvpBottomIsBlack).append(";")
        moveHistory.forEach { piece ->
            sb.append(piece.row).append(",").append(piece.col).append(",").append(piece.color.name).append("|")
        }
        prefs.edit().putString("saved_game_${mode.name}", sb.toString()).apply()
    }

    private fun saveGameAsync(state: State.WaitingForPlayer) {
        saveGame(state.mode, state.difficulty, state.aiPlayerColor, state.pvpBottomIsBlack, state.moveHistory)
    }

    private fun restoreSavedGame(mode: GameMode): Boolean {
        val savedString = prefs.getString("saved_game_${mode.name}", null) ?: return false
        return try {
            // split limit=4：前3个字段 + 剩余全部作为棋步字符串
            val parts = savedString.split(";", limit = 4)
            if (parts.size < 4) return false

            val difficulty = Difficulty.valueOf(parts[0])
            val aiPlayerColor = if (parts[1] == "NONE") null else PieceColor.valueOf(parts[1])
            val pvpBottomIsBlack = parts[2].toBoolean()
            val movesString = parts[3]

            val moveHistory = mutableListOf<Piece>()
            if (movesString.isNotEmpty()) {
                movesString.split("|").filter { it.isNotEmpty() }.forEach { token ->
                    val p = token.split(",")
                    if (p.size == 3) {
                        moveHistory.add(Piece(p[0].toInt(), p[1].toInt(), PieceColor.valueOf(p[2])))
                    }
                }
            }

            // 从历史重建棋盘
            val board = moveHistory.fold(Board.empty()) { b, piece ->
                b.placePiece(piece.row, piece.col, piece.color)
            }
            // currentPlayer 由步数奇偶推导（黑棋先手）
            val currentPlayer = if (moveHistory.size % 2 == 0) PieceColor.BLACK else PieceColor.WHITE
            val lastMove = moveHistory.lastOrNull()

            val settings = GameSettings(
                soundEnabled = prefs.getBoolean("sound_enabled", true),
                vibrationEnabled = prefs.getBoolean("vibration_enabled", true),
                undoEnabled = prefs.getBoolean("undo_enabled", true),
                aiAssistEnabled = prefs.getBoolean("ai_assist_enabled", false)
            )

            // 判断轮到谁：若是 AI 的回合则恢复为 WaitingForAi，否则 WaitingForPlayer
            val restoredState: State = if (mode == GameMode.VS_AI && aiPlayerColor == currentPlayer) {
                State.WaitingForAi(
                    board = board,
                    currentPlayer = currentPlayer,
                    mode = mode,
                    difficulty = difficulty,
                    settings = settings,
                    moveHistory = moveHistory,
                    aiPlayerColor = aiPlayerColor!!,
                    lastMove = lastMove,
                    pvpBottomIsBlack = pvpBottomIsBlack
                )
            } else {
                State.WaitingForPlayer(
                    board = board,
                    currentPlayer = currentPlayer,
                    mode = mode,
                    difficulty = difficulty,
                    settings = settings,
                    moveHistory = moveHistory,
                    aiPlayerColor = aiPlayerColor,
                    lastMove = lastMove,
                    pvpBottomIsBlack = pvpBottomIsBlack
                )
            }

            sendCommand(Cmd.Restore(restoredState))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            prefs.edit().remove("saved_game_${mode.name}").apply()
            false
        }
    }

    private fun clearSavedGame(mode: GameMode) {
        prefs.edit().remove("saved_game_${mode.name}").apply()
    }

    /** 检查指定模式是否存在未完成的存档 */
    fun hasSavedGame(mode: GameMode): Boolean =
        prefs.getString("saved_game_${mode.name}", null) != null

    private fun saveGameRecord(state: State.GameOver) {
        if (state.mode != GameMode.VS_AI) return

        val playerId = prefs.getLong("selected_player_id", -1L)
        if (playerId <= 0L) return

        val aiColor = state.aiPlayerColor ?: return
        val humanColor = aiColor.opposite()

        val result = when (val winner = state.winner) {
            null -> io.github.ian_miller.wuziqi.domain.repository.GameResult.DRAW
            humanColor -> io.github.ian_miller.wuziqi.domain.repository.GameResult.WIN
            else -> io.github.ian_miller.wuziqi.domain.repository.GameResult.LOSE
        }

        val boardSnapshot = state.moveHistory.joinToString("|") {
            "${it.row},${it.col},${it.color.name}"
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.saveGameRecord(
                    playerId = playerId,
                    opponentId = null,
                    gameMode = GameMode.VS_AI,
                    difficulty = state.difficulty,
                    result = result,
                    boardSnapshot = boardSnapshot,
                    moves = state.moveHistory.size,
                )
            }
        }
    }
    
    /**
     * 保存双人游戏记录
     */
    fun savePvPGame(blackName: String, whiteName: String) {
        // TODO: 实现双人游戏战绩保存
    }
    
    // ========================================================================
    // 工具函数
    // ========================================================================
    
    private fun isValidMove(board: Board, row: Int, col: Int): Boolean {
        return row in 0 until Board.SIZE && 
               col in 0 until Board.SIZE &&
               board.getPiece(row, col) == null
    }
    
    private fun checkWin(board: Board, row: Int, col: Int, color: PieceColor): Boolean {
        val directions = listOf(
            Pair(0, 1),   // 水平
            Pair(1, 0),   // 垂直
            Pair(1, 1),   // 对角线
            Pair(1, -1)   // 反对角线
        )
        
        return directions.any { (dr, dc) ->
            var count = 1
            
            // 正向
            for (i in 1..4) {
                val r = row + dr * i
                val c = col + dc * i
                if (r in 0 until Board.SIZE && c in 0 until Board.SIZE && board.getPiece(r, c) == color) {
                    count++
                } else break
            }
            
            // 反向
            for (i in 1..4) {
                val r = row - dr * i
                val c = col - dc * i
                if (r in 0 until Board.SIZE && c in 0 until Board.SIZE && board.getPiece(r, c) == color) {
                    count++
                } else break
            }
            
            count >= 5
        }
    }
    
    // ========================================================================
    // 生命周期
    // ========================================================================
    
    fun onPause() = addPauseSource(PauseSource.LIFECYCLE)
    fun onResume() = removePauseSource(PauseSource.LIFECYCLE)
    
    override fun onCleared() {
        super.onCleared()
        soundPool.release()
        cancelAiJob()
        cancelAssistJob()
        cancelDelayJob()
        invalidateAndDestroyAi()
    }
}

// ========================================================================
// 外部使用的密封类（GameStatus 定义在 GameViewModel.kt 中）
// ========================================================================

sealed class GameResult {
    data class Win(val winner: PieceColor) : GameResult()
    object Draw : GameResult()
}
