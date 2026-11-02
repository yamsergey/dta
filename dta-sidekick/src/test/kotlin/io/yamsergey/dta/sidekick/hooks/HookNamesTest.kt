package io.yamsergey.dta.sidekick.hooks

import kotlin.test.Test
import kotlin.test.assertEquals

class HookNamesTest {

    @Test
    fun `a method reads as a reader would write it`() {
        assertEquals(
            "RealWebSocket.failWebSocket(Exception, Response, boolean)",
            HookNames.method("okhttp3.internal.ws.RealWebSocket", "failWebSocket", "(Ljava/lang/Exception;Lokhttp3/Response;Z)V"),
        )
        assertEquals(
            "RealWebSocket.initReaderAndWriter(String, RealWebSocket.Streams)",
            HookNames.method("okhttp3.internal.ws.RealWebSocket", "initReaderAndWriter", "(Ljava/lang/String;Lokhttp3/internal/ws/RealWebSocket\$Streams;)V"),
        )
        assertEquals("Toast.show()", HookNames.method("android.widget.Toast", "show", "()V"))
        assertEquals(listOf("int[]", "String[][]", "long"), HookNames.parameters("([I[[Ljava/lang/String;J)V"))
    }

    @Test
    fun `an absent method names the method and the class`() {
        val absent = Hooks.Install.MethodAbsent("okhttp3.internal.ws.RealWebSocket", "failWebSocket", "(Ljava/lang/Exception;Lokhttp3/Response;)V")
        assertEquals("method-absent", absent.code)
        assertEquals("RealWebSocket.failWebSocket(Exception, Response) is not in this version of RealWebSocket", absent.describe())
    }

    @Test
    fun `our transform's reason comes first, the runtime's code is only a detail`() {
        val ours = Hooks.Install.refused("slicer: bad branch\u001fRetransformClasses: JVMTI_ERROR_ILLEGAL_ARGUMENT")
        assertEquals("DTA could not add its hook to the method's code", ours.reason)
        assertEquals("slicer: bad branch; RetransformClasses: JVMTI_ERROR_ILLEGAL_ARGUMENT", ours.detail)

        val runtime = Hooks.Install.refused("\u001fRetransformClasses: JVMTI_ERROR_FAILS_VERIFICATION")
        assertEquals("Android refused to reload the class with the hook", runtime.reason)
        assertEquals("RetransformClasses: JVMTI_ERROR_FAILS_VERIFICATION", runtime.detail)
    }
}
