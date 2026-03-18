package io.github.ian_miller.wuziqi.ui.game

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import io.github.ian_miller.wuziqi.R
import io.github.ian_miller.wuziqi.ui.theme.LocalStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialScreen(
    onBack: () -> Unit
) {
    val s = LocalStrings.current

    // 木质渐变背景
    val woodBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFE0C39E), // Light Wood
                Color(0xFFA47E5C)  // Dark Wood
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        s.tutorialTitle,
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
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ========== 游戏简介 ==========
                TutorialSection(
                    icon = Icons.Default.SportsEsports,
                    title = s.tutorialIntroTitle,
                    content = {
                        Text(
                            text = s.tutorialIntro1,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color(0xFF3E2723),
                            lineHeight = 24.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = s.tutorialIntro2,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color(0xFF3E2723),
                            lineHeight = 24.sp
                        )
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 棋盘介绍图片
                Image(
                    painter = painterResource(id = R.drawable.board),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ========== 基本规则 ==========
                TutorialSection(
                    icon = Icons.Default.Star,
                    title = s.tutorialRulesTitle,
                    content = {
                        RuleItem(number = 1, text = s.tutorialRule1)
                        RuleItem(number = 2, text = s.tutorialRule2)
                        RuleItem(number = 3, text = s.tutorialRule3)
                        RuleItem(number = 4, text = s.tutorialRule4)
                        RuleItem(number = 5, text = s.tutorialRule5)
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 落子演示图片
                Image(
                    painter = painterResource(id = R.drawable.place_stones),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ========== 游戏模式 ==========
                TutorialSection(
                    icon = Icons.Default.EmojiEvents,
                    title = s.tutorialModesTitle,
                    content = {
                        GameModeItem(
                            title = s.tutorialModeAiTitle,
                            description = s.tutorialModeAiDesc
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        GameModeItem(
                            title = s.tutorialModeHumanTitle,
                            description = s.tutorialModeHumanDesc
                        )
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 游戏界面截图（高图）- 宽度填满，高度自适应
                Image(
                    painter = painterResource(id = R.drawable.duel_match),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ========== 特色功能 ==========
                TutorialSection(
                    icon = Icons.Default.Lightbulb,
                    title = s.tutorialFeaturesTitle,
                    content = {
                        FeatureItem(
                            title = s.tutorialMagnifierTitle,
                            description = s.tutorialMagnifierDesc
                        )
                        FeatureItem(
                            title = s.tutorialAiHintTitle,
                            description = s.tutorialAiHintDesc
                        )
                        FeatureItem(
                            title = s.tutorialUndoFeatTitle,
                            description = s.tutorialUndoFeatDesc
                        )
                        FeatureItem(
                            title = s.tutorialStatsFeatTitle,
                            description = s.tutorialStatsFeatDesc
                        )
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 放大镜功能演示图片
                Image(
                    painter = painterResource(id = R.drawable.magifier),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ========== 游戏技巧 ==========
                TutorialSection(
                    icon = Icons.Default.EmojiEvents,
                    title = s.tutorialTipsTitle,
                    content = {
                        TipItem(
                            level = s.tutorialBeginnerLevel,
                            tips = s.tutorialBeginnerTips
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        HorizontalDivider(color = Color(0xFF5D4037).copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(16.dp))

                        TipItem(
                            level = s.tutorialIntermLevel,
                            tips = s.tutorialIntermTips
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        HorizontalDivider(color = Color(0xFF5D4037).copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(16.dp))

                        TipItem(
                            level = s.tutorialAdvancedLevel,
                            tips = s.tutorialAdvancedTips
                        )
                    }
                )

                Spacer(modifier = Modifier.height(32.dp))

                // 底部提示
                Text(
                    text = s.tutorialFooter1,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF5D4037),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = s.tutorialFooter2,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF3E2723),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun TutorialSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFF8E1).copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color(0xFF5D4037),
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFF3E2723),
                    fontWeight = FontWeight.Bold
                )
            }
            content()
        }
    }
}

@Composable
private fun RuleItem(number: Int, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF5D4037),
            modifier = Modifier.size(24.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number.toString(),
                    color = Color(0xFFFFE082),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF3E2723),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun GameModeItem(title: String, description: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF5D4037),
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF3E2723).copy(alpha = 0.8f),
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun FeatureItem(title: String, description: String) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = Color(0xFFFFE082),
            modifier = Modifier.size(8.dp)
        ) {
            Box {}
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFF5D4037),
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF3E2723).copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun TipItem(level: String, tips: List<String>) {
    Column {
        Text(
            text = level,
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF5D4037),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        tips.forEach { tip ->
            Row(
                modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFE74C3C),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = tip,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF3E2723),
                    lineHeight = 20.sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
