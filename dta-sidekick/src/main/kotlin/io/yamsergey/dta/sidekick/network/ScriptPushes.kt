package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.InjectionSource
import io.yamsergey.dta.protocol.network.ScriptHandler
import io.yamsergey.dta.sidekick.network.script.ScriptConnection
import io.yamsergey.dta.sidekick.network.script.ScriptHost

/** The network script's pushes go through the one injector (feature 020, constitution VI). */
internal class ScriptPushes(private val injector: WsInjector) : ScriptHost {

    override fun push(connectionId: String, text: String?, bytes: ByteArray?, scriptId: String, handler: ScriptHandler?, delayMs: Long, onFailure: (String) -> Unit) {
        try {
            injector.push(connectionId, PushMessage(text, bytes), PushSource(InjectionSource.SCRIPT, scriptId, handler), WsPushMethods.DEFAULT_WAIT_MS, delayMs, onFailure)
        } catch (e: PushException) {
            throw IllegalStateException(e.message)
        }
    }

    override fun connections(): List<ScriptConnection> =
        injector.openConnections().map { ScriptConnection(it.url, it.library, it.id) }
}
