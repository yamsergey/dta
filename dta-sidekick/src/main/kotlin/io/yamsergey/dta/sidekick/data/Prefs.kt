package io.yamsergey.dta.sidekick.data

import android.content.Context
import android.content.SharedPreferences
import io.yamsergey.dta.protocol.data.Access
import io.yamsergey.dta.protocol.data.PrefEntry
import io.yamsergey.dta.protocol.data.PrefType
import io.yamsergey.dta.protocol.data.PrefsContent
import io.yamsergey.dta.protocol.data.StoreIds
import io.yamsergey.dta.protocol.data.StoreInfo
import io.yamsergey.dta.protocol.data.StoreKind
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Feature 027 (research R3): SharedPreferences files. Plain ones through the app's own cached
 * instance (`getSharedPreferences` hands back the object the app uses, so its listeners fire);
 * EncryptedSharedPreferences through the app's live instance (any master key), else opened with
 * the default master key.
 */
internal class Prefs(private val context: Context) {
    private val dir get() = File(context.applicationInfo.dataDir, "shared_prefs")

    private fun files(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }

    fun list(): List<StoreInfo> {
        val live = liveEncrypted()
        return files().map { f ->
            val name = f.name.removeSuffix(".xml")
            val encrypted = isEncrypted(f)
            val (access, note) = when {
                !encrypted -> Access.LIVE to "the app's own SharedPreferences: its listeners see writes"
                live?.containsKey(name) == true -> Access.LIVE to "the app's EncryptedSharedPreferences: decrypted and written with its own keys"
                else -> Access.FILE to "encrypted; the app has not opened it in this process" +
                    (if (live == null) " (or its instances cannot be seen: ${LiveObjects.reason})" else "") +
                    ": DTA tries the default master key (open the screen that uses it if that fails)"
            }
            val count = if (encrypted) null else runCatching { context.getSharedPreferences(name, Context.MODE_PRIVATE).all.size }.getOrNull()
            StoreInfo(StoreIds.of(StoreKind.PREFS, name), StoreKind.PREFS, name, f.path, f.length(), if (encrypted) "encrypted" else "plain", access, note, count)
        }
    }

    fun read(store: String): PrefsContent {
        val (prefs, access, note) = open(store)
        val entries = prefs.all.entries.filterNot { it.key.startsWith(KEYSET_PREFIX) }.map { (k, v) -> entry(k, v) }.sortedBy { it.key }
        return PrefsContent(store, entries, access, note)
    }

    fun set(store: String, key: String, value: String, type: PrefType?): String {
        val (prefs, _, _) = open(store)
        val t = type ?: prefs.all[key]?.let { entry(key, it).type } ?: PrefType.STRING
        val parsed = try { t.parseFor(key, value) } catch (e: IllegalArgumentException) { throw InvalidParamsException(e.message!!) }
        val editor = prefs.edit()
        when (t) {
            PrefType.STRING -> editor.putString(key, (parsed as JsonPrimitive).content)
            PrefType.INT -> editor.putInt(key, (parsed as JsonPrimitive).content.toInt())
            PrefType.LONG -> editor.putLong(key, (parsed as JsonPrimitive).content.toLong())
            PrefType.FLOAT -> editor.putFloat(key, (parsed as JsonPrimitive).content.toFloat())
            PrefType.BOOLEAN -> editor.putBoolean(key, (parsed as JsonPrimitive).content.toBoolean())
            PrefType.STRING_SET -> editor.putStringSet(key, (parsed as JsonArray).map { (it as JsonPrimitive).content }.toSet())
            else -> throw InvalidParamsException("SharedPreferences hold string, int, long, float, boolean and stringSet values; ${t.wireName} is for Preferences DataStore")
        }
        check(editor.commit()) { "the app's SharedPreferences refused the write (commit returned false)" }
        return "$key = $value (${t.wireName})"
    }

    fun remove(store: String, keys: List<String>): String {
        val (prefs, _, _) = open(store)
        val editor = prefs.edit()
        keys.forEach { editor.remove(it) }
        check(editor.commit()) { "the app's SharedPreferences refused the write (commit returned false)" }
        return "removed ${keys.joinToString()}"
    }

    private fun open(store: String): Triple<SharedPreferences, Access, String> {
        val (kind, name) = try { StoreIds.parse(store) } catch (e: IllegalArgumentException) { throw InvalidParamsException(e.message!!) }
        if (kind != StoreKind.PREFS) throw InvalidParamsException("$store is not a SharedPreferences file (prefs:<name>)")
        val file = File(dir, "$name.xml")
        if (!file.isFile) throw InvalidParamsException("no SharedPreferences $name in the app (data list shows them)")
        if (!isEncrypted(file)) return Triple(context.getSharedPreferences(name, Context.MODE_PRIVATE), Access.LIVE, "the app's own SharedPreferences")
        liveEncrypted()?.get(name)?.let { return Triple(it, Access.LIVE, "the app's EncryptedSharedPreferences") }
        val opened = try {
            openWithDefaultKey(name)
        } catch (t: Throwable) {
            throw IllegalStateException(
                "$name is encrypted and the app has not opened it in this process; the default master key does not open it " +
                    "(${t.javaClass.simpleName}${t.message?.let { ": $it" } ?: ""}). Open the screen that uses it, then read again",
            )
        }
        return Triple(opened, Access.FILE, "opened by DTA with the default master key")
    }

    /** The app's EncryptedSharedPreferences instances by file name; null when they cannot be seen. */
    private fun liveEncrypted(): Map<String, SharedPreferences>? =
        LiveObjects.of(ENCRYPTED, context.classLoader)?.mapNotNull { p ->
            val name = runCatching { Reflect.field(p, "mFileName") as String? }.getOrNull() ?: return@mapNotNull null
            name to (p as SharedPreferences)
        }?.toMap()

    private fun openWithDefaultKey(name: String): SharedPreferences {
        val loader = context.classLoader
        val masterKeyBuilder = Class.forName("androidx.security.crypto.MasterKey\$Builder", true, loader)
        val scheme = Class.forName("androidx.security.crypto.MasterKey\$KeyScheme", true, loader)
        val builder = masterKeyBuilder.getConstructor(Context::class.java).newInstance(context)
        masterKeyBuilder.getMethod("setKeyScheme", scheme).invoke(builder, scheme.getField("AES256_GCM").get(null))
        val masterKey = masterKeyBuilder.getMethod("build").invoke(builder)
        val esp = Class.forName(ENCRYPTED, true, loader)
        val keyScheme = Class.forName("$ENCRYPTED\$PrefKeyEncryptionScheme", true, loader)
        val valueScheme = Class.forName("$ENCRYPTED\$PrefValueEncryptionScheme", true, loader)
        val create = esp.methods.first { it.name == "create" && it.parameterTypes.size == 5 && it.parameterTypes[0] == Context::class.java }
        return Reflect.invoke(create, null, context, name, masterKey, keyScheme.getField("AES256_SIV").get(null), valueScheme.getField("AES256_GCM").get(null)) as SharedPreferences
    }

    companion object {
        const val ENCRYPTED = "androidx.security.crypto.EncryptedSharedPreferences"
        private const val KEYSET_PREFIX = "__androidx_security_crypto_encrypted_prefs_"

        fun isEncrypted(file: File): Boolean = runCatching { file.readText().contains(KEYSET_PREFIX) }.getOrDefault(false)

        fun entry(key: String, v: Any?): PrefEntry = when (v) {
            is String -> PrefEntry(key, PrefType.STRING, JsonPrimitive(v))
            is Int -> PrefEntry(key, PrefType.INT, JsonPrimitive(v))
            is Long -> PrefEntry(key, PrefType.LONG, JsonPrimitive(v))
            is Float -> PrefEntry(key, PrefType.FLOAT, JsonPrimitive(v))
            is Boolean -> PrefEntry(key, PrefType.BOOLEAN, JsonPrimitive(v))
            is Set<*> -> PrefEntry(key, PrefType.STRING_SET, JsonArray(v.map { JsonPrimitive(it.toString()) }))
            else -> PrefEntry(key, PrefType.STRING, JsonPrimitive(v?.toString()) as JsonElement)
        }
    }
}
