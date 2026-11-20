package io.yamsergey.dta.daemon.store

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * How a stored format becomes the next one. Each version names the rule that turns the version
 * before it into it, so a store of any earlier version reaches the current one step by step.
 * Until DTA is released, every new version is a decision taken with the user (keep the data, or
 * start over); see the decision log of 2026-11-20.
 */
sealed interface Migration {
    /** Statements turning the version before into this one, run in one transaction; the data is kept. */
    class Steps(val statements: List<String>) : Migration

    /** The data of earlier versions is not kept: the store is made anew at the current version. */
    class StartOver(val why: String) : Migration
}

/**
 * A stored format: its current [version], the statements that [create] it at that version, and
 * the rule for each version from 1 up to it ([migrations] by the version the rule leads to).
 * Version 0 is a store from before versions were kept.
 */
class StoreSchema(val version: Int, val create: List<String>, val migrations: Map<Int, Migration>)

/** The store was written by a newer DTA; this one does not read it and leaves it as it is. */
class NewerStoreException(message: String) : Exception(message)

/** An SQLite database kept at its schema's version (`PRAGMA user_version`). */
object VersionedDatabase {
    /**
     * Opens [file] at [schema]'s version. A store of an earlier version is moved by the rules; when
     * one of them starts over, the database is removed and [startOver] is told why, to remove what
     * is kept beside it (body files).
     */
    fun open(file: File, schema: StoreSchema, startOver: (why: String) -> Unit): Connection {
        var db = connect(file)
        try {
            val stored = userVersion(db)
            val empty = db.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM sqlite_master").use { it.next(); it.getInt(1) == 0 } }
            if (empty) return db.also { create(it, schema) }
            if (stored > schema.version) {
                throw NewerStoreException("$file is in format version $stored, written by a newer DTA; this one reads version ${schema.version}. Use the newer DTA, or remove the file to start over.")
            }
            val rules = (stored + 1..schema.version).map { v -> schema.migrations[v] ?: error("no rule turns format version ${v - 1} of $file into $v") }
            val lastStartOver = rules.filterIsInstance<Migration.StartOver>().lastOrNull()
            if (lastStartOver != null) {
                db.close()
                listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
                startOver(lastStartOver.why)
                db = connect(file)
                return db.also { create(it, schema) }
            }
            rules.forEachIndexed { i, rule -> transaction(db) { (rule as Migration.Steps).statements.forEach { sql -> it.execute(sql) }; it.execute("PRAGMA user_version=${stored + i + 1}") } }
            return db
        } catch (e: Throwable) {
            runCatching { db.close() }
            throw e
        }
    }

    private fun connect(file: File) = DriverManager.getConnection("jdbc:sqlite:${file.path}")

    private fun userVersion(db: Connection) = db.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }

    private fun create(db: Connection, schema: StoreSchema) = transaction(db) { st ->
        schema.create.forEach(st::execute)
        st.execute("PRAGMA user_version=${schema.version}")
    }

    private fun transaction(db: Connection, body: (java.sql.Statement) -> Unit) {
        db.autoCommit = false
        try {
            db.createStatement().use(body)
            db.commit()
        } catch (e: Throwable) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
    }
}
