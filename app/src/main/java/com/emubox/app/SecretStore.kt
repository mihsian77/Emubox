package com.emubox.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感字符串（API Key / Token）的本地加密存储。
 *
 * 为什么不用 EncryptedSharedPreferences：androidx.security 的 crypto 库长期停留在
 * 1.0.0（Tink 依赖重、targetSdk 37 下仍有兼容告警），而这里只需要「一个字符串」的
 * 加解密，直接用 Android Keystore 的 AES/GCM 更轻、无额外依赖。
 *
 * 密钥存在 AndroidKeyStore（不可导出、随 App 卸载销毁），密文存普通
 * SharedPreferences。备份到别的设备因缺密钥而无法解密，等价于「换机即失效」，
 * 安全预期正确（旧版明文值会一次性迁移并覆写为密文）。
 */
object SecretStore {

    private const val TAG = "EmuBoxSecret"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "emubox_secret_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("emu_hub_secrets", Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: run {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
                generator.init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generator.generateKey()
            }
    }.onFailure { Log.w(TAG, "Keystore 初始化失败，敏感值将不落盘", it) }.getOrNull()

    /** 进程内缓存：Keystore 每次解密约 1-3ms，翻译/API 请求会高频读，缓存避免抖动 */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun put(context: Context, key: String, value: String) {
        cache[key] = value
        val target = prefs(context)
        if (value.isBlank()) {
            target.edit().remove(key).apply()
            return
        }
        val keyObj = secretKey()
        if (keyObj == null) {
            // 极端情况（Keystore 不可用）：宁可不存，也不落明文
            target.edit().remove(key).apply()
            return
        }
        val encrypted = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keyObj)
            val iv = cipher.iv
            val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + body, Base64.NO_WRAP)
        }.getOrNull()
        if (encrypted == null) {
            target.edit().remove(key).apply()
        } else {
            target.edit().putString(key, encrypted).apply()
        }
    }

    fun get(context: Context, key: String): String {
        cache[key]?.let { return it }
        val raw = prefs(context).getString(key, null) ?: return ""
        val keyObj = secretKey() ?: return ""
        return runCatching {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            if (bytes.size <= IV_BYTES) return@runCatching ""
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keyObj,
                GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES)
            )
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
                .also { cache[key] = it }
        }.onFailure { Log.w(TAG, "解密失败（密钥已失效），按未配置处理", it) }.getOrDefault("")
    }

    /** 一次性迁移：把旧的明文值搬进加密存储并抹掉明文 */
    fun migrateLegacy(
        context: Context,
        legacyPrefs: SharedPreferences,
        legacyKey: String,
        newKey: String
    ) {
        val plain = legacyPrefs.getString(legacyKey, null) ?: return
        if (plain.isNotBlank() && get(context, newKey).isBlank()) {
            put(context, newKey, plain)
        }
        legacyPrefs.edit().remove(legacyKey).apply()
    }
}
