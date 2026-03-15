package io.github.ian_miller.wuziqi.ui.remote

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    data class Error(val message: String) : RemotePhase()
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
        client.connect(viewModelScope)
        viewModelScope.launch {
            client.events.collect { event -> handleEvent(event) }
        }
        _state.update { it.copy(myPublicKey = keyPair.publicKeyHex) }

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
            var backoff = 3_000L
            while (isActive && !_lanPeerConnected.value) {
                delay(backoff)
                lanBridge?.reconnect(lanStoredIp, lanStoredPort)
                backoff = minOf(backoff * 2, 30_000L)
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
                // 初次连接：发送 JOIN
                lanBridge?.send(GameMsg(MsgType.JOIN, gameId = lanGameId, seq = 0))
            }
        }
    }

    /** 对端断开（房主/加入方均会触发） */
    private fun onLanDisconnected() {
        viewModelScope.launch {
            _lanPeerConnected.value = false
            _gameState.value?.let { saveGameToDisk(it) }
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
        val blockedUntil = prefs.getLong("relay_blocked_until", 0L)
        if (blockedUntil > System.currentTimeMillis()) {
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
                prefs.edit().putLong("relay_blocked_until", System.currentTimeMillis() + 3_600_000L).apply()
            } else {
                prefs.edit().remove("relay_blocked_until").apply()
            }
        }
    }

    fun retryRelayConnection() {
        prefs.edit().remove("relay_blocked_until").apply()
        _state.update { it.copy(nostrAvailable = null) }
        client.disconnect()
        client.connect(viewModelScope)
        viewModelScope.launch {
            delay(3000L)
            val connected = relayStatus.value.values.count { it }
            val available = connected > 0
            _state.update { it.copy(nostrAvailable = available) }
            if (!available) {
                prefs.edit().putLong("relay_blocked_until", System.currentTimeMillis() + 3_600_000L).apply()
            } else {
                prefs.edit().remove("relay_blocked_until").apply()
            }
        }
    }

    fun consumeNavToGame() {
        _state.update { it.copy(pendingNavToGame = false) }
    }

    // ── 用户操作 ───────────────────────────────────────────────────────────────

    fun createRoom() {
        if (_state.value.nostrAvailable == false) {
            retryRelayConnection()
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
        if (LanInviteCode.isLanCode(code)) joinRoomLan(code) else joinRoomNostr(code)
    }

    // ── 局域网模式 ─────────────────────────────────────────────────────────────

    fun createRoomLan() {
        reconnectJob?.cancel()
        lanBridge?.close()
        val gameId = UUID.randomUUID().toString().replace("-", "").take(16)
        val ip = getLocalIpAddress() ?: run {
            _state.update {
                it.copy(phase = RemotePhase.Error("无法获取本机局域网 IP，请确认已连接 WiFi 或已开启热点"))
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

    private fun joinRoomLan(code: String) {
        val decoded = LanInviteCode.decode(code) ?: run {
            _state.update { it.copy(phase = RemotePhase.Error("局域网邀请码格式错误")) }
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
    }

    private fun handleLanMessage(msg: GameMsg) {
        when (_state.value.phase) {
            is RemotePhase.Creating -> {
                if (msg.type == MsgType.JOIN && msg.gameId == lanGameId) {
                    val ack = GameMsg(MsgType.JOIN_ACK, gameId = lanGameId, seq = 1)
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
            _state.update { it.copy(phase = RemotePhase.Error("邀请码格式错误，请检查后重试")) }
            return
        }
        val (hostPubkey, gameId) = decoded
        client.subscribe(gameId)
        runCatching {
            val msg = GameMsg(MsgType.JOIN, gameId = gameId, seq = 0)
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
        val msg = GameMsg(MsgType.MOVE, gameId = gs.gameId, seq = seq, row = row, col = col)
        lanBridge?.send(msg)
        sendNostrGameMsg(msg)
    }

    fun resignRemote() {
        val gs = _gameState.value ?: return
        val msg = GameMsg(MsgType.RESIGN, gameId = gs.gameId, seq = gs.mySeq)
        lanBridge?.send(msg)
        sendNostrGameMsg(msg)
        _gameState.update { it?.copy(winner = gs.myColor.opposite(), isGameOver = true, mySeq = gs.mySeq + 1) }
        clearSavedGame()
    }

    fun offerDraw() {
        val gs = _gameState.value ?: return
        val msg = GameMsg(MsgType.DRAW_REQUEST, gameId = gs.gameId, seq = gs.mySeq)
        lanBridge?.send(msg)
        sendNostrGameMsg(msg)
        _gameState.update { it?.copy(mySeq = gs.mySeq + 1) }
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
            }
            MsgType.RESIGN -> {
                _gameState.update { it?.copy(winner = gs.myColor, isGameOver = true) }
                clearSavedGame()
            }
            MsgType.DRAW_REQUEST -> _gameState.update { it?.copy(drawOfferedByOpponent = true) }
            MsgType.DRAW_ACCEPT -> {
                _gameState.update { it?.copy(isDraw = true, isGameOver = true, drawOfferedByOpponent = false) }
                clearSavedGame()
            }
            MsgType.DRAW_REJECT -> _gameState.update { it?.copy(drawOfferedByOpponent = false) }
            MsgType.RESYNC_REQUEST -> {
                // 对方重连后请求棋盘同步
                val movesStr = gs.moveHistory.map { "${it.first},${it.second}" }
                val resync = GameMsg(MsgType.RESYNC, gameId = gs.gameId, seq = gs.mySeq, moves = movesStr)
                lanBridge?.send(resync)
                sendNostrGameMsg(resync)
            }
            MsgType.RESYNC -> rebuildFromResync(msg)
            else -> Unit
        }
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
        reconnectJob?.cancel()
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
        reconnectJob?.cancel()
        lanBridge?.close()
        client.disconnect()
    }
}
