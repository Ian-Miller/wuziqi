package io.github.ian_miller.wuziqi.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.ian_miller.wuziqi.domain.model.Difficulty
import io.github.ian_miller.wuziqi.domain.model.GameMode
import io.github.ian_miller.wuziqi.domain.model.PieceColor
import io.github.ian_miller.wuziqi.domain.repository.Player
import io.github.ian_miller.wuziqi.ui.theme.LocalStrings

@Composable
fun SettingsDialog(
    selectedMode: GameMode,
    selectedDifficulty: Difficulty,
    gameStatus: GameStatus,
    soundEnabled: Boolean,
    vibrationEnabled: Boolean,
    undoEnabled: Boolean,
    aiAssistEnabled: Boolean,
    magnifierEnabled: Boolean = true,
    isMainMenu: Boolean,
    hasSavedSinglePlayerGame: Boolean = false,
    language: String = "auto",
    onDismiss: () -> Unit,
    onSetDifficulty: (Difficulty) -> Unit,
    onToggleSound: (Boolean) -> Unit,
    onToggleVibration: (Boolean) -> Unit,
    onToggleUndo: (Boolean) -> Unit,
    onToggleAiAssist: (Boolean) -> Unit,
    onToggleMagnifier: (Boolean) -> Unit = {},
    onSetLanguage: ((String) -> Unit)? = null,
    onStopGame: (() -> Unit)? = null,
    onExitGame: (() -> Unit)? = null
) {
    val s = LocalStrings.current
    // Wood/Parchment Themed Card
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFF8E1) // Parchment / Light Cream
        ),
        border = androidx.compose.foundation.BorderStroke(4.dp, Color(0xFF5D4037)), // Dark Wood Border
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
         Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
             Box(
                 modifier = Modifier.fillMaxWidth(),
                 contentAlignment = Alignment.Center
             ) {
                Text(
                    text = s.gameSettings, 
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = Color(0xFF3E2723)
                    ),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
             }

            // Divider
            HorizontalDivider(color = Color(0xFF8D6E63), thickness = 2.dp)
            Spacer(modifier = Modifier.height(16.dp))

            // Difficulty: Show in Main Menu OR VS_AI Mode
            if (isMainMenu || selectedMode == GameMode.VS_AI) {
                Text(
                    text = s.difficultyLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF5D4037)
                )
                
                // 如果有人机对战存档，显示提示并禁用难度选择
                if (hasSavedSinglePlayerGame) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = s.savedGameWarning,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB71C1C)
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                DifficultySelector(
                    selected = selectedDifficulty,
                    onSelect = onSetDifficulty,
                    enabled = !hasSavedSinglePlayerGame && gameStatus != GameStatus.PLAYING
                )
                
                Spacer(modifier = Modifier.height(24.dp))
            }
            
            // Settings Rows with consistent styling
            SettingsSwitchRow(s.sound, Icons.Filled.VolumeUp, soundEnabled, onToggleSound)
            SettingsSwitchRow(s.vibration, Icons.Filled.Vibration, vibrationEnabled, onToggleVibration)
            SettingsSwitchRow(s.allowUndo, Icons.Filled.Undo, undoEnabled, onToggleUndo)
            
            // AI Assist: Show in Main Menu OR VS_HUMAN Mode
            if (isMainMenu || selectedMode == GameMode.VS_HUMAN) {
                val isAiAssistEditable = gameStatus != GameStatus.PLAYING
                SettingsSwitchRow(s.aiAssist, Icons.Filled.Assessment, aiAssistEnabled, onToggleAiAssist, enabled = isAiAssistEditable)
            }
            
            SettingsSwitchRow(s.magnifier, Icons.Filled.ZoomIn, magnifierEnabled, onToggleMagnifier)

            // Language selector (only shown from main menu where onSetLanguage is provided)
            if (onSetLanguage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) {
                    Icon(Icons.Filled.Language, contentDescription = null, tint = Color(0xFF5D4037))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(text = s.language, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF3E2723), modifier = Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("auto" to s.langAuto, "en" to s.langEnglish, "zh" to s.langChinese).forEach { (code, label) ->
                        FilterChip(
                            selected = language == code,
                            onClick = { onSetLanguage(code) },
                            label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF5D4037),
                                selectedLabelColor = Color(0xFFFFE082)
                            )
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            // In-Game Actions
            if (!isMainMenu) {
                HorizontalDivider(color = Color(0xFF8D6E63).copy(alpha = 0.5f), thickness = 1.dp)
                Spacer(modifier = Modifier.height(16.dp))

                if (onStopGame != null && gameStatus == GameStatus.PLAYING) {
                    Button(
                        onClick = { onStopGame(); onDismiss() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFC62828), // Red Warning Color
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Filled.Stop, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(s.stopGame)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (onExitGame != null) {
                    Button(
                        onClick = { onExitGame(); onDismiss() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF5D4037),
                            contentColor = Color(0xFFFFE082)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Filled.ExitToApp, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(s.exitGame)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            } else {
                 Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF5D4037),
                        contentColor = Color(0xFFFFE082)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(s.done, style = MaterialTheme.typography.titleMedium)
                }
            }
         }
    }
}

@Composable
internal fun SettingsSwitchRow(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
     Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 8.dp)
            .then(if (!enabled) Modifier.alpha(0.5f) else Modifier)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
             Icon(icon, contentDescription = null, tint = Color(0xFF5D4037))
             Spacer(modifier = Modifier.width(16.dp))
             Text(text = text, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF3E2723))
        }
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF5D4037),
                checkedTrackColor = Color(0xFFD7CCC8),
                uncheckedThumbColor = Color(0xFF8D6E63),
                uncheckedTrackColor = Color(0xFFEFEBE9)
            )
        )
    }
}



@Composable
fun StatsDialog(
    stats: Map<String, Any>,
    players: List<Player>,
    selectedPlayer: Player?,
    onSelectPlayer: (Player) -> Unit,
    onCreatePlayer: (String) -> Unit,
    onRenamePlayer: (Player, String) -> Unit,
    onDismiss: () -> Unit
) {
    var showRenameDialog by remember { mutableStateOf<Player?>(null) }

    if (showRenameDialog != null) {
        var newName by remember { mutableStateOf(showRenameDialog!!.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("修改名称") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("玩家名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onRenamePlayer(showRenameDialog!!, newName)
                            showRenameDialog = null
                        }
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) { Text("取消") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            val modeTitle = when (stats["mode"] as? GameMode) {
                GameMode.VS_AI -> "人机模式"
                GameMode.VS_HUMAN -> "双人模式"
                else -> ""
            }
            Text(if (modeTitle.isNotEmpty()) "$modeTitle 数据统计" else "数据统计") 
        },
        text = {
            Column {
                // Player Selector
                Text("当前账号:", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    players.forEach { p ->
                        InputChip(
                            selected = p.id == selectedPlayer?.id,
                            onClick = { onSelectPlayer(p) },
                            label = { Text(p.name) },
                            trailingIcon = if (p.id == selectedPlayer?.id) {
                                {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "修改名称",
                                        modifier = Modifier.size(16.dp).clickable { showRenameDialog = p }
                                    )
                                }
                            } else null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    IconButton(onClick = { onCreatePlayer("玩家${players.size + 1}") }) { // Simple create for now
                        Icon(Icons.Default.Add, "新建")
                    }
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                if (stats.isEmpty()) {
                    Text("暂无数据")
                } else {
                    Text("总场次: ${stats["total"]}")
                    Text("获胜: ${stats["wins"]}")
                    Text("胜率: ${String.format("%.1f", stats["winRate"])}%")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
fun PlayerSelectionDialog(
    players: List<Player>,
    selectedPlayer: Player?,
    onSelectPlayer: (Player) -> Unit,
    onCreatePlayer: (String) -> Unit,
    onShowStats: () -> Unit, // Add callback
    onDismiss: () -> Unit
) {
    var newPlayerName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "账号管理") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                // Header Stats Button
                if (selectedPlayer != null) {
                    Button(
                        onClick = { onShowStats() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("查看 ${selectedPlayer.name} 的详细战绩")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Text("切换账号:", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                players.forEach { player ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { onSelectPlayer(player) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedPlayer?.id == player.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = if (selectedPlayer?.id == player.id) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = player.name)
                                if (selectedPlayer?.id == player.id) {
                                    Icon(Icons.Default.Check, contentDescription = "Selected", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text("新建账号:", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = newPlayerName,
                    onValueChange = { newPlayerName = it },
                    label = { Text("玩家昵称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (newPlayerName.isNotBlank()) {
                            onCreatePlayer(newPlayerName)
                            newPlayerName = ""
                        }
                    },
                    enabled = newPlayerName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("创建新玩家")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}


/*
@Composable
fun GameMenuDialog(
    onDismiss: () -> Unit,
    onStop: (() -> Unit)? = null,
    onSettings: () -> Unit,
    onStats: () -> Unit,
    onExit: () -> Unit,
    onUndo: (() -> Unit)? = null
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFFFF8E1) // Parchment
            ),
            border = androidx.compose.foundation.BorderStroke(4.dp, Color(0xFF5D4037)),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "游戏菜单",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = Color(0xFF3E2723)
                    )
                )

                HorizontalDivider(color = Color(0xFF8D6E63), thickness = 2.dp)
                Spacer(modifier = Modifier.height(4.dp))

                val buttonModifier = Modifier.fillMaxWidth().height(48.dp)
                val secondaryButtonColors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF8D6E63), // Light Brown
                    contentColor = Color(0xFFFFF8E1)
                )

                if (onUndo != null) {
                    Button(
                        onClick = { onUndo(); onDismiss() },
                        modifier = buttonModifier,
                        colors = secondaryButtonColors
                    ) {
                        Icon(Icons.Filled.Undo, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("撤销一步", style = MaterialTheme.typography.titleMedium)
                    }
                }

                if (onStop != null) {
                    Button(
                        onClick = { onStop(); onDismiss() },
                        modifier = buttonModifier,
                        colors = secondaryButtonColors
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("终止本局", style = MaterialTheme.typography.titleMedium)
                    }
                }

                Button(
                    onClick = { onSettings(); onDismiss() },
                    modifier = buttonModifier,
                    colors = secondaryButtonColors
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("设置", style = MaterialTheme.typography.titleMedium)
                }

                Button(
                    onClick = { onStats(); onDismiss() },
                    modifier = buttonModifier,
                    colors = secondaryButtonColors
                ) {
                    Icon(Icons.Filled.Assessment, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("统计", style = MaterialTheme.typography.titleMedium)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { onExit(); onDismiss() },
                    modifier = buttonModifier,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F), // Red
                        contentColor = Color.White
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                ) {
                    Icon(Icons.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("退出到主菜单", style = MaterialTheme.typography.titleMedium)
                }

                TextButton(onClick = onDismiss) { 
                    Text(
                        "返回游戏", 
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF5D4037)
                    ) 
                }
            }
        }
    }
}
*/


@Composable
fun SavePvPDialog(
    players: List<Player>,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var blackName by remember { mutableStateOf("") }
    var whiteName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存对局记录") },
        text = {
            Column {
                Text("黑方 (先手):")
                OutlinedTextField(
                    value = blackName,
                    onValueChange = { blackName = it },
                    singleLine = true,
                    placeholder = { Text("输入玩家姓名") }
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text("快速选择:", style = MaterialTheme.typography.bodySmall)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                   players.forEach { p ->
                       SuggestionChip(
                           onClick = { if(blackName.isEmpty()) blackName = p.name else if(whiteName.isEmpty()) whiteName = p.name },
                           label = { Text(p.name) }
                       )
                       Spacer(modifier = Modifier.width(4.dp))
                   }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("白方 (后手):")
                OutlinedTextField(
                    value = whiteName,
                    onValueChange = { whiteName = it },
                    singleLine = true,
                    placeholder = { Text("输入玩家姓名") }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(blackName, whiteName) },
                enabled = blackName.isNotBlank() && whiteName.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("跳过") }
        }
    )
}

@Composable
internal fun DifficultySelector(
    selected: Difficulty,
    onSelect: (Difficulty) -> Unit,
    enabled: Boolean
) {
    val s = LocalStrings.current
    val selectedIndex = when (selected) {
        Difficulty.EASY -> 0
        Difficulty.MEDIUM -> 1
        Difficulty.HARD -> 2
        Difficulty.MASTER -> 3
    }

    val animatedIndex by androidx.compose.animation.core.animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        label = "indicator_anim",
        animationSpec = androidx.compose.animation.core.spring(
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        )
    )
    
    // 大师模式专用深红色
    val masterSelectedColor = Color(0xFFB71C1C) // 深红色 - 选中指示器
    val masterUnselectedColor = Color(0xFF8B0000) // 暗红色 - 未选中文字

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFD7CCC8))
            .padding(4.dp)
            .drawWithContent {
                val tabWidth = size.width / 4
                val indicatorWidth = tabWidth
                val visualPadding = 4.dp.toPx()
                val visualWidth = indicatorWidth - (visualPadding * 2)
                val indicatorOffset = (tabWidth * animatedIndex) + visualPadding

                // 背景指示器颜色：大师模式用深红色，其他用棕色
                val indicatorColor = when (selected) {
                    Difficulty.MASTER -> if (enabled) masterSelectedColor else Color.Gray
                    else -> if (enabled) Color(0xFF5D4037) else Color.Gray
                }
                
                drawRoundRect(
                    color = indicatorColor,
                    topLeft = androidx.compose.ui.geometry.Offset(x = indicatorOffset, y = 0f),
                    size = androidx.compose.ui.geometry.Size(width = visualWidth, height = size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
                )
                
                drawContent()
            }
    ) {
        Difficulty.entries.forEach { diff ->
            val isSelected = diff == selected
            
            // 文字颜色配置
            val targetTextColor = when {
                isSelected -> Color(0xFFFFE082) // 选中：金色（所有难度统一）
                diff == Difficulty.MASTER -> masterUnselectedColor // 大师未选中：深红色
                else -> Color(0xFF4E342E) // 其他未选中：深棕色
            }
            
            val textColor by androidx.compose.animation.animateColorAsState(
                targetValue = if (enabled) targetTextColor else targetTextColor.copy(alpha = 0.5f),
                label = "text_color"
            )
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled) { onSelect(diff) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (diff) {
                        Difficulty.EASY -> s.easy
                        Difficulty.MEDIUM -> s.medium
                        Difficulty.HARD -> s.hard
                        Difficulty.MASTER -> s.master
                    },
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    ),
                    color = textColor
                )
            }
        }
    }
}

@Composable
fun GameResultOverlay(result: GameResult, onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().zIndex(4f).background(Color.Black.copy(alpha = 0.5f)).clickable{ onDismiss() }, contentAlignment = Alignment.Center) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
             Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if(result is GameResult.Win) "${if(result.winner == PieceColor.BLACK) "黑方" else "白方"}获胜!" else "平局",
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(Modifier.height(16.dp))
                Text("点击任意处关闭", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
