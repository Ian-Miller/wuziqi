package io.github.ian_miller.wuziqi.nostr

import android.content.SharedPreferences
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import java.math.BigInteger
import java.security.SecureRandom

// ── secp256k1 曲线参数（懒加载，进程内单例）──────────────────────────────────

internal val SECP256K1: ECDomainParameters by lazy {
    val p = CustomNamedCurves.getByName("secp256k1")
    ECDomainParameters(p.curve, p.g, p.n, p.h)
}

/** BigInteger → 正好 32 字节（左补零或去除前导 0x00） */
internal fun BigInteger.toBytes32(): ByteArray {
    val b = toByteArray()
    return when {
        b.size == 32 -> b
        b.size >  32 -> b.copyOfRange(b.size - 32, b.size) // 去正数前导 0x00
        else         -> ByteArray(32 - b.size) + b          // 左补零
    }
}

// ── Hex 工具 ──────────────────────────────────────────────────────────────────

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(length / 2) { i ->
        ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte()
    }
}

// ── 密钥对 ────────────────────────────────────────────────────────────────────

/**
 * Nostr 密钥对（secp256k1）
 *
 * - [privateKey] 32 字节随机标量
 * - [publicKey]  32 字节 x-only 公钥（BIP340 格式，与 Nostr NIP-01 兼容）
 */
data class NostrKeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray, // x-only 32 bytes
) {
    val privateKeyHex: String get() = privateKey.toHex()
    val publicKeyHex: String  get() = publicKey.toHex()

    companion object {
        /** 生成新密钥对（BouncyCastle secp256k1） */
        fun generate(): NostrKeyPair {
            val rng = SecureRandom()
            val sk  = ByteArray(32)
            var d: BigInteger
            do {
                rng.nextBytes(sk)
                d = BigInteger(1, sk)
            } while (d == BigInteger.ZERO || d >= SECP256K1.n)
            val point = SECP256K1.g.multiply(d).normalize()
            val xOnly = point.xCoord.toBigInteger().toBytes32()
            return NostrKeyPair(sk.copyOf(), xOnly)
        }

        /** 从持久化的 hex 私钥恢复 */
        fun fromPrivHex(hex: String): NostrKeyPair {
            val sk    = hex.hexToBytes()
            val point = SECP256K1.g.multiply(BigInteger(1, sk)).normalize()
            val xOnly = point.xCoord.toBigInteger().toBytes32()
            return NostrKeyPair(sk, xOnly)
        }
    }

    override fun equals(other: Any?) =
        other is NostrKeyPair && privateKey.contentEquals(other.privateKey)
    override fun hashCode() = privateKey.contentHashCode()
}

// ── 持久化存储 ─────────────────────────────────────────────────────────────────

/**
 * 将 Nostr 私钥持久化到 SharedPreferences。
 * 每台设备一个固定密钥对，首次使用时自动生成。
 */
object NostrKeyStore {
    private const val KEY_PRIV = "nostr_private_key_hex"

    fun load(prefs: SharedPreferences): NostrKeyPair {
        val hex = prefs.getString(KEY_PRIV, null)
        return if (hex != null) {
            NostrKeyPair.fromPrivHex(hex)
        } else {
            val kp = NostrKeyPair.generate()
            prefs.edit().putString(KEY_PRIV, kp.privateKeyHex).apply()
            kp
        }
    }
}
