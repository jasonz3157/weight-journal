package com.jasonz3157.weightjournal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class JournalStore(context: Context) {
    private val prefs = context.getSharedPreferences("journal", Context.MODE_PRIVATE)

    fun readJournal(): Journal = prefs.getString("journal", null)?.let(Journal::parse) ?: Journal()

    fun saveJournal(journal: Journal) {
        prefs.edit().putString("journal", journal.toJson()).apply()
    }

    fun readDav(): DavSettings? {
        val stored = prefs.getString("dav", null) ?: return null
        val data = JSONObject(stored)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(data.getString("iv"))))
        val secret = JSONObject(cipher.doFinal(decode(data.getString("payload"))).toString(Charsets.UTF_8))
        return DavSettings.validate(secret.getString("address"), secret.getString("username"), secret.getString("password"))
    }

    fun saveDav(settings: DavSettings?) {
        val edit = prefs.edit()
        if (settings == null) {
            edit.remove("dav")
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val secret = JSONObject()
                .put("address", settings.address.toString())
                .put("username", settings.username)
                .put("password", settings.password)
            val stored = JSONObject()
                .put("iv", encode(cipher.iv))
                .put("payload", encode(cipher.doFinal(secret.toString().toByteArray(Charsets.UTF_8))))
            edit.putString("dav", stored.toString())
        }
        check(edit.commit()) { "无法保存 WebDAV 设置，请检查设备存储。" }
    }

    private fun key(): SecretKey {
        val alias = "weight-journal-webdav"
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keystore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
}
