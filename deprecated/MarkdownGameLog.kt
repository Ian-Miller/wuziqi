package io.github.ian_miller.wuziqi.ui.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownDimens

/**
 * 使用 Markdown 渲染游戏日志（使用较小的标题字体和紧凑表格）
 * 
 * @param useHorizontalScroll 是否启用水平滚动（在 Dialog 等容器中应设为 false 避免约束冲突）
 */
@Composable
fun MarkdownGameLog(
    markdownContent: String,
    modifier: Modifier = Modifier,
    useHorizontalScroll: Boolean = false
) {
    val scrollState = rememberScrollState()
    
    // 自定义 Markdown 样式，减小标题字体
    val customTypography = markdownTypography(
        h1 = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp),
        h2 = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
        h3 = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
        paragraph = MaterialTheme.typography.bodyMedium,
        list = MaterialTheme.typography.bodyMedium,
        table = MaterialTheme.typography.bodyMedium,  // 表格字体恢复正常
    )
    
    // 关键：自定义表格尺寸，减小列宽和padding
    val customDimens = markdownDimens(
        tableCellWidth = 48.dp,      // 默认160dp -> 48dp，紧凑自适应
        tableCellPadding = 4.dp,     // 默认16dp -> 4dp，减小内边距
        tableCornerSize = 4.dp,      // 默认8dp -> 4dp，更小圆角
    )
    
    val colors = markdownColor()
    
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        // 根据 useHorizontalScroll 决定是否添加水平滚动
        val scrollModifier = if (useHorizontalScroll) {
            Modifier
                .padding(8.dp)
                .horizontalScroll(rememberScrollState())
                .verticalScroll(scrollState)
        } else {
            Modifier
                .padding(8.dp)
                .verticalScroll(scrollState)
        }
        
        Box(modifier = scrollModifier) {
            Markdown(
                content = markdownContent,
                modifier = if (useHorizontalScroll) Modifier.wrapContentWidth() else Modifier.fillMaxWidth(),
                typography = customTypography,
                colors = colors,
                dimens = customDimens  // 关键：传入自定义尺寸
            )
        }
    }
}

/**
 * 将原始日志转换为 Markdown 格式
 */
fun formatGameLogAsMarkdown(
    title: String = "五子棋 AI 对局记录",
    gameInfo: Map<String, String>,
    moves: List<GameMoveMarkdown>,
    stats: Map<String, String>
): String {
    val sb = StringBuilder()
    
    // 标题
    sb.appendLine("# $title")
    sb.appendLine()
    
    // 游戏信息
    sb.appendLine("## 📋 对局信息")
    sb.appendLine()
    gameInfo.forEach { (key, value) ->
        sb.appendLine("- **$key**: $value")
    }
    sb.appendLine()
    
    // 走法记录 - 使用更紧凑的列名
    if (moves.isNotEmpty()) {
        sb.appendLine("## ♟️ 走法记录")
        sb.appendLine()
        // 缩短列名以减小列宽
        sb.appendLine("|手数|玩家|位置|层|节点|分|耗时|")
        sb.appendLine("|:--:|:--:|:--:|:--:|:--:|:--:|:--:|")
        moves.forEach { move ->
            val playerShort = when {
                move.player.contains("Black", ignoreCase = true) -> "黑"
                move.player.contains("White", ignoreCase = true) -> "白"
                else -> move.player.take(1)
            }
            val nodesShort = formatNumberCompact(move.nodes)
            val timeShort = "${move.timeMs}ms"
            sb.appendLine("|${move.moveNumber}|$playerShort|${move.position}|${move.depth}|$nodesShort|${move.score}|$timeShort|")
        }
        sb.appendLine()
    }
    
    // 统计信息
    sb.appendLine("## 📊 统计信息")
    sb.appendLine()
    stats.forEach { (key, value) ->
        sb.appendLine("- **$key**: $value")
    }
    
    return sb.toString()
}

data class GameMoveMarkdown(
    val moveNumber: Int,
    val player: String,
    val position: String,
    val depth: Int,
    val nodes: String,
    val score: Int,
    val timeMs: Int
)

/**
 * 格式化数字为可读形式（K/M）
 */
fun formatNumberMarkdown(n: Long): String {
    return when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fK".format(n / 1_000.0)
        else -> n.toString()
    }
}

/**
 * 更紧凑的数字格式化
 */
private fun formatNumberCompact(nodes: String): String {
    val n = nodes.toLongOrNull() ?: return nodes
    return when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fK".format(n / 1_000.0)
        else -> n.toString()
    }
}
