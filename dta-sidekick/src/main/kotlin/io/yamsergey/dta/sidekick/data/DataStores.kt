package io.yamsergey.dta.sidekick.data

import android.content.Context
import io.yamsergey.dta.protocol.data.Access
import io.yamsergey.dta.protocol.data.PrefEntry
import io.yamsergey.dta.protocol.data.PrefType
import io.yamsergey.dta.protocol.data.PreferencesCodec
import io.yamsergey.dta.protocol.data.PrefsContent
import io.yamsergey.dta.protocol.data.ProtoContent
import io.yamsergey.dta.protocol.data.StoreIds
import io.yamsergey.dta.protocol.data.StoreInfo
import io.yamsergey.dta.protocol.data.StoreKind
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Base64

/**
 * Feature 027 (research R4): DataStore files under `files/datastore/`. Read from the file (the
 * store writes it atomically); written through the app's live DataStore when it has one (its
 * `data` flow emits), else the file is rewritten and the app reads it on next open.
 */
internal class DataStores(private val context: Context) {
    private val dir get() = File(context.filesDir, "datastore")

    private fun files(): List<File> = dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }.orEmpty().sortedBy { it.name }

    fun list(): List<StoreInfo> {
        val live = live()
        return files().map { f ->
            val prefs = f.name.endsWith(PREFS_SUFFIX)
            val l = live?.get(Databases.canonical(f.path))
            val (access, note) = when {
                l != null -> Access.LIVE to "read from the file; written through the app's DataStore (its data flow emits)"
                live == null -> Access.FILE to "read and written as a file: the app's DataStore instances cannot be seen (${LiveObjects.reason}); the app reads a write on next open"
                else -> Access.FILE to "the app has no DataStore on it in this process: read and written as a file, the app reads a write on next open"
            }
            val keys = if (prefs) runCatching { PreferencesCodec.decode(f.readBytes()).size }.getOrNull() else null
            StoreInfo(StoreIds.of(StoreKind.DATASTORE, f.name), StoreKind.DATASTORE, f.name, f.path, f.length(), if (prefs) "preferences" else "proto", access, note, keys)
        }
    }

    fun readPrefs(store: String): PrefsContent {
        val (file, live) = locate(store, preferences = true)
        val entries = try { PreferencesCodec.decode(file.readBytes()) } catch (e: IllegalArgumentException) { throw IllegalStateException("${file.name}: ${e.message}") }
        return PrefsContent(store, entries, if (live != null) Access.LIVE else Access.FILE, if (live != null) "read from the file the app's DataStore writes" else "read from the file")
    }

    fun setPref(store: String, key: String, value: String, type: PrefType?): Pair<String, Access> {
        val (file, live) = locate(store, preferences = true)
        val current = runCatching { PreferencesCodec.decode(file.readBytes()) }.getOrDefault(emptyList())
        val t = type ?: current.firstOrNull { it.key == key }?.type ?: PrefType.STRING
        val parsed = try { t.parseFor(key, value) } catch (e: IllegalArgumentException) { throw InvalidParamsException(e.message!!) }
        val change = "$key = $value (${t.wireName})"
        if (live != null) {
            updatePreferences(live) { mutable, keyOf -> Reflect.call(mutable, "set", keyOf(key), javaValue(t, parsed)) }
            return change to Access.LIVE
        }
        writeAtomically(file, PreferencesCodec.encode(current.filterNot { it.key == key } + PrefEntry(key, t, parsed)))
        return change to Access.FILE
    }

    fun removePrefs(store: String, keys: List<String>): Pair<String, Access> {
        val (file, live) = locate(store, preferences = true)
        val change = "removed ${keys.joinToString()}"
        if (live != null) {
            updatePreferences(live) { mutable, keyOf -> keys.forEach { Reflect.call(mutable, "remove", keyOf(it)) } }
            return change to Access.LIVE
        }
        writeAtomically(file, PreferencesCodec.encode(PreferencesCodec.decode(file.readBytes()).filterNot { it.key in keys }))
        return change to Access.FILE
    }

    fun readProto(store: String): ProtoContent {
        val (file, live) = locate(store, preferences = false)
        val bytes = file.readBytes()
        return ProtoContent(store, Base64.getEncoder().encodeToString(bytes), bytes.size, if (live != null) Access.LIVE else Access.FILE, if (live != null) "read from the file the app's DataStore writes" else "read from the file")
    }

    fun writeProto(store: String, bytes: ByteArray): Access {
        val (file, live) = locate(store, preferences = false)
        if (live != null) {
            val serializer = findSerializer(live) ?: throw IllegalStateException("the app's DataStore for ${file.name} has no serializer DTA can find; nothing written")
            val readFrom = Reflect.methodNamed(serializer.javaClass, "readFrom", 2)!!
            val message = Reflect.callSuspend(serializer, readFrom, bytes.inputStream())
            updateData(live) { message }
            return Access.LIVE
        }
        writeAtomically(file, bytes)
        return Access.FILE
    }

    private fun locate(store: String, preferences: Boolean): Pair<File, Any?> {
        val (kind, name) = try { StoreIds.parse(store) } catch (e: IllegalArgumentException) { throw InvalidParamsException(e.message!!) }
        if (kind != StoreKind.DATASTORE) throw InvalidParamsException("$store is not a DataStore (datastore:<file>)")
        val file = File(dir, name)
        if (!file.isFile) throw InvalidParamsException("no DataStore file $name in the app (data list shows them)")
        val isPrefs = name.endsWith(PREFS_SUFFIX)
        if (isPrefs != preferences) {
            throw InvalidParamsException(if (isPrefs) "$name is a Preferences DataStore: use data prefs read/set/remove" else "$name is a Proto DataStore: use data proto read/write")
        }
        return file to live()?.get(Databases.canonical(file.path))
    }

    /** The app's DataStores by the canonical path of their file; null when they cannot be seen. */
    private fun live(): Map<String, Any>? {
        val stores = LiveObjects.of(DATASTORE, context.classLoader) ?: return null
        val out = LinkedHashMap<String, Any>()
        // A Preferences wrapper and the store it delegates to name the same file: the wrapper wins.
        for (s in stores.sortedBy { if (it.javaClass.name.contains("PreferenceDataStore")) 0 else 1 }) {
            fileOf(s, 0)?.let { out.putIfAbsent(Databases.canonical(it), s) }
        }
        return out
    }

    /** The file behind a DataStore: a File field, or its storage's produceFile / producePath. */
    private fun fileOf(o: Any, depth: Int): String? {
        if (depth > 4) return null
        for (f in Reflect.fields(o.javaClass)) {
            val v = runCatching { f.get(o) }.getOrNull() ?: continue
            when {
                v is File -> return v.path
                (f.name == "produceFile" || f.name == "producePath") && v is Function0<*> ->
                    runCatching { v.invoke()?.toString() }.getOrNull()?.let { return it }
                v.javaClass.name.startsWith("androidx.datastore") || f.name == "delegate" || f.name == "storage" ->
                    fileOf(v, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun findSerializer(o: Any, depth: Int = 0): Any? {
        if (depth > 4) return null
        val serializerType = runCatching { Class.forName("androidx.datastore.core.Serializer", false, context.classLoader) }.getOrNull() ?: return null
        for (f in Reflect.fields(o.javaClass)) {
            val v = runCatching { f.get(o) }.getOrNull() ?: continue
            if (serializerType.isInstance(v)) return v
            if (v.javaClass.name.startsWith("androidx.datastore") || f.name == "delegate" || f.name == "storage") findSerializer(v, depth + 1)?.let { return it }
        }
        return null
    }

    private fun updatePreferences(store: Any, edit: (mutable: Any, keyOf: (String) -> Any) -> Unit) {
        val loader = store.javaClass.classLoader
        val keyClass = Class.forName("androidx.datastore.preferences.core.Preferences\$Key", true, loader)
        val keyOf = { name: String -> keyClass.getConstructor(String::class.java).newInstance(name) }
        updateData(store) { prefs ->
            val mutable = Reflect.call(prefs!!, "toMutablePreferences")!!
            edit(mutable, keyOf)
            mutable
        }
    }

    /** `store.updateData(transform)`: a suspend call whose transform is a non-suspending lambda. */
    private fun updateData(store: Any, transform: (Any?) -> Any?) {
        val update = Reflect.methodNamed(store.javaClass, "updateData", 2) ?: throw IllegalStateException("the app's DataStore has no updateData")
        val fn: Function2<Any?, Any?, Any?> = { current, _ -> transform(current) }
        Reflect.callSuspend(store, update, fn)
    }

    private fun javaValue(t: PrefType, v: kotlinx.serialization.json.JsonElement): Any = when (t) {
        PrefType.STRING -> (v as JsonPrimitive).content
        PrefType.INT -> (v as JsonPrimitive).content.toInt()
        PrefType.LONG -> (v as JsonPrimitive).content.toLong()
        PrefType.FLOAT -> (v as JsonPrimitive).content.toFloat()
        PrefType.DOUBLE -> (v as JsonPrimitive).content.toDouble()
        PrefType.BOOLEAN -> (v as JsonPrimitive).content.toBoolean()
        PrefType.STRING_SET -> (v as JsonArray).map { (it as JsonPrimitive).content }.toSet()
        PrefType.BYTES -> Base64.getDecoder().decode((v as JsonPrimitive).content)
    }

    companion object {
        const val DATASTORE = "androidx.datastore.core.DataStore"
        const val PREFS_SUFFIX = ".preferences_pb"

        fun writeAtomically(file: File, bytes: ByteArray) {
            val tmp = File(file.parentFile, file.name + ".dta.tmp")
            tmp.writeBytes(bytes)
            check(tmp.renameTo(file)) { "could not replace ${file.name}" }
        }
    }
}
