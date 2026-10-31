package io.yamsergey.dta.studio.agents

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.AppExecutorUtil
import io.yamsergey.dta.mcp.McpHttpServer
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.studio.DtaApplicationService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Whether the agent server is on and its port, kept across restarts (off by default). */
@Service(Service.Level.APP)
@State(name = "DtaAgentServer", storages = [Storage("dta.xml")])
class AgentServerSettings : SimplePersistentStateComponent<AgentServerSettings.Options>(Options()), AgentServerPrefs {
    class Options : BaseState() {
        var enabled by property(false)
        var port by property(AgentServerPrefs.DEFAULT_PORT)
    }

    override var enabled: Boolean
        get() = state.enabled
        set(value) { state.enabled = value }

    override var port: Int
        get() = state.port
        set(value) { state.port = value }
}

/** The credential in the IDE's password safe, never in settings files. */
private class PasswordSafeStore : CredentialStore {
    private val attributes = CredentialAttributes(generateServiceName("DTA", "agent server"))

    override fun get(): String? = PasswordSafe.instance.getPassword(attributes)

    override fun set(value: String) = PasswordSafe.instance.set(attributes, Credentials("dta", value))
}

/** The agent server for the whole IDE (one port, one credential), stopped with the IDE. */
@Service(Service.Level.APP)
class AgentServerService : Disposable {
    private val log = logger<AgentServerService>()
    private val started = AtomicBoolean(false)

    val controller = AgentServerController(
        prefs = ApplicationManager.getApplication().getService(AgentServerSettings::class.java),
        credentials = PasswordSafeStore(),
        factory = { port, credential ->
            HttpAgentServer(
                McpHttpServer(
                    port, credential,
                    connect = { DtaApplicationService.get().connect(ClientKind.MCP_HTTP) },
                    log = log::info,
                    projects = { RunnableProjects.of(ProjectManager.getInstance().openProjects.map { it.basePath }) },
                ),
            )
        },
        run = AppExecutorUtil.createBoundedApplicationPoolExecutor("DTA agent server", 1),
    )

    /** Once per IDE session, on the first project opened. */
    fun startOnce() {
        if (started.compareAndSet(false, true)) controller.startIfEnabled()
    }

    override fun dispose() = controller.dispose()

    private class HttpAgentServer(private val server: McpHttpServer) : AgentServer {
        override val url get() = server.url
        override fun start() = server.start()
        override fun stop() = server.stop()
    }

    companion object {
        fun get(): AgentServerService = ApplicationManager.getApplication().getService(AgentServerService::class.java)
    }
}

/** Brings the agent server back at IDE start when it was on. */
class AgentServerStartup : ProjectActivity {
    override suspend fun execute(project: Project) = AgentServerService.get().startOnce()
}

/** The open projects `app_run` can run without a `project` argument: those with a Gradle wrapper (feature 010 US5). */
object RunnableProjects {
    fun of(basePaths: List<String?>): List<File> =
        basePaths.filterNotNull().map(::File).filter { File(it, "gradlew").isFile }.distinct()
}
