package io.yamsergey.dta.present.data

import io.yamsergey.dta.protocol.data.ThreadInfo
import io.yamsergey.dta.protocol.data.ThreadList

/**
 * Feature 032: the Runtime tab's threads as a state, not as a crash — grouped (the app's own,
 * the system's, DTA's), states in words, the frame that matters, and one health line on top.
 */
class ThreadRows(list: ThreadList) {
    data class Frame(val text: String, val app: Boolean)

    data class Row(val info: ThreadInfo, val stateText: String, val topFrame: String, val frames: List<Frame>) {
        val name: String get() = info.name
        val header: String get() = listOfNotNull(info.name, stateText, "daemon".takeIf { info.daemon }, "priority ${info.priority}", info.group?.let { "group $it" }).joinToString(" · ")
    }

    data class Group(val label: String, val threads: List<Row>, val expanded: Boolean)

    val groups: List<Group>
    val health: String

    init {
        val rows = list.threads.map(::row)
        val (dta, rest) = rows.partition { it.name.startsWith("dta-") }
        val (system, app) = rest.partition { isSystem(it.name) }
        groups = listOf(
            Group("App", app.sortedBy { if (it.name == "main") 0 else 1 }, true),
            Group("System", system, false),
            Group("DTA", dta, false),
        ).filter { it.threads.isNotEmpty() }
        health = health(rows)
    }

    private fun row(t: ThreadInfo): Row {
        val frames = t.stack.map { Frame(it, isApp(it)) }
        val top = frames.firstOrNull { it.app }?.text ?: frames.firstOrNull()?.text ?: ""
        return Row(t, stateText(t), top, frames)
    }

    private fun health(rows: List<Row>): String {
        val main = rows.firstOrNull { it.name == "main" }
        val first = main?.info?.stack?.firstOrNull()
        val mainText = when {
            main == null -> "No main thread in the list."
            main.info.state == "blocked" -> "Main thread blocked at ${main.topFrame}."
            first == null || first.startsWith(POLL) -> "Main thread idle (waiting for messages)."
            main.info.state == "runnable" -> "Main thread busy in ${main.topFrame}."
            else -> "Main thread ${main.stateText} at ${main.topFrame}."
        }
        val blocked = rows.filter { it.info.state == "blocked" }
        val blockedText = if (blocked.isEmpty()) "No blocked threads." else "${blocked.size} thread${if (blocked.size == 1) "" else "s"} blocked: ${blocked.joinToString(", ") { it.name }}."
        return "$mainText $blockedText"
    }

    companion object {
        private const val POLL = "android.os.MessageQueue.nativePollOnce"

        private val FRAMEWORK = listOf(
            "java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.", "android.", "androidx.", "com.android.",
            "dalvik.", "libcore.", "com.google.android.", "io.yamsergey.dta.",
        )
        private val SYSTEM_NAMES = setOf(
            "FinalizerDaemon", "FinalizerWatchdogDaemon", "HeapTaskDaemon", "ReferenceQueueDaemon", "Signal Catcher",
            "Profile Saver", "RenderThread", "SurfaceSyncGroupTimer", "queued-work-looper", "Jit thread pool",
        )
        private val SYSTEM_PREFIXES = listOf("binder:", "Binder:", "HwBinder:", "hwuiTask", "Jit thread pool", "ADB-JDWP", "Jdwp", "perfetto")

        fun isSystem(name: String) = name in SYSTEM_NAMES || SYSTEM_PREFIXES.any { name.startsWith(it) }

        /** A frame of the app (or a library it ships) rather than of Java, Kotlin, Android or DTA. */
        fun isApp(frame: String) = FRAMEWORK.none { frame.startsWith(it) }

        fun stateText(t: ThreadInfo): String = when {
            t.stack.isEmpty() && t.state == "runnable" -> "native"
            t.state == "runnable" && t.stack.first().startsWith(POLL) -> "idle (waiting for messages)"
            t.state == "runnable" -> "running"
            t.state == "timed_waiting" -> "waiting (timed)"
            else -> t.state
        }
    }
}
