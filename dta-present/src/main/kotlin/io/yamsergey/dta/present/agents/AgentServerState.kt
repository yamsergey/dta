package io.yamsergey.dta.present.agents

/** The agent server as the tool window states it (contract agent-server.md §States). */
sealed interface AgentServerState {
    /** The state line. */
    val line: String

    /** The one action that fixes this state, when there is one. */
    val action: String? get() = null

    data object Off : AgentServerState {
        override val line = "off"
    }

    data class Starting(val port: Int) : AgentServerState {
        override val line = "starting"
    }

    data class On(val url: String) : AgentServerState {
        override val line = "on — $url"
    }

    data class Failed(val reason: String, val portInUse: Int? = null) : AgentServerState {
        override val line = "failed — $reason"
        override val action = if (portInUse != null) "Use another port" else null

        companion object {
            fun portInUse(port: Int) = Failed("port $port is in use", port)
        }
    }
}
