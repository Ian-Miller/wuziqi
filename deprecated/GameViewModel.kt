package io.github.ian_miller.wuziqi.ui.game

import android.app.Application
import android.media.AudioManager
import android.media.SoundPool
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

import io.github.ian_miller.wuziqi.domain.logic.GameEngine
import io.github.ian_miller.wuziqi.domain.model.GameMode
import io.github.ian_miller.wuziqi.domain.model.PieceColor
import io.github.ian_miller.wuziqi.domain.model.GameState
import io.github.ian_miller.wuziqi.domain.model.Difficulty
import io.github.ian_miller.wuziqi.domain.model.Piece
import io.github.ian_miller.wuziqi.domain.repository.GameRepository
import io.github.ian_miller.wuziqi.domain.repository.Player
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import io.github.ian_miller.wuziqi.domain.repository.GameResult
import kotlinx.coroutines.withContext
import javax.inject.Inject

// GameStatus 已迁移到 GameStatus.kt

@HiltViewModel
@Deprecated(
    message = "已由 GameViewModelV2 取代。此文件通过 sourceSets 排除，不参与编译。",
    level = DeprecationLevel.ERROR
)
class GameViewModel @Inject constructor(
    application: Application,
    private val repository: GameRepository
) : AndroidViewModel(application) {
    private val gameEngine = GameEngine()

    private val _gameState = MutableStateFlow(gameEngine.getCurrentState())
    val gameState: StateFlow<GameState> = _gameState

    private val _gameMode = MutableStateFlow(gameEngine.getGameMode())
    val gameMode: StateFlow<GameMode> = _gameMode

    private val _players = MutableStateFlow<List<Player>>(emptyList())
    val players: StateFlow<List<Player>> = _players

    private val _selectedPlayer = MutableStateFlow<Player?>(null)
    val selectedPlayer: StateFlow<Player?> = _selectedPlayer

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState
    
    // 统计数据
    private val _stats = MutableStateFlow<Map<String, Any>>(emptyMap())
    val stats: StateFlow<Map<String, Any>> = _stats

    private val _isAiThinking = MutableStateFlow(false)
    val isAiThinking: StateFlow<Boolean> = _isAiThinking
    private var aiJob: Job? = null
    
    // AI 辅助相关
    private var assistJob: Job? = null
    private var assistTimerJob: Job? = null
    
    // 音效相关
    private val soundPool = SoundPool.Builder().setMaxStreams(2).build()
    private var moveSoundId: Int = 0
    private var stampSoundId: Int = 0

    // 震动相关
    private val vibrator = getApplication<Application>().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    
    // Preferences
    private val prefs = getApplication<Application>().getSharedPreferences("gomoku_prefs", Context.MODE_PRIVATE)


    // 记录比赛结果
    fun saveGameRecord() {
        if (_uiState.value.gameStatus != GameStatus.FINISHED) return
        if (_gameMode.value != GameMode.VS_AI) return // 暂时只自动保存人机对战记录

        val currentState = _gameState.value
        val winner = currentState.winner()
        
        // 确定胜负关系
        // 在 PVE 中，我们需要知道谁是玩家。
        // GameEngine.getAiPlayer() 返回 AI 的颜色。
        val aiColor = gameEngine.getAiPlayer()
        val playerColor = aiColor.opposite()
        
        val result = when(winner) {
            playerColor -> GameResult.WIN
            aiColor -> GameResult.LOSE
            else -> GameResult.DRAW // null or other
        }
        
        viewModelScope.launch {
            val player = _selectedPlayer.value ?: return@launch
            // opponentId null 代表 AI 或非玩家对手
            repository.saveGameRecord(
                playerId = player.id,
                opponentId = null, 
                gameMode = _gameMode.value,
                difficulty = if (_gameMode.value == GameMode.VS_AI) _uiState.value.selectedDifficulty else null,
                result = result,
                boardSnapshot = "", // 暂时不存快照
                moves = currentState.board.getAllPieces().size
            )
            // 刷新统计
            loadPlayerStats()
        }
    }

    // 初始化默认玩家
    private suspend fun ensureDefaultPlayer() {
        val players = repository.getAllPlayers()
        if (players.isEmpty()) {
            repository.createPlayer("Sunny")
        }
        loadPlayers()
    }
    
    init {
        // Load settings
        val undoEnabled = prefs.getBoolean("undo_enabled", true)
        val soundEnabled = prefs.getBoolean("sound_enabled", true)
        val vibrationEnabled = prefs.getBoolean("vibration_enabled", true)
        val aiAssistEnabled = prefs.getBoolean("ai_assist_enabled", true)
        
        val savedDifficultyName = prefs.getString("selected_difficulty", Difficulty.EASY.name)
        val savedDifficulty = try {
            Difficulty.valueOf(savedDifficultyName ?: Difficulty.EASY.name)
        } catch (e: Exception) {
            Difficulty.EASY
        }

        _uiState.value = _uiState.value.copy(
            undoEnabled = undoEnabled,
            soundEnabled = soundEnabled,
            vibrationEnabled = vibrationEnabled,
            aiAssistEnabled = aiAssistEnabled,
            selectedDifficulty = savedDifficulty
        )
        // Ensure engine has the correct difficulty initially
        gameEngine.setDifficulty(savedDifficulty)

        viewModelScope.launch {
            ensureDefaultPlayer()
            // 默认选中第一个
            @OptIn(ExperimentalCoroutinesApi::class)
            _gameMode.flatMapLatest { mode ->
                repository.observePlayersSortedByGames(mode)
            }.collect { list ->
                 _players.value = list
                 
                 // 尝试恢复选中的玩家
                 val savedPlayerId = prefs.getLong("selected_player_id", -1L)
                 var restored = false
                 
                 if (savedPlayerId != -1L) {
                      val savedPlayer = list.find { it.id == savedPlayerId }
                      if (savedPlayer != null) {
                          _selectedPlayer.value = savedPlayer
                          _uiState.value = _uiState.value.copy(selectedPlayerId = savedPlayer.id)
                          restored = true
                      }
                 }
                 
                 // 如果没恢复成功且当前没有选中，默认选第一个
                 if (!restored && _selectedPlayer.value == null && list.isNotEmpty()) {
                     _selectedPlayer.value = list[0]
                     _uiState.value = _uiState.value.copy(selectedPlayerId = list[0].id)
                     // 更新 Preference 为默认第一个
                     prefs.edit().putLong("selected_player_id", list[0].id).apply()
                 }
                 
                 // 如果玩家列表更新了（例如统计数据变化），刷新 _selectedPlayer 对象引用
                 if (_selectedPlayer.value != null) {
                     val current = _selectedPlayer.value!!
                     val updated = list.find { it.id == current.id }
                     if (updated != null && updated != current) {
                         _selectedPlayer.value = updated
                     }
                 }
            }
        }
        
        // 加载音效资源
        try {
            val context = getApplication<Application>()
            // 尝试加载 raw/place_piece.mp3 (如果存在)
            val resId = context.resources.getIdentifier("place_piece", "raw", context.packageName)
            if (resId != 0) {
                moveSoundId = soundPool.load(context, resId, 1)
            }
            
            // 加载 raw/stamp.wav
            val stampResId = context.resources.getIdentifier("stamp", "raw", context.packageName)
            if (stampResId != 0) {
                stampSoundId = soundPool.load(context, stampResId, 1)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun playStampSound() {
        if (!_uiState.value.soundEnabled) return
        
        // Safety check: Don't play if VM is dying
        if (!viewModelScope.coroutineContext.isActive) return

        if (stampSoundId != 0) {
           try {
               soundPool.play(stampSoundId, 1f, 1f, 1, 0, 1f)
           } catch (e: Exception) {
               // Ignore SoundPool errors
           }
        }
    }
    
    private val soundPoolLock = Any()

    private fun playMoveSound() {
        if (!_uiState.value.soundEnabled) return
        
        // Safety check
        if (!viewModelScope.coroutineContext.isActive) return

        if (moveSoundId != 0) {
            try {
                soundPool.play(moveSoundId, 1f, 1f, 1, 0, 1f)
            } catch (e: Exception) {
                // Ignore
            }
        }
        // Removed fallback to system key click to avoid confusion or ghost sounds
    }

    private fun triggerVibration() {
        if (!_uiState.value.vibrationEnabled) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }

    override fun onCleared() {
        super.onCleared()
        soundPool.release()
        // 应用完全退出时释放AI资源
        // 清理资源
    }
    
    // 应用进入后台
    fun onPause() {
        saveActiveGame()
        // 应用进入后台
    }
    
    // 应用从后台恢复
    fun onResume() {
        // 应用进入前台
    }

    // 初始化游戏（导航进入时调用）
    fun initializeGame(mode: GameMode) {
        if (restoreActiveGame(mode)) {
            // Already restored to PLAYING
        } else {
            setGameMode(mode) // 设置模式
            reset()           // 确保状态为 NOT_STARTED，等待 startGame
        }
        // 进入人机对战页面时触发难度提示
        if (mode == GameMode.VS_AI) {
            triggerDifficultyToast()
        }
    }

    // 游戏状态查询
    fun isGameNotStarted(): Boolean = _uiState.value.gameStatus == GameStatus.NOT_STARTED
    fun isGamePlaying(): Boolean = _uiState.value.gameStatus == GameStatus.PLAYING
    fun isGameFinished(): Boolean = _uiState.value.gameStatus == GameStatus.FINISHED

    // 开始游戏（从 NOT_STARTED 或 FINISHED 进入 PLAYING）
    fun startGame(aiFirst: Boolean? = null, pvpBottomBlack: Boolean = true) {
        if (_uiState.value.gameStatus == GameStatus.PLAYING) return
        cancelAiJob()
        gameEngine.reset()
        gameEngine.setGameMode(_uiState.value.selectedMode)
        gameEngine.setDifficulty(_uiState.value.selectedDifficulty)
        
        if (_uiState.value.selectedMode == GameMode.VS_AI && aiFirst != null) {
            val aiColor = if(aiFirst) PieceColor.BLACK else PieceColor.WHITE
            gameEngine.setAiPlayer(aiColor)
        } else if (_uiState.value.selectedMode == GameMode.VS_AI) {
            gameEngine.setAiPlayer(PieceColor.WHITE)
        }
        
        _gameState.value = gameEngine.getCurrentState()
        _gameMode.value = _uiState.value.selectedMode
        _uiState.value = _uiState.value.copy(
            gameStatus = GameStatus.PLAYING,
            gameResult = null,
            activeAiColor = if (_uiState.value.selectedMode == GameMode.VS_AI) gameEngine.getAiPlayer() else null,
            pvpBottomPlayerIsBlack = if (_uiState.value.selectedMode == GameMode.VS_HUMAN) pvpBottomBlack else true
        )
        
        if (_uiState.value.selectedMode == GameMode.VS_AI &&
            gameEngine.getAiPlayer() == _gameState.value.currentPlayer
        ) {
            launchAiMove(1000)
        } else if (_uiState.value.selectedMode == GameMode.VS_HUMAN) {
            startAssistTimer()
        }
    }

    // 重新开始（保持当前模式，清空棋盘）
    fun restartGame() {
        if (_uiState.value.gameStatus != GameStatus.PLAYING && _uiState.value.gameStatus != GameStatus.FINISHED) return
        // 游戏被主动重置，清除存档和缓存
        clearSavedGame(_gameMode.value)
        // 新游戏开始
        cancelAiJob()
        cancelAssist()
        gameEngine.reset()
        _gameState.value = gameEngine.getCurrentState()
        _uiState.value = _uiState.value.copy(
            gameStatus = GameStatus.PLAYING,
            gameResult = null,
            activeAiColor = if (_uiState.value.selectedMode == GameMode.VS_AI) gameEngine.getAiPlayer() else null
        )
        // 如果是人机对战且 AI 先手，自动触发 AI 落子
        if (_uiState.value.selectedMode == GameMode.VS_AI &&
            gameEngine.getAiPlayer() == _gameState.value.currentPlayer
        ) {
            launchAiMove(1000)
        } else if (_uiState.value.selectedMode == GameMode.VS_HUMAN) {
            startAssistTimer()
        }
    }

    // 返回主菜单（回到 NOT_STARTED）
    fun backToMenu() {
        clearSavedGame(_gameMode.value)
        cancelAiJob()
        cancelAssist()
        gameEngine.reset()
        _gameState.value = gameEngine.getCurrentState()
        _uiState.value = _uiState.value.copy(
            gameStatus = GameStatus.NOT_STARTED,
            gameResult = null
        )
    }

    // 落子
    fun placePiece(row: Int, col: Int) {
        if (_uiState.value.gameStatus != GameStatus.PLAYING) return
        val success = gameEngine.placePiece(row, col)
        if (success) {
            cancelAssist() // 成功落子，清除提示
            playMoveSound()
            triggerVibration()
            _gameState.value = gameEngine.getCurrentState()
            // 检查游戏是否结束
            if (_gameState.value.isGameOver()) {
                val result = _gameState.value.winner()?.let { UiGameResult.Win(it) } ?: UiGameResult.Draw
                _uiState.value = _uiState.value.copy(
                    gameStatus = GameStatus.FINISHED,
                    gameResult = result
                )
                saveGameRecord()
                clearSavedGame(gameEngine.getGameMode())
                cancelAssist()
                return
            }
            // 如果是人机对战且轮到 AI，则自动触发 AI 落子
            if (gameEngine.getGameMode() == GameMode.VS_AI &&
                _gameState.value.currentPlayer == gameEngine.getAiPlayer()
            ) {
                launchAiMove(1000)
            } else if (gameEngine.getGameMode() == GameMode.VS_HUMAN) {
                startAssistTimer()
            }
        }
    }

    // AI 落子（异步执行）
    suspend fun aiMove() {
        if (_uiState.value.gameStatus != GameStatus.PLAYING) return
        _isAiThinking.value = true
        
        // AI开始思考前清理缓存
        // AI开始思考
        
        try {
            val move = withContext(Dispatchers.Default) {
                gameEngine.aiMove()
            }
            
            // 关键修正：检查协程是否仍处于活跃状态。
            // 如果用户已经离开界面，viewModelScope 可能已被取消，此时不应继续执行后续UI/声音逻辑。
            viewModelScope.ensureActive()

            if (move != null) {
                playMoveSound()
                triggerVibration()
                _gameState.value = gameEngine.getCurrentState()
                // 检查游戏是否结束
                if (_gameState.value.isGameOver()) {
                    val result = _gameState.value.winner()?.let { UiGameResult.Win(it) } ?: UiGameResult.Draw
                    _uiState.value = _uiState.value.copy(
                        gameStatus = GameStatus.FINISHED,
                        gameResult = result
                    )
                    saveGameRecord()
                    clearSavedGame(gameEngine.getGameMode())
                }
            }
        } finally {
            _isAiThinking.value = false
            // AI思考结束
            // AI思考结束
        }
    }

    private fun cancelAiJob() {
        aiJob?.cancel()
        aiJob = null
    }

    private fun launchAiMove(delayMs: Long = 0) {
        cancelAiJob()
        aiJob = viewModelScope.launch {
            _isAiThinking.value = true
            try {
                if (delayMs > 0) {
                    kotlinx.coroutines.delay(delayMs)
                }
                aiMove()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
            } finally {
                _isAiThinking.value = false
            }
        }
    }

    fun savePvPGame(blackName: String, whiteName: String) {
        // 暂时不保存双人模式战绩
        return
        /*
        viewModelScope.launch {
            // Find or create players
            val currentPlayers = players.value
            val blackPlayer = currentPlayers.find { it.name == blackName }
            val blackId = blackPlayer?.id ?: repository.createPlayer(blackName)
            
            val whitePlayer = currentPlayers.find { it.name == whiteName }
            val whiteId = whitePlayer?.id ?: repository.createPlayer(whiteName)
            
            val winner = _gameState.value.winner()
            
            // Save for Black
            val blackResult = when(winner) {
                PieceColor.BLACK -> GameResult.WIN
                PieceColor.WHITE -> GameResult.LOSE
                else -> GameResult.DRAW
            }
            repository.saveGameRecord(
                playerId = blackId,
                opponentId = whiteId,
                gameMode = GameMode.VS_HUMAN,
                result = blackResult,
                boardSnapshot = "",
                moves = _gameState.value.board.getAllPieces().size
            )
            
            // Save for White
            val whiteResult = when(winner) {
                PieceColor.WHITE -> GameResult.WIN
                PieceColor.BLACK -> GameResult.LOSE
                else -> GameResult.DRAW
            }
            repository.saveGameRecord(
                playerId = whiteId,
                opponentId = blackId,
                gameMode = GameMode.VS_HUMAN,
                result = whiteResult,
                boardSnapshot = "",
                moves = _gameState.value.board.getAllPieces().size
            )
        }
        */
    }

    // 停止游戏（人机模式下点击中止按钮）
    fun stopGame() {
        if (_uiState.value.gameStatus != GameStatus.PLAYING) return
        clearSavedGame(_gameMode.value)
        cancelAiJob()
        // 不重置棋盘，只是停止状态，改为 NOT_STARTED (等待开始)
        reset()
    }
    
    // 复位/重置 (Internal helper)
    fun reset() {
        // 重置资源
        cancelAiJob()
        cancelAssist()
        gameEngine.reset()
        _gameState.value = gameEngine.getCurrentState()
        _uiState.value = _uiState.value.copy(
            gameStatus = GameStatus.NOT_STARTED,
            gameResult = null
        )
    }

    // 撤销
    fun undo() {
        try {
            if (_uiState.value.gameStatus != GameStatus.PLAYING) return
            if (_isAiThinking.value) return
            
            // 撤销会改变盘面，清空相关缓存
            // 撤销操作
            
            // 撤销也会改变盘面，需要重置辅助
            cancelAssist()
            
            val mode = gameEngine.getGameMode()
            if (mode == GameMode.VS_AI) {
                // 人机对战模式：尝试撤销两步（AI一步，玩家一步）
                var undone = false
                if (gameEngine.undo()) {
                    undone = true
                    // 如果还有历史，再撤销一步
                    gameEngine.undo() // 第二次撤销可能失败，忽略
                }
                if (undone) {
                    _gameState.value = gameEngine.getCurrentState()
                    // 如果撤销后轮到 AI，自动触发 AI 落子
                    if (_gameState.value.currentPlayer == gameEngine.getAiPlayer()) {
                        launchAiMove()
                    }
                }
            } else {
                // 人人对战模式：正常撤销一步
                if (gameEngine.undo()) {
                    _gameState.value = gameEngine.getCurrentState()
                    // 重启辅助计时
                    startAssistTimer() 
                }
            }
        } catch (e: Exception) {
            // 忽略异常，防止崩溃
            e.printStackTrace()
        }
    }

    // 重置（完全重置，回到 NOT_STARTED）
    // fun reset() removed duplicate


    // 模式切换（仅在 NOT_STARTED 或 FINISHED 时允许）
    fun setGameMode(mode: GameMode) {
        if (_uiState.value.gameStatus == GameStatus.PLAYING) return
        _uiState.value = _uiState.value.copy(selectedMode = mode)
        gameEngine.setGameMode(mode)
        _gameMode.value = mode
    }

    fun showSettings() {
        _uiState.value = _uiState.value.copy(showSettings = true)
    }

    fun hideSettings() {
        _uiState.value = _uiState.value.copy(showSettings = false)
    }
    
    fun showStats() {
        _uiState.value = _uiState.value.copy(showStats = true)
        loadPlayerStats()
    }
    
    fun hideStats() {
        _uiState.value = _uiState.value.copy(showStats = false)
    }
    
    fun loadPlayerStats(mode: GameMode? = null) {
        viewModelScope.launch {
            val player = _selectedPlayer.value
            if (player != null) {
                // 加载该玩家在指定模式下的数据
                val targetMode = mode ?: _gameMode.value
                
                // 将耗时任务切换到 IO 线程
                val statsMap = withContext(Dispatchers.IO) {
                    val total = repository.getTotalGames(player.id, targetMode)
                    val wins = repository.getWins(player.id, targetMode)
                    val losses = repository.getLosses(player.id, targetMode)
                    val winRate = if (total > 0) (wins.toDouble() / total) * 100 else 0.0
                    
                    val map = mutableMapOf<String, Any>(
                        "total" to total,
                        "wins" to wins,
                        "losses" to losses,
                        "winRate" to winRate,
                        "mode" to targetMode
                    )

                    // 如果是人机模式，额外通过 Difficulty 分类加载数据
                    if (targetMode == GameMode.VS_AI) {
                        Difficulty.values().forEach { diff ->
                            val dTotal = repository.getTotalGames(player.id, targetMode, diff)
                            val dWins = repository.getWins(player.id, targetMode, diff)
                            val dLosses = repository.getLosses(player.id, targetMode, diff)
                            val dRate = if (dTotal > 0) (dWins.toDouble() / dTotal) * 100 else 0.0
                            
                            map["${diff.name}_total"] = dTotal
                            map["${diff.name}_wins"] = dWins
                            map["${diff.name}_losses"] = dLosses
                            map["${diff.name}_winRate"] = dRate
                        }
                    }
                    map
                }
                
                _stats.value = statsMap
            } else {
                 _stats.value = emptyMap()
            }
        }
    }

    // 难度设置（不影响已进行的对局）
    fun setDifficulty(difficulty: Difficulty) {
        _uiState.value = _uiState.value.copy(selectedDifficulty = difficulty)
        gameEngine.setDifficulty(difficulty)
        prefs.edit().putString("selected_difficulty", difficulty.name).apply()
        // 修改难度后触发 Toast（只在游戏未开始或已结束时）
        if (_uiState.value.gameStatus != GameStatus.PLAYING) {
            triggerDifficultyToast()
        }
    }

    // AI 玩家颜色设置（仅在 NOT_STARTED 或 FINISHED 时允许）
    fun setAiPlayer(color: PieceColor) {
        if (_uiState.value.gameStatus == GameStatus.PLAYING) return
        gameEngine.setAiPlayer(color)
    }

    private fun saveActiveGame() {
        if (_uiState.value.gameStatus != GameStatus.PLAYING) return
        val mode = _gameMode.value
        val history = gameEngine.getMoveHistory()
        if (history.isEmpty()) return

        val sb = StringBuilder()
        val diff = gameEngine.getDifficulty().name
        val aiColor = gameEngine.getAiPlayer().name

        sb.append(diff).append(";").append(aiColor).append(";")

        history.forEach { piece ->
            sb.append(piece.row).append(",").append(piece.col).append(",").append(piece.color.name).append("|")
        }

        prefs.edit().putString("saved_game_${mode.name}", sb.toString()).apply()
    }

    private fun clearSavedGame(mode: GameMode) {
        prefs.edit().remove("saved_game_${mode.name}").apply()
    }

    private fun restoreActiveGame(mode: GameMode): Boolean {
        val savedString = prefs.getString("saved_game_${mode.name}", null) ?: return false
        try {
            val parts = savedString.split(";")
            if (parts.size < 3) return false

            val diffName = parts[0]
            val aiColorName = parts[1]
            val movesString = parts[2]

            val diff = Difficulty.valueOf(diffName)
            val aiColor = PieceColor.valueOf(aiColorName)

            val moves = mutableListOf<Piece>()
            if (movesString.isNotEmpty()) {
                val moveTokens = movesString.split("|").filter { it.isNotEmpty() }
                for (token in moveTokens) {
                    val p = token.split(",")
                    if (p.size == 3) {
                        moves.add(Piece(p[0].toInt(), p[1].toInt(), PieceColor.valueOf(p[2])))
                    }
                }
            }

            gameEngine.restoreGame(moves, mode, diff, aiColor)
            _gameState.value = gameEngine.getCurrentState()
            _gameMode.value = mode
            _uiState.value = _uiState.value.copy(
                gameStatus = GameStatus.PLAYING,
                gameResult = null,
                selectedMode = mode,
                selectedDifficulty = diff,
                activeAiColor = if (mode == GameMode.VS_AI) aiColor else null
            )

            // Resume AI if needed
            if (mode == GameMode.VS_AI && gameEngine.getAiPlayer() == _gameState.value.currentPlayer) {
                launchAiMove(1000)
            } else if (mode == GameMode.VS_HUMAN) {
                startAssistTimer()
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            clearSavedGame(mode)
            return false
        }
    }

    // 显示/隐藏玩家选择面板
    fun showPlayerSelection() {
        _uiState.value = _uiState.value.copy(showPlayerSelection = true)
    }

    fun hidePlayerSelection() {
        _uiState.value = _uiState.value.copy(showPlayerSelection = false)
    }

    // 玩家相关
    private suspend fun loadPlayers() {
        val playersList = withContext(Dispatchers.IO) {
            repository.observeAllPlayers().first()
        }
        _players.value = playersList
    }

    fun selectPlayer(player: Player) {
        _selectedPlayer.value = player
        _uiState.value = _uiState.value.copy(selectedPlayerId = player.id)
        prefs.edit().putLong("selected_player_id", player.id).apply()
    }

    fun createPlayer(name: String) {
        if (name.isBlank()) return
        // 检查重名 (忽略大小写)
        val exists = _players.value.any { it.name.equals(name, ignoreCase = true) }
        if (exists) return

        viewModelScope.launch(Dispatchers.IO) {
            val id = repository.createPlayer(name)
            loadPlayers()
        }
    }

    fun renamePlayer(player: Player, newName: String) {
        if (newName.isBlank()) return
        
        viewModelScope.launch(Dispatchers.IO) {
            // Check for duplicate names (case-insensitive mainly for user experience, logic can vary)
            val exists = _players.value.any { it.name.equals(newName, ignoreCase = true) && it.id != player.id }
            if (exists) {
                 // You might want to expose an err state here, for now we just return
                 return@launch
            }
            
            repository.updatePlayer(player.copy(name = newName))
            loadPlayers()
            // 如果改的是当前选中的玩家，需要刷新选中状态
            if (_selectedPlayer.value?.id == player.id) {
                _selectedPlayer.value = _selectedPlayer.value?.copy(name = newName)
            }
        }
    }

    fun setAiAssistEnabled(enabled: Boolean) {
        if (_uiState.value.gameStatus == GameStatus.PLAYING) return
        _uiState.value = _uiState.value.copy(aiAssistEnabled = enabled)
        prefs.edit().putBoolean("ai_assist_enabled", enabled).apply()
    }
    
    // 启动/重置辅助计时
    private fun startAssistTimer() {
        cancelAssist() // 每次启动前先清理
        
        if (!_uiState.value.aiAssistEnabled) return
        if (_gameMode.value != GameMode.VS_HUMAN) return
        if (_uiState.value.gameStatus != GameStatus.PLAYING) return
        
        assistTimerJob = viewModelScope.launch {
            // 延迟5秒后启动AI计算（给AI更多计算时间，总等待时间不变）
            kotlinx.coroutines.delay(5000L)
            launchAssistCalculation()
        }
    }
    
    // 启动 AI 辅助计算
    private fun launchAssistCalculation() {
       assistJob?.cancel()
       assistJob = viewModelScope.launch(Dispatchers.Default) {
           val move = gameEngine.calculateAssistMove()
           if (move != null) {
               withContext(Dispatchers.Main) {
                   // 再次确认状态，防止计算期间已经落子
                   if (_uiState.value.gameStatus == GameStatus.PLAYING) {
                       _uiState.value = _uiState.value.copy(
                           showAssistButton = true,
                           assistMove = move
                       )
                   }
               }
           }
       }
    }
    
    fun onShowAssistHint() {
        _uiState.value = _uiState.value.copy(showingAssistHint = true)
    }
    
    private fun cancelAssist() {
        assistTimerJob?.cancel()
        assistJob?.cancel()
        _uiState.value = _uiState.value.copy(
            showAssistButton = false,
            showingAssistHint = false,
            assistMove = null
        )
    }

    // 更新设置
    fun setSoundEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(soundEnabled = enabled)
        prefs.edit().putBoolean("sound_enabled", enabled).apply()
    }

    fun setVibrationEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(vibrationEnabled = enabled)
        prefs.edit().putBoolean("vibration_enabled", enabled).apply()
    }

    fun setUndoEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(undoEnabled = enabled)
        prefs.edit().putBoolean("undo_enabled", enabled).apply()
    }
    
    // 重新加载设置（用于不同 ViewModel 实例间同步偏好）
    fun reloadSettings() {
        val undoEnabled = prefs.getBoolean("undo_enabled", true)
        val soundEnabled = prefs.getBoolean("sound_enabled", true)
        val vibrationEnabled = prefs.getBoolean("vibration_enabled", true)
        val aiAssistEnabled = prefs.getBoolean("ai_assist_enabled", true)
        // Difficulty is mode specific, loaded in loadPlayerStats/initializeGame

        _uiState.value = _uiState.value.copy(
            undoEnabled = undoEnabled,
            soundEnabled = soundEnabled,
            vibrationEnabled = vibrationEnabled,
            aiAssistEnabled = aiAssistEnabled
        )
    }

    // 检查是否存在指定模式的存档
    fun hasSavedGame(mode: GameMode): Boolean {
        return prefs.getString("saved_game_${mode.name}", null) != null
    }

    // 触发难度提示 Toast（用于进入页面或修改难度后）
    fun triggerDifficultyToast() {
        // 仅在人机模式下触发
        if (_gameMode.value != GameMode.VS_AI) return
        _uiState.value = _uiState.value.copy(showDifficultyToast = true)
    }
    
    // 隐藏难度提示 Toast
    fun hideDifficultyToast() {
        _uiState.value = _uiState.value.copy(showDifficultyToast = false)
    }

}

// UI 状态
data class GameUiState(
    val gameStatus: GameStatus = GameStatus.NOT_STARTED,
    val selectedMode: GameMode = GameMode.VS_HUMAN,
    val selectedDifficulty: Difficulty = Difficulty.EASY,
    val showSettings: Boolean = false,
    val showStats: Boolean = false,
    val showPlayerSelection: Boolean = false,
    val selectedPlayerId: Long? = null,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val undoEnabled: Boolean = true, // Added undoEnabled
    val activeAiColor: PieceColor? = null, // AI 的颜色，仅在 VS_AI 模式下有效
    val gameResult: UiGameResult? = null,   // 游戏结果（仅当 FINISHED 时有效）
    val aiAssistEnabled: Boolean = false,   // 是否启用双人模式AI辅助
    val showAssistButton: Boolean = false,  // 是否显示AI提示按钮
    val assistMove: Pair<Int, Int>? = null, // AI 推荐的落子位置
    val showingAssistHint: Boolean = false, // 是否正在显示棋盘上的提示
    val pvpBottomPlayerIsBlack: Boolean = true, // PvP模式下方玩家是否执黑（先手）
    val showDifficultyToast: Boolean = false  // 是否显示难度提示Toast
)

// 简化的游戏结果，用于 UI 显示
sealed class UiGameResult {
    data class Win(val winner: PieceColor) : UiGameResult()
    object Draw : UiGameResult()
}