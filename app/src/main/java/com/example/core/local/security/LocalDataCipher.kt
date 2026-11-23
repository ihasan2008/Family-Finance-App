package com.example.core.local.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption abstraction for sensitive fields persisted in the local Room/SQLite store.
 * Sensitive text columns use an `enc1:<ivB64>:<ciphertextB64>` envelope so legacy unprefixed rows
 * remain readable while all newly written or updated rows are encrypted with AES-256-GCM.
 */
interface LocalDataCipher {
    val engineName: String
    val isHardwareKeystoreActive: Boolean

    fun encrypt(plainText: String): String
    fun decrypt(storedValue: String): String

    fun encryptNullable(plainText: String?): String? =
        plainText?.takeIf { it.isNotEmpty() }?.let(::encrypt)

    fun decryptNullable(storedValue: String?): String? =
        storedValue?.let(::decrypt)

    fun isEncryptedEnvelope(value: String?): Boolean =
        value != null && value.startsWith(ENVELOPE_PREFIX)

    companion object {
        const val ENVELOPE_PREFIX = "enc1:"
    }
}

/**
 * AES-256-GCM cipher backed by `AndroidKeyStore` when running on Android devices/emulators,
 * with an automatic process-scoped AES-256 software key fallback for pure JVM / Robolectric
 * environments that do not register the `AndroidKeyStore` JCE provider.
 */
class AndroidKeyStoreCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : LocalDataCipher {

    private val secureRandom = SecureRandom()

    @Volatile
    private var keystoreBacked: Boolean = false

    @Volatile
    private var cachedKey: SecretKey? = null

    override val isHardwareKeystoreActive: Boolean
        get() {
            ensureKey()
            return keystoreBacked
        }

    override val engineName: String
        get() = if (isHardwareKeystoreActive) {
            "AES-256-GCM (AndroidKeyStore)"
        } else {
            "AES-256-GCM (JVM Test Fallback)"
        }

    override fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        if (plainText.startsWith(LocalDataCipher.ENVELOPE_PREFIX)) return plainText
        val key = ensureKey()
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
        return "${LocalDataCipher.ENVELOPE_PREFIX}$ivB64:$ctB64"
    }

    override fun decrypt(storedValue: String): String {
        if (storedValue.isEmpty()) return ""
        if (!storedValue.startsWith(LocalDataCipher.ENVELOPE_PREFIX)) {
            return storedValue
        }
        return runCatching {
            val payload = storedValue.removePrefix(LocalDataCipher.ENVELOPE_PREFIX)
            val splitIdx = payload.indexOf(':')
            if (splitIdx <= 0) return ""
            val iv = Base64.decode(payload.substring(0, splitIdx), Base64.NO_WRAP)
            val cipherBytes = Base64.decode(payload.substring(splitIdx + 1), Base64.NO_WRAP)
            val key = ensureKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrElse { "" }
    }

    private fun ensureKey(): SecretKey {
        cachedKey?.let { return it }
        synchronized(lock) {
            cachedKey?.let { return it }
            val loaded = tryGetOrCreateAndroidKeyStoreKey() ?: fallbackSoftwareKey()
            cachedKey = loaded
            return loaded
        }
    }

    private fun tryGetOrCreateAndroidKeyStoreKey(): SecretKey? {
        return runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
            val existing = keyStore.getKey(keyAlias, null) as? SecretKey
            if (existing != null) {
                keystoreBacked = true
                return existing
            }
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE_PROVIDER
            )
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(false)
                .build()
            keyGenerator.init(spec)
            keystoreBacked = true
            keyGenerator.generateKey()
        }.getOrNull()
    }

    private fun fallbackSoftwareKey(): SecretKey {
        keystoreBacked = false
        synchronized(fallbackLock) {
            sharedFallbackKey?.let { return it }
            val raw = ByteArray(AES_KEY_SIZE_BYTES).also { secureRandom.nextBytes(it) }
            val generated = SecretKeySpec(raw, "AES")
            sharedFallbackKey = generated
            return generated
        }
    }

    companion object {
        private const val DEFAULT_KEY_ALIAS = "family_finance_local_aes_v1"
        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val AES_KEY_SIZE_BITS = 256
        private const val AES_KEY_SIZE_BYTES = 32

        private val lock = Any()
        private val fallbackLock = Any()

        @Volatile
        private var sharedFallbackKey: SecretKey? = null
    }
}
