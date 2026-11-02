package io.yamsergey.dta.sidekick.hooks

import kotlin.test.Test
import io.yamsergey.dta.sidekick.capability.Reasons
import kotlin.test.assertEquals

class AttachPreconditionsTest {

    private fun check(sdk: Int = 34, debuggable: Boolean = true, agentPath: String? = "/data/app/lib/x86_64/libdta-agent.so") =
        AttachPreconditions.check(sdkInt = sdk, debuggable = debuggable, agentPath = agentPath, abis = listOf("x86_64", "arm64-v8a"))

    @Test
    fun `api below 28 is unavailable with the device level`() {
        assertEquals(AttachPreconditions.Result.Unavailable(Reasons.hooksNeedApi28(27)), check(sdk = 27))
    }

    @Test
    fun `non-debuggable app is unavailable`() {
        assertEquals(
            AttachPreconditions.Result.Unavailable(Reasons.NOT_DEBUGGABLE),
            check(debuggable = false),
        )
    }

    @Test
    fun `missing agent library names the abis searched`() {
        assertEquals(
            AttachPreconditions.Result.Unavailable(Reasons.agentMissing(listOf("x86_64", "arm64-v8a"))),
            check(agentPath = null),
        )
    }

    @Test
    fun `all preconditions met means attempt with the agent path`() {
        assertEquals(AttachPreconditions.Result.Attempt("/data/app/lib/x86_64/libdta-agent.so"), check())
    }
}
