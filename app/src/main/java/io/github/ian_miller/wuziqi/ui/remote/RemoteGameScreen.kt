package io.github.ian_miller.wuziqi.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.ian_miller.wuziqi.domain.model.PieceColor
import io.github.ian_miller.wuziqi.ui.game.BoardCanvas
import io.github.ian_miller.wuziqi.ui.game.GameStatus
import io.github.ian_miller.wuziqi.ui.game.GlassyButton
import io.github.ian_miller.wuziqi.ui.game.MagnifierState
import io.github.ian_miller.wuziqi.ui.game.MagnifierView
import io.github.ian_miller.wuziqi.ui.game.SinglePlayerGameHud
import io.github.ian_miller.wuziqi.ui.menu.MenuViewModel
import io.github.ian_miller.wuziqi.ui.theme.LocalStrings

/**
 * 远程对弈棋盘界面（路由: remote_game）
 *
 * UI 风格与 ActiveGameScreen 一致：
 * - 木纹渐变背景
 * - SinglePlayerGameHud（带旋转光圈、胜利图章）
 * - 放大镜功能
 * - 断线悬浮横幅（自动重连进度）
 * - 返回/操作按钮叠加在棋盘上方
 */
@Composable
fun RemoteGameScreen(
    onBack: () -> Unit,
    viewModel: RemoteViewModel = hiltViewModel(),
    menuViewModel: MenuViewModel = hiltViewModel(),
) {
    val gs by viewModel.gameState.collectAsState()
    val lanConnected by viewModel.lanPeerConnected.collectAsState()
    val menuState by menuViewModel.uiState.collectAsState()
    val magnifierEnabled = menuState.magnifierEnabled
    var magnifierState by remember { mutableStateOf<MagnifierState?>(null) }

    // 返回大厅（不结束对局，对局仍保存）
    BackHandler { onBack() }

    val woodBrush = remember {
        Brush.verticalGradient(colors = listOf(Color(0xFFE0C39E), Color(0xFFA47E5C)))
    }

    if (gs == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(woodBrush),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(color = Color(0xFF5D4037), strokeWidth = 3.dp) }
        return
    }

    val gameState = gs!!
    val s = LocalStrings.current
    var showResignDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val soundEnabled by viewModel.soundEnabled.collectAsState()
    val vibrationEnabled by viewModel.vibrationEnabled.collectAsState()

    // ── 对话框 ────────────────────────────────────────────────────────────────

    if (showResignDialog) {
        AlertDialog(
            onDismissRequest = { showResignDialog = false },
            title = { Text(s.confirmResignTitle) },
            text = { Text(s.confirmResignText) },
            confirmButton = {
                TextButton(onClick = { viewModel.resignRemote(); showResignDialog = false }) {
                    Text(s.confirmResign, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResignDialog = false }) { Text(s.cancel) }
            },
        )
    }

    if (gameState.drawOfferedByOpponent) {
        AlertDialog(
            onDismissRequest = { viewModel.rejectDraw() },
            title = { Text(s.drawOfferedTitle) },
            text = { Text(s.drawOfferedText) },
            confirmButton = {
                TextButton(onClick = { viewModel.acceptDraw() }) { Text(s.acceptDraw) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.rejectDraw() }) { Text(s.rejectDraw) }
            },
        )
    }

    // ── 设置面板 ─────────────────────────────────────────────────────────────────────────
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text(s.gameSettings, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.VolumeUp, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(s.sound, modifier = Modifier.weight(1f))
                        Switch(
                            checked = soundEnabled,
                            onCheckedChange = { viewModel.setSoundEnabled(it) },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Vibration, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(s.vibration, modifier = Modifier.weight(1f))
                        Switch(
                            checked = vibrationEnabled,
                            onCheckedChange = { viewModel.setVibrationEnabled(it) },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.ZoomIn, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(s.magnifier, modifier = Modifier.weight(1f))
                        Switch(
                            checked = magnifierEnabled,
                            onCheckedChange = { menuViewModel.setMagnifierEnabled(it) },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text(s.close) }
            },
        )
    }

    // ── 主布局：覆盖层模式（无 Scaffold） ────────────────────────────────────

    Box(modifier = Modifier.fillMaxSize().background(woodBrush)) {

        Column(modifier = Modifier.fillMaxSize()) {

            // ── 对手 HUD（顶部，对手视角） ─────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .statusBarsPadding(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                SinglePlayerGameHud(
                    isBlack = gameState.myColor.opposite() == PieceColor.BLACK,
                    isPlayer = true,
                    isActive = !gameState.isMyTurn && !gameState.isGameOver && lanConnected,
                    isWinner = gameState.winner == gameState.myColor.opposite(),
                    isDraw = gameState.isDraw,
                    gameStatus = if (gameState.isGameOver) GameStatus.FINISHED else GameStatus.PLAYING,
                    rotate180 = false,
                    playerName = s.opponent,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            // ── 棋盘 ─────────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(horizontal = 12.dp)
                    .zIndex(if (magnifierState?.visible == true) 10f else 0f),
            ) {
                BoardCanvas(
                    board = gameState.board,
                    enabled = gameState.isMyTurn && lanConnected,
                    onPlacePiece = { row, col -> viewModel.placePieceRemote(row, col) },
                    onUpdateMagnifier = { if (magnifierEnabled) magnifierState = it else magnifierState = null },
                    modifier = Modifier.fillMaxSize(),
                    gameStatus = if (gameState.isGameOver) GameStatus.FINISHED else GameStatus.PLAYING,
                    isAiThinking = !gameState.isMyTurn && !gameState.isGameOver,
                    lastMove = gameState.lastMove,
                    currentPlayer = gameState.currentTurn,
                )

                // 放大镜浮层
                if (magnifierState?.visible == true) {
                    val density = LocalDensity.current
                    val magSize = 200.dp
                    val baseOffset = 150.dp
                    MagnifierView(
                        state = magnifierState!!,
                        board = gameState.board,
                        currentPlayer = gameState.currentTurn,
                        lastMove = gameState.lastMove,
                        modifier = Modifier
                            .offset {
                                val state = magnifierState ?: return@offset IntOffset.Zero
                                val yOffsetPx = with(density) { baseOffset.toPx() }
                                val magPx = with(density) { magSize.toPx() }
                                IntOffset(
                                    (state.sourceCenter.x - magPx / 2).toInt(),
                                    (state.sourceCenter.y - yOffsetPx - magPx / 2).toInt(),
                                )
                            }
                            .size(magSize),
                    )
                }
            }

            // ── 我方 HUD（底部） ──────────────────────────────────────────────
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SinglePlayerGameHud(
                        isBlack = gameState.myColor == PieceColor.BLACK,
                        isPlayer = true,
                        isActive = gameState.isMyTurn && !gameState.isGameOver,
                        isWinner = gameState.winner == gameState.myColor,
                        isDraw = gameState.isDraw,
                        gameStatus = if (gameState.isGameOver) GameStatus.FINISHED else GameStatus.PLAYING,
                        rotate180 = false,
                        playerName = s.me,
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    // 操作按钮（游戏进行中时显示）
                    if (!gameState.isGameOver) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Max)
                                .padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            GlassyButton(
                                onClick = { viewModel.offerDraw() },
                                modifier = Modifier.weight(1f),
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            ) {
                                Icon(Icons.Default.Balance, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(s.requestDraw)
                            }
                            GlassyButton(
                                onClick = { showResignDialog = true },
                                modifier = Modifier.weight(1f),
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ) {
                                Icon(Icons.Default.Flag, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(s.resign)
                            }
                        }
                    }
                }
            }
        }

        // ── 断线横幅（顶部悬浮） ──────────────────────────────────────────────
        AnimatedVisibility(
            visible = !lanConnected,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(8f),
            enter = slideInVertically(initialOffsetY = { -it }, animationSpec = tween(300)) +
                    fadeIn(animationSpec = tween(300)),
            exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(300)) +
                    fadeOut(animationSpec = tween(300)),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFB71C1C),
            ) {
                Row(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    Text(
                        s.connectionLost,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onBack,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                    ) { Text(s.returnToLobby) }
                }
            }
        }

        // ── 返回按钮（左上浮层） ────────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 8.dp, top = 4.dp)
                .zIndex(7f),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.background(
                    Color(0xFF5D4037).copy(alpha = 0.75f),
                    RoundedCornerShape(50),
                ),
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回大厅", tint = Color(0xFFFFE082))
            }
        }

        // ── 右上角：设置 + 游戏结束时显示退出按钮 ────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 8.dp, top = 4.dp)
                .zIndex(7f),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // 游戏结束后显示明确的“退出对局”按钮
                if (gameState.isGameOver) {
                    IconButton(
                        onClick = { viewModel.reset(); onBack() },
                        modifier = Modifier.background(
                            Color(0xFF5D4037).copy(alpha = 0.75f),
                            RoundedCornerShape(50),
                        ),
                    ) {
                        Icon(Icons.Default.ExitToApp, contentDescription = s.exitMatch, tint = Color(0xFFFFE082))
                    }
                }
                // 设置按钮（始终可用）
                IconButton(
                    onClick = { showSettings = true },
                    modifier = Modifier.background(
                        Color(0xFF5D4037).copy(alpha = 0.75f),
                        RoundedCornerShape(50),
                    ),
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color(0xFFFFE082))
                }
            }
        }

        // ── 当前回合 Chip（顶部居中，位于返回按钮下方） ───────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp)
                .zIndex(7f),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF5D4037).copy(alpha = 0.85f),
            ) {
                Text(
                    text = when {
                        !lanConnected -> s.reconnecting
                        gameState.isGameOver && gameState.isDraw -> s.drawChip
                        gameState.isGameOver && gameState.winner == gameState.myColor -> s.youWinChip
                        gameState.isGameOver -> s.opponentWinsChip
                        gameState.isMyTurn ->
                            if (gameState.myColor == PieceColor.BLACK) s.myBlackTurn else s.myWhiteTurn
                        else ->
                            if (gameState.myColor.opposite() == PieceColor.BLACK) s.opponentBlackTurn else s.opponentWhiteTurn
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFE082),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}


