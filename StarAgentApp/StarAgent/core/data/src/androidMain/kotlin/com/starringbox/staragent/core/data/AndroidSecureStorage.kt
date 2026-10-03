package com.starringbox.staragent.core.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android 硬件级密钥库（AndroidKeyStore）安全加密存储。
 *
 * 专门保护用户配置的模型 API Key、语音凭据等高敏感凭证：
 * 1. 采用 AES/GCM/NoPadding 256 位认证加密；
 * 2. 密钥托管在系统硬件安全模块（TEE/SE），App 自身和系统外部均无法导出私密密钥；
 * 3. 向后完全兼容：读取未带 enc:v1: 前缀的旧存档明文时原样返回，并可在下次保存时透明升级；
 * 4. 容错回退：在无 KeyStore 支持的极端精简测试环境下平滑降级，绝不阻断 App 正常启动。
 */
internal object AndroidSecureStorage {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "StarAgentMasterKey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12
    private const val ENC_PREFIX = "enc:v1:"

    private var cachedKey: SecretKey? = null

    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            cachedKey = existing
            return existing
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        val created = generator.generateKey()
        cachedKey = created
        return created
    }

    /** 是否属于需要硬件加密保护的敏感凭据键名（如 apikey:*, speechKey:*, custom:*:key）。 */
    fun isSensitive(key: String): Boolean =
        key.contains("key", ignoreCase = true)

    fun encrypt(plainText: String): String {
        if (plainText.isBlank()) return plainText
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

            ENC_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Throwable) {
            // 极端测试环境或 KeyStore 故障时兜底，确保流程不中断
            plainText
        }
    }

    fun decrypt(stored: String): String {
        if (!stored.startsWith(ENC_PREFIX)) {
            // 向后兼容：读取升级前明文保存的历史凭证
            return stored
        }
        return try {
            val combined = Base64.decode(stored.removePrefix(ENC_PREFIX), Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return stored
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val cipherText = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (_: Throwable) {
            stored
        }
    }
}
