package io.github.ian_miller.wuziqi.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.ian_miller.wuziqi.domain.model.Difficulty
import io.github.ian_miller.wuziqi.domain.model.GameMode
import io.github.ian_miller.wuziqi.domain.repository.Player
import io.github.ian_miller.wuziqi.ui.menu.MenuViewModel
import io.github.ian_miller.wuziqi.ui.theme.LocalStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerProfileScreen(
    onBack: () -> Unit,
    viewModel: MenuViewModel = hiltViewModel()
) {
    val players by viewModel.players.collectAsState()
    val selectedPlayer by viewModel.selectedPlayer.collectAsState()
    val stats by viewModel.stats.collectAsState()
    
    // Manage stats mode locally
    var statsMode by remember { mutableStateOf(GameMode.VS_AI) }
    val s = LocalStrings.current

    LaunchedEffect(selectedPlayer, statsMode) {
        viewModel.loadStats(statsMode)
    }
    
    // UI State for renaming/creating
    var showRenameDialog by remember { mutableStateOf<Player?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    // Wood background like other screens
    val woodBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFE0C39E),
                Color(0xFFA47E5C)
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.playerCenter, color = Color(0xFF3E2723), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color(0xFF3E2723))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = Color(0xFF5D4037),
                contentColor = Color(0xFFFFE082),
                icon = { Icon(Icons.Default.Add, s.newAccount) },
                text = { Text(s.createNewAccount) }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(woodBrush)
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // 1. Current Profile Header
                val currentPlayer = selectedPlayer
                if (currentPlayer != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF5D4037)),
                        elevation = CardDefaults.cardElevation(8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFFFFE082),
                                modifier = Modifier.size(80.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = currentPlayer.name.take(1).uppercase(),
                                        style = MaterialTheme.typography.displayMedium,
                                        color = Color(0xFF5D4037),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = currentPlayer.name,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = Color(0xFFFFE082),
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(onClick = { showRenameDialog = currentPlayer }) {
                                    Icon(Icons.Default.Edit, "Edit", tint = Color(0xFFFFE082).copy(alpha = 0.8f))
                                }
                            }
                        }
                    }

                    // 2. Stats Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "战绩统计",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color(0xFF3E2723),
                            fontWeight = FontWeight.Bold
                        )
                        
                        // Toggle Switch for Mode
                        // 暂时隐藏双人模式切换，只展示人机战绩
                        /*
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF5D4037).copy(alpha = 0.1f))
                                .padding(4.dp)
                        ) {
                            GameMode.values().forEach { mode ->
                                val selected = statsMode == mode
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (selected) Color(0xFF5D4037) else Color.Transparent)
                                        .clickable { statsMode = mode }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = if (mode == GameMode.VS_AI) "人机" else "双人",
                                        color = if (selected) Color(0xFFFFE082) else Color(0xFF5D4037),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        */
                    }
                    
                    if (statsMode == GameMode.VS_AI) {
                        // Display Difficulty Cards
                        Difficulty.values().forEach { diff ->
                            val diffName = when(diff) {
                                Difficulty.EASY -> s.easy
                                Difficulty.MEDIUM -> s.medium
                                Difficulty.HARD -> s.hard
                                Difficulty.MASTER -> s.master
                            }
                            // Using stats map keys: "EASY_total", etc.
                             Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.6f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(diffName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF3E2723))
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(s.winRateLabel, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                            Text("${String.format("%.0f", stats["${diff.name}_winRate"] ?: 0.0)}%", fontWeight = FontWeight.Bold, color = Color(0xFF3E2723))
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(s.winLossLabel, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                            val w = stats["${diff.name}_wins"] ?: 0
                                            val l = stats["${diff.name}_losses"] ?: 0
                                            Text("$w/$l", fontWeight = FontWeight.Bold, color = Color(0xFF3E2723))
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(s.totalGamesLabel, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                            Text("${stats["${diff.name}_total"] ?: 0}", fontWeight = FontWeight.Bold, color = Color(0xFF3E2723))
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // VS_HUMAN (Overall only)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            StatCard(
                                label = s.totalGames,
                                value = stats["total"]?.toString() ?: "0",
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.VideogameAsset
                            )
                            StatCard(
                                label = s.winRateLabel,
                                value = "${String.format("%.1f", stats["winRate"] ?: 0.0)}%",
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.EmojiEvents
                            )
                        }
                         Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            StatCard(
                                label = s.wins,
                                value = stats["wins"]?.toString() ?: "0",
                                modifier = Modifier.weight(1f),
                                color = Color(0xFF2E7D32) // Green
                            )
                             StatCard(
                                label = s.losses,
                                value = stats["losses"]?.toString() ?: "0", 
                                modifier = Modifier.weight(1f),
                                color = Color(0xFFC62828) // Red
                            )
                        }
                    } // End else
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 3. Switch Account List
                Text(
                    s.switchAccount,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFF3E2723),
                    fontWeight = FontWeight.Bold
                )
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.5f))
                ) {
                    Column {
                        players.forEach { player ->
                            val isSelected = player.id == selectedPlayer?.id
                            ListItem(
                                headlineContent = { Text(player.name, fontWeight = FontWeight.SemiBold) },
                                leadingContent = {
                                    Icon(Icons.Default.Person, null)
                                },
                                trailingContent = {
                                    if (isSelected) {
                                        Icon(Icons.Default.CheckCircle, "Selected", tint = Color(0xFF5D4037))
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable { viewModel.selectPlayer(player) }
                            )
                            if (player != players.last()) {
                                HorizontalDivider(color = Color(0xFF3E2723).copy(alpha = 0.1f))
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(64.dp)) // Bottom padding for FAB
            }
        }
    }

    // Dialogs
    if (showCreateDialog) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(s.createNewAccount) },
            text = {
                OutlinedTextField(
                     value = newName,
                     onValueChange = { newName = it },
                     label = { Text(s.nickname) },
                     singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            viewModel.createPlayer(newName)
                            showCreateDialog = false
                        }
                    }
                ) { Text(s.create) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text(s.cancel) }
            }
        )
    }

    if (showRenameDialog != null) {
        var newName by remember { mutableStateOf(showRenameDialog!!.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
             title = { Text(s.renameTitle) },
            text = {
                OutlinedTextField(
                     value = newName,
                     onValueChange = { newName = it },
                     label = { Text(s.newNickname) },
                     singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            viewModel.renamePlayer(showRenameDialog!!, newName)
                            showRenameDialog = null
                        }
                    }
                ) { Text(s.save) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) { Text(s.cancel) }
            }
        )
    }
}

@Composable
fun StatCard(
    label: String, 
    value: String, 
    modifier: Modifier = Modifier, 
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    color: Color = Color(0xFF5D4037)
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.8f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, modifier = Modifier.size(20.dp), tint = color)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    value, 
                    style = MaterialTheme.typography.headlineSmall, 
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
        }
    }
}