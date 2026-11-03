package io.yamsergey.dta.sidekick.data

import android.content.Context
import io.yamsergey.dta.protocol.data.FileChunk
import io.yamsergey.dta.protocol.data.FileEntry
import io.yamsergey.dta.protocol.data.FileListing
import io.yamsergey.dta.protocol.data.FileRoot
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64

/**
 * Feature 027 (research R5): the app's files under two roots — `data:` (its private folder) and
 * `external:` (its app-specific external folder). Read in ranges; written in chunks to a part
 * file that replaces the target when the last chunk arrives.
 */
internal class AppFiles(private val context: Context) {
    fun roots(): List<FileRoot> = listOfNotNull(
        FileRoot("data", context.applicationInfo.dataDir),
        runCatching { context.getExternalFilesDir(null)?.parentFile?.path }.getOrNull()?.let { FileRoot("external", it) },
    )

    fun resolve(path: String): File {
        val root = path.substringBefore(":/", "")
        val base = roots().firstOrNull { it.name == root }?.path
            ?: throw InvalidParamsException("a path is data:/… or external:/…; got \"$path\"")
        val rel = path.substringAfter(":/").trimStart('/')
        val file = if (rel.isEmpty()) File(base) else File(base, rel)
        val canonicalBase = File(base).canonicalPath
        val canonical = file.canonicalPath
        if (canonical != canonicalBase && !canonical.startsWith("$canonicalBase/")) throw InvalidParamsException("$path leaves its root ($root:/)")
        return file
    }

    private fun pathOf(root: String, file: File): String {
        val base = roots().first { it.name == root }.path
        return "$root:/" + file.path.removePrefix(base).trimStart('/')
    }

    fun list(path: String, offset: Int, limit: Int): FileListing {
        val dir = resolve(path)
        if (!dir.exists()) throw InvalidParamsException("$path does not exist")
        if (!dir.isDirectory) throw InvalidParamsException("$path is a file (data files read)")
        val root = path.substringBefore(":/")
        val all = dir.listFiles().orEmpty().sortedWith(compareBy({ !it.isDirectory }, { it.name }))
        val entries = all.drop(offset).take(limit).map { f ->
            FileEntry(f.name, pathOf(root, f), f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified(), if (f.isDirectory) f.list()?.size ?: 0 else null)
        }
        return FileListing(pathOf(root, dir), entries, offset, all.size)
    }

    fun read(path: String, offset: Long, length: Int): FileChunk {
        val file = resolve(path)
        if (!file.isFile) throw InvalidParamsException(if (file.isDirectory) "$path is a folder (data files list)" else "$path does not exist")
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            val n = (size - offset).coerceIn(0, length.toLong()).toInt()
            val buf = ByteArray(n)
            raf.seek(offset)
            raf.readFully(buf)
            return FileChunk(path, offset, Base64.getEncoder().encodeToString(buf), size, file.lastModified())
        }
    }

    /** Appends a chunk at [offset] of the part file; [last] moves it over the target. */
    fun write(path: String, offset: Long, base64: String, last: Boolean): Long {
        val file = resolve(path)
        if (file.isDirectory) throw InvalidParamsException("$path is a folder")
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".dta-part")
        if (offset == 0L) part.delete()
        check(part.length() == offset) { "chunk at $offset but ${part.length()} bytes written so far" }
        part.appendBytes(Base64.getDecoder().decode(base64))
        if (last) {
            if (file.exists()) file.delete()
            check(part.renameTo(file)) { "could not move the written file into place" }
            return file.length()
        }
        return part.length()
    }

    fun mkdir(path: String) {
        val dir = resolve(path)
        check(dir.isDirectory || dir.mkdirs()) { "could not create $path" }
    }

    fun delete(path: String): Int {
        val file = resolve(path)
        if (!file.exists()) throw InvalidParamsException("$path does not exist")
        if (roots().any { File(it.path).canonicalPath == file.canonicalPath }) throw InvalidParamsException("$path is a root; delete what is inside it")
        val count = file.walkBottomUp().count()
        check(file.deleteRecursively()) { "could not delete everything under $path" }
        return count
    }
}
