package io.github.ian_miller.wuziqi.ui.aitest

import io.github.ian_miller.wuziqi.ui.aitest.AITestViewModel.State as GameState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.min

/**
 * AI 测试页面 - Rust AI 自对弈
 * 
 * 使用 ADT + Actor 架构：
 * - 状态是数据（GameState 密封类）
 * - 纯函数转换（transition）
 * - Actor 保证并发安全
 * - 支持撤销/重做
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AITestScreen(
    onBack: () -> Unit,
    viewModel: AITestViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    
    // 监听生命周期：App 退到后台时自动暂停
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    // App 退到后台，设置后台暂停源
                    viewModel.setPauseSource(PauseCoordinator.Source.BACKGROUND, true)
                }
                Lifecycle.Event.ON_RESUME -> {
                    // App 回到前台，取消后台暂停源
                    viewModel.setPauseSource(PauseCoordinator.Source.BACKGROUND, false)
                }
                Lifecycle.Event.ON_DESTROY -> {
                    // 页面销毁，发送 Stop
                    viewModel.stop()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rust AI 自对弈") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 状态和控制面板
            ControlPanel(
                state = state,
                onStart = { viewModel.start() },
                onPause = { viewModel.setPauseSource(PauseCoordinator.Source.USER_CLICK, true) },
                onResume = { viewModel.setPauseSource(PauseCoordinator.Source.USER_CLICK, false) },
                onStop = { viewModel.stop() },
                onDebugInvalidate = { viewModel.debugInvalidateBothAis() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 棋盘显示
            val boardArray = when (val s = state) {
                is GameState.Idle -> s.board
                is GameState.Running -> s.board
                is GameState.Delaying -> s.board
                is GameState.Pausing -> s.board
                is GameState.Paused -> s.board
                is GameState.Stopping -> s.board
            }
            
            val lastMove = when (val s = state) {
                is GameState.Running -> s.lastMove
                is GameState.Delaying -> s.lastMove
                is GameState.Pausing -> s.lastMove
                is GameState.Paused -> s.lastMove
                else -> null
            }
            
            val currentPlayer = when (val s = state) {
                is GameState.Running -> s.currentPlayer
                is GameState.Delaying -> s.nextPlayer  // 延迟中，显示下一个玩家
                is GameState.Pausing -> s.currentPlayer
                is GameState.Paused -> s.currentPlayer
                else -> 1
            }
            
            GameBoard(
                board = boardArray,
                lastMove = lastMove,
                currentPlayer = currentPlayer
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 对局结果
            val winner = checkWinner(state)
            val moveCount = getMoveCount(state)
            
            if (winner != null) {
                WinnerCard(winner = winner, moveCount = moveCount)
            }
        }
    }
}

// 辅助函数：从状态获取获胜者
private fun checkWinner(state: GameState): String? {
    return when (state) {
        is GameState.Idle -> state.winner
        else -> null
    }
}

// 辅助函数：从状态获取步数
private fun getMoveCount(state: GameState): Int {
    return when (state) {
        is GameState.Idle -> 0
        is GameState.Running -> state.moveCount
        is GameState.Delaying -> state.moveCount
        is GameState.Pausing -> state.moveCount
        is GameState.Paused -> state.moveCount
        is GameState.Stopping -> 0
    }
}

@Composable
private fun ControlPanel(
    state: GameState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDebugInvalidate: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 当前状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val statusText = when (state) {
                    is GameState.Idle -> 
                        "⏹️ 就绪"
                    is GameState.Running -> 
                        "${if (state.currentPlayer == 1) "⚫ 黑方" else "⚪ 白方"} 思考中..."
                    is GameState.Delaying -> 
                        "${if (state.nextPlayer == 1) "⚫ 黑方" else "⚪ 白方"} 准备中..."
                    is GameState.Pausing -> 
                        "⏳ 暂停中..."
                    is GameState.Paused -> 
                        "⏸️ 已暂停"
                    is GameState.Stopping -> 
                        "⏹️ 停止中..."
                }
                
                val statusColor = when (state) {
                    is GameState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
                    is GameState.Running -> {
                        if (state.currentPlayer == 1) Color.Black else MaterialTheme.colorScheme.primary
                    }
                    is GameState.Delaying -> {
                        if (state.nextPlayer == 1) Color.Black else MaterialTheme.colorScheme.primary
                    }
                    is GameState.Pausing, is GameState.Paused -> 
                        MaterialTheme.colorScheme.tertiary
                    is GameState.Stopping -> 
                        MaterialTheme.colorScheme.error
                }

                Text(
                    text = statusText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )

                val moveCount = when (state) {
                    is GameState.Running -> state.moveCount
                    is GameState.Delaying -> state.moveCount
                    is GameState.Pausing -> state.moveCount
                    is GameState.Paused -> state.moveCount
                    else -> 0
                }
                
                Text(
                    text = "步数: $moveCount",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 控制按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (state) {
                    is GameState.Idle -> {
                        Button(
                            onClick = onStart,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("开始对弈")
                        }
                    }

                    is GameState.Running, is GameState.Delaying -> {
                        Button(
                            onClick = onPause,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            )
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("暂停")
                        }

                        OutlinedButton(
                            onClick = onStop,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("停止")
                        }
                    }

                    is GameState.Pausing -> {
                        // 暂停中，按钮禁用
                        Button(
                            onClick = {},
                            modifier = Modifier.weight(1f),
                            enabled = false
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("请稍候...")
                        }

                        OutlinedButton(
                            onClick = onStop,
                            modifier = Modifier.weight(1f),
                            enabled = false
                        ) {
                            Text("停止")
                        }
                    }

                    is GameState.Paused -> {
                        Button(
                            onClick = onResume,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("恢复")
                        }

                        OutlinedButton(
                            onClick = onStop,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("停止")
                        }
                    }

                    is GameState.Stopping -> {
                        Button(
                            onClick = {},
                            modifier = Modifier.weight(1f),
                            enabled = false
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("清理中...")
                        }

                        OutlinedButton(
                            onClick = {},
                            modifier = Modifier.weight(1f),
                            enabled = false
                        ) {
                            Text("停止")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 调试按钮：直接 invalidate 两个 AI（不经过 Actor）
            val hasAi = state is GameState.Running 
                || state is GameState.Delaying 
                || state is GameState.Pausing 
                || state is GameState.Paused
            
            OutlinedButton(
                onClick = onDebugInvalidate,
                modifier = Modifier.fillMaxWidth(),
                enabled = hasAi,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                )
            ) {
                Text("🐛 调试: 强制 invalidate 两个 AI")
            }
        }
    }
}

@Composable
private fun GameBoard(
    board: Array<IntArray>,
    lastMove: Pair<Int, Int>?,
    currentPlayer: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize(0.95f)) {
                val size = min(size.width, size.height)
                val padding = size * 0.05f
                val boardSize = size - 2 * padding
                val cellSize = boardSize / 14
                val stoneRadius = cellSize * 0.4f

                // 背景
                drawRect(color = Color(0xFFDEB887))

                // 网格线
                for (i in 0..14) {
                    val pos = padding + i * cellSize
                    drawLine(
                        color = Color(0xFF5D4037),
                        start = Offset(padding, pos),
                        end = Offset(padding + boardSize, pos),
                        strokeWidth = 2f
                    )
                    drawLine(
                        color = Color(0xFF5D4037),
                        start = Offset(pos, padding),
                        end = Offset(pos, padding + boardSize),
                        strokeWidth = 2f
                    )
                }

                // 星位
                val stars = listOf(3 to 3, 3 to 11, 7 to 7, 11 to 3, 11 to 11)
                for ((sr, sc) in stars) {
                    val x = padding + sc * cellSize
                    val y = padding + sr * cellSize
                    drawCircle(
                        color = Color(0xFF5D4037),
                        radius = 4f,
                        center = Offset(x, y)
                    )
                }

                // 棋子
                for (r in 0..14) {
                    for (c in 0..14) {
                        val x = padding + c * cellSize
                        val y = padding + r * cellSize

                        when (board[r][c]) {
                            1 -> { // 黑子
                                drawCircle(
                                    color = Color.Black,
                                    radius = stoneRadius,
                                    center = Offset(x, y)
                                )
                                if (lastMove == r to c) {
                                    drawCircle(
                                        color = Color.Red,
                                        radius = stoneRadius + 4,
                                        center = Offset(x, y),
                                        style = Stroke(width = 3f)
                                    )
                                }
                            }

                            2 -> { // 白子
                                drawCircle(
                                    color = Color.White,
                                    radius = stoneRadius,
                                    center = Offset(x, y)
                                )
                                drawCircle(
                                    color = Color.Black,
                                    radius = stoneRadius,
                                    center = Offset(x, y),
                                    style = Stroke(width = 1f)
                                )
                                if (lastMove == r to c) {
                                    drawCircle(
                                        color = Color.Red,
                                        radius = stoneRadius + 4,
                                        center = Offset(x, y),
                                        style = Stroke(width = 3f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 当前玩家指示器
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (currentPlayer == 1) Color.Black else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                ) {}
            }
        }
    }
}

@Composable
private fun WinnerCard(winner: String, moveCount: Int) {
    val (icon, color, text) = when (winner) {
        "BLACK" -> Triple("⚫", Color.Black, "黑方获胜！")
        "WHITE" -> Triple("⚪", MaterialTheme.colorScheme.primary, "白方获胜！")
        else -> Triple("🤝", MaterialTheme.colorScheme.onSurfaceVariant, "平局")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "$icon $text",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "共 $moveCount 步",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
