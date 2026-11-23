package de.meindaten.digitalisierer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Entsperren per Fingerabdruck:
 * Der Tresor-Schlüssel wird mit einem Schlüssel aus dem Android-Keystore verschlüsselt,
 * der sich nur nach erfolgreichem Fingerabdruck benutzen lässt und das Gerät nie verlässt.
 * Neue Fingerabdrücke im System machen ihn ungültig → dann wieder Passwort nötig.
 */
object Bio {
    private const val ALIAS = "mdd_bio_key"
    private const val PREFS = "bio"

    fun available(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun enabled(ctx: Context): Boolean = prefs(ctx).contains("ct")

    fun disable(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    /** Speichert den Tresor-Schlüssel nach Bestätigung per Fingerabdruck. */
    fun enable(act: FragmentActivity, raw: ByteArray, done: (String) -> Unit) {
        try {
            disable(act)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, newKey())
            prompt(act, c, "Fingerabdruck einrichten", { ok ->
                val ct = ok.doFinal(raw)
                prefs(act).edit()
                    .putString("iv", Base64.encodeToString(ok.iv, Base64.NO_WRAP))
                    .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                    .apply()
                done("OK")
            }, done)
        } catch (e: Exception) {
            done("FEHLER:${e.message}")
        } finally {
            raw.fill(0)
        }
    }

    /** Gibt den Tresor-Schlüssel (Base64) nach Fingerabdruck zurück. */
    fun unlock(act: FragmentActivity, done: (String) -> Unit) {
        val p = prefs(act)
        val iv = p.getString("iv", null)
        val ct = p.getString("ct", null)
        val key = runCatching { keyStore().getKey(ALIAS, null) as? SecretKey }.getOrNull()
        if (iv == null || ct == null || key == null) { disable(act); done("INVALID"); return }
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            prompt(act, c, "Entsperren", { ok ->
                val raw = ok.doFinal(Base64.decode(ct, Base64.NO_WRAP))
                done("OK:" + Base64.encodeToString(raw, Base64.NO_WRAP))
                raw.fill(0)
            }, done)
        } catch (e: KeyPermanentlyInvalidatedException) {
            disable(act); done("INVALID")
        } catch (e: Exception) {
            done("FEHLER:${e.message}")
        }
    }

    private fun prompt(
        act: FragmentActivity, cipher: Cipher, title: String,
        onOk: (Cipher) -> Unit, onFail: (String) -> Unit
    ) {
        val p = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher
                if (c == null) { onFail("FEHLER:Kein Schlüssel"); return }
                try { onOk(c) } catch (e: Exception) { onFail("FEHLER:${e.message}") }
            }

            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                val cancel = code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    code == BiometricPrompt.ERROR_USER_CANCELED || code == BiometricPrompt.ERROR_CANCELED
                onFail(if (cancel) "CANCEL" else "FEHLER:$msg")
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Mein Daten-Digitalisierer")
            .setNegativeButtonText("Passwort verwenden")
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()
        p.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun newKey(): SecretKey {
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        return kg.generateKey()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
