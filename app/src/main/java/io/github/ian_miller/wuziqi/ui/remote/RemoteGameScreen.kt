package io.github.ian_miller.wuziqi.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
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
    // 认输确认状态（本地临时态，任意对方请求到来时立即清除）
    var confirmResign by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val soundEnabled by viewModel.soundEnabled.collectAsState()
    val vibrationEnabled by viewModel.vibrationEnabled.collectAsState()

    // 和棋/再来一局/认输确认 均在操作行内以 AnimatedContent 滑动切换（InlineAction）

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

            // ── 对手 HUD（顶部）+ 操作按钮（棋盘上方）────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.3f)
                    .statusBarsPadding(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.BottomCenter),
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
                        onPlayVictorySound = { viewModel.playStampSound() },
                    )
                    // 操作行：AnimatedContent 统一管理所有状态（按钮 / 认输确认 / 对方请求）
                    // 对方请求超时在 LaunchedEffect 中倒计时；认输确认在其自身 content block 中倒计时
                    if (gameState.drawOfferedByOpponent) {
                        LaunchedEffect(Unit) {
                            delay(RemoteTiming.REQUEST_AUTO_DISMISS_MS)
                            viewModel.rejectDraw()
                        }
                    }
                    if (gameState.rematchOfferedColor != null) {
                        LaunchedEffect(gameState.rematchOfferedColor) {
                            delay(RemoteTiming.REQUEST_AUTO_DISMISS_MS)
                            viewModel.clearRematchOffer()
                        }
                    }
                    val hasIncomingRequest =
                        gameState.drawOfferedByOpponent || gameState.rematchOfferedColor != null
                    // 任意对方请求优先级最高，立即取消本地认输确认
                    LaunchedEffect(hasIncomingRequest) {
                        if (hasIncomingRequest) confirmResign = false
                    }
                    // 缓存再来一局颜色，防止退出动画期间 rematchOfferedColor 变 null 导致内容闪变
                    var lastSeenRematchColor by remember { mutableStateOf(PieceColor.BLACK) }
                    gameState.rematchOfferedColor?.let { lastSeenRematchColor = it }
                    // 推导当前操作行状态（优先级：再来一局请求 > 游戏结束占位 > 求和请求 > 认输确认 > 按钮）
                    // 注意：rematchOfferedColor 在 isGameOver=true 时才到达，必须先于 Idle 判断
                    val inlineAction: InlineAction = when {
                        gameState.rematchOfferedColor != null -> InlineAction.IncomingRematch
                        gameState.isGameOver -> InlineAction.Idle
                        gameState.drawOfferedByOpponent -> InlineAction.IncomingDraw
                        confirmResign -> InlineAction.ResignConfirm
                        else -> InlineAction.Buttons
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    AnimatedContent(
                        targetState = inlineAction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                        label = "inline_action",
                        transitionSpec = {
                            val enteringRequest = targetState == InlineAction.IncomingDraw
                                    || targetState == InlineAction.IncomingRematch
                            val enteringResign = targetState == InlineAction.ResignConfirm
                            val leavingToNeutral = targetState == InlineAction.Buttons
                                    || targetState == InlineAction.Idle
                            when {
                                // 新内容（对方请求或认输确认）从右侧滑入，旧内容向左退出
                                enteringRequest || enteringResign ->
                                    (slideInHorizontally(tween(250)) { it } + fadeIn(tween(250))) togetherWith
                                    (slideOutHorizontally(tween(220)) { -it } + fadeOut(tween(220)))
                                // 返回按钮或空态：从左侧恢复，旧内容向右退出
                                leavingToNeutral ->
                                    (slideInHorizontally(tween(250)) { -it } + fadeIn(tween(250))) togetherWith
                                    (slideOutHorizontally(tween(220)) { it } + fadeOut(tween(220)))
                                else ->
                                    fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                            }
                        },
                    ) { action ->
                        when (action) {
                            InlineAction.Idle ->
                                // 游戏结束：保持行高，避免棋盘跳动
                                Spacer(Modifier.fillMaxWidth().height(48.dp))
                            InlineAction.Buttons ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    GlassyButton(
                                        onClick = { viewModel.offerDraw() },
                                        modifier = Modifier.weight(1f),
                                        enabled = !gameState.drawSentByMe,
                                        containerColor = Color(0xFFA1760D).copy(alpha = 0.88f),
                                        contentColor = Color(0xFFFFE082),
                                    ) {
                                        Icon(Icons.Default.Balance, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(s.requestDraw)
                                    }
                                    GlassyButton(
                                        onClick = { confirmResign = true },
                                        modifier = Modifier.weight(1f),
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    ) {
                                        Icon(Icons.Default.Flag, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(s.resign)
                                    }
                                }
                            InlineAction.ResignConfirm -> {
                                // 认输确认 toast：5s 自动取消；✓=红色（危险操作），✗=中性（取消）
                                LaunchedEffect(Unit) {
                                    delay(RemoteTiming.RESIGN_CONFIRM_MS)
                                    confirmResign = false
                                }
                                InlineRequestRow(
                                    message = s.confirmResignTitle,
                                    onAccept = { viewModel.resignRemote(); confirmResign = false },
                                    onReject = { confirmResign = false },
                                    acceptColor = Color(0xFFB71C1C).copy(alpha = 0.88f),
                                    acceptContentColor = Color.White,
                                    rejectColor = Color(0xFF5D4037).copy(alpha = 0.5f),
                                    rejectContentColor = Color(0xFFFFE082),
                                )
                            }
                            InlineAction.IncomingDraw ->
                                InlineRequestRow(
                                    message = s.drawOfferedText,
                                    onAccept = { viewModel.acceptDraw() },
                                    onReject = { viewModel.rejectDraw() },
                                )
                            InlineAction.IncomingRematch ->
                                InlineRequestRow(
                                    message = s.rematchOffered(
                                        if (lastSeenRematchColor == PieceColor.BLACK) s.blackFirst else s.whiteSecond,
                                    ),
                                    onAccept = { viewModel.acceptRematch() },
                                    onReject = { viewModel.rejectRematch() },
                                )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
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
                modifier = Modifier.fillMaxWidth().weight(0.8f).navigationBarsPadding(),
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
                        onPlayVictorySound = { viewModel.playStampSound() },
                        // 游戏结束且尚未发出请求时，显示"点击选择先后手"选择模式
                        showStartSelection = gameState.isGameOver && !gameState.rematchSentByMe
                                && gameState.rematchOfferedColor == null,
                        selectionHint = s.clickToRematch,
                        onSelect = {
                            // 点击后请求再来一局：我方执黑（先手）
                            viewModel.requestRematch(PieceColor.BLACK)
                        },
                        // 待回应请求光圈：再来一局=绿，求和=琥珀
                        pendingGlowColor = when {
                            gameState.rematchSentByMe -> Color(0xFF4CAF50)
                            gameState.drawSentByMe -> Color(0xFFFFB300)
                            else -> null
                        },
                    )

                    // 等待回应的状态文字（兼容求和和再来一局两种等待状态）
                    val pendingStatusText = when {
                        gameState.rematchSentByMe -> s.rematchRequestSent
                        gameState.drawSentByMe -> s.drawSentWaiting
                        else -> null
                    }
                    if (pendingStatusText != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = if (gameState.rematchSentByMe) Color(0xFF4CAF50) else Color(0xFFFFB300),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                pendingStatusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF5D4037),
                            )
                        }
                    }

                    // 操作按钮（游戏进行中时显示）—— 已移动至对手 HUD 下方（棋盘上方）
                }
            }
        }

        // ── 断线横幅（顶部悬浮）：游戏结束后不显示（对方已主动离开） ────────────────────────
        AnimatedVisibility(
            visible = !lanConnected && !gameState.isGameOver,
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

/**
 * 操作行的状态机，使用纯 data object 保证 AnimatedContent 的 key 稳定（无 lambda 捕获）。
 * 优先级（高→低）：对方请求 > 认输确认 > 正常按钮 > 游戏结束占位
 */
private sealed interface InlineAction {
    data object Idle : InlineAction           // 游戏结束，维持行高
    data object Buttons : InlineAction        // 正常操作按钮（求和 / 认输）
    data object ResignConfirm : InlineAction  // 我方认输确认（临时态，被任意对方请求取代）
    data object IncomingDraw : InlineAction   // 对方求和请求
    data object IncomingRematch : InlineAction // 对方再来一局请求
}

/** 嵌入请求行：接受圆 + 消息文字 + 拒绝圆，颜色可自定义以复用于求和请求和认输确认 */
@Composable
private fun InlineRequestRow(
    message: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    acceptColor: Color = Color(0xFF2E7D32).copy(alpha = 0.88f),
    acceptContentColor: Color = Color.White,
    rejectColor: Color = Color(0xFFB71C1C).copy(alpha = 0.88f),
    rejectContentColor: Color = Color.White,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onAccept,
            modifier = Modifier.size(48.dp).clip(CircleShape),
            shape = CircleShape,
            color = acceptColor,
            contentColor = acceptContentColor,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color(0xFF3E2723),
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Surface(
            onClick = onReject,
            modifier = Modifier.size(48.dp).clip(CircleShape),
            shape = CircleShape,
            color = rejectColor,
            contentColor = rejectContentColor,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
    }
}


