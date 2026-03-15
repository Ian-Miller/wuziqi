package io.github.ian_miller.wuziqi.ui.menu

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ian_miller.wuziqi.domain.model.Difficulty
import io.github.ian_miller.wuziqi.domain.model.GameMode
import io.github.ian_miller.wuziqi.domain.repository.GameRepository
import io.github.ian_miller.wuziqi.domain.repository.Player
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.core.content.edit

/**
 * 主菜单 ViewModel
 * 管理：设置、玩家、统计、存档检查
 * 不管理：游戏流程（由 GameViewModelV2 管理）
 */
@HiltViewModel
class MenuViewModel @Inject constructor(
    application: Application,
    private val repository: GameRepository
) : AndroidViewModel(application) {

    private val prefs = getApplication<Application>().getSharedPreferences("gomoku_prefs", Context.MODE_PRIVATE)

    // ========================================
    // UI 状态
    // ========================================
    data class UiState(
        val selectedMode: GameMode = GameMode.VS_AI,
        val selectedDifficulty: Difficulty = Difficulty.EASY,
        val soundEnabled: Boolean = true,
        val vibrationEnabled: Boolean = true,
        val undoEnabled: Boolean = true,
        val aiAssistEnabled: Boolean = false,
        val magnifierEnabled: Boolean = true,
        val showSettings: Boolean = false,
        val showStats: Boolean = false,
        val showPlayerSelection: Boolean = false,
        /** "auto" | "en" | "zh" */
        val language: String = "auto"
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    // ========================================
    // 玩家系统
    // ========================================
    val players = repository.observeAllPlayers()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedPlayer = MutableStateFlow<Player?>(null)
    val selectedPlayer: StateFlow<Player?> = _selectedPlayer

    private val _stats = MutableStateFlow<Map<String, Any>>(emptyMap())
    val stats: StateFlow<Map<String, Any>> = _stats

    init {
        loadSettings()
        initPlayers()
    }

    // ========================================
    // 设置管理
    // ========================================
    private fun loadSettings() {
        _uiState.value = UiState(
            selectedMode = getModePreference(),
            selectedDifficulty = getDifficultyPreference(),
            soundEnabled = prefs.getBoolean("sound_enabled", true),
            vibrationEnabled = prefs.getBoolean("vibration_enabled", true),
            undoEnabled = prefs.getBoolean("undo_enabled", true),
            aiAssistEnabled = prefs.getBoolean("ai_assist_enabled", false),
            magnifierEnabled = prefs.getBoolean("magnifier_enabled", true),
            language = prefs.getString("language", "auto") ?: "auto",
        )
    }

    fun setLanguage(lang: String) {
        prefs.edit { putString("language", lang) }
        _uiState.update { it.copy(language = lang) }
    }

    fun setMode(mode: GameMode) {
        prefs.edit { putString("selected_mode", mode.name) }
        _uiState.update { it.copy(selectedMode = mode) }
    }

    fun setDifficulty(difficulty: Difficulty) {
        prefs.edit { putString("selected_difficulty", difficulty.name) }
        _uiState.update { it.copy(selectedDifficulty = difficulty) }
    }

    fun setSoundEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("sound_enabled", enabled) }
        _uiState.update { it.copy(soundEnabled = enabled) }
    }

    fun setVibrationEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("vibration_enabled", enabled) }
        _uiState.update { it.copy(vibrationEnabled = enabled) }
    }

    fun setUndoEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("undo_enabled", enabled) }
        _uiState.update { it.copy(undoEnabled = enabled) }
    }

    fun setAiAssistEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("ai_assist_enabled", enabled) }
        _uiState.update { it.copy(aiAssistEnabled = enabled) }
    }

    fun setMagnifierEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("magnifier_enabled", enabled) }
        _uiState.update { it.copy(magnifierEnabled = enabled) }
    }

    fun showSettings() = _uiState.update { it.copy(showSettings = true) }
    fun hideSettings() = _uiState.update { it.copy(showSettings = false) }
    fun showStats() = _uiState.update { it.copy(showStats = true) }
    fun hideStats() = _uiState.update { it.copy(showStats = false) }

    // ========================================
    // 存档检查
    // ========================================
    fun hasSavedGame(mode: GameMode): Boolean {
        return prefs.getString("saved_game_${mode.name}", null) != null
    }

    // ========================================
    // 玩家管理
    // ========================================
    private fun initPlayers() {
        viewModelScope.launch {
            ensureDefaultPlayer()
            observePlayers()
        }
    }

    private suspend fun ensureDefaultPlayer() {
        val players = repository.getAllPlayers()
        if (players.isEmpty()) {
            repository.createPlayer("Sunny")
        }
    }

    private fun observePlayers() {
        viewModelScope.launch {
            repository.observeAllPlayers().collect { list ->
                restoreOrSelectDefaultPlayer(list)
            }
        }
    }

    private fun restoreOrSelectDefaultPlayer(list: List<Player>) {
        val savedPlayerId = prefs.getLong("selected_player_id", -1L)
        val savedPlayer = list.find { it.id == savedPlayerId }

        if (savedPlayer != null) {
            _selectedPlayer.value = savedPlayer
        } else if (_selectedPlayer.value == null && list.isNotEmpty()) {
            _selectedPlayer.value = list[0]
            prefs.edit { putLong("selected_player_id", list[0].id) }
        }
    }

    fun selectPlayer(player: Player) {
        _selectedPlayer.value = player
        prefs.edit { putLong("selected_player_id", player.id) }
    }

    fun createPlayer(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createPlayer(name)
        }
    }

    fun renamePlayer(player: Player, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            repository.updatePlayer(player.copy(name = newName))
        }
    }

    // ========================================
    // 统计
    // ========================================
    fun loadStats(mode: GameMode? = null) {
        viewModelScope.launch {
            val player = _selectedPlayer.value ?: return@launch
            val targetMode = mode ?: _uiState.value.selectedMode

            val statsMap = mutableMapOf<String, Any>()

            val total = repository.getTotalGames(player.id, targetMode)
            val wins = repository.getWins(player.id, targetMode)
            val losses = repository.getLosses(player.id, targetMode)
            val winRate = if (total > 0) (wins.toDouble() / total) * 100 else 0.0

            statsMap["total"] = total
            statsMap["wins"] = wins
            statsMap["losses"] = losses
            statsMap["winRate"] = winRate
            statsMap["mode"] = targetMode

            if (targetMode == GameMode.VS_AI) {
                Difficulty.entries.forEach { diff ->
                    val dTotal = repository.getTotalGames(player.id, targetMode, diff)
                    val dWins = repository.getWins(player.id, targetMode, diff)
                    val dLosses = repository.getLosses(player.id, targetMode, diff)
                    val dRate = if (dTotal > 0) (dWins.toDouble() / dTotal) * 100 else 0.0

                    statsMap["${diff.name}_total"] = dTotal
                    statsMap["${diff.name}_wins"] = dWins
                    statsMap["${diff.name}_losses"] = dLosses
                    statsMap["${diff.name}_winRate"] = dRate
                }
            }

            _stats.value = statsMap
        }
    }

    // ========================================
    // 工具方法
    // ========================================
    private fun getModePreference(): GameMode = try {
        GameMode.valueOf(prefs.getString("selected_mode", GameMode.VS_AI.name)!!)
    } catch (e: Exception) {
        GameMode.VS_AI
    }

    private fun getDifficultyPreference(): Difficulty = try {
        Difficulty.valueOf(prefs.getString("selected_difficulty", Difficulty.EASY.name)!!)
    } catch (e: Exception) {
        Difficulty.EASY
    }
}
