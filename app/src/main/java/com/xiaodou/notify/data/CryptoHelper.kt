package com.xiaodou.notify.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 基于 Android Keystore 的 AES-256-GCM 本地加密工具。
 * 密钥由系统 Keystore 保管，不可导出；满足 PRD「本地加密存储、数据不出设备」。
 */
object CryptoHelper {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "xiaodou_aes_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        kg.init(spec)
        return kg.generateKey()
    }

    /**
     * 返回 Base64(IV + 密文)。加密失败抛异常，由调用方拒绝落库，
     * 绝不以明文形式写入数据库（隐私红线 FR-P1）。
     */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** 兼容旧版本可能存在的 PLAIN: 明文数据；新数据绝不会产生该前缀 */
    fun decrypt(cipherText: String): String {
        if (cipherText.isEmpty()) return ""
        if (cipherText.startsWith("PLAIN:")) return cipherText.removePrefix("PLAIN:")
        return try {
            val raw = Base64.decode(cipherText, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_LEN)
            val ct = raw.copyOfRange(IV_LEN, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            "[内容已加密，当前无法解密]"
        }
    }
}
