package io.github.ian_miller.wuziqi.ui.game

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.ian_miller.wuziqi.domain.model.Board
import io.github.ian_miller.wuziqi.domain.model.Difficulty
import io.github.ian_miller.wuziqi.domain.model.GameMode
import io.github.ian_miller.wuziqi.domain.model.PieceColor
import io.github.ian_miller.wuziqi.ui.menu.MenuViewModel
import io.github.ian_miller.wuziqi.ui.theme.GomokuTheme
import io.github.ian_miller.wuziqi.ui.remote.RemoteLobbyScreen
import io.github.ian_miller.wuziqi.ui.remote.RemoteJoinScreen
import io.github.ian_miller.wuziqi.ui.remote.RemoteGameScreen
import io.github.ian_miller.wuziqi.ui.remote.RemoteViewModel
import androidx.navigation.compose.navigation
import io.github.ian_miller.wuziqi.BuildConfig
import io.github.ian_miller.wuziqi.ui.theme.AppStrings
import io.github.ian_miller.wuziqi.ui.theme.EnStrings
import io.github.ian_miller.wuziqi.ui.theme.LocalStrings
import io.github.ian_miller.wuziqi.ui.theme.ZhStrings
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

// GameStatus 和 GameResult 定义在 GameViewModelV2 同包中

@Composable
fun GameApp() {
    val menuViewModel: MenuViewModel = hiltViewModel()
    val menuState by menuViewModel.uiState.collectAsState()

    // 语言选择：auto = 跟随系统 Locale
    val strings: AppStrings = remember(menuState.language) {
        when (menuState.language) {
            "en" -> EnStrings
            "zh" -> ZhStrings
            else -> if (Locale.getDefault().language == "zh") ZhStrings else EnStrings
        }
    }

    GomokuTheme {
        androidx.compose.runtime.CompositionLocalProvider(LocalStrings provides strings) {
            GameAppContent(menuViewModel = menuViewModel)
        }
    }
}

@Composable
private fun GameAppContent(menuViewModel: MenuViewModel) {
    val navController = rememberNavController()

    NavHost(
        navController = navController, 
        startDestination = "menu",
        // 全局默认动画（会被每个页面的设置覆盖）
        enterTransition = { 
            slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(300)
            ) 
        },
        exitTransition = { 
            slideOutHorizontally(
                targetOffsetX = { -it },
                animationSpec = tween(300)
            ) 
        },
        popEnterTransition = { 
            slideInHorizontally(
                initialOffsetX = { -it },
                animationSpec = tween(300)
            ) 
        },
        popExitTransition = { 
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(300)
            ) 
        }
    ) {
        composable(
            route = "menu",
            // 主菜单退出时：完全向左滑出
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it },  // 完全滑出屏幕左侧
                    animationSpec = tween(300)
                )
            },
            // 主菜单进入时：从左侧滑入
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },  // 从屏幕左侧外进入
                    animationSpec = tween(300)
                )
            }
        ) {
            MainMenuScreen(
                onNavigateToGame = { mode -> navController.navigate("game/${mode.name}") },
                onNavigateToProfile = { navController.navigate("profile") },
                onNavigateToTutorial = { navController.navigate("tutorial") },
                onNavigateToRemote = { navController.navigate("remote_graph") },
                onNavigateToSettings = { navController.navigate("settings") },
                viewModel = menuViewModel,
            )
        }
        
        composable(
            route = "settings"
        ) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                viewModel = menuViewModel,
            )
        }

        navigation(startDestination = "remote_lobby", route = "remote_graph") {
            composable(route = "remote_lobby") { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry("remote_graph")
                }
                val vm: RemoteViewModel = hiltViewModel(parentEntry)
                RemoteLobbyScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack("remote_graph", inclusive = true) },
                    onNavigateToJoin = { navController.navigate("remote_join") },
                    onNavigateToGame = { navController.navigate("remote_game") },
                )
            }
            composable(route = "remote_join") { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry("remote_graph")
                }
                val vm: RemoteViewModel = hiltViewModel(parentEntry)
                RemoteJoinScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onNavigateToGame = {
                        navController.navigate("remote_game") {
                            popUpTo("remote_join") { inclusive = true }
                        }
                    },
                )
            }
            composable(route = "remote_game") { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry("remote_graph")
                }
                val vm: RemoteViewModel = hiltViewModel(parentEntry)
                RemoteGameScreen(
                    viewModel = vm,
                    menuViewModel = menuViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
        }
        
        composable(
            route = "tutorial"
            // 使用 NavHost 的全局默认动画
        ) {
            TutorialScreen(
                onBack = { navController.popBackStack() }
            )
        }
        
        composable(
            route = "profile"
            // 使用 NavHost 的全局默认动画
        ) {
            PlayerProfileScreen(
                onBack = { navController.popBackStack() }
            )
        }
        
        composable(
            route = "game/{mode}",
            arguments = listOf(navArgument("mode") { type = NavType.StringType })
            // 使用 NavHost 的全局默认动画
        ) { backStackEntry ->
            val modeName = backStackEntry.arguments?.getString("mode") ?: GameMode.VS_AI.name
            val mode = try { GameMode.valueOf(modeName) } catch(e: Exception) { GameMode.VS_AI }
            
            GameRoute(
                mode = mode,
                menuViewModel = menuViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

@Composable
fun GameRoute(
    mode: GameMode,
    onBack: () -> Unit,
    viewModel: GameViewModelV2 = hiltViewModel(),
    menuViewModel: MenuViewModel = hiltViewModel()
) {
    var interactionEnabled by remember { mutableStateOf(true) }

    // 初始化游戏
    LaunchedEffect(mode) {
        viewModel.initialize(mode)
    }

    val handleBack = {
        if (interactionEnabled) {
            interactionEnabled = false
            // 导航退出：添加 NAVIGATION 暂停源（触发保存），不再调用 onPause()
            viewModel.addPauseSource(GameViewModelV2.PauseSource.NAVIGATION)
            onBack()
        }
    }

    // 拦截系统返回键
    BackHandler(enabled = interactionEnabled, onBack = handleBack)
    
    // 监听生命周期：仅负责 LIFECYCLE 源
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.addPauseSource(GameViewModelV2.PauseSource.LIFECYCLE)
                Lifecycle.Event.ON_RESUME -> viewModel.removePauseSource(GameViewModelV2.PauseSource.LIFECYCLE)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            // Composable 销毁时清理，确保两个源都移除（防止泄漏）
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.addPauseSource(GameViewModelV2.PauseSource.NAVIGATION)
        }
    }
    
    ActiveGameScreen(
        viewModel = viewModel,
        menuViewModel = menuViewModel,
        onBack = handleBack,
        interactionEnabled = interactionEnabled
    )
}

@Composable
fun MainMenuScreen(
    onNavigateToGame: (GameMode) -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToTutorial: () -> Unit,
    onNavigateToRemote: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: MenuViewModel = hiltViewModel()
) {
    val s = LocalStrings.current
    // Wood Gradient Background
    val woodBrush = remember {
        androidx.compose.ui.graphics.Brush.verticalGradient(
            colors = listOf(
                Color(0xFFE0C39E), // Light Wood
                Color(0xFFA47E5C)  // Dark Wood
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(woodBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Title with Shadow
            Box(
                contentAlignment = Alignment.Center
            ) {
                 Text(
                    text = s.appTitle,
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        shadow = androidx.compose.ui.graphics.Shadow(
                             color = Color(0xFF3E2723).copy(alpha = 0.5f),
                             offset = androidx.compose.ui.geometry.Offset(4f, 4f),
                             blurRadius = 8f
                        )
                    ),
                    color = Color(0xFF3E2723)
                )
            }
            
            Spacer(modifier = Modifier.height(48.dp))

            // Player Profile Card (玩家名片)
            val selectedPlayer by viewModel.selectedPlayer.collectAsState()
            val stats by viewModel.stats.collectAsState()
            
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) // Clip the ripple content
                    .clickable { onNavigateToProfile() }, 
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = Color(0xFF5D4037).copy(alpha = 0.9f),
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFFFE082),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = selectedPlayer?.name?.take(1)?.uppercase() ?: "?",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color(0xFF5D4037),
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = selectedPlayer?.name ?: s.clickToCreatePlayer,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFFFFE082),
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                            Text(
                                text = s.viewProfileHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFFE082).copy(alpha = 0.7f)
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Edit",
                        tint = Color(0xFFFFE082).copy(alpha = 0.7f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            val buttonColors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF5D4037),
                contentColor = Color(0xFFFFE082)
            )
            val buttonModifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 16.dp)

            // 检查是否存在存档
            val hasSavedAiGame = remember { viewModel.hasSavedGame(GameMode.VS_AI) }
            val hasSavedHumanGame = remember { viewModel.hasSavedGame(GameMode.VS_HUMAN) }

            // 人机对战按钮（带续局提示）
            Box(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onNavigateToGame(GameMode.VS_AI) },
                    modifier = buttonModifier,
                    colors = buttonColors,
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 8.dp,
                        pressedElevation = 2.dp
                    ),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(s.vsAi, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                }
                
                // 续局徽章 - 木纹风格
                if (hasSavedAiGame) {
                    ResumeBadge(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-20).dp, y = (-8).dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            
            // 双人对战按钮（带续局提示）
            Box(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onNavigateToGame(GameMode.VS_HUMAN) },
                    modifier = buttonModifier,
                    colors = buttonColors,
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 8.dp,
                        pressedElevation = 2.dp
                    ),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Icon(Icons.Filled.Group, contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(s.vsHuman, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                }
                
                // 续局徽章 - 木纹风格
                if (hasSavedHumanGame) {
                    ResumeBadge(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-20).dp, y = (-8).dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            
            // 远程对弈按钮
            Button(
                onClick = onNavigateToRemote,
                modifier = buttonModifier,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF5D4037),
                    contentColor = Color(0xFFFFE082)
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 8.dp,
                    pressedElevation = 2.dp
                ),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Icon(Icons.Filled.WifiTethering, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(s.remotePlay, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            }
            Spacer(modifier = Modifier.height(24.dp))
            
            // Tutorial Button
            Button(
                onClick = onNavigateToTutorial,
                modifier = buttonModifier,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6D4C41), // Medium brown
                    contentColor = Color(0xFFFFF8E1)
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 6.dp,
                    pressedElevation = 2.dp
                ),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(s.tutorialBtn, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            }
            Spacer(modifier = Modifier.height(24.dp))
            
            // Settings Button (Secondary Style)
            Button(
                onClick = onNavigateToSettings,
                modifier = buttonModifier,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF8D6E63), // Lighter brown
                    contentColor = Color(0xFFFFF8E1)
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 6.dp,
                    pressedElevation = 2.dp
                ),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(s.settingsBtn, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            }
        }
        
        // Stats Dialog Handling
            val uiState by viewModel.uiState.collectAsState()

            if (uiState.showStats) {
                val stats by viewModel.stats.collectAsState()
                val players by viewModel.players.collectAsState()
                val selectedPlayer by viewModel.selectedPlayer.collectAsState()
                
                // Overlay for the custom dialog
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .zIndex(3f)
                        .clickable { viewModel.hideStats() },
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}) {
                        StatsDialog(
                            stats = stats,
                            players = players,
                            selectedPlayer = selectedPlayer,
                            onSelectPlayer = { viewModel.selectPlayer(it); viewModel.showStats() },
                            onCreatePlayer = { viewModel.createPlayer(it) },
                            onRenamePlayer = { player, newName -> viewModel.renamePlayer(player, newName) },
                            onDismiss = { viewModel.hideStats() }
                        )
                    }
                }
            }
        }
}

@Composable
fun ActiveGameScreen(
    viewModel: GameViewModelV2, 
    menuViewModel: MenuViewModel? = null,  // 可选的 MenuViewModel，用于 PvP 保存和玩家显示
    onBack: () -> Unit,
    interactionEnabled: Boolean = true
) {
    val model by viewModel.uiModel.collectAsState()
    val selectedPlayer by menuViewModel?.selectedPlayer?.collectAsState() ?: remember { mutableStateOf(null) }
    val menuState by menuViewModel?.uiState?.collectAsState() ?: remember { mutableStateOf(MenuViewModel.UiState()) }
    val magnifierEnabled = menuState.magnifierEnabled
    var magnifierState by remember { mutableStateOf<MagnifierState?>(null) } 

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val gameMode = model.mode
    var boardCopied by remember { mutableStateOf(false) }

    LaunchedEffect(boardCopied) {
        if (boardCopied) {
            delay(1_200)
            boardCopied = false
        }
    }
    
    // Manage fullscreen mode for VS_HUMAN
    DisposableEffect(gameMode) {
        val window = (context as? Activity)?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (gameMode == GameMode.VS_HUMAN) {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        
        onDispose {
            val window = (context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    GomokuTheme {
        // Root Container
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFE0C39E), // Light Wood
                            Color(0xFFA47E5C)  // Dark Wood
                        )
                    )
                )
        ) {
            // 0. DIALOGS & OVERLAYS (State Handling)
            // gameMode already collected above
            val isVsHuman = gameMode == GameMode.VS_HUMAN
            var showPvPSaveDialog by remember { mutableStateOf(false) }
            
            // 难度提示 Toast（人机模式）
            // 生命周期由 ViewModel.triggerDifficultyToast() 统一管理（支持重置持续时间）
            AnimatedVisibility(
                visible = model.showDifficultyToast,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
                    .zIndex(10f),
                enter = androidx.compose.animation.fadeIn(animationSpec = tween(200)) + 
                        androidx.compose.animation.slideInVertically(
                            initialOffsetY = { -it },
                            animationSpec = tween(200)
                        ),
                exit = androidx.compose.animation.fadeOut(animationSpec = tween(200)) +
                       androidx.compose.animation.slideOutVertically(
                           targetOffsetY = { -it },
                           animationSpec = tween(200)
                       )
            ) {
                DifficultyToast(difficulty = model.difficulty)
            }

            LaunchedEffect(model.gameStatus) {
                if (model.gameStatus == GameStatus.PLAYING) {
                    showPvPSaveDialog = false
                } else if (model.gameStatus == GameStatus.FINISHED && isVsHuman) {
                    // showPvPSaveDialog = true // Temporarily disabled for better victory effect
                }
            }

            if (showPvPSaveDialog) {
                val players = menuViewModel?.players?.collectAsState()?.value ?: emptyList()
                SavePvPDialog(
                    players = players,
                    onDismiss = { showPvPSaveDialog = false },
                    onSave = { black, white ->
                        viewModel.savePvPGame(black, white)
                        showPvPSaveDialog = false
                    }
                )
            }
            
            // 游戏结束结果展示覆盖层 (PvE 或 PvP已保存后)
            /*
            if (viewModel.getGameStatus() == GameStatus.FINISHED && !showPvPSaveDialog) {
                viewModel.getGameResult()?.let { result ->
                     var showResultOverlay by remember(viewModel.getGameStatus()) { mutableStateOf(true) }
                     if (showResultOverlay) {
                         androidx.activity.compose.BackHandler { showResultOverlay = false }
                         GameResultOverlay(result = result, onDismiss = { 
                             showResultOverlay = false 
                         })
                     }
                }
            }
            */

            // Determine HUD colors based on mode
            val topPlayerColor: PieceColor
            val bottomPlayerColor: PieceColor
            
            if (isVsHuman) {
                // PvP: Swapping based on selection
                if (model.pvpBottomIsBlack) {
                    topPlayerColor = PieceColor.WHITE
                    bottomPlayerColor = PieceColor.BLACK
                } else {
                    topPlayerColor = PieceColor.BLACK
                    bottomPlayerColor = PieceColor.WHITE
                }
            } else {
                // PvE: AI at Top, Player at Bottom
                val aiColor = model.aiPlayerColor
                if (aiColor == PieceColor.BLACK) {
                    // AI is Black (First), so Top is Black (AI side)
                    topPlayerColor = PieceColor.BLACK
                    bottomPlayerColor = PieceColor.WHITE
                } else {
                    // AI is White (Second) or Null, Top is White
                    topPlayerColor = PieceColor.WHITE
                    bottomPlayerColor = PieceColor.BLACK
                }
            }

            // 1. MODERN LAYOUT STRUCTURE (Column)
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                 // --- TOP SECTION ---
                 Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .statusBarsPadding(),
                    contentAlignment = Alignment.BottomCenter
                 ) {
                     val topIsPlayer = isVsHuman // simplified: in PvE top is always AI (false), in PvP is Human (true)
                     val isSelectionPhase = (model.gameStatus == GameStatus.NOT_STARTED || model.gameStatus == GameStatus.FINISHED)
                     
                     SymmetricalPlayerArea(
                        isTop = true,
                        isVsHuman = isVsHuman,
                        hudContent = {
                             SinglePlayerGameHud(
                                isBlack = topPlayerColor == PieceColor.BLACK,
                                isPlayer = topIsPlayer,
                                isActive = model.currentPlayer == topPlayerColor && model.gameStatus == GameStatus.PLAYING,
                                isWinner = (model.gameResult as? GameResult.Win)?.winner == topPlayerColor,
                                isDraw = model.gameResult is GameResult.Draw,
                                gameStatus = model.gameStatus,
                                rotate180 = false, // HUD is inside a rotated container, so it doesn't need self-rotation
                                showStartSelection = isSelectionPhase,
                                aiProgress = if (!isVsHuman) model.aiProgress else 0f,
                                aiThinkingSeconds = if (!isVsHuman) model.aiThinkingSeconds else 0,
                                onSelect = if (isSelectionPhase) {
                                    if (isVsHuman) { { viewModel.startGame(pvpBottomIsBlack = false) } }
                                    else { { viewModel.startGame(aiFirst = true) } }
                                } else null,
                                onPlayVictorySound = { viewModel.playStampSound() }
                             )
                        },
                        controlsContent = {
                            GameControlsRow(
                                gameStatus = model.gameStatus,
                                isVsHuman = isVsHuman,
                                isTurn = model.currentPlayer == topPlayerColor,
                                undoEnabled = model.canUndo,
                                showAssistButton = model.shouldShowAssistButton,
                                lastMoveExists = model.lastMove != null,
                                isAiThinking = model.isAiThinking,
                                onUndo = { viewModel.undo() },
                                onAssist = { viewModel.onShowAssistHint() },
                                onMenu = null // Menu only at bottom per user request
                            )
                        },
                        modifier = Modifier.padding(bottom = 16.dp) // Maintain distance from board
                     )
                 }

                 // --- BOARD SECTION ---
                 val isAiTurn = model.aiPlayerColor != null && model.currentPlayer == model.aiPlayerColor
                 Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(horizontal = 16.dp)
                        .zIndex(if (magnifierState?.visible == true) 10f else 0f)
                 ) {
                     BoardCanvas(
                        board = model.board,
                        enabled = model.gameStatus == GameStatus.PLAYING && !model.isAiThinking && !isAiTurn,
                        onPlacePiece = { row, col -> viewModel.placePiece(row, col) },
                        onUpdateMagnifier = { if (magnifierEnabled) magnifierState = it else magnifierState = null },
                        modifier = Modifier.fillMaxSize(),
                        gameStatus = model.gameStatus,
                        isAiThinking = model.isAiThinking,
                        lastMove = model.lastMove,
                        currentPlayer = model.currentPlayer,
                        assistMove = model.aiHint,
                        showAssistHint = model.showingAssistHint
                    )
                    
                    if (magnifierState?.visible == true) {
                        val density = LocalDensity.current
                        val magSize = 200.dp // Increased from 130.dp (+50%)
                        val baseOffset = 150.dp // Increased from 100.dp (+50%)
                        val verticalOffsetSign = if (isVsHuman && model.currentPlayer == topPlayerColor) 1 else -1

                        MagnifierView(
                            state = magnifierState!!,
                            board = model.board,
                            currentPlayer = model.currentPlayer ?: PieceColor.BLACK,
                            lastMove = model.lastMove,
                            modifier = Modifier
                                .offset {
                                    val state = magnifierState ?: return@offset IntOffset.Zero
                                    val yOffsetPx = with(density) { baseOffset.toPx() }
                                    val magHeightPx = with(density) { magSize.toPx() }
                                    val finalY = if (verticalOffsetSign == -1) {
                                         state.sourceCenter.y - yOffsetPx - magHeightPx/2
                                    } else {
                                         state.sourceCenter.y + yOffsetPx - magHeightPx/2
                                    }
                                    
                                    IntOffset(
                                        x = state.sourceCenter.x.roundToInt() - (magHeightPx/2).roundToInt(),
                                        y = finalY.roundToInt()
                                    )
                                }
                                .size(magSize)
                        )
                    }
                 }

                 // --- BOTTOM SECTION ---
                 Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .navigationBarsPadding(),
                    contentAlignment = Alignment.TopCenter
                 ) {
                     val bottomIsPlayer = true // simplified: In both PvP and PvE, the bottom is a Player (or me)
                     val isSelectionPhase = (model.gameStatus == GameStatus.NOT_STARTED || model.gameStatus == GameStatus.FINISHED)
                     
                     SymmetricalPlayerArea(
                        isTop = false,
                        isVsHuman = isVsHuman,
                        hudContent = {
                             SinglePlayerGameHud(
                                isBlack = bottomPlayerColor == PieceColor.BLACK,
                                isPlayer = bottomIsPlayer,
                                isActive = model.currentPlayer == bottomPlayerColor && model.gameStatus == GameStatus.PLAYING,
                                isWinner = (model.gameResult as? GameResult.Win)?.winner == bottomPlayerColor,
                                isDraw = model.gameResult is GameResult.Draw,
                                gameStatus = model.gameStatus,
                                showStartSelection = isSelectionPhase,
                                playerName = if (bottomIsPlayer) selectedPlayer?.name else null,
                                aiProgress = 0f,
                                aiThinkingSeconds = 0,
                                onSelect = if (isSelectionPhase) {
                                    if (isVsHuman) { { viewModel.startGame(pvpBottomIsBlack = true) } }
                                    else { { viewModel.startGame(aiFirst = false) } }
                                } else null,
                                onPlayVictorySound = { viewModel.playStampSound() }
                             )
                        },
                        controlsContent = {
                             GameControlsRow(
                                gameStatus = model.gameStatus,
                                isVsHuman = isVsHuman,
                                isTurn = model.currentPlayer == bottomPlayerColor,
                                undoEnabled = model.canUndo,
                                showAssistButton = model.shouldShowAssistButton,
                                lastMoveExists = model.lastMove != null,
                                isAiThinking = model.isAiThinking,
                                onUndo = { viewModel.undo() },
                                onAssist = { viewModel.onShowAssistHint() },
                                onMenu = { viewModel.showSettings() },
                                debugBoardCopyVisible = BuildConfig.DEBUG && !isVsHuman && model.board.getAllPieces().isNotEmpty(),
                                debugBoardCopied = boardCopied,
                                onCopyBoard = {
                                    val boardDump = viewModel.buildDebugBoardDump() ?: return@GameControlsRow
                                    clipboardManager.setText(AnnotatedString(boardDump))
                                    boardCopied = true
                                },
                                onStartAiFirst = null,
                                onStartPlayerFirst = null
                            )
                        },
                        modifier = Modifier.padding(top = 16.dp) // Maintain distance from board
                     )
                 }
            }


            // 6. DIALOGS (Settings)
            if (model.showSettings) {
                androidx.activity.compose.BackHandler { viewModel.hideSettings() }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .zIndex(3f)
                        .clickable { viewModel.hideSettings() },
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}) {
                        SettingsDialog(
                            selectedMode = model.mode,
                            selectedDifficulty = model.difficulty,
                            gameStatus = model.gameStatus,
                            soundEnabled = model.settings.soundEnabled,
                            vibrationEnabled = model.settings.vibrationEnabled,
                            undoEnabled = model.settings.undoEnabled,
                            aiAssistEnabled = model.settings.aiAssistEnabled,
                            isMainMenu = false,
                            language = menuViewModel?.uiState?.collectAsState()?.value?.language ?: "auto",
                            onDismiss = { viewModel.hideSettings() },
                            onSetDifficulty = { viewModel.setDifficulty(it) },
                            onToggleSound = { viewModel.setSoundEnabled(it) },
                            onToggleVibration = { viewModel.setVibrationEnabled(it) },
                            onToggleUndo = { viewModel.setUndoEnabled(it) },
                            onToggleAiAssist = { viewModel.setAiAssistEnabled(it) },
                            magnifierEnabled = menuState.magnifierEnabled,
                            onToggleMagnifier = { menuViewModel?.setMagnifierEnabled(it) },
                            onSetLanguage = null,
                            onStopGame = if (model.gameStatus == GameStatus.PLAYING) {
                                { viewModel.stopGame() }
                            } else null,
                            onExitGame = { onBack() }
                        )
                    }
                }
            }

            // 拦截所有交互，防止退出动画期间的误触
            if (!interactionEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(100f) // 确保在最上层
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                )
            }
        }
    }
}

/**
 * 续局徽章 - 显示在按钮右上角提示有可恢复的对局
 * 采用木纹质感风格，与整体UI协调
 */
@Composable
private fun ResumeBadge(modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Surface(
        modifier = modifier
            .wrapContentSize()
            .padding(4.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFB71C1C), // 深红色背景
        shadowElevation = 4.dp,
        border = androidx.compose.foundation.BorderStroke(
            width = 2.dp,
            color = Color(0xFFFFE082) // 金色边框
        )
    ) {
        Text(
            text = s.resumeBadge,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = Color(0xFFFFF8E1) // 米白色文字
            ),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * 难度提示 Toast - 人机对战开始时显示当前难度
 * 采用木纹羊皮纸风格，从顶部滑入
 */
@Composable
private fun DifficultyToast(difficulty: Difficulty) {
    val s = LocalStrings.current
    val difficultyText = when (difficulty) {
        Difficulty.EASY -> s.easyMode
        Difficulty.MEDIUM -> s.mediumMode
        Difficulty.HARD -> s.hardMode
        Difficulty.MASTER -> s.masterMode
    }
    
    // 根据难度使用不同颜色
    val (backgroundColor, textColor) = when (difficulty) {
        Difficulty.MASTER -> Color(0xFFB71C1C) to Color(0xFFFFE082) // 深红+金色
        else -> Color(0xFF5D4037) to Color(0xFFFFE082) // 深棕+金色
    }
    
    Surface(
        modifier = Modifier
            .wrapContentSize()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp), // 胶囊形状
        color = backgroundColor,
        shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(
            width = 2.dp,
            color = Color(0xFFFFE082).copy(alpha = 0.6f) // 半透金色边框
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // 棋子图标装饰
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        color = if (difficulty == Difficulty.MASTER) Color(0xFFFFE082) else Color.Black,
                        shape = CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = difficultyText,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = textColor
                )
            )
        }
    }
}
