package io.github.ian_miller.wuziqi.nostr

import android.util.Base64
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URI
import java.nio.ByteBuffer

// ── 端口 ───────────────────────────────────────────────────────────────────────

const val LAN_PORT = 18765

// ── 局域网邀请码 ───────────────────────────────────────────────────────────────

/**
 * 局域网邀请码格式：Base64-URL( "LAN|{gameId}|{ip}|{port}" )
 *
 * 与 Nostr 邀请码格式不同（Nostr 码不含 "LAN|" 前缀），
 * 可通过 [isLanCode] 自动区分，让 joinRoom() 做透明分流。
 */
object LanInviteCode {
    private const val SCHEME = "LAN"

    fun encode(gameId: String, ip: String, port: Int = LAN_PORT): String {
        val raw = "$SCHEME|$gameId|$ip|$port"
        return Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
    }

    /** @return Triple(gameId, ip, port)，格式错误时返回 null */
    fun decode(code: String): Triple<String, String, Int>? = runCatching {
        val raw = String(Base64.decode(code.trim(), Base64.URL_SAFE), Charsets.UTF_8)
        val parts = raw.split("|")
        if (parts.size == 4 && parts[0] == SCHEME) Triple(parts[1], parts[2], parts[3].toInt()) else null
    }.getOrNull()

    /** 快速判断是否为局域网邀请码（无需完整解码） */
    fun isLanCode(code: String): Boolean = runCatching {
        val raw = String(Base64.decode(code.trim(), Base64.URL_SAFE), Charsets.UTF_8)
        raw.startsWith("$SCHEME|")
    }.getOrElse { false }
}

// ── IP 地址工具 ────────────────────────────────────────────────────────────────

/**
 * 获取本机局域网 IPv4 地址（优先选非回环 WiFi/以太网接口）。
 *
 * 如果设备正在使用 VPN，可能返回 VPN 分配的地址，
 * 此时对端需要和本机在同一 VPN 网段才能连通。
 */
fun getLocalIpAddress(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces()
        ?.asSequence()
        ?.filter { !it.isLoopback && it.isUp }
        ?.flatMap { it.inetAddresses.asSequence() }
        ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        ?.hostAddress
}.getOrNull()

// ── LanGameBridge ─────────────────────────────────────────────────────────────

/**
 * 局域网直连桥接器：封装 WebSocket Server（房主）/ Client（加入方）。
 *
 * ## 回调说明
 * | 回调 | 触发方 | 含义 |
 * |---|---|---|
 * | [onMessage] | 任意 | 收到对方的 GameMsg |
 * | [onPeerConnected] | 房主 | 加入方 WebSocket 握手完成 |
 * | [onClientConnected] | 加入方 | 与房主 WebSocket 握手完成 |
 * | [onDisconnected] | 任意 | 连接断开 |
 *
 * ## 线程安全
 * 所有回调均在 Java-WebSocket 内部线程触发，调用者需自行 `launch` 到协程。
 */
class LanGameBridge(
    private val onMessage: (GameMsg) -> Unit,
    private val onPeerConnected: () -> Unit = {},
    private val onClientConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {},
) {
    private var server: InternalServer? = null
    private var client: InternalClient? = null

    /** 服务端缓存的对端连接（仅一个 Joiner） */
    @Volatile private var peerConn: WebSocket? = null

    // ── Host side ──────────────────────────────────────────────────────────────

    /** 房主：在 [port] 上启动 WebSocket 服务器 */
    fun startServer(port: Int = LAN_PORT) {
        server?.stop(200)
        InternalServer(port).also { server = it; it.start() }
    }

    // ── Joiner side ────────────────────────────────────────────────────────────

    /** 加入方：连接到房主的 ws://[ip]:[port] */
    fun connectToHost(ip: String, port: Int = LAN_PORT) {
        client?.close()
        InternalClient(URI("ws://$ip:$port")).also { client = it; it.connect() }
    }

    // ── Common ─────────────────────────────────────────────────────────────────

    /** 向对端发送消息（Server 模式广播，Client 模式直发） */
    fun send(msg: GameMsg) {
        val json = JsonCodec.encodeToString(msg)
        peerConn?.send(json)   // 房主 → Joiner
        client?.send(json)     // Joiner → 房主
    }

    /**
     * 重新拨号到指定主机（仅加入方模式有效；服务端本身就一直在监听，无需操作）。
     * 会先关闭旧连接，再建新的 WebSocket 客户端。
     * 连接成功后 [onClientConnected] 会照常触发。
     */
    fun reconnect(ip: String, port: Int = LAN_PORT) {
        client?.close()
        InternalClient(URI("ws://$ip:$port")).also { client = it; it.connect() }
    }

    /** 关闭所有连接，应在 ViewModel.onCleared() / reset() 中调用 */
    fun close() {
        server?.stop(200)
        client?.close()
        server = null
        client = null
        peerConn = null
    }

    // ── Internal WebSocketServer ───────────────────────────────────────────────

    private inner class InternalServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
        init {
            isReuseAddr = true
            isTcpNoDelay = true
            connectionLostTimeout = 30
        }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            peerConn = conn
            onPeerConnected()
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            if (peerConn == conn) { peerConn = null; onDisconnected() }
        }

        override fun onMessage(conn: WebSocket, message: String) {
            peerConn = conn
            runCatching { onMessage(JsonCodec.decodeFromString<GameMsg>(message)) }
        }

        override fun onMessage(conn: WebSocket, message: ByteBuffer) { /* 忽略二进制帧 */ }
        override fun onError(conn: WebSocket?, ex: Exception) {}
        override fun onStart() {}
    }

    // ── Internal WebSocketClient ───────────────────────────────────────────────

    private inner class InternalClient(uri: URI) : WebSocketClient(uri) {
        init { isTcpNoDelay = true; connectionLostTimeout = 30 }

        override fun onOpen(handshake: ServerHandshake) = onClientConnected()

        override fun onMessage(message: String) {
            runCatching { onMessage(JsonCodec.decodeFromString<GameMsg>(message)) }
        }

        override fun onClose(code: Int, reason: String, remote: Boolean) = onDisconnected()
        override fun onError(ex: Exception) {}
    }
}
