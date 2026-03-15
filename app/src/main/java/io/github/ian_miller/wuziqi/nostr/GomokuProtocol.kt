package io.github.ian_miller.wuziqi.nostr

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ── Nostr 协议常量 ─────────────────────────────────────────────────────────────

/** NIP-78 Application-specific arbitrary data */
const val KIND_GOMOKU = 30078

// ── 游戏消息类型 ───────────────────────────────────────────────────────────────

enum class MsgType {
    JOIN,            // 加入房间（joiner → host）
    JOIN_ACK,        // 确认加入（host → joiner，游戏开始）
    MOVE,            // 落子
    RESIGN,          // 认输
    DRAW_REQUEST,    // 请求和棋
    DRAW_ACCEPT,     // 接受和棋
    DRAW_REJECT,     // 拒绝和棋
    RESYNC_REQUEST,  // 请求完整棋局（重连后）
    RESYNC,          // 完整棋局同步（含全部落子历史）
}

// ── 游戏消息模型 ───────────────────────────────────────────────────────────────

/**
 * 单次游戏消息，经 [GomokuCrypto] 加密后作为 Nostr 事件的 content。
 *
 * @param type      消息类型
 * @param gameId    全局唯一游戏 ID（16 字符 hex）
 * @param seq       序列号，单调递增，用于乱序检测
 * @param row       落子行（0-based，MOVE 时有效）
 * @param col       落子列（0-based，MOVE 时有效）
 * @param boardHash 当前棋盘 SHA256，用于完整性校验；为空时跳过校验
 * @param moves     RESYNC 时携带完整落子列表，格式 "row,col,BLACK|row,col,WHITE|..."
 */
@Serializable
data class GameMsg(
    val type: MsgType,
    val gameId: String,
    val seq: Int = 0,
    val row: Int = -1,
    val col: Int = -1,
    val boardHash: String = "",
    val moves: List<String> = emptyList(),
)

// ── JSON 编解码器 ──────────────────────────────────────────────────────────────

/** 全局 JSON 编解码器（忽略未知字段，保证前后版本兼容） */
val JsonCodec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

// ── 邀请码 ─────────────────────────────────────────────────────────────────────

/**
 * 邀请码格式：Base64-URL(pubkeyHex|gameId)
 *
 * 约 80 字符，可通过文字复制或 QR 码传递给对方。
 * pubkeyHex = 64 字符 hex（256 bit）
 * gameId    = 16 字符 hex（对局 ID）
 */
object InviteCode {

    fun encode(pubkeyHex: String, gameId: String): String {
        val raw = "$pubkeyHex|$gameId"
        return android.util.Base64.encodeToString(
            raw.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
        )
    }

    /**
     * 解码邀请码
     * @return Pair(hostPubkeyHex, gameId)，格式错误时返回 null
     */
    fun decode(code: String): Pair<String, String>? = runCatching {
        val raw = String(
            android.util.Base64.decode(code.trim(), android.util.Base64.URL_SAFE),
            Charsets.UTF_8,
        )
        val parts = raw.split("|")
        require(parts.size == 2 && parts[0].length == 64) { "邀请码结构无效" }
        Pair(parts[0], parts[1])
    }.getOrNull()
}
