package io.yamsergey.dta.protocol.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameClassifierTest {

    private fun frame(cls: String, method: String = "m") = StackTraceElement(cls, method, "F.kt", 1)

    @Test
    fun `known library and framework packages are library frames, the rest app`() {
        for (cls in listOf("java.lang.Thread", "kotlin.coroutines.X", "android.os.Handler", "androidx.work.Y", "okhttp3.internal.connection.RealCall",
                "okio.Buffer", "retrofit2.OkHttpCall", "com.squareup.moshi.Z", "io.reactivex.Single", "com.neovisionaries.ws.client.WebSocket",
                "org.java_websocket.WebSocketImpl", "dalvik.system.VMStack", "com.android.internal.os.ZygoteInit")) {
            assertEquals(false, FrameClassifier.isApp(cls), cls)
        }
        for (cls in listOf("com.example.auth.SessionRepository", "io.yamsergey.dta.fixture.channel.NetworkScreen", "okhttp3x.Fake")) {
            assertEquals(true, FrameClassifier.isApp(cls), cls)
        }
    }

    @Test
    fun `capture frames are trimmed from the top and the first app frame is found`() {
        val initiator = FrameClassifier.initiator(
            thread = "main",
            site = InitiatorSite.ENQUEUE,
            stack = listOf(
                frame("dalvik.system.VMStack", "getThreadStackTrace"),
                frame("java.lang.Thread", "getStackTrace"),
                frame("io.yamsergey.dta.sidekick.network.Initiators", "capture"),
                frame("io.yamsergey.dta.shim.HookDispatcher", "onEntry"),
                frame("okhttp3.internal.connection.RealCall", "enqueue"),
                frame("com.example.Repo", "load"),
                frame("android.os.Looper", "loop"),
            ),
        )
        assertEquals(listOf("okhttp3.internal.connection.RealCall", "com.example.Repo", "android.os.Looper"), initiator.frames!!.map { it.className })
        assertEquals(listOf(false, true, false), initiator.frames!!.map { it.app })
        assertEquals(1, initiator.firstAppFrame)
        assertEquals("main", initiator.thread)
        assertEquals(InitiatorSite.ENQUEUE, initiator.site)
        assertNull(initiator.truncatedFrames)
    }

    @Test
    fun `stacks over the cap are truncated with the count stated`() {
        val stack = (0 until 100).map { frame("com.example.Deep", "f$it") }
        val initiator = FrameClassifier.initiator("t", InitiatorSite.CALL, stack)
        assertEquals(Initiator.MAX_FRAMES, initiator.frames!!.size)
        assertEquals(36, initiator.truncatedFrames)
    }

    @Test
    fun `a stack with no app frame has no first app frame`() {
        val initiator = FrameClassifier.initiator("OkHttp Dispatcher", InitiatorSite.CALL, listOf(frame("okhttp3.Dispatcher"), frame("java.lang.Thread", "run")))
        assertNull(initiator.firstAppFrame)
        assertTrue(initiator.frames!!.isNotEmpty())
    }

    @Test
    fun `file and line are kept, negative lines dropped`() {
        val initiator = FrameClassifier.initiator("t", InitiatorSite.CALL, listOf(StackTraceElement("com.example.A", "native", null, -2)))
        val f = initiator.frames!!.single()
        assertNull(f.file)
        assertNull(f.line)
    }

    @Test
    fun `compiler-generated lambda glue is not app code, and the real lambda body is`() {
        val initiator = FrameClassifier.initiator("t", InitiatorSite.CONNECT, listOf(
            StackTraceElement("io.yamsergey.dta.fixture.channel.NetworkScreen", "protoWs", "NetworkScreen.kt", 422),
            StackTraceElement("io.yamsergey.dta.fixture.channel.NetworkScreen", "run\$lambda\$0", "NetworkScreen.kt", 91),
            StackTraceElement("io.yamsergey.dta.fixture.channel.NetworkScreen", "\$r8\$lambda\$Kr0ca", null, 0),
            StackTraceElement("io.yamsergey.dta.fixture.channel.NetworkScreen\$\$ExternalSyntheticLambda0", "run", "D8\$\$SyntheticClass", 0),
            StackTraceElement("com.example.Old\$\$Lambda\$1", "run", null, -1),
        ))
        assertEquals(listOf(true, true, false, false, false), initiator.frames!!.map { it.app })
        assertEquals(0, initiator.firstAppFrame)
    }

    @Test
    fun `a line without a file or a zero line is no line - the hooked method and synthetic frames`() {
        val initiator = FrameClassifier.initiator("t", InitiatorSite.CONNECT, listOf(
            StackTraceElement("okhttp3.internal.ws.RealWebSocket", "connect", null, 22),
            StackTraceElement("com.example.A\$\$ExternalSyntheticLambda0", "run", "D8\$\$SyntheticClass", 0),
        ))
        assertEquals(listOf<Int?>(null, null), initiator.frames!!.map { it.line })
        assertEquals("D8\$\$SyntheticClass", initiator.frames!![1].file)
    }
}
