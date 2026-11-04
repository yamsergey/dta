package io.yamsergey.dta.sidekick.data

import android.content.Context
import android.database.Cursor
import io.yamsergey.dta.protocol.data.Access
import io.yamsergey.dta.protocol.data.Cell
import io.yamsergey.dta.protocol.data.CellType
import io.yamsergey.dta.protocol.data.DbColumn
import io.yamsergey.dta.protocol.data.DbObject
import io.yamsergey.dta.protocol.data.DbSchema
import io.yamsergey.dta.protocol.data.ExecResult
import io.yamsergey.dta.protocol.data.QueryPage
import io.yamsergey.dta.protocol.data.SqlKind
import io.yamsergey.dta.protocol.data.SqlStatements
import io.yamsergey.dta.protocol.data.StoreIds
import io.yamsergey.dta.protocol.data.StoreInfo
import io.yamsergey.dta.protocol.data.StoreKind
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import java.io.File
import java.util.Base64

/**
 * Feature 027 (research R2): the app's databases. Reached through the app's own open database
 * object when there is one — framework SQLite, SQLCipher (4.x `net.zetetic`, legacy
 * `net.sqlcipher`; same method names as the framework) or an androidx.sqlite driver connection —
 * otherwise opened from the file for the call and closed again.
 */
internal class Databases(private val context: Context) {
    private val loader get() = context.classLoader

    private class Live(val obj: Any, val flavor: String, val path: String)

    /** Open databases of the app, by canonical path. */
    private fun live(): Map<String, Live>? {
        val found = LinkedHashMap<String, Live>()
        val framework = LiveObjects.of(android.database.sqlite.SQLiteDatabase::class.java) ?: return null
        for (db in framework) {
            val d = db as android.database.sqlite.SQLiteDatabase
            if (runCatching { d.isOpen }.getOrDefault(false)) d.path?.let { found.putIfAbsent(canonical(it), Live(d, "sqlite", it)) }
        }
        for (name in CIPHER_CLASSES) {
            for (db in LiveObjects.of(name, loader).orEmpty()) {
                val open = runCatching { Reflect.call(db, "isOpen") as Boolean }.getOrDefault(false)
                val path = runCatching { Reflect.call(db, "getPath") as String? }.getOrNull()
                if (open && path != null) found[canonical(path)] = Live(db, "sqlcipher", path)
            }
        }
        for (conn in LiveObjects.of(DRIVER_CONNECTION, loader).orEmpty()) {
            val path = driverPath(conn) ?: continue
            found.putIfAbsent(canonical(path), Live(conn, "driver", path))
        }
        return found
    }

    fun list(): List<StoreInfo> {
        val live = live()
        val files = context.databaseList().filterNot { n -> SIDE_FILES.any { n.endsWith(it) } }
            .map { context.getDatabasePath(it) }.filter { it.isFile }
        val paths = LinkedHashMap<String, File>()
        files.forEach { paths[canonical(it.path)] = it }
        live?.values?.forEach { l -> if (!l.path.startsWith(":memory:") && l.path.isNotEmpty()) paths.putIfAbsent(canonical(l.path), File(l.path)) }
        return paths.map { (key, file) -> info(file, live?.get(key), live == null) }
    }

    private fun info(file: File, live: Live?, liveUnknown: Boolean): StoreInfo {
        val encrypted = !isPlain(file)
        val id = StoreIds.of(StoreKind.DATABASE, nameOf(file))
        val tables = live?.let { runCatching { Session.of(it.obj, owned = false).use { s -> s.count("SELECT count(*) FROM sqlite_master WHERE type='table'") } }.getOrNull() }
        val room = live?.let { runCatching { Session.of(it.obj, owned = false).use { s -> s.count("SELECT count(*) FROM sqlite_master WHERE name='room_master_table'") > 0 } }.getOrDefault(false) } ?: false
        val flavor = when {
            live != null && room -> if (live.flavor == "sqlite") "room" else "room-${live.flavor}"
            live != null -> live.flavor
            encrypted -> "sqlcipher"
            else -> "sqlite"
        }
        val (access, note) = when {
            live != null -> Access.LIVE to "the app's open ${describe(live.flavor)}: DTA reads and writes through it" + (if (room) "; Room observers are told after writes" else if (sqlDelight()) "; SQLDelight queries are told after writes" else "")
            encrypted && liveUnknown -> Access.UNREADABLE to "encrypted, and the app's open databases cannot be seen (${LiveObjects.reason}); give its SQLCipher passphrase"
            encrypted -> Access.UNREADABLE to "encrypted (SQLCipher), and the app has not opened it in this process: open the screen that uses it, or give its passphrase"
            else -> Access.FILE to "not open in the app: DTA opens the file for each call" + (if (liveUnknown) " (the app's open databases cannot be seen: ${LiveObjects.reason})" else "")
        }
        return StoreInfo(id, StoreKind.DATABASE, nameOf(file), file.path, file.length(), flavor, access, note, tables?.toInt())
    }

    fun schema(store: String, passphrase: String?): DbSchema = session(store, passphrase) { s, access, note ->
        val objects = s.rows("SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name").map { r ->
            val type = r[0] ?: ""
            val name = r[1] ?: ""
            val columns = if (type == "table" || type == "view") {
                s.rows("PRAGMA table_info(${SqlStatements.identifier(name)})").map { c ->
                    DbColumn(c[1] ?: "", c[2] ?: "", c[3] == "1", c[4], c[5]?.toIntOrNull() ?: 0)
                }
            } else emptyList()
            val count = if (type == "table") runCatching { s.count("SELECT count(*) FROM ${SqlStatements.identifier(name)}") }.getOrNull() else null
            DbObject(name, type, r[3], columns, count, r[3]?.contains("WITHOUT ROWID", ignoreCase = true) == true, r[2]?.takeIf { type == "index" || type == "trigger" })
        }
        val room = objects.any { it.name == "room_master_table" }
        DbSchema(store, objects, runCatching { s.count("PRAGMA user_version").toInt() }.getOrNull(), room, access, note)
    }

    fun query(store: String, sql: String, args: List<String>, offset: Int, limit: Int, fullBlobs: Boolean, passphrase: String?): QueryPage =
        session(store, passphrase) { s, _, _ -> s.query(store, sql, args, offset, limit, fullBlobs) }

    fun exec(store: String, sql: String, args: List<String>, passphrase: String?): ExecResult =
        session(store, passphrase) { s, access, _ ->
            val result = s.exec(store, sql, args)
            if (access == Access.LIVE && (result.changed != 0 || SqlStatements.kind(sql) == SqlKind.OTHER)) {
                val tables = runCatching { s.rows("SELECT name FROM sqlite_master WHERE type='table'").mapNotNull { it[0] } }.getOrDefault(emptyList())
                result.copy(invalidated = invalidateRoom() + invalidateSqlDelight(tables))
            } else result
        }

    /**
     * Feature 030: SQLDelight's flows listen to their driver, not to SQLite: every live
     * AndroidSqliteDriver is told the written database's tables changed (its query keys are table
     * names), so `asFlow()` queries re-run as after the app's own write.
     */
    private fun invalidateSqlDelight(tables: List<String>): Int {
        if (tables.isEmpty()) return 0
        var told = 0
        for (driver in LiveObjects.of("app.cash.sqldelight.driver.android.AndroidSqliteDriver", loader).orEmpty()) {
            val m = driver.javaClass.methods.firstOrNull { it.name == "notifyListeners" && it.parameterTypes.size == 1 && it.parameterTypes[0].isArray } ?: continue
            if (runCatching { m.invoke(driver, tables.toTypedArray()) }.isSuccess) told++
        }
        return told
    }

    private fun sqlDelight(): Boolean = LiveObjects.of("app.cash.sqldelight.driver.android.AndroidSqliteDriver", loader).orEmpty().isNotEmpty()

    /** Every live Room InvalidationTracker re-checks its tables (as Android Studio's RoomInvalidationRegistry). */
    private fun invalidateRoom(): Int {
        var told = 0
        for (tracker in LiveObjects.of("androidx.room.InvalidationTracker", loader).orEmpty()) {
            val m = Reflect.methodNamed(tracker.javaClass, "refreshVersionsAsync", 0) ?: Reflect.methodNamed(tracker.javaClass, "refreshAsync", 0)
            if (m != null && runCatching { Reflect.invoke(m, tracker) }.isSuccess) told++
        }
        return told
    }

    private fun <T> session(store: String, passphrase: String?, block: (Session, Access, String) -> T): T {
        val (kind, name) = try { StoreIds.parse(store) } catch (e: IllegalArgumentException) { throw InvalidParamsException(e.message!!) }
        if (kind != StoreKind.DATABASE) throw InvalidParamsException("$store is not a database (db:<file> as data list shows it)")
        val info = list().firstOrNull { it.id == store } ?: throw InvalidParamsException("no database $name in the app (data list shows them)")
        val live = live()?.get(canonical(info.path))
        val session = when {
            live != null -> Session.of(live.obj, owned = false)
            isPlain(File(info.path)) -> Session.of(android.database.sqlite.SQLiteDatabase.openDatabase(info.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE), owned = true)
            passphrase != null -> Session.of(openCipher(info.path, passphrase), owned = true)
            else -> throw InvalidParamsException("$name: ${info.accessNote}")
        }
        val access = if (live != null) Access.LIVE else Access.FILE
        val note = if (live != null) info.accessNote else if (passphrase != null) "opened by DTA with the given passphrase" else info.accessNote
        return session.use { s ->
            try {
                block(s, access, note)
            } catch (e: android.database.SQLException) {
                throw InvalidParamsException(e.message ?: e.javaClass.simpleName)
            } catch (e: RuntimeException) {
                if (e.javaClass.name.contains("SQLite") || e.javaClass.name.contains("sqlcipher")) throw InvalidParamsException(e.message ?: e.javaClass.simpleName)
                throw e
            }
        }
    }

    /** Opens a SQLCipher file with the app's own SQLCipher classes. */
    private fun openCipher(path: String, passphrase: String): Any {
        val zetetic = runCatching { Class.forName(CIPHER_CLASSES[0], true, loader) }.getOrNull()
        if (zetetic != null) {
            runCatching { System.loadLibrary("sqlcipher") }
            val open = zetetic.methods.firstOrNull {
                it.name == "openDatabase" && it.parameterTypes.size == 5 && it.parameterTypes[1] == String::class.java && it.parameterTypes[3] == Int::class.javaPrimitiveType
            } ?: zetetic.methods.first { it.name == "openDatabase" && it.parameterTypes.size == 5 && it.parameterTypes[1] == ByteArray::class.java }
            val secret: Any = if (open.parameterTypes[1] == String::class.java) passphrase else passphrase.toByteArray()
            return Reflect.invoke(open, null, path, secret, null, 0 /* OPEN_READWRITE */, null)!!
        }
        val legacy = runCatching { Class.forName(CIPHER_CLASSES[1], true, loader) }.getOrNull()
            ?: throw InvalidParamsException("the database is encrypted but the app does not ship SQLCipher; DTA cannot open it")
        Reflect.methodNamed(legacy, "loadLibs", 1)?.let { Reflect.invoke(it, null, context) }
        val open = legacy.methods.first { it.name == "openDatabase" && it.parameterTypes.size == 4 && it.parameterTypes[1] == String::class.java }
        return Reflect.invoke(open, null, path, passphrase, null, 0)!!
    }

    private fun driverPath(conn: Any): String? =
        Reflect.fields(conn.javaClass).firstNotNullOfOrNull { f -> (runCatching { f.get(conn) }.getOrNull() as? String)?.takeIf { it.endsWith(".db") || it.contains("/databases/") } }

    companion object {
        val CIPHER_CLASSES = listOf("net.zetetic.database.sqlcipher.SQLiteDatabase", "net.sqlcipher.database.SQLiteDatabase")
        const val DRIVER_CONNECTION = "androidx.sqlite.SQLiteConnection"
        private val SIDE_FILES = listOf("-journal", "-wal", "-shm")
        private val HEADER = "SQLite format 3\u0000".toByteArray()

        fun isPlain(file: File): Boolean = runCatching {
            if (file.length() == 0L) return true
            val head = ByteArray(16)
            file.inputStream().use { it.read(head) }
            head.contentEquals(HEADER)
        }.getOrDefault(true)

        fun canonical(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)

        fun nameOf(file: File) = file.name

        private fun describe(flavor: String) = when (flavor) {
            "sqlcipher" -> "SQLCipher database (no passphrase needed)"
            "driver" -> "androidx.sqlite driver connection"
            else -> "database"
        }
    }
}

/** One database the call works on: the app's object (left open) or one DTA opened (closed after). */
internal abstract class Session(private val owned: Boolean) : AutoCloseable {
    abstract fun cursor(sql: String, args: List<String>): Cursor?
    abstract fun exec(store: String, sql: String, args: List<String>): ExecResult
    protected abstract fun closeDb()
    open fun rowsOf(sql: String): List<List<String?>>? = null

    fun rows(sql: String): List<List<String?>> = rowsOf(sql) ?: cursor(sql, emptyList())!!.use { c ->
        val out = mutableListOf<List<String?>>()
        while (c.moveToNext()) out += (0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }
        out
    }

    fun count(sql: String): Long = rows(sql).firstOrNull()?.firstOrNull()?.toLongOrNull() ?: 0

    open fun query(store: String, sql: String, args: List<String>, offset: Int, limit: Int, fullBlobs: Boolean): QueryPage =
        cursor(sql, args)!!.use { c ->
            val total = c.count
            val rows = mutableListOf<List<Cell>>()
            if (offset < total && c.moveToPosition(offset)) {
                do rows += (0 until c.columnCount).map { cell(c, it, fullBlobs) } while (rows.size < limit && c.moveToNext())
            }
            QueryPage(store, c.columnNames.toList(), rows, offset, total)
        }

    override fun close() {
        if (owned) runCatching { closeDb() }
    }

    companion object {
        fun of(db: Any, owned: Boolean): Session = when {
            db is android.database.sqlite.SQLiteDatabase -> Framework(db, owned)
            runCatching { Class.forName(Databases.DRIVER_CONNECTION, false, db.javaClass.classLoader).isInstance(db) }.getOrDefault(false) -> Driver(db, owned)
            else -> Reflective(db, owned)
        }

        fun cell(c: Cursor, i: Int, fullBlobs: Boolean): Cell = when (c.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> Cell.NULL
            Cursor.FIELD_TYPE_INTEGER -> Cell(CellType.INTEGER, c.getLong(i).toString())
            Cursor.FIELD_TYPE_FLOAT -> Cell(CellType.REAL, c.getDouble(i).toString())
            Cursor.FIELD_TYPE_BLOB -> blob(c.getBlob(i), fullBlobs)
            else -> Cell(CellType.TEXT, c.getString(i))
        }

        fun blob(b: ByteArray, full: Boolean): Cell =
            if (full) Cell(CellType.BLOB, Base64.getEncoder().encodeToString(b), b.size)
            else Cell(CellType.BLOB, b.take(Cell.BLOB_PREFIX).joinToString("") { "%02x".format(it) }, b.size, truncated = b.size > Cell.BLOB_PREFIX)
    }

    /** The framework's SQLiteDatabase. */
    private class Framework(val db: android.database.sqlite.SQLiteDatabase, owned: Boolean) : Session(owned) {
        override fun cursor(sql: String, args: List<String>): Cursor = db.rawQuery(sql, args.toTypedArray())
        override fun exec(store: String, sql: String, args: List<String>): ExecResult = db.compileStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.bindString(i + 1, a) }
            when (SqlStatements.kind(sql)) {
                SqlKind.UPDATE_DELETE -> ExecResult(store, changed = st.executeUpdateDelete(), statement = sql)
                SqlKind.INSERT -> st.executeInsert().let { ExecResult(store, changed = if (it >= 0) 1 else 0, insertedRowId = it.takeIf { id -> id >= 0 }, statement = sql) }
                else -> { st.execute(); ExecResult(store, statement = sql) }
            }
        }
        override fun closeDb() = db.close()
    }

    /** SQLCipher's SQLiteDatabase: the framework's method names, called by reflection. */
    private class Reflective(val db: Any, owned: Boolean) : Session(owned) {
        override fun cursor(sql: String, args: List<String>): Cursor {
            val m = db.javaClass.methods.first { it.name == "rawQuery" && it.parameterTypes.size == 2 && it.parameterTypes[0] == String::class.java && it.parameterTypes[1].isArray }
            val arr = java.lang.reflect.Array.newInstance(m.parameterTypes[1].componentType!!, args.size).also { a -> args.forEachIndexed { i, s -> java.lang.reflect.Array.set(a, i, s) } }
            return Reflect.invoke(m, db, sql, arr) as Cursor
        }
        override fun exec(store: String, sql: String, args: List<String>): ExecResult {
            val st = Reflect.call(db, "compileStatement", sql)!!
            try {
                args.forEachIndexed { i, a -> Reflect.invoke(st.javaClass.methods.first { it.name == "bindString" && it.parameterTypes.size == 2 }, st, i + 1, a) }
                return when (SqlStatements.kind(sql)) {
                    SqlKind.UPDATE_DELETE -> ExecResult(store, changed = (Reflect.call(st, "executeUpdateDelete") as Number).toInt(), statement = sql)
                    SqlKind.INSERT -> (Reflect.call(st, "executeInsert") as Number).toLong().let { ExecResult(store, changed = if (it >= 0) 1 else 0, insertedRowId = it.takeIf { id -> id >= 0 }, statement = sql) }
                    else -> { Reflect.call(st, "execute"); ExecResult(store, statement = sql) }
                }
            } finally {
                runCatching { Reflect.call(st, "close") }
            }
        }
        override fun closeDb() { Reflect.call(db, "close") }
    }

    /** An androidx.sqlite SQLiteConnection (Room with a driver): prepare / step. */
    private class Driver(val conn: Any, owned: Boolean) : Session(owned) {
        private fun <T> statement(sql: String, args: List<String>, block: (Any) -> T): T {
            val st = Reflect.call(conn, "prepare", sql)!!
            try {
                args.forEachIndexed { i, a -> Reflect.call(st, "bindText", i + 1, a) }
                return block(st)
            } finally {
                runCatching { Reflect.call(st, "close") }
            }
        }

        override fun cursor(sql: String, args: List<String>): Cursor? = null

        override fun rowsOf(sql: String): List<List<String?>> = statement(sql, emptyList()) { st ->
            val out = mutableListOf<List<String?>>()
            val n = (Reflect.call(st, "getColumnCount") as Number).toInt()
            while (Reflect.call(st, "step") as Boolean) out += (0 until n).map { i -> if (Reflect.call(st, "isNull", i) as Boolean) null else Reflect.call(st, "getText", i) as String }
            out
        }

        override fun query(store: String, sql: String, args: List<String>, offset: Int, limit: Int, fullBlobs: Boolean): QueryPage = statement(sql, args) { st ->
            val n = (Reflect.call(st, "getColumnCount") as Number).toInt()
            val names = (0 until n).map { Reflect.call(st, "getColumnName", it) as String }
            val rows = mutableListOf<List<Cell>>()
            var index = 0
            while (Reflect.call(st, "step") as Boolean) {
                if (index >= offset && rows.size < limit) rows += (0 until n).map { i ->
                    when ((Reflect.call(st, "getColumnType", i) as Number).toInt()) {
                        1 -> Cell(CellType.INTEGER, Reflect.call(st, "getLong", i).toString())
                        2 -> Cell(CellType.REAL, Reflect.call(st, "getDouble", i).toString())
                        4 -> blob(Reflect.call(st, "getBlob", i) as ByteArray, fullBlobs)
                        5 -> Cell.NULL
                        else -> Cell(CellType.TEXT, Reflect.call(st, "getText", i) as String)
                    }
                }
                index++
            }
            QueryPage(store, names, rows, offset, index)
        }

        override fun exec(store: String, sql: String, args: List<String>): ExecResult {
            statement(sql, args) { st -> while (Reflect.call(st, "step") as Boolean) Unit }
            val changed = rowsOf("SELECT changes(), last_insert_rowid()").first()
            return when (SqlStatements.kind(sql)) {
                SqlKind.UPDATE_DELETE -> ExecResult(store, changed = changed[0]?.toInt(), statement = sql)
                SqlKind.INSERT -> ExecResult(store, changed = changed[0]?.toInt(), insertedRowId = changed[1]?.toLong(), statement = sql)
                else -> ExecResult(store, statement = sql)
            }
        }

        override fun closeDb() { Reflect.call(conn, "close") }
    }
}
