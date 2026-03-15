package io.github.ian_miller.wuziqi.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.launch
import io.github.ian_miller.wuziqi.ui.theme.LocalStrings

// ── 大厅主屏（路由: remote_lobby）─────────────────────────────────────────────

@Composable
fun RemoteLobbyScreen(
    onBack: () -> Unit,
    onNavigateToJoin: () -> Unit,
    onNavigateToGame: () -> Unit,
    viewModel: RemoteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val relayStatus by viewModel.relayStatus.collectAsState()
    val gameState by viewModel.gameState.collectAsState()
    val lanConnected by viewModel.lanPeerConnected.collectAsState()

    // 第一次进入时 ping 中继
    LaunchedEffect(Unit) { viewModel.pingRelays() }

    // 一次性导航到棋盘
    LaunchedEffect(state.pendingNavToGame) {
        if (state.pendingNavToGame) {
            viewModel.consumeNavToGame()
            onNavigateToGame()
        }
    }

    val woodBrush = remember {
        Brush.verticalGradient(colors = listOf(Color(0xFFE0C39E), Color(0xFFA47E5C)))
    }

    Scaffold(
        topBar = {
            RemoteTopBar(
                phase = state.phase,
                onBack = { viewModel.reset(); onBack() },
                onReset = viewModel::reset,
            )
        },
        containerColor = Color.Transparent,
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(woodBrush)
                .padding(paddingValues),
        ) {
            when (val p = state.phase) {
                is RemotePhase.Idle ->
                    RemoteLobbyContent(
                        nostrAvailable = state.nostrAvailable,
                        relayConnected = relayStatus.values.count { it },
                        relayTotal = relayStatus.size.coerceAtLeast(3),
                        hasActiveGame = gameState != null && !gameState!!.isGameOver,
                        onCreateRoom = viewModel::createRoom,
                        onCreateRoomLan = viewModel::createRoomLan,
                        onJoinRoom = {
                            viewModel.startJoining()
                            onNavigateToJoin()
                        },
                        onResumeGame = onNavigateToGame,
                        onEndGame = { viewModel.resignRemote(); viewModel.reset() },
                    )

                is RemotePhase.Creating ->
                    RemoteCreatingContent(inviteCode = p.inviteCode, isLan = p.isLan)

                is RemotePhase.Connected ->
                    RemoteConnectedLobbyContent(
                        phase = p,
                        lanConnected = lanConnected,
                        onResume = onNavigateToGame,
                        onEnd = { viewModel.resignRemote(); viewModel.reset() },
                    )

                is RemotePhase.Error ->
                    RemoteErrorContent(message = p.message, onRetry = viewModel::reset)

                else ->
                    RemoteWaitingContent(message = LocalStrings.current.remoteProcessing)
            }

            RelayStatusBar(
                connected = relayStatus.values.count { it },
                total = relayStatus.size.coerceAtLeast(3),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            )
        }
    }
}

// ── TopBar ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteTopBar(
    phase: RemotePhase,
    onBack: () -> Unit,
    onReset: () -> Unit,
) {
    TopAppBar(
        title = {
            val s = LocalStrings.current
            Text(
                text = when (phase) {
                    is RemotePhase.Idle -> s.remoteLobbyTitle
                    is RemotePhase.Creating -> s.creatingRoom
                    is RemotePhase.Joining -> s.joiningRoom
                    is RemotePhase.WaitingForOpponent -> s.waitingConnection
                    is RemotePhase.Connected -> s.gameInProgress
                    is RemotePhase.Error -> s.connectionFailed
                },
                fontWeight = FontWeight.Bold,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.Home, contentDescription = "返回主菜单")
            }
        },
        actions = {
            if (phase !is RemotePhase.Idle) {
                IconButton(onClick = onReset) {
                    Icon(Icons.Default.Close, contentDescription = "取消当前操作")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFF5D4037),
            titleContentColor = Color(0xFFFFE082),
            navigationIconContentColor = Color(0xFFFFE082),
            actionIconContentColor = Color(0xFFFFE082),
        ),
    )
}

// ── 大厅内容 ────────────────────────────────────────────────────────────────────

@Composable
private fun RemoteLobbyContent(
    nostrAvailable: Boolean?,
    relayConnected: Int,
    relayTotal: Int,
    hasActiveGame: Boolean,
    onCreateRoom: () -> Unit,
    onCreateRoomLan: () -> Unit,
    onJoinRoom: () -> Unit,
    onResumeGame: () -> Unit,
    onEndGame: () -> Unit,
) {
    val s = LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.WifiTethering,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = Color(0xFF3E2723),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = s.remoteTagline,
            style = MaterialTheme.typography.titleLarge,
            color = Color(0xFF3E2723),
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = s.remoteSubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF5D4037),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 6.dp),
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── 活跃对局恢复卡片 ──────────────────────────────────────────────────
        if (hasActiveGame) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1B5E20).copy(alpha = 0.12f),
                border = BorderStroke(1.5.dp, Color(0xFF4CAF50)),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        s.remoteActiveGameTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1B5E20),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onResumeGame,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2E7D32),
                                contentColor = Color.White,
                            ),
                        ) { Text(s.remoteResumeGame) }
                        OutlinedButton(
                            onClick = onEndGame,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB71C1C)),
                            border = BorderStroke(1.5.dp, Color(0xFFB71C1C)),
                        ) { Text(s.remoteEndGame) }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // ── 创建房间区块 ──────────────────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF5D4037).copy(alpha = 0.08f),
            border = BorderStroke(1.dp, Color(0xFF5D4037).copy(alpha = 0.25f)),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = s.creatingRoom,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF5D4037),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = s.remoteCreateHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8D6E63),
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Nostr 网络创建
                Button(
                    onClick = onCreateRoom,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (nostrAvailable) {
                            false -> Color(0xFF9E9E9E)
                            else -> Color(0xFF5D4037)
                        },
                        contentColor = Color(0xFFFFE082),
                    ),
                    shape = MaterialTheme.shapes.large,
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    when (nostrAvailable) {
                        null -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color(0xFFFFE082),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(s.remoteCheckingRelays, style = MaterialTheme.typography.titleMedium)
                        }
                        false -> {
                            Icon(Icons.Default.WifiOff, null, Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(s.remoteRelayDown, style = MaterialTheme.typography.titleMedium)
                                Text(s.remoteRelayDownNote, style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFE082).copy(alpha = 0.8f))
                            }
                        }
                        true -> {
                            Icon(Icons.Default.Cloud, null, Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(s.remoteCreateOnline, style = MaterialTheme.typography.titleMedium)
                                Text(s.remoteRelayStatus(relayConnected, relayTotal), style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFE082).copy(alpha = 0.8f))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 局域网创建
                Button(
                    onClick = onCreateRoomLan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1B5E20),
                        contentColor = Color(0xFFC8E6C9),
                    ),
                    shape = MaterialTheme.shapes.large,
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Default.Wifi, null, Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(s.remoteLanCreate, style = MaterialTheme.typography.titleMedium)
                        Text(s.remoteLanHint, style = MaterialTheme.typography.labelSmall, color = Color(0xFFC8E6C9).copy(alpha = 0.8f))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── 加入对局 ─────────────────────────────────────────────────────────
        Button(
            onClick = onJoinRoom,
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF01579B),
                contentColor = Color(0xFFE1F5FE),
            ),
            shape = MaterialTheme.shapes.large,
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Default.Login, null, Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(s.remoteJoinAction, style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ── 已连接但用户从棋盘返回（显示恢复/结束选项）────────────────────────────────

@Composable
private fun RemoteConnectedLobbyContent(
    phase: RemotePhase.Connected,
    lanConnected: Boolean = true,
    onResume: () -> Unit,
    onEnd: () -> Unit,
) {
    val s = LocalStrings.current
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (lanConnected) Icons.Default.CheckCircle else Icons.Default.WifiOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = if (lanConnected) Color(0xFF4CAF50) else Color(0xFFFF9800),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            if (lanConnected) s.remoteConnectedTitle else s.remoteGamePaused,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF3E2723),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            if (phase.myColor == io.github.ian_miller.wuziqi.domain.model.PieceColor.BLACK) s.remoteYouBlack else s.remoteYouWhite,
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF5D4037),
        )
        if (!lanConnected) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color(0xFFFF9800), strokeWidth = 2.dp)
                Text(
                    s.remoteAutoReconnect,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF9800),
                )
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onResume,
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .sizeIn(minHeight = 56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2E7D32),
                contentColor = Color.White,
            ),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(Icons.Default.PlayArrow, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(s.remoteResumeGame, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onEnd,
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .sizeIn(minHeight = 56.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB71C1C)),
            border = BorderStroke(1.5.dp, Color(0xFFB71C1C)),
            shape = MaterialTheme.shapes.large,
        ) {
            Text(s.remoteForfeit, style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ── 创建中：显示 QR + 邀请码 ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteCreatingContent(inviteCode: String, isLan: Boolean = false) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val qrPainter = rememberQrCodePainter(inviteCode)
    var copied by remember { mutableStateOf(false) }
    val s = LocalStrings.current

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (isLan) s.remoteLanRoomReady else s.remoteShareInvite,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF3E2723),
        )
        Text(
            text = if (isLan) s.remoteLanInstructions else s.remoteOnlineInstructions,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF5D4037),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Spacer(modifier = Modifier.height(24.dp))

        // QR 码
        Surface(
            modifier = Modifier
                .size(240.dp)
                .clip(RoundedCornerShape(16.dp)),
            color = Color.White,
            shadowElevation = 4.dp,
        ) {
            Box(
                modifier = Modifier.padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = qrPainter,
                    contentDescription = "邀请码 QR",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        // 邀请码文本
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF5D4037).copy(alpha = 0.12f),
        ) {
            Text(
                text = inviteCode,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF3E2723),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        // 复制按钮
        Button(
            onClick = {
                clipboardManager.setText(AnnotatedString(inviteCode))
                copied = true
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF5D4037),
                contentColor = Color(0xFFFFE082),
            ),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(
                if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (copied) s.remoteCopied else s.remoteCopyCode)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 保存 / 分享
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val bmp = painterToBitmap(qrPainter, density)
                        val uri = saveBitmapToGallery(context, bmp)
                        snackbarHostState.showSnackbar(
                            if (uri != null) s.remoteSavedGallery else s.remoteSaveFailed
                        )
                    }
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF5D4037)),
                border = BorderStroke(1.5.dp, Color(0xFF5D4037)),
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(Icons.Default.SaveAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(s.remoteSaveImage)
            }
            OutlinedButton(
                onClick = {
                    val bmp = painterToBitmap(qrPainter, density)
                    shareBitmapAsImage(context, bmp)
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF5D4037)),
                border = BorderStroke(1.5.dp, Color(0xFF5D4037)),
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(s.remoteShareAction)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 等待提示
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color(0xFF5D4037),
                strokeWidth = 2.dp,
            )
            Text(
                s.remoteWaitingFriend,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF5D4037),
            )
        }
    } // end Column

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(16.dp),
    )
    } // end Box
}

// ── 等待中 ────────────────────────────────────────────────────────────────────

@Composable
internal fun RemoteWaitingContent(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(56.dp),
            color = Color(0xFF5D4037),
            strokeWidth = 4.dp,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF3E2723),
        )
    }
}

// ── 错误 ──────────────────────────────────────────────────────────────────────

@Composable
internal fun RemoteErrorContent(message: String, onRetry: () -> Unit) {
    val s = LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5D4037)),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(s.remoteRetry, color = Color(0xFFFFE082))
        }
    }
}

// ── 中继状态指示器 ────────────────────────────────────────────────────────────

@Composable
internal fun RelayStatusBar(
    connected: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            val iconColor = when {
                connected == total && total > 0 -> Color(0xFF81C784)
                connected > 0 -> Color(0xFFFFB74D)
                else -> Color(0xFFEF9A9A)
            }
            Icon(
                imageVector = if (connected > 0) Icons.Default.Wifi else Icons.Default.WifiOff,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = LocalStrings.current.remoteRelayCount(connected, total),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}
