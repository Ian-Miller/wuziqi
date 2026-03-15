package io.github.ian_miller.wuziqi.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.ian_miller.wuziqi.domain.model.GameMode
import io.github.ian_miller.wuziqi.ui.menu.MenuViewModel
import io.github.ian_miller.wuziqi.ui.theme.LocalStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: MenuViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val s = LocalStrings.current
    val hasSavedSinglePlayerGame = remember { viewModel.hasSavedGame(GameMode.VS_AI) }

    val woodBrush = remember {
        Brush.verticalGradient(
            colors = listOf(Color(0xFFE0C39E), Color(0xFFA47E5C))
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = s.settingsBtn,
                        color = Color(0xFF3E2723),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = Color(0xFF3E2723)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFE0C39E)
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(woodBrush)
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── 难度 ──────────────────────────────────────────────────────
                SettingsPageCard {
                    Text(
                        text = s.difficultyLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF5D4037),
                        fontWeight = FontWeight.Bold
                    )
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
                        selected = uiState.selectedDifficulty,
                        onSelect = { viewModel.setDifficulty(it) },
                        enabled = !hasSavedSinglePlayerGame
                    )
                }

                // ── 游戏选项 ──────────────────────────────────────────────────
                SettingsPageCard {
                    SettingsSwitchRow(
                        text = s.sound,
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        checked = uiState.soundEnabled,
                        onCheckedChange = { viewModel.setSoundEnabled(it) }
                    )
                    HorizontalDivider(color = Color(0xFF8D6E63).copy(alpha = 0.3f))
                    SettingsSwitchRow(
                        text = s.vibration,
                        icon = Icons.Filled.Vibration,
                        checked = uiState.vibrationEnabled,
                        onCheckedChange = { viewModel.setVibrationEnabled(it) }
                    )
                    HorizontalDivider(color = Color(0xFF8D6E63).copy(alpha = 0.3f))
                    SettingsSwitchRow(
                        text = s.allowUndo,
                        icon = Icons.AutoMirrored.Filled.Undo,
                        checked = uiState.undoEnabled,
                        onCheckedChange = { viewModel.setUndoEnabled(it) }
                    )
                    HorizontalDivider(color = Color(0xFF8D6E63).copy(alpha = 0.3f))
                    SettingsSwitchRow(
                        text = s.aiAssist,
                        icon = Icons.Filled.Assessment,
                        checked = uiState.aiAssistEnabled,
                        onCheckedChange = { viewModel.setAiAssistEnabled(it) }
                    )
                    HorizontalDivider(color = Color(0xFF8D6E63).copy(alpha = 0.3f))
                    SettingsSwitchRow(
                        text = s.magnifier,
                        icon = Icons.Filled.ZoomIn,
                        checked = uiState.magnifierEnabled,
                        onCheckedChange = { viewModel.setMagnifierEnabled(it) }
                    )
                }

                // ── 界面语言 ──────────────────────────────────────────────────
                SettingsPageCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Icon(Icons.Filled.Language, contentDescription = null, tint = Color(0xFF5D4037))
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = s.language,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF5D4037),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("auto" to s.langAuto, "en" to s.langEnglish, "zh" to s.langChinese).forEach { (code, label) ->
                            FilterChip(
                                selected = uiState.language == code,
                                onClick = { viewModel.setLanguage(code) },
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
            }
        }
    }
}

@Composable
private fun SettingsPageCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFF8E1).copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}
