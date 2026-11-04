package io.yamsergey.dta.present.data

import io.yamsergey.dta.protocol.data.ThreadInfo
import io.yamsergey.dta.protocol.data.ThreadList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadRowsTest {
    private val looper = listOf(
        "android.os.MessageQueue.nativePollOnce(Native Method)",
        "android.os.MessageQueue.next(MessageQueue.java:346)",
        "android.os.Looper.loop(Looper.java:317)",
        "android.app.ActivityThread.main(ActivityThread.java:8705)",
    )
    private fun t(name: String, state: String, stack: List<String> = emptyList(), daemon: Boolean = false) = ThreadInfo(name.hashCode().toLong(), name, state, daemon, 5, "main", stack)

    private val idle = ThreadList(listOf(
        t("FinalizerDaemon", "waiting", listOf("java.lang.Object.wait(Native Method)"), daemon = true),
        t("binder:10721_1", "runnable"),
        t("RenderThread", "runnable", daemon = true),
        t("dta-network-store", "timed_waiting", listOf("java.lang.Object.wait(Native Method)", "io.yamsergey.dta.sidekick.network.store.NetworkStore.writerLoop(NetworkStore.kt:568)"), daemon = true),
        t("main", "runnable", looper),
        t("OkHttp TaskRunner", "timed_waiting", listOf("java.lang.Object.wait(Native Method)", "okhttp3.internal.concurrent.TaskRunner.awaitTaskToRun(TaskRunner.kt:220)")),
        t("sync", "runnable", listOf("java.io.FileOutputStream.write(FileOutputStream.java:1)", "com.example.shop.Sync.flush(Sync.kt:42)", "java.lang.Thread.run(Thread.java:1012)")),
    ))

    @Test
    fun `threads are grouped app, system, DTA, the app first`() {
        val rows = ThreadRows(idle)
        assertEquals(listOf("App", "System", "DTA"), rows.groups.map { it.label })
        assertEquals(listOf("main", "OkHttp TaskRunner", "sync"), rows.groups[0].threads.map { it.name })
        assertEquals(listOf("FinalizerDaemon", "binder:10721_1", "RenderThread"), rows.groups[1].threads.map { it.name })
        assertEquals(listOf("dta-network-store"), rows.groups[2].threads.map { it.name })
        assertTrue(rows.groups[0].expanded)
        assertFalse(rows.groups[1].expanded)
    }

    @Test
    fun `states read as words, the top frame prefers the app's own`() {
        val rows = ThreadRows(idle).groups.flatMap { it.threads }.associateBy { it.name }
        assertEquals("idle (waiting for messages)", rows["main"]!!.stateText)
        assertEquals("native", rows["binder:10721_1"]!!.stateText)
        assertEquals("waiting (timed)", rows["OkHttp TaskRunner"]!!.stateText)
        assertEquals("waiting", rows["FinalizerDaemon"]!!.stateText)
        assertEquals("running", rows["sync"]!!.stateText)
        assertEquals("com.example.shop.Sync.flush(Sync.kt:42)", rows["sync"]!!.topFrame)
        assertEquals("okhttp3.internal.concurrent.TaskRunner.awaitTaskToRun(TaskRunner.kt:220)", rows["OkHttp TaskRunner"]!!.topFrame)
        assertEquals("", rows["binder:10721_1"]!!.topFrame)
        assertEquals(listOf(false, true, false), rows["sync"]!!.frames.map { it.app })
        assertEquals("sync · running · priority 5 · group main", rows["sync"]!!.header)
    }

    @Test
    fun `the health line says whether main is free and names blocked threads`() {
        assertEquals("Main thread idle (waiting for messages). No blocked threads.", ThreadRows(idle).health)
        val busy = ThreadList(listOf(t("main", "runnable", listOf("com.example.shop.Feed.parse(Feed.kt:7)") + looper)))
        assertEquals("Main thread busy in com.example.shop.Feed.parse(Feed.kt:7). No blocked threads.", ThreadRows(busy).health)
        val blocked = ThreadList(listOf(
            t("main", "blocked", listOf("com.example.shop.Cache.get(Cache.kt:3)") + looper),
            t("io-1", "blocked", listOf("com.example.shop.Cache.put(Cache.kt:9)")),
        ))
        assertEquals("Main thread blocked at com.example.shop.Cache.get(Cache.kt:3). 2 threads blocked: main, io-1.", ThreadRows(blocked).health)
    }
}
