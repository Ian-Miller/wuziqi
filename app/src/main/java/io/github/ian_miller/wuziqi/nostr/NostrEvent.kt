package io.github.ian_miller.wuziqi.nostr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.math.BigInteger
import java.security.MessageDigest

// ── 数据模型 ───────────────────────────────────────────────────────────────────

/**
 * Nostr 事件（NIP-01 格式）
 *
 * 所有字段与 Nostr 协议字段名完全一致，供 JSON 序列化直接使用。
 */
@Serializable
data class NostrEvent(
    val id: String,         // SHA256(canonical JSON)
    val pubkey: String,     // 发送方 x-only 公钥 hex
    val created_at: Long,   // Unix 时间戳（秒）
    val kind: Int,          // 事件类型
    val tags: List<List<String>>,
    val content: String,    // 加密后的游戏消息
    val sig: String,        // Schnorr 签名 hex（64 字节）
)

// ── 签名与构建 ─────────────────────────────────────────────────────────────────

/**
 * 构建并签名一个 Nostr 事件。
 *
 * 1. 按 NIP-01 规范序列化为 canonical JSON 数组
 * 2. 计算 SHA256 作为事件 ID
 * 3. 用 Schnorr（BIP340）对 ID 签名
 */
fun buildAndSign(
    keyPair: NostrKeyPair,
    kind: Int,
    tags: List<List<String>>,
    content: String,
): NostrEvent {
    val now = System.currentTimeMillis() / 1000L
    val pubkeyHex = keyPair.publicKeyHex

    // NIP-01 canonical serialization:
    // [0, pubkey, created_at, kind, tags, content]
    val tagsJson = buildJsonArray {
        tags.forEach { tag ->
            add(buildJsonArray { tag.forEach { add(it) } })
        }
    }
    val canonical = buildJsonArray {
        add(0); add(pubkeyHex); add(now); add(kind); add(tagsJson); add(content)
    }

    val idBytes  = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toString().toByteArray(Charsets.UTF_8))
    val sigBytes = schnorrSign(idBytes, keyPair.privateKey)

    return NostrEvent(
        id         = idBytes.toHex(),
        pubkey     = pubkeyHex,
        created_at = now,
        kind       = kind,
        tags       = tags,
        content    = content,
        sig        = sigBytes.toHex(),
    )
}

// ── BIP340 Schnorr 签名（BouncyCastle secp256k1）─────────────────────────────

/**
 * BIP340 Schnorr 签名
 *
 * 1. 若 P.y 为奇数，取 d = n − d（BIP340 要求公钥 y 坐标为偶数）
 * 2. 确定性 nonce k' = H_nonce(d_bytes ‖ P_x ‖ msg) mod n
 * 3. 若 R.y 为奇数，取 k = n − k'
 * 4. e = H_challenge(R_x ‖ P_x ‖ msg) mod n
 * 5. sig = R_x ‖ (k + e·d) mod n
 */
fun schnorrSign(msg32: ByteArray, privKey: ByteArray): ByteArray {
    val G = SECP256K1.g
    val n = SECP256K1.n

    val d0 = BigInteger(1, privKey)
    val P  = G.multiply(d0).normalize()

    // 若 P.y 为奇数则取模逆（BIP340）
    val d      = if (P.yCoord.toBigInteger().testBit(0)) n.subtract(d0) else d0
    val dBytes = d.toBytes32()
    val Px     = P.xCoord.toBigInteger().toBytes32()

    // 确定性 nonce（游戏场景无需 aux_rand）
    val kPrime = BigInteger(1, taggedHash("BIP0340/nonce", dBytes + Px + msg32)).mod(n)
    val R      = G.multiply(kPrime).normalize()
    val k      = if (R.yCoord.toBigInteger().testBit(0)) n.subtract(kPrime) else kPrime
    val Rx     = R.xCoord.toBigInteger().toBytes32()

    val e = BigInteger(1, taggedHash("BIP0340/challenge", Rx + Px + msg32)).mod(n)
    val s = k.add(e.multiply(d)).mod(n)
    return Rx + s.toBytes32()
}

// ── 工具函数 ──────────────────────────────────────────────────────────────────

/** BIP340 Tagged Hash: SHA256(SHA256(tag) ‖ SHA256(tag) ‖ data) */
private fun taggedHash(tag: String, data: ByteArray): ByteArray {
    val tagHash = sha256(tag.toByteArray(Charsets.UTF_8))
    return sha256(tagHash + tagHash + data)
}

internal fun sha256(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)
