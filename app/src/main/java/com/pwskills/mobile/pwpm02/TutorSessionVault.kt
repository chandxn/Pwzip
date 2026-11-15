package com.pwskills.mobile.pwpm02

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.JavascriptInterface
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the tutor bearer token encrypted with a non-exportable Android Keystore key. */
class TutorSessionVault(context: Context) {
    private val preferences = context.getSharedPreferences("pw_tutor_session_v1", Context.MODE_PRIVATE)

    @JavascriptInterface
    @Synchronized
    fun getToken(serverUrl: String): String {
        val server = TutorSessionPolicy.canonicalHttpsServer(serverUrl) ?: return ""
        if (preferences.getString("server", null) != server) return ""
        return try {
            val iv = Base64.decode(preferences.getString("iv", ""), Base64.NO_WRAP)
            val ciphertext = Base64.decode(preferences.getString("ciphertext", ""), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(server.toByteArray(StandardCharsets.UTF_8))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    @JavascriptInterface
    @Synchronized
    fun putToken(serverUrl: String, token: String): Boolean {
        val server = TutorSessionPolicy.canonicalHttpsServer(serverUrl) ?: return false
        if (token.isBlank() || token.length > 4096) return false
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(server.toByteArray(StandardCharsets.UTF_8))
            val ciphertext = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
            preferences.edit()
                .putString("server", server)
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    @Synchronized
    fun clearToken() {
        preferences.edit().clear().commit()
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "pw_tutor_session_aes_v1"
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }
}

internal object TutorSessionPolicy {
    fun canonicalHttpsServer(serverUrl: String): String? {
        return try {
            val uri = URI(serverUrl.trim()).normalize()
            val host = uri.host ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
            val path = (uri.rawPath ?: "").trimEnd('/')
            "https://${host.lowercase()}$port$path"
        } catch (_: Exception) {
            null
        }
    }
}
