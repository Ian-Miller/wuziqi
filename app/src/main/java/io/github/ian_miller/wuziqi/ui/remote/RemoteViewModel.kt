package io.github.ian_miller.wuziqi.ui.remote

import android.content.Context
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ian_miller.wuziqi.AppLifecycleState
import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.Piece
import io.github.ian_miller.wuziqi.domain.model.PieceColor
import io.github.ian_miller.wuziqi.nostr.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.util.UUID
import javax.inject.Inject

// ── 错误类型（UI 层根据 type 显示本地化字符串） ────────────────────────────────

enum class RemoteErrorType {
    GENERIC,              // 通用错误（使用 message 原文）
    INVALID_LAN_CODE,     // 局域网邀请码格式错误
    INVALID_NOSTR_CODE,   // 线上邀请码格式错误
    CONNECTION_TIMEOUT,   // 连接超时
    NO_NETWORK,           // 无法获取本机 IP
    RELAY_UNAVAILABLE,    // 中继不可用
    VERSION_MISMATCH,     // 协议版本不兼容
}

// ── 连接阶段 ───────────────────────────────────────────────────────────────────

sealed class RemotePhase {
    object Idle : RemotePhase()
    data class Creating(val inviteCode: String, val gameId: String, val isLan: Boolean = false) : RemotePhase()
    object Joining : RemotePhase()
    object WaitingForOpponent : RemotePhase()
    data class Connected(
        val gameId: String,
        val opponentPubkey: String,
        val myColor: PieceColor,
        val isMyTurn: Boolean,
    ) : RemotePhase()
    data class Error(
        val message: String,
        val type: RemoteErrorType = RemoteErrorType.GENERIC,
    ) : RemotePhase()
}

// ── 棋局状态 ───────────────────────────────────────────────────────────────────

data class RemoteGameState(
    val board: Board = Board.empty(),
    val moveHistory: List<Pair<Int, Int>> = emptyList(),
    val currentTurn: PieceColor = PieceColor.BLACK,
    val myColor: PieceColor,
    val gameId: String,
    val winner: PieceColor? = null,
    val isDraw: Boolean = false,
    val isGameOver: Boolean = false,
    val drawOfferedByOpponent: Boolean = false,
    val mySeq: Int = 2,
    /** 我方已发出求和请求，等待对方回应 */
    val drawSentByMe: Boolean = false,
    /** 对方发来的再来一局请求中对方想执的颜色（null 表示无请求） */
    val rematchOfferedColor: PieceColor? = null,
    /** 我方已发出再来一局请求，等待对方回应 */
    val rematchSentByMe: Boolean = false,
) {
    val isMyTurn: Boolean get() = currentTurn == myColor && !isGameOver

    val lastMove: Piece?
        get() {
            if (moveHistory.isEmpty()) return null
            val (row, col) = moveHistory.last()
            val color = if ((moveHistory.size - 1) % 2 == 0) PieceColor.BLACK else PieceColor.WHITE
            return Piece(row, col, color)
        }
}

// ── UI 状态 ────────────────────────────────────────────────────────────────────

data class RemoteUiState(
    val phase: RemotePhase = RemotePhase.Idle,
    val myPublicKey: String = "",
    val joinInputCode: String = "",
    /** null=检测中，true=可用，false=不可用 */
    val nostrAvailable: Boolean? = null,
    /** 一次性触发：导航到棋盘界面 */
    val pendingNavToGame: Boolean = false,
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class RemoteViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    companion object {
        private const val KEY_RELAY_BLOCKED_PERMANENT = "relay_blocked_permanent"
    }

    private val prefs = context.getSharedPreferences("nostr_prefs", Context.MODE_PRIVATE)
    private val keyPair: NostrKeyPair by lazy { NostrKeyStore.load(prefs) }
    private val client = NostrClient()

    private var lanBridge: LanGameBridge? = null
    private var lanGameId: String = ""

    // LAN 断线重连所需信息
    private var lanIsHost: Boolean = false
    private var lanStoredIp: String = ""
    private var lanStoredPort: Int = LAN_PORT
    private var reconnectJob: Job? = null
    private var joinTimeoutJob: Job? = null

    private val _state = MutableStateFlow(RemoteUiState())
    val state: StateFlow<RemoteUiState> = _state.asStateFlow()

    private val _gameState = MutableStateFlow<RemoteGameState?>(null)
    val gameState: StateFlow<RemoteGameState?> = _gameState.asStateFlow()

    /** LAN 对端是否在线（Nostr 模式始终为 true） */
    private val _lanPeerConnected = MutableStateFlow(true)
    val lanPeerConnected: StateFlow<Boolean> = _lanPeerConnected.asStateFlow()

    // ── 音效 / 震动设置（与主游戏共享 SharedPreferences）──────────────────────
    private val gomokuPrefs = context.getSharedPreferences("gomoku_prefs", Context.MODE_PRIVATE)
    private val _soundEnabled = MutableStateFlow(gomokuPrefs.getBoolean("sound_enabled", true))
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()
    private val _vibrationEnabled = MutableStateFlow(gomokuPrefs.getBoolean("vibration_enabled", true))
    val vibrationEnabled: StateFlow<Boolean> = _vibrationEnabled.asStateFlow()

    // 音效播放
    private val soundPool = SoundPool.Builder().setMaxStreams(2).build()
    private var moveSoundId: Int = 0
    private var stampSoundId: Int = 0
    @Suppress("DEPRECATION")
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private fun isRelayBlockedPermanently(): Boolean =
        prefs.getBoolean(KEY_RELAY_BLOCKED_PERMANENT, false)

    fun setSoundEnabled(enabled: Boolean) {
        gomokuPrefs.edit().putBoolean("sound_enabled", enabled).apply()
        _soundEnabled.value = enabled
    }

    fun setVibrationEnabled(enabled: Boolean) {
        gomokuPrefs.edit().putBoolean("vibration_enabled", enabled).apply()
        _vibrationEnabled.value = enabled
    }

    val relayStatus = client.relayStatus

    init {
        if (!isRelayBlockedPermanently()) {
            client.connect(viewModelScope)
        } else {
            _state.update { it.copy(nostrAvailable = false) }
        }
        viewModelScope.launch {
            client.events.collect { event -> handleEvent(event) }
        }
        _state.update { it.copy(myPublicKey = keyPair.publicKeyHex) }
        loadSoundEffects()

        // 恢复磁盘存档（应用重启或 ViewModel 重建后）
        loadGameFromDisk()?.let { saved ->
            _gameState.value = saved
            _state.update {
                it.copy(
                    phase = RemotePhase.Connected(
                        gameId = saved.gameId,
                        opponentPubkey = "lan_peer",
                        myColor = saved.myColor,
                        isMyTurn = saved.isMyTurn,
                    ),
                )
            }
            _lanPeerConnected.value = false
            lanGameId = saved.gameId
            // 如果是房主，自动重启服务器等待对方重连
            if (lanIsHost) {
                restartLanServer()
            } else if (lanStoredIp.isNotEmpty()) {
                // 加入方：先创建 bridge（否则 lanBridge 为 null，reconnect 是空操作）
                lanBridge = LanGameBridge(
                    onMessage = { msg -> viewModelScope.launch { handleLanMessage(msg) } },
                    onClientConnected = ::onLanClientConnected,
                    onDisconnected = ::onLanDisconnected,
                )
                startAutoReconnect()
            }
        }
    }

    // ── 断线重连 ───────────────────────────────────────────────────────────────

    /** 房主：重开 WebSocket 服务器（相同端口，服务器重启后等待对方重连） */
    private fun restartLanServer() {
        lanBridge?.close()
        lanBridge = LanGameBridge(
            onMessage = { msg -> viewModelScope.launch { handleLanMessage(msg) } },
            onPeerConnected = ::onLanPeerConnected,
            onDisconnected = ::onLanDisconnected,
        ).also { it.startServer() }
    }

    /** 加入方：指数退避自动重连存储的 IP:Port */
    private fun startAutoReconnect() {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            var backoff = RemoteTiming.RECONNECT_INITIAL_BACKOFF_MS
            while (isActive && !_lanPeerConnected.value) {
                delay(backoff)
                lanBridge?.reconnect(lanStoredIp, lanStoredPort)
                backoff = minOf(backoff * 2, RemoteTiming.RECONNECT_MAX_BACKOFF_MS)
            }
        }
    }

    /** 房主侧：对端（加入方）连接或重连到本机服务器 */
    private fun onLanPeerConnected() {
        viewModelScope.launch {
            _lanPeerConnected.value = true
            reconnectJob?.cancel()
            // 若已有棋局（重连场景），主动发送 RESYNC 让对方同步完整棋盘
            val gs = _gameState.value
            if (gs != null && !gs.isGameOver) {
                val movesStr = gs.moveHistory.map { "${it.first},${it.second}" }
                lanBridge?.send(
                    GameMsg(MsgType.RESYNC, gameId = gs.gameId, seq = gs.mySeq, moves = movesStr)
                )
            }
        }
    }

    /** 加入方侧：成功连接到房主服务器 */
    private fun onLanClientConnected() {
        viewModelScope.launch {
            _lanPeerConnected.value = true
            reconnectJob?.cancel()
            val gs = _gameState.value
            if (gs != null && !gs.isGameOver) {
                // 重连场景：请求对方同步完整棋盘
                lanBridge?.send(
                    GameMsg(MsgType.RESYNC_REQUEST, gameId = gs.gameId, seq = gs.mySeq)
                )
            } else {
                // 初次连接：发送 JOIN（携带协议版本）
                lanBridge?.send(GameMsg(MsgType.JOIN, gameId = lanGameId, seq = 0, version = PROTOCOL_VERSION))
            }
        }
    }

    /** 对端断开（房主/加入方均会触发） */
    private fun onLanDisconnected() {
        viewModelScope.launch {
            _lanPeerConnected.value = false
            _gameState.value?.let { saveGameToDisk(it) }
            // 游戏已结束时不自动重连（对方已主动离开）
            if (_gameState.value?.isGameOver == true) return@launch
            // 加入方：自动重连
            if (!lanIsHost && lanStoredIp.isNotEmpty()) {
                startAutoReconnect()
            }
            // 房主：服务器继续监听，无需主动重连
        }
    }

    // ── 磁盘存档 ───────────────────────────────────────────────────────────────

    private fun saveGameToDisk(gs: RemoteGameState) {
        val movesStr = gs.moveHistory.joinToString(";") { "${it.first},${it.second}" }
        val role = if (lanIsHost) "HOST" else "JOINER"
        val data = "${gs.gameId}|${gs.myColor.name}|$role|$lanStoredIp|$lanStoredPort|$movesStr"
        prefs.edit().putString("remote_game_save", data).apply()
    }

    /** 对局结束或用户主动放弃时清除存档 */
    fun clearSavedGame() {
        prefs.edit().remove("remote_game_save").apply()
    }

    /**
     * 从磁盘加载游戏状态；同时恢复 [lanIsHost]、[lanStoredIp]、[lanStoredPort]。
     * 返回 null 说明没有可恢复的进行中对局。
     */
    private fun loadGameFromDisk(): RemoteGameState? {
        val data = prefs.getString("remote_game_save", null) ?: return null
        return runCatching {
            val parts = data.split("|", limit = 6)
            if (parts.size < 5) return null
            val gameId = parts[0]
            val myColor = PieceColor.valueOf(parts[1])
            lanIsHost = parts[2] == "HOST"
            lanStoredIp = parts[3]
            lanStoredPort = parts[4].toIntOrNull() ?: LAN_PORT
            val movesStr = if (parts.size >= 6) parts[5] else ""
            val moves = if (movesStr.isEmpty()) emptyList()
            else movesStr.split(";").mapNotNull { s ->
                val c = s.split(",")
                if (c.size == 2) (c[0].toIntOrNull() ?: return@mapNotNull null) to
                        (c[1].toIntOrNull() ?: return@mapNotNull null)
                else null
            }
            var board = Board.empty()
            moves.forEachIndexed { i, (r, c) ->
                board = board.placePiece(r, c, if (i % 2 == 0) PieceColor.BLACK else PieceColor.WHITE)
            }
            val winner = if (moves.isNotEmpty()) {
                val last = moves.last()
                val lc = if ((moves.size - 1) % 2 == 0) PieceColor.BLACK else PieceColor.WHITE
                checkWinner(board, last.first, last.second, lc)
            } else null
            val gameOver = winner != null || board.isFull()
            RemoteGameState(
                board = board,
                moveHistory = moves,
                currentTurn = if (moves.size % 2 == 0) PieceColor.BLACK else PieceColor.WHITE,
                myColor = myColor,
                gameId = gameId,
                winner = winner,
                isGameOver = gameOver,
            ).takeIf { !gameOver }  // 已结束的存档不恢复
        }.getOrNull()
    }

    // ── 中继可用性检测 ─────────────────────────────────────────────────────────

    fun pingRelays() {
        if (isRelayBlockedPermanently()) {
            _state.update { it.copy(nostrAvailable = false) }
            return
        }
        if (_state.value.nostrAvailable != null) return
        _state.update { it.copy(nostrAvailable = null) }
        viewModelScope.launch {
            delay(3000L)
            val connected = relayStatus.value.values.count { it }
            val available = connected > 0
            _state.update { it.copy(nostrAvailable = available) }
            if (!available) {
                prefs.edit().putBoolean(KEY_RELAY_BLOCKED_PERMANENT, true).apply()
                client.disconnect()
            } else {
                prefs.edit().putBoolean(KEY_RELAY_BLOCKED_PERMANENT, false).apply()
            }
        }
    }

    fun retryRelayConnection() {
        prefs.edit().putBoolean(KEY_RELAY_BLOCKED_PERMANENT, false).apply()
        _state.update { it.copy(nostrAvailable = null) }
        client.disconnect()
        client.connect(viewModelScope)
        viewModelScope.launch {
            delay(3000L)
            val connected = relayStatus.value.values.count { it }
            val available = connected > 0
            _state.update { it.copy(nostrAvailable = available) }
            if (!available) {
                prefs.edit().putBoolean(KEY_RELAY_BLOCKED_PERMANENT, true).apply()
                client.disconnect()
            } else {
                prefs.edit().putBoolean(KEY_RELAY_BLOCKED_PERMANENT, false).apply()
            }
        }
    }

    fun consumeNavToGame() {
        _state.update { it.copy(pendingNavToGame = false) }
    }

    // ── 用户操作 ───────────────────────────────────────────────────────────────

    fun createRoom() {
        if (_state.value.nostrAvailable == false) {
            _state.update { it.copy(phase = RemotePhase.Error("", RemoteErrorType.RELAY_UNAVAILABLE)) }
            return
        }
        val gameId = UUID.randomUUID().toString().replace("-", "").take(16)
        val inviteCode = InviteCode.encode(keyPair.publicKeyHex, gameId)
        client.subscribe(gameId)
        _state.update { it.copy(phase = RemotePhase.Creating(inviteCode, gameId)) }
    }

    fun startJoining() {
        _state.update { it.copy(phase = RemotePhase.Joining) }
    }

    fun cancelJoining() {
        joinTimeoutJob?.cancel()
        reconnectJob?.cancel()
        lanBridge?.close()
        lanBridge = null
        lanGameId = ""
        _state.update { it.copy(phase = RemotePhase.Idle, joinInputCode = "") }
    }

    fun onJoinCodeChanged(code: String) {
        _state.update { it.copy(joinInputCode = code) }
    }

    fun joinRoom() {
        val code = _state.value.joinInputCode.trim()
        if (LanInviteCode.isLanCode(code)) {
            joinRoomLan(code)
        } else {
            if (_state.value.nostrAvailable == false) {
                _state.update { it.copy(phase = RemotePhase.Error("", RemoteErrorType.RELAY_UNAVAILABLE)) }
                return
            }
            joinRoomNostr(code)
        }
    }

    // ── 局域网模式 ─────────────────────────────────────────────────────────────

    fun createRoomLan() {
        reconnectJob?.cancel()
        lanBridge?.close()
        val gameId = UUID.randomUUID().toString().replace("-", "").take(16)
        val ip = getLocalIpAddress() ?: run {
            _state.update {
                it.copy(phase = RemotePhase.Error("", RemoteErrorType.NO_NETWORK))
            }
            return
        }
        lanGameId = gameId
        lanIsHost = true
        lanStoredIp = ""
        lanBridge = LanGameBridge(
            onMessage = { msg -> viewModelScope.launch { handleLanMessage(msg) } },
            onPeerConnected = ::onLanPeerConnected,
            onDisconnected = ::onLanDisconnected,
        ).also { it.startServer() }
        val inviteCode = LanInviteCode.encode(gameId, ip)
        _state.update { it.copy(phase = RemotePhase.Creating(inviteCode, gameId, isLan = true)) }
    }

    /**
     * 周期性检查 LAN 服务器存活状态（由 UI 层在 Creating 阶段调用）。
     * 热点关闭再重开时 bridge 可能失效，此方法会重建服务器并在 IP 变化时更新邀请码。
     */
    fun ensureLanServerAlive() {
        val phase = _state.value.phase as? RemotePhase.Creating ?: return
        if (!phase.isLan) return
        val ip = getLocalIpAddress() ?: return

        // Bridge 被意外清理或服务器线程终止时重建
        if (lanBridge == null) {
            lanBridge = LanGameBridge(
                onMessage = { msg -> viewModelScope.launch { handleLanMessage(msg) } },
                onPeerConnected = ::onLanPeerConnected,
                onDisconnected = ::onLanDisconnected,
            ).also { it.startServer() }
        }

        // IP 变化时更新邀请码（热点重启后 IP 可能变化）
        val newCode = LanInviteCode.encode(phase.gameId, ip)
        if (newCode != phase.inviteCode) {
            _state.update {
                it.copy(phase = RemotePhase.Creating(newCode, phase.gameId, isLan = true))
            }
        }
    }

    private fun joinRoomLan(code: String) {
        val decoded = LanInviteCode.decode(code) ?: run {
            _state.update { it.copy(phase = RemotePhase.Error("", RemoteErrorType.INVALID_LAN_CODE)) }
            return
        }
        val (gameId, ip, port) = decoded
        lanGameId = gameId
        lanIsHost = false
        lanStoredIp = ip
        lanStoredPort = port
        reconnectJob?.cancel()
        lanBridge?.close()
        lanBridge = LanGameBridge(
            onMessage = { msg -> viewModelScope.launch { handleLanMessage(msg) } },
            onClientConnected = ::onLanClientConnected,
            onDisconnected = ::onLanDisconnected,
        ).also { it.connectToHost(ip, port) }
        _state.update { it.copy(phase = RemotePhase.WaitingForOpponent) }

        // 连接超时：若固定时间内未收到 JOIN_ACK，视为连接失败
        joinTimeoutJob?.cancel()
        joinTimeoutJob = viewModelScope.launch {
            delay(RemoteTiming.JOIN_TIMEOUT_MS)
            if (_state.value.phase is RemotePhase.WaitingForOpponent) {
                reconnectJob?.cancel()
                lanBridge?.close()
                lanBridge = null
                _state.update {
                    it.copy(phase = RemotePhase.Error("", RemoteErrorType.CONNECTION_TIMEOUT))
                }
            }
        }
    }

    private fun handleLanMessage(msg: GameMsg) {
        when (_state.value.phase) {
            is RemotePhase.Creating -> {
                if (msg.type == MsgType.JOIN && msg.gameId == lanGameId) {
                    // 检查对方协议版本
                    if (msg.version != 0 && msg.version != PROTOCOL_VERSION) {
                        _state.update {
                            it.copy(phase = RemotePhase.Error("", RemoteErrorType.VERSION_MISMATCH))
                        }
                        return
                    }
                    val ack = GameMsg(MsgType.JOIN_ACK, gameId = lanGameId, seq = 1, version = PROTOCOL_VERSION)
                    lanBridge?.send(ack)
                    _state.update {
                        it.copy(
                            phase = RemotePhase.Connected(
                                gameId = lanGameId, opponentPubkey = "lan_peer",
                                myColor = PieceColor.BLACK, isMyTurn = true,
                            ),
                            pendingNavToGame = true,
                        )
                    }
                    initGameState(lanGameId, PieceColor.BLACK)
                }
            }
            is RemotePhase.WaitingForOpponent -> {
                if (msg.type == MsgType.JOIN_ACK) {
                    joinTimeoutJob?.cancel()
                    // 检查对方协议版本
                    if (msg.version != 0 && msg.version != PROTOCOL_VERSION) {
                        reconnectJob?.cancel()
                        lanBridge?.close()
                        lanBridge = null
                        _state.update {
                            it.copy(phase = RemotePhase.Error("", RemoteErrorType.VERSION_MISMATCH))
                        }
                        return
                    }
                    val gameId = msg.gameId.ifBlank { lanGameId }
                    _state.update {
                        it.copy(
                            phase = RemotePhase.Connected(
                                gameId = gameId, opponentPubkey = "lan_peer",
                                myColor = PieceColor.WHITE, isMyTurn = false,
                            ),
                            pendingNavToGame = true,
                        )
                    }
                    initGameState(gameId, PieceColor.WHITE)
                }
            }
            is RemotePhase.Connected -> handleGameMessage(msg)
            else -> Unit
        }
    }

    // ── Nostr 模式 ─────────────────────────────────────────────────────────────

    private fun joinRoomNostr(code: String) {
        val decoded = InviteCode.decode(code)
        if (decoded == null) {
            _state.update { it.copy(phase = RemotePhase.Error("", RemoteErrorType.INVALID_NOSTR_CODE)) }
            return
        }
        val (hostPubkey, gameId) = decoded
        client.subscribe(gameId)
        runCatching {
            val msg = GameMsg(MsgType.JOIN, gameId = gameId, seq = 0, version = PROTOCOL_VERSION)
            val encrypted = GomokuCrypto.encrypt(
                keyPair.privateKey, hostPubkey.hexToBytes(), JsonCodec.encodeToString(msg)
            )
            val event = buildAndSign(
                keyPair = keyPair, kind = KIND_GOMOKU,
                tags = listOf(listOf("d", gameId), listOf("p", hostPubkey)),
                content = encrypted,
            )
            client.publish(event)
        }.onFailure { e ->
            _state.update { it.copy(phase = RemotePhase.Error("发送失败：${e.message}")) }
            return
        }
        _state.update { it.copy(phase = RemotePhase.WaitingForOpponent) }
    }

    private fun sendNostrGameMsg(msg: GameMsg) {
        val phase = _state.value.phase as? RemotePhase.Connected ?: return
        if (phase.opponentPubkey == "lan_peer") return
        viewModelScope.launch {
            runCatching {
                val encrypted = GomokuCrypto.encrypt(
                    keyPair.privateKey, phase.opponentPubkey.hexToBytes(), JsonCodec.encodeToString(msg)
                )
                val event = buildAndSign(
                    keyPair = keyPair, kind = KIND_GOMOKU,
                    tags = listOf(listOf("d", msg.gameId), listOf("p", phase.opponentPubkey)),
                    content = encrypted,
                )
                client.publish(event)
            }
        }
    }

    // ── 棋局逻辑 ───────────────────────────────────────────────────────────────

    private fun initGameState(gameId: String, myColor: PieceColor) {
        _gameState.value = RemoteGameState(
            board = Board.empty(), moveHistory = emptyList(),
            currentTurn = PieceColor.BLACK, myColor = myColor, gameId = gameId,
        )
        _lanPeerConnected.value = true
    }

    fun placePieceRemote(row: Int, col: Int) {
        val gs = _gameState.value ?: return
        if (!gs.isMyTurn) return
        if (gs.board.getPiece(row, col) != null) return
        val newBoard = gs.board.placePiece(row, col, gs.myColor)
        val winner = checkWinner(newBoard, row, col, gs.myColor)
        val newHistory = gs.moveHistory + (row to col)
        val gameOver = winner != null || newBoard.isFull()
        val seq = gs.mySeq
        val newGs = gs.copy(
            board = newBoard, moveHistory = newHistory,
            currentTurn = gs.myColor.opposite(), winner = winner,
            isGameOver = gameOver, mySeq = seq + 1,
        )
        _gameState.value = newGs
        if (gameOver) clearSavedGame() else saveGameToDisk(newGs)
        playMoveSound(); triggerVibration()
        val msg = GameMsg(MsgType.MOVE, gameId = gs.gameId, seq = seq, row = row, col = col)
        lanBridge?.send(msg)
        sendNostrGameMsg(msg)
    }

    fun resignRemote() {
        val gs = _gameState.value ?: return
        val msg = GameMsg(MsgType.RESIGN, gameId = gs.gameId, seq = gs.mySeq)
        // 先更新本地状态，确保即使发送失败也能正确清理
        _gameState.update { it?.copy(winner = gs.myColor.opposite(), isGameOver = true, mySeq = gs.mySeq + 1) }
        clearSavedGame()
        // 游戏已结束：停止重连尝试
        reconnectJob?.cancel()
        // 尽力通知对方（发送失败不影响本地状态）
        lanBridge?.send(msg)
        sendNostrGameMsg(msg)
    }

    private var drawTimeoutJob: Job? = null

    fun offerDraw() {
        val gs = _gameState.value ?: return
        if (gs.drawSentByMe) return  // 已发出，避免重复发送
        val msg = GameMsg(MsgType.DRAW_REQUEST, gameId = gs.gameId, seq = gs.mySeq)
        lanBridge?.send(msg)
        sendNostrGameMsg(msg)
        _gameState.update { it?.copy(drawSentByMe = true, mySeq = gs.mySeq + 1) }
        // 发起方超时 t_2 = 5s：超时后自动取消等待状态，任何延迟到达的 DRAW_ACCEPT 将被拒绝
        drawTimeoutJob?.cancel()
        drawTimeoutJob = viewModelScope.launch {
            delay(RemoteTiming.REQUEST_AUTO_DISMISS_MS)
            _gameState.update { it?.copy(drawSentByMe = false) }
            drawTimeoutJob = null
        }
    }

    fun acceptDraw() {
        val gs = _gameState.value ?: return
        val msg = GameMsg(MsgType.DRAW_ACCEPT, gameId = gs.gameId, seq = gs.mySeq)
        lanBridge?.send(msg)
        sendNostrGameMsg(msg)
        _gameState.update {
            it?.copy(isDraw = true, isGameOver = true, drawOfferedByOpponent = false, mySeq = gs.mySeq + 1)
        }
        clearSavedGame()
    }

    fun rejectDraw() {
        val gs = _gameState.value ?: return
        val msg = GameMsg(MsgType.DRAW_REJECT, gameId = gs.gameId, seq = gs.mySeq)
        lanBridge?.send(msg)
        sendNostrGameMsg(msg)
        _gameState.update { it?.copy(drawOfferedByOpponent = false, mySeq = gs.mySeq + 1) }
    }

    // ── 再来一局 ───────────────────────────────────────────────────────────────

    private var rematchTimeoutJob: Job? = null

    /** 请求再来一局，myWantedColor = 我方想执的颜色 */
    fun requestRematch(myWantedColor: PieceColor) {
        val gs = _gameState.value ?: return
        if (!gs.isGameOver || gs.rematchSentByMe) return
        val msg = GameMsg(
            MsgType.REMATCH_REQUEST, gameId = gs.gameId, seq = gs.mySeq,
            payload = myWantedColor.name,
        )
        lanBridge?.send(msg)
        sendNostrGameMsg(msg)
        _gameState.update { it?.copy(rematchSentByMe = true, mySeq = gs.mySeq + 1) }
        // 发起方超时 t_2 = 8s：接收方 t_1 = 5s，额外保留 3s 处理传输与调度延迟
        rematchTimeoutJob?.cancel()
        rematchTimeoutJob = viewModelScope.launch {
            delay(RemoteTiming.REQUEST_SENDER_GRACE_MS)
            _gameState.update { it?.copy(rematchSentByMe = false) }
        }
    }

    fun acceptRematch() {
        val gs = _gameState.value ?: return
        val offeredColor = gs.rematchOfferedColor ?: return  // 对方想执的颜色
        val myNewColor = offeredColor.opposite()
        val msg = GameMsg(
            MsgType.REMATCH_ACCEPT, gameId = gs.gameId, seq = gs.mySeq,
            payload = myNewColor.name,  // 告知对方：我方执此颜色
        )
        lanBridge?.send(msg)
        sendNostrGameMsg(msg)
        _gameState.update { it?.copy(mySeq = gs.mySeq + 1) }
        startRematch(myColor = myNewColor)
    }

    fun rejectRematch() {
        val gs = _gameState.value ?: return
        val msg = GameMsg(MsgType.REMATCH_REJECT, gameId = gs.gameId, seq = gs.mySeq)
        lanBridge?.send(msg)
        sendNostrGameMsg(msg)
        _gameState.update { it?.copy(rematchOfferedColor = null, mySeq = gs.mySeq + 1) }
    }

    /** 清除对方发来的再开局请求（超时自动调用）。
     *  若请求仍在活跃（未被显式拒绝），同时向发起方发送 REMATCH_REJECT，
        *  避免对方一直处于"等待对方接受"的状态直到其自身发起方等待超时。
     *  若已被 rejectRematch() 显式拒绝（rematchOfferedColor 已为 null），幂等退出。
     */
    fun clearRematchOffer() {
        val gs = _gameState.value ?: return
        if (gs.rematchOfferedColor == null) return  // 已被显式拒绝，无需重复发送
        val msg = GameMsg(MsgType.REMATCH_REJECT, gameId = gs.gameId, seq = gs.mySeq)
        lanBridge?.send(msg)
        sendNostrGameMsg(msg)
        _gameState.update { it?.copy(rematchOfferedColor = null, mySeq = gs.mySeq + 1) }
    }

    /** 重置棋盘开始新一局，保持连接不断 */
    private fun startRematch(myColor: PieceColor) {
        rematchTimeoutJob?.cancel()
        val gs = _gameState.value ?: return
        _gameState.value = RemoteGameState(
            board = Board.empty(),
            moveHistory = emptyList(),
            currentTurn = PieceColor.BLACK,
            myColor = myColor,
            gameId = gs.gameId,
            mySeq = 2,
        )
        // 更新 phase 中的 myColor 和 isMyTurn
        val phase = _state.value.phase
        if (phase is RemotePhase.Connected) {
            _state.update {
                it.copy(phase = phase.copy(
                    myColor = myColor,
                    isMyTurn = myColor == PieceColor.BLACK,
                ))
            }
        }
    }

    /** 从对方发来的 RESYNC 消息重建棋盘（只接受落子数 ≥ 本地的状态） */
    private fun rebuildFromResync(msg: GameMsg) {
        val gs = _gameState.value ?: return
        val moves = msg.moves.mapNotNull { s ->
            val c = s.split(",")
            if (c.size == 2) (c[0].toIntOrNull() ?: return@mapNotNull null) to
                    (c[1].toIntOrNull() ?: return@mapNotNull null)
            else null
        }
        if (moves.size < gs.moveHistory.size) return  // 对方的数据更旧，忽略
        var board = Board.empty()
        moves.forEachIndexed { i, (r, c) ->
            board = board.placePiece(r, c, if (i % 2 == 0) PieceColor.BLACK else PieceColor.WHITE)
        }
        val winner = if (moves.isNotEmpty()) {
            val last = moves.last()
            val lc = if ((moves.size - 1) % 2 == 0) PieceColor.BLACK else PieceColor.WHITE
            checkWinner(board, last.first, last.second, lc)
        } else null
        val gameOver = winner != null || board.isFull()
        val newGs = gs.copy(
            board = board, moveHistory = moves,
            currentTurn = if (moves.size % 2 == 0) PieceColor.BLACK else PieceColor.WHITE,
            winner = winner, isGameOver = gameOver,
        )
        _gameState.value = newGs
        if (gameOver) clearSavedGame() else saveGameToDisk(newGs)
    }

    private fun handleGameMessage(msg: GameMsg) {
        val gs = _gameState.value ?: return
        when (msg.type) {
            MsgType.MOVE -> {
                if (msg.row !in 0..14 || msg.col !in 0..14) return
                val opponentColor = gs.myColor.opposite()
                if (gs.currentTurn != opponentColor) return
                if (gs.board.getPiece(msg.row, msg.col) != null) return
                val newBoard = gs.board.placePiece(msg.row, msg.col, opponentColor)
                val winner = checkWinner(newBoard, msg.row, msg.col, opponentColor)
                val newHistory = gs.moveHistory + (msg.row to msg.col)
                val gameOver = winner != null || newBoard.isFull()
                val newGs = gs.copy(
                    board = newBoard, moveHistory = newHistory,
                    currentTurn = gs.myColor, winner = winner, isGameOver = gameOver,
                )
                _gameState.value = newGs
                if (gameOver) clearSavedGame() else saveGameToDisk(newGs)
                playMoveSound(); triggerVibration()
            }
            MsgType.RESIGN -> {
                _gameState.update { it?.copy(winner = gs.myColor, isGameOver = true) }
                clearSavedGame()
                // 对方已认输离开：停止重连尝试
                reconnectJob?.cancel()
            }
            MsgType.DRAW_REQUEST -> _gameState.update { it?.copy(drawOfferedByOpponent = true) }
            MsgType.DRAW_ACCEPT -> {
                drawTimeoutJob?.cancel(); drawTimeoutJob = null
                if (gs.drawSentByMe) {
                    // 正常流程：我方求和，对方接受，游戏结束
                    _gameState.update { it?.copy(isDraw = true, isGameOver = true, drawOfferedByOpponent = false, drawSentByMe = false) }
                    clearSavedGame()
                } else {
                    // 超时后收到延迟接受：我方已取消，向对方发送拒绝以维持双端一致性
                    val reject = GameMsg(MsgType.DRAW_REJECT, gameId = gs.gameId, seq = gs.mySeq)
                    lanBridge?.send(reject)
                    sendNostrGameMsg(reject)
                }
            }
            MsgType.DRAW_REJECT -> {
                // 对方拒绝求和：取消超时计时，清除发送状态
                drawTimeoutJob?.cancel(); drawTimeoutJob = null
                _gameState.update { it?.copy(drawOfferedByOpponent = false, drawSentByMe = false) }
            }
            MsgType.RESYNC_REQUEST -> {
                // 对方重连后请求棋盘同步
                val movesStr = gs.moveHistory.map { "${it.first},${it.second}" }
                val resync = GameMsg(MsgType.RESYNC, gameId = gs.gameId, seq = gs.mySeq, moves = movesStr)
                lanBridge?.send(resync)
                sendNostrGameMsg(resync)
            }
            MsgType.RESYNC -> rebuildFromResync(msg)
            MsgType.REMATCH_REQUEST -> {
                // 对方请求再来一局：payload = 对方想执的颜色
                val wantedColor = runCatching { PieceColor.valueOf(msg.payload) }.getOrNull()
                if (wantedColor != null && gs.isGameOver) {
                    _gameState.update { it?.copy(rematchOfferedColor = wantedColor) }
                }
            }
            MsgType.REMATCH_ACCEPT -> {
                if (gs.rematchSentByMe && gs.isGameOver) {
                    // 对方接受了我方的请求，payload = 对方选择执的颜色
                    val opponentColor = runCatching { PieceColor.valueOf(msg.payload) }.getOrNull()
                    val myColor = opponentColor?.opposite() ?: gs.myColor.opposite()
                    startRematch(myColor = myColor)
                } else if (gs.isGameOver) {
                    // 超时后延迟到达的 REMATCH_ACCEPT（我方已取消等待）：
                    // 发回 REMATCH_REJECT 维持双端状态一致，避免对方进入已开局但我方未开局的状态
                    val reject = GameMsg(MsgType.REMATCH_REJECT, gameId = gs.gameId, seq = gs.mySeq)
                    lanBridge?.send(reject)
                    sendNostrGameMsg(reject)
                }
            }
            MsgType.REMATCH_REJECT -> {
                _gameState.update { it?.copy(rematchSentByMe = false) }
                rematchTimeoutJob?.cancel()
            }
            else -> Unit
        }
    }

    // ========================================================================
    // 音效和震动
    // ========================================================================

    private fun loadSoundEffects() {
        try {
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
        if (!_soundEnabled.value || moveSoundId == 0) return
        try {
            soundPool.play(moveSoundId, 1f, 1f, 1, 0, 1f)
        } catch (e: Exception) {}
    }

    fun playStampSound() {
        if (!AppLifecycleState.isInForeground) return
        if (!_soundEnabled.value || stampSoundId == 0) return
        try {
            soundPool.play(stampSoundId, 1f, 1f, 1, 0, 1f)
        } catch (e: Exception) {}
    }

    private fun triggerVibration() {
        if (!AppLifecycleState.isInForeground) return
        if (!_vibrationEnabled.value) return
        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ── 胜负判断 ───────────────────────────────────────────────────────────────

    private fun checkWinner(board: Board, row: Int, col: Int, color: PieceColor): PieceColor? {
        val dirs = listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)
        for ((dr, dc) in dirs) {
            var count = 1
            for (d in 1..4) {
                val r = row + dr * d; val c = col + dc * d
                if (r !in 0..14 || c !in 0..14 || board.getPiece(r, c) != color) break
                count++
            }
            for (d in 1..4) {
                val r = row - dr * d; val c = col - dc * d
                if (r !in 0..14 || c !in 0..14 || board.getPiece(r, c) != color) break
                count++
            }
            if (count >= 5) return color
        }
        return null
    }

    // ── 重置 ───────────────────────────────────────────────────────────────────

    fun reset() {
        joinTimeoutJob?.cancel()
        reconnectJob?.cancel()
        rematchTimeoutJob?.cancel()
        lanBridge?.close()
        lanBridge = null
        lanGameId = ""
        lanIsHost = false
        lanStoredIp = ""
        _gameState.value = null
        _lanPeerConnected.value = true
        clearSavedGame()
        _state.update {
            RemoteUiState(
                myPublicKey = keyPair.publicKeyHex,
                nostrAvailable = it.nostrAvailable,
            )
        }
    }

    // ── Nostr 事件处理 ─────────────────────────────────────────────────────────

    private fun handleEvent(event: NostrEvent) {
        if (event.pubkey == keyPair.publicKeyHex) return
        when (val phase = _state.value.phase) {
            is RemotePhase.Creating -> handleAsHost(event, phase)
            is RemotePhase.WaitingForOpponent -> handleAsJoiner(event)
            is RemotePhase.Connected -> handleAsConnectedNostr(event)
            else -> Unit
        }
    }

    private fun handleAsHost(event: NostrEvent, phase: RemotePhase.Creating) {
        runCatching {
            val decrypted = GomokuCrypto.decrypt(keyPair.privateKey, event.pubkey.hexToBytes(), event.content)
            val msg = JsonCodec.decodeFromString<GameMsg>(decrypted)
            if (msg.type != MsgType.JOIN || msg.gameId != phase.gameId) return
            val ack = GameMsg(MsgType.JOIN_ACK, gameId = phase.gameId, seq = 1)
            val encrypted = GomokuCrypto.encrypt(
                keyPair.privateKey, event.pubkey.hexToBytes(), JsonCodec.encodeToString(ack)
            )
            val ackEvent = buildAndSign(
                keyPair = keyPair, kind = KIND_GOMOKU,
                tags = listOf(listOf("d", phase.gameId), listOf("p", event.pubkey)),
                content = encrypted,
            )
            client.publish(ackEvent)
            _state.update {
                it.copy(
                    phase = RemotePhase.Connected(
                        gameId = phase.gameId, opponentPubkey = event.pubkey,
                        myColor = PieceColor.BLACK, isMyTurn = true,
                    ),
                    pendingNavToGame = true,
                )
            }
            initGameState(phase.gameId, PieceColor.BLACK)
        }
    }

    private fun handleAsJoiner(event: NostrEvent) {
        val pTag = event.tags.firstOrNull { it.firstOrNull() == "p" }?.getOrNull(1)
        if (pTag != keyPair.publicKeyHex) return
        runCatching {
            val decrypted = GomokuCrypto.decrypt(keyPair.privateKey, event.pubkey.hexToBytes(), event.content)
            val msg = JsonCodec.decodeFromString<GameMsg>(decrypted)
            if (msg.type != MsgType.JOIN_ACK) return
            _state.update {
                it.copy(
                    phase = RemotePhase.Connected(
                        gameId = msg.gameId, opponentPubkey = event.pubkey,
                        myColor = PieceColor.WHITE, isMyTurn = false,
                    ),
                    pendingNavToGame = true,
                )
            }
            initGameState(msg.gameId, PieceColor.WHITE)
        }
    }

    private fun handleAsConnectedNostr(event: NostrEvent) {
        val phase = _state.value.phase as? RemotePhase.Connected ?: return
        if (event.pubkey != phase.opponentPubkey) return
        runCatching {
            val decrypted = GomokuCrypto.decrypt(keyPair.privateKey, event.pubkey.hexToBytes(), event.content)
            val msg = JsonCodec.decodeFromString<GameMsg>(decrypted)
            handleGameMessage(msg)
        }
    }

    override fun onCleared() {
        super.onCleared()
        joinTimeoutJob?.cancel()
        reconnectJob?.cancel()
        rematchTimeoutJob?.cancel()
        lanBridge?.close()
        client.disconnect()
        soundPool.release()
    }
}
