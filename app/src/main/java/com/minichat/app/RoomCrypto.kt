package com.minichat.app

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Из названия (и пароля) комнаты получаем:
 *  - тему (topic) на сервере — одинаковую у всех, кто вписал те же данные;
 *  - для приватной комнаты ещё и ключ шифрования, чтобы сообщения нельзя было прочитать без пароля.
 */
object RoomCrypto {
    class Keys(val topic: String, val aesKey: ByteArray?)

    private val rnd = SecureRandom()

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun keysFor(name: String, password: String): Keys {
        val n = name.trim().lowercase()
        if (password.isEmpty()) {
            val d = MessageDigest.getInstance("SHA-256")
                .digest("minichat:open:$n".toByteArray(Charsets.UTF_8))
            return Keys("mo" + hex(d).take(30), null)
        }
        // Пароль превращаем в «безопасные» ASCII-символы, чтобы на разных версиях Android получался один и тот же ключ
        val safePass = hex(password.toByteArray(Charsets.UTF_8)).toCharArray()
        val salt = "minichat:salt:$n".toByteArray(Charsets.UTF_8)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(safePass, salt, 20000, 512))
            .encoded
        // первые 32 байта — ключ шифрования, следующие 16 — для названия темы
        return Keys("mp" + hex(raw.copyOfRange(32, 48)), raw.copyOfRange(0, 32))
    }

    fun encrypt(key: ByteArray, plain: String): String {
        val iv = ByteArray(12)
        rnd.nextBytes(iv)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    /** Возвращает расшифрованный текст или null, если ключ не подошёл. */
    fun decrypt(key: ByteArray, b64: String): String? {
        return try {
            val all = Base64.decode(b64, Base64.NO_WRAP)
            if (all.size < 12 + 16) return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, all.copyOfRange(0, 12)))
            String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
