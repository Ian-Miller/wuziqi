package io.github.ian_miller.wuziqi.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Nostr WebSocket 客户端，连接多个去中心化公共中继。
 *
 * ## 设计要点
 * - **多中继冗余**：同时连接 3 个中继，任意一个送达即可，不依赖单点
 * - **自动重连**：连接失败 5 秒后自动重试
 * - **订阅恢复**：重连后自动重发当前订阅
 * - **系统代理**：OkHttp 默认读取 Android 系统代理（HTTP/SOCKS5 均支持）
 *
 * ## 使用方式
 * ```kotlin
 * val client = NostrClient()
 * client.connect(viewModelScope)
 * client.subscribe(gameId)
 * client.publish(event)
 * // ...
 * client.disconnect()
 * ```
 */
class NostrClient(
    private val relayUrls: List<String> = DEFAULT_RELAYS,
) {

    companion object {
        /** 默认公共中继列表（覆盖全球，任意一个可达即可） */
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.snort.social",
        )
    }

    // ── OkHttp 客户端 ──────────────────────────────────────────────────────────

    private val okhttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)      // WebSocket 不设读取超时
        .writeTimeout(10, TimeUnit.SECONDS)
        .proxySelector(java.net.ProxySelector.getDefault()) // 自动使用系统代理
        .build()

    // ── 事件流 ─────────────────────────────────────────────────────────────────

    private val _events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 128)

    /** 从中继接收到的事件流（过滤后仅包含当前订阅的 gameId） */
    val events: SharedFlow<NostrEvent> = _events.asSharedFlow()

    // ── 连接状态 ───────────────────────────────────────────────────────────────

    /** relayUrl -> 当前 WebSocket（null = 断开中） */
    private val connections = ConcurrentHashMap<String, WebSocket>()

    /** 中继连接状态（relayUrl -> 是否已连接） */
    private val _relayStatus = MutableStateFlow(relayUrls.associateWith { false })
    val relayStatus: StateFlow<Map<String, Boolean>> = _relayStatus.asStateFlow()

    /** 当前活跃的游戏 ID，用于重连后恢复订阅 */
    private var activeGameId: String = ""
    private var currentSubId: String = ""
    private var currentFilter: JsonObject? = null

    // ── 生命周期 ───────────────────────────────────────────────────────────────

    /** 连接所有中继，协程 scope 与 ViewModel 绑定 */
    fun connect(scope: CoroutineScope) {
        relayUrls.forEach { url -> openRelay(scope, url) }
    }

    /** 关闭所有连接，应在 ViewModel.onCleared() 中调用 */
    fun disconnect() {
        connections.values.forEach { it.close(1000, "client disconnected") }
        connections.clear()
    }

    // ── 订阅 ───────────────────────────────────────────────────────────────────

    /**
     * 订阅指定 gameId 的事件频道。
     *
     * @param gameId 对局 ID
     * @param since  Unix 时间戳（秒），仅获取此时间之后的事件（增量同步用）
     */
    fun subscribe(gameId: String, since: Long = 0) {
        activeGameId = gameId
        currentSubId = "gm-${gameId.take(8)}"
        currentFilter = buildJsonObject {
            put("kinds", buildJsonArray { add(KIND_GOMOKU) })
            put("#d", buildJsonArray { add(gameId) })
            if (since > 0) put("since", since)
        }
        sendAll(buildReqJson())
    }

    // ── 发布 ───────────────────────────────────────────────────────────────────

    /** 向所有已连接中继广播一个 Nostr 事件 */
    fun publish(event: NostrEvent) {
        val msg = buildJsonArray {
            add("EVENT")
            add(JsonCodec.encodeToJsonElement(event))
        }.toString()
        sendAll(msg)
    }

    // ── 私有辅助 ───────────────────────────────────────────────────────────────

    private fun openRelay(scope: CoroutineScope, url: String) {
        val request = Request.Builder().url(url).build()
        val ws = okhttp.newWebSocket(request, RelayListener(scope, url))
        connections[url] = ws
    }

    private fun sendAll(msg: String) {
        connections.values.forEach { it.send(msg) }
    }

    private fun buildReqJson(): String = buildJsonArray {
        add("REQ"); add(currentSubId); add(currentFilter!!)
    }.toString()

    /** 解析中继消息，仅返回 EVENT 类型 */
    private fun parseEvent(text: String): NostrEvent? = runCatching {
        val arr = Json.parseToJsonElement(text).jsonArray
        if (arr[0].jsonPrimitive.content == "EVENT") {
            JsonCodec.decodeFromJsonElement<NostrEvent>(arr[2])
        } else null
    }.getOrNull()

    // ── WebSocket 监听器 ───────────────────────────────────────────────────────

    private inner class RelayListener(
        private val scope: CoroutineScope,
        private val url: String,
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            connections[url] = webSocket
            _relayStatus.value = _relayStatus.value + (url to true)
            // 重连后恢复订阅
            currentFilter?.let { webSocket.send(buildReqJson()) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch(Dispatchers.Default) {
                parseEvent(text)?.let { _events.emit(it) }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connections.remove(url)
            _relayStatus.value = _relayStatus.value + (url to false)
            // 指数退避重连（固定 5 秒，后续可改为指数退避）
            scope.launch {
                delay(5_000)
                if (scope.isActive) openRelay(scope, url)
            }
        }
    }
}
