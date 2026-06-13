package org.msgat.core.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.jcajce.provider.digest.Blake2b
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {

    init {
        // 注册 BouncyCastle
        Security.addProvider(BouncyCastleProvider())
    }

    /**
     * 计算信箱 ID：Blake2b512(UUID) -> 截取转 64位 Hex
     */
    fun calculateMailboxId(uuid: String): String {
        val digest = Blake2b.Blake2b512()
        val hash = digest.digest(uuid.toByteArray())
        // 取前32字节转成64位Hex字符串
        return hash.take(32).joinToString("") { "%02x".format(it) }
    }

    /**
     * 对服务端 Nonce 进行签名
     * 返回 Pair: <公钥(32 bytes), 签名结果(64 bytes)>
     */

    fun signChallenge(privateKey: Ed25519PrivateKeyParameters, nonce: ByteArray): Pair<ByteArray, ByteArray> {
        // 由私钥推导出公钥
        val publicKey = privateKey.generatePublicKey().encoded

        // 对乱码进行签名
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(nonce, 0, nonce.size)
        val signature = signer.generateSignature()

        // 返回：[公钥(32字节) + 签名(64字节)]
        return Pair(publicKey, signature)
    }

    // --- AES-GCM 加解密部分 (用于与朋友的端到端加密) ---
    // 简易实现：使用双方 UUID 的组合计算出对等密钥
    private fun getSymmetricKey(myUuid: String, targetUuid: String): SecretKeySpec {
        val sorted = listOf(myUuid, targetUuid).sorted()
        val combined = "${sorted[0]}|MSGAT|${sorted[1]}"
        val digest = Blake2b.Blake2b256()
        return SecretKeySpec(digest.digest(combined.toByteArray()), "AES")
    }

    fun encryptPayload(myUuid: String, targetUuid: String, iv: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val key = getSymmetricKey(myUuid, targetUuid)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        val cipherTextWithTag = cipher.doFinal(plaintext)

        // GCM 的特点是最后的 16 字节是 Auth Tag
        val cipherText = cipherTextWithTag.copyOfRange(0, cipherTextWithTag.size - 16)
        val authTag = cipherTextWithTag.copyOfRange(cipherTextWithTag.size - 16, cipherTextWithTag.size)
        return Pair(cipherText, authTag)
    }

    fun decryptPayload(myUuid: String, targetUuid: String, iv: ByteArray, cipherText: ByteArray, authTag: ByteArray): ByteArray {
        val key = getSymmetricKey(myUuid, targetUuid)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        // 将密文和 Tag 拼接回去让 Cipher 解密
        val combined = cipherText + authTag
        return cipher.doFinal(combined)
    }
}