package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.network.QueryLine
import io.yamsergey.dta.protocol.network.RecentFilter
import io.yamsergey.dta.protocol.network.SavedFilters
import io.yamsergey.dta.protocol.network.SavedFiltersApplied
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Feature 043: each app's saved network filters, in `<dataDir>/filters/<package>.json`, written
 * before a change returns. The daemon applies them to every list, search and export, so the
 * plugin, the CLI and an agent see the same rows and are told what was hidden.
 */
class SavedFilterStore(
    private val dataDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val apps = HashMap<String, SavedFilters>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true }

    @Synchronized
    fun state(pkg: String): SavedFilters = apps.getOrPut(pkg) { load(pkg) }

    /** Saves the filter words of [words]; anything else in it is refused, naming it. */
    @Synchronized
    fun add(pkg: String, words: String): SavedFilters {
        val commit = QueryLine.commit(words)
        if (commit.rest.isNotEmpty() || commit.words.isEmpty()) {
            throw InvalidParamsException("not filters that can be saved: '${commit.rest.ifEmpty { words }}'. Save key:value filters; run:, since: and until: belong to one session.")
        }
        var s = state(pkg)
        for (word in commit.words) {
            if (word in s.saved) continue
            val key = SavedFilters.positiveKey(word)
            val replaced = s.saved.filter { key != null && SavedFilters.positiveKey(it) == key }
            s = s.copy(
                saved = s.saved - replaced.toSet() + word,
                recent = recent(s.recent.filter { it.word != word }, replaced),
            )
        }
        return save(pkg, s.copy(problem = null))
    }

    /** True when [word] was saved; it stays among the recent ones. */
    @Synchronized
    fun remove(pkg: String, word: String): Boolean {
        val s = state(pkg)
        if (word !in s.saved) return false
        save(pkg, s.copy(saved = s.saved - word, recent = recent(s.recent, listOf(word))))
        return true
    }

    /**
     * Runs a list or search as the app's saved filters say and marks the result with the words
     * that applied (feature 069: what they hid is not counted; that would read the whole history).
     */
    suspend fun <T> applied(
        pkg: String,
        query: NetworkQuery,
        run: suspend (NetworkQuery) -> T,
        mark: (T, SavedFiltersApplied) -> T,
    ): T {
        val saved = state(pkg).saved
        if (saved.isEmpty()) return run(query)
        if (query.ignoreSavedFilters) return mark(run(query), SavedFiltersApplied(saved, ignored = true))
        val merged = SavedFilters.merge(saved, query.q)
        if (merged.applied.isEmpty()) return run(query)
        return mark(run(query.copy(q = merged.q)), SavedFiltersApplied(merged.applied))
    }

    /**
     * Feature 065: the saved words the Timeline judges its network rows with, merged with the
     * Timeline's typed line as Network merges them ([SavedFilters.merge]: a key typed there wins),
     * and the entries they let through ([passing], Network's own evaluator). A failure of
     * [passing] is the reason nothing is hidden.
     */
    suspend fun judge(
        pkg: String,
        typed: String?,
        ignore: Boolean,
        passing: suspend (NetworkQuery) -> io.yamsergey.dta.protocol.network.NetworkHistoryView.Passing,
    ): Judgement {
        val state = state(pkg)
        if (state.problem != null && state.saved.isEmpty()) return Judgement(emptyList(), problem = state.problem)
        if (state.saved.isEmpty()) return Judgement.NONE
        if (ignore) return Judgement(state.saved, ignored = true)
        val words = runCatching { SavedFilters.merge(state.saved, typed).applied }.getOrDefault(state.saved)
        if (words.isEmpty()) return Judgement.NONE
        val p = try {
            passing(NetworkQuery(q = words.joinToString(" ")))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Judgement(words, unavailable = e.message ?: e.javaClass.simpleName)
        }
        return Judgement(words, passing = p)
    }

    private fun recent(recent: List<RecentFilter>, gone: List<String>): List<RecentFilter> {
        val now = clock()
        return (gone.map { RecentFilter(it, now) } + recent.filter { r -> r.word !in gone }).take(SavedFilters.MAX_RECENT)
    }

    private fun load(pkg: String): SavedFilters {
        val f = file(pkg)
        if (!f.isFile) return SavedFilters()
        return runCatching { json.decodeFromString(SavedFilters.serializer(), f.readText()) }.getOrElse { e ->
            runCatching { Files.move(f.toPath(), File(f.path + ".bad").toPath(), StandardCopyOption.REPLACE_EXISTING) }
            SavedFilters(problem = "saved filters could not be read (${e.message?.lineSequence()?.first()}); kept as ${f.name}.bad, starting with none")
        }
    }

    private fun save(pkg: String, s: SavedFilters): SavedFilters {
        apps[pkg] = s
        val f = file(pkg).apply { parentFile.mkdirs() }
        val tmp = File(f.path + ".tmp")
        tmp.writeText(json.encodeToString(SavedFilters.serializer(), s))
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return s
    }

    private fun file(pkg: String) = File(File(dataDir, "filters"), "$pkg.json")
}
