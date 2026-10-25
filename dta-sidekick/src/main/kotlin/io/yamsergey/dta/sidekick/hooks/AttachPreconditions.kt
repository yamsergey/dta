package io.yamsergey.dta.sidekick.hooks

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
        sdkInt < MIN_SDK -> Result.Unavailable("requires API $MIN_SDK+ (device is API $sdkInt)")
        !debuggable -> Result.Unavailable("app is not debuggable (add the sidekick with debugImplementation)")
        agentPath == null -> Result.Unavailable("libdta-agent.so not found for ABIs ${abis.joinToString(", ")}")
        else -> Result.Attempt(agentPath)
    }
}
