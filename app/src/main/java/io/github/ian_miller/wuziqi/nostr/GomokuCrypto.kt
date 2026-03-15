package io.github.ian_miller.wuziqi.nostr

import android.util.Base64
import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 游戏消息加密（NIP-04 风格）
 *
 * ## 流程
 * 1. ECDH(ourPrivKey, theirXOnlyPubKey) → 32 字节 x 坐标（NIP-04：无哈希，直接用作 AES 密钥）
 * 2. AES-256-CBC + 随机 IV 加密明文
 * 3. 密文格式：`base64(ciphertext)?iv=base64(iv)`
 *
 * ## x-only 公钥处理
 * 用 `02 || x` 假设偶数 y 重建压缩公钥。ECDH 结果仅取 x 坐标，与 y 奇偶无关，
 * 双方推导出相同密钥。
 */
object GomokuCrypto {

    fun encrypt(ourPrivKey: ByteArray, theirXOnlyPubkey: ByteArray, plaintext: String): String {
        val key    = sharedKey(ourPrivKey, theirXOnlyPubkey)
        val iv     = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return "${ct.toBase64()}?iv=${iv.toBase64()}"
    }

    fun decrypt(ourPrivKey: ByteArray, theirXOnlyPubkey: ByteArray, ciphertext: String): String {
        val parts  = ciphertext.split("?iv=")
        require(parts.size == 2) { "密文格式无效，缺少 iv 分隔符" }
        val key    = sharedKey(ourPrivKey, theirXOnlyPubkey)
        val iv     = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(Base64.decode(parts[0], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    // ── 私有辅助 ───────────────────────────────────────────────────────────────

    /**
     * NIP-04 ECDH 共享密钥：`ECDH(ourPriv, theirPub).x`（32 字节，无哈希）
     *
     * 直接使用 BouncyCastle secp256k1 曲线点乘法，不经过 JCA/JCE。
     */
    private fun sharedKey(privKey: ByteArray, xOnlyPub: ByteArray): ByteArray {
        val compressed  = byteArrayOf(0x02.toByte()) + xOnlyPub   // 33 字节压缩公钥
        val theirPoint  = SECP256K1.curve.decodePoint(compressed).normalize()
        val sharedPoint = theirPoint.multiply(BigInteger(1, privKey)).normalize()
        return sharedPoint.xCoord.toBigInteger().toBytes32()       // NIP-04：原始 x 坐标
    }

    private fun ByteArray.toBase64(): String =
        Base64.encodeToString(this, Base64.NO_WRAP)
}
