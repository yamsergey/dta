package io.yamsergey.dta.sidekick.hooks

import io.yamsergey.dta.sidekick.capability.Reasons

/**
 * Whether the JVMTI agent may be attached, and if not, the reason the network
 * capability reports (constitution III). Pure, so the reasons are tested without a device.
 */
object AttachPreconditions {

    sealed interface Result {
        data class Attempt(val agentPath: String) : Result
        data class Unavailable(val reason: String) : Result
    }

    /** JVMTI attach to a running app (Debug.attachJvmtiAgent) exists from API 28. */
    const val MIN_SDK = 28

    fun check(sdkInt: Int, debuggable: Boolean, agentPath: String?, abis: List<String>): Result = when {
        sdkInt < MIN_SDK -> Result.Unavailable(Reasons.hooksNeedApi28(sdkInt))
        !debuggable -> Result.Unavailable(Reasons.NOT_DEBUGGABLE)
        agentPath == null -> Result.Unavailable(Reasons.agentMissing(abis))
        else -> Result.Attempt(agentPath)
    }
}
