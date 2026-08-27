package com.voiceconfig.app.remote

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

data class SshManagedKey(
    val id: String,
    val name: String,
    val type: String,
    val privateKey: String,
    val publicKey: String,
    val createdAt: Long,
)

/**
 * SSH 密钥库：保存命名密钥，私钥使用 Android Keystore 加密。
 * 支持多密钥、重命名、删除。
 */
@Singleton
class SshKeyStore @Inject constructor(
    @ApplicationContext context: Context,
    private val credentialStore: SshCredentialStore,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("voiceconfig_ssh_keys", Context.MODE_PRIVATE)

    fun list(): List<SshManagedKey> {
        val raw = prefs.getString("keys", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val private = credentialStore.decryptText(obj.optString("private", "")) ?: continue
                add(
                    SshManagedKey(
                        id = obj.optString("id"),
                        name = obj.optString("name", "未命名"),
                        type = obj.optString("type", "unknown"),
                        privateKey = private,
                        publicKey = obj.optString("public", ""),
                        createdAt = obj.optLong("createdAt", 0),
                    ),
                )
            }
        }
    }

    fun get(id: String): SshManagedKey? = list().firstOrNull { it.id == id }

    fun save(name: String, type: String, privateKey: String, publicKey: String): SshManagedKey {
        val key = SshManagedKey(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "$type 密钥" },
            type = type,
            privateKey = privateKey,
            publicKey = publicKey,
            createdAt = System.currentTimeMillis(),
        )
        val current = list().filterNot { it.id == key.id }
        writeAll(current + key)
        return key
    }

    fun delete(id: String) {
        writeAll(list().filterNot { it.id == id })
    }

    fun rename(id: String, name: String) {
        val current = list()
        val updated = current.map {
            if (it.id == id) it.copy(name = name.ifBlank { it.name }) else it
        }
        writeAll(updated)
    }

    private fun writeAll(keys: List<SshManagedKey>) {
        val arr = JSONArray()
        keys.forEach { k ->
            val encrypted = credentialStore.encryptText(k.privateKey) ?: ""
            arr.put(
                JSONObject()
                    .put("id", k.id)
                    .put("name", k.name)
                    .put("type", k.type)
                    .put("private", encrypted)
                    .put("public", k.publicKey)
                    .put("createdAt", k.createdAt),
            )
        }
        prefs.edit().putString("keys", arr.toString()).apply()
    }
}
